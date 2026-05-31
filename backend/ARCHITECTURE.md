# MockMaster Backend — Architecture & File Reference

A complete map of `mockmaster_backend/` — every file, every type, every
endpoint, every data flow. Read this first before changing anything.

---

## 1. What this binary is

A single Rust process that exposes two TCP listeners:

| Port | Purpose |
|------|---------|
| `:3000` | Axum REST + SSE control API (consumed by the Compose UI). |
| `:8080` | Hudsucker MITM proxy. Devices set this as their HTTP/HTTPS proxy and the binary either answers with a stored mock or transparently forwards. |

Both listeners share a single in-process state (`AppState`) so a write to
the API is visible to the very next intercepted request — no caching, no
reload.

```
                       ┌─────────────────────────────────────┐
                       │ Compose UI (composeApp/ wasmJs)     │
                       └────────────┬────────────────────────┘
                                    │  HTTP+SSE
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│ mantis backend (Rust, single process)                            │
│                                                                  │
│  ┌────────────────────┐   shared Arc<Mutex<WorkspaceState>>     │
│  │ Axum router :3000  │ ◀────────────────────┐                  │
│  └─────────┬──────────┘                      │                  │
│            │ broadcast::Sender<Intercepted>  │                  │
│            ▼                                 │                  │
│  ┌────────────────────┐    spawn / abort     │                  │
│  │ Proxy supervisor   │ ─────────────┐       │                  │
│  └────────────────────┘              ▼       │                  │
│                              ┌──────────────┴───────────┐       │
│                              │ Hudsucker MITM proxy :8080│      │
│                              └──────────────────────────┘       │
└──────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                         devices on the LAN
```

---

## 2. Top-level layout

```
mockmaster_backend/
├── Cargo.toml          dependency manifest (axum, hudsucker, rcgen, etc.)
├── Cargo.lock          checked in
├── .gitignore          ignores /target and the CA private key
├── src/
│   ├── main.rs         binary entrypoint + proxy supervisor + AppState
│   ├── models.rs       all serde types (also the API/disk wire format)
│   ├── storage.rs      filesystem persistence (workspace_data/*.json)
│   ├── workspace.rs    pure tree manipulation helpers
│   ├── api.rs          axum handlers (one per HTTP route)
│   └── proxy.rs        Hudsucker handler + match resolution algorithm
└── workspace_data/
    ├── tree.json                       folder tree (no rules)
    ├── flows.json                      every flow + its steps
    ├── state.json                      { "activeFlowId": ... }
    ├── f_<uuid>.json                   one file per folder, holding its rules
    ├── mockmaster-ca.crt               root CA (served by /cert)
    └── mockmaster-ca.key               CA private key — keep local!
```

The `workspace_data/` directory is the entire database. To wipe state:
delete the JSON files (keep the CA pair if you've already trusted it on
devices).

---

## 3. Cargo.toml — dependencies & why

```toml
axum = "0.7"        # HTTP framework: routing, JSON, SSE
tokio = "1.37"      # async runtime
serde / serde_json  # serialization for API and on-disk JSON
tower-http          # CORS layer
chrono              # ISO-8601 timestamps in logs
uuid                # rule / flow ids

# MITM stack
hudsucker = "0.24"  # MITM proxy framework with TLS spoofing
rcgen      = "0.14" # generates the per-host certs Hudsucker signs on the fly
rustls     = "0.23" # the TLS impl Hudsucker uses
rustls-pemfile     # parse our PEM CA
tokio-rustls
hyper-util         # HTTP 1+2 client used by Hudsucker for upstream

# Streaming + body capture
futures-util  # SSE stream adapter
http-body-util# .collect() the request body so we can log it
bytes         # buffered request bodies

# UX
local-ip-address # auto-detect the LAN IP shown to the user
```

If you change ports or rename, edit constants in `main.rs` (`API_PORT`,
`PROXY_PORT`) — they aren't read from env.

---

## 4. `src/models.rs` — data contracts

The single source of truth for both the on-disk JSON and the wire format
the UI consumes. Every UI-facing field uses serde rename to camelCase
because the Kotlin side mirrors it.

| Type | Fields | Purpose |
|------|--------|---------|
| `FolderNode` | `id`, `name`, `is_enabled` (`isEnabled`), `sub_folders` (`subFolders`) | Recursive tree node. |
| `MockRule` | `id`, `path`, `method` (default `"GET"`), `status_code` (`statusCode`), `body`, `is_enabled`, `created_at` (`createdAt`, ms epoch) | One mock rule. `created_at` is the tiebreaker for "last write wins". |
| `FlowStep` | `id`, `path`, `method`, `status_code`, `body`, `is_consumed` (`isConsumed`) | One step in a flow. `is_consumed` is mutated by the proxy at request time. |
| `Flow` | `id`, `name`, `description`, `steps`, `created_at` | Container of steps. |
| `WorkspaceState` | `folders`, `rules_map` (`rulesMap`: `{folderId → [MockRule]}`), `flows`, `active_flow_id` (`activeFlowId`) | Everything. Held under a single `Arc<Mutex<…>>`. |
| `InterceptedCall` | `id`, `method`, `url`, `path`, `status_code`, `matched`, `match_source` (`folder`/`flow`/`passthrough`), `match_label` (folder or flow name), `request_headers`, `request_body`, `response_body`, `timestamp` (RFC3339) | One log entry. |
| `ProxyStatus` | enum: `stopped`, `starting`, `running`, `stopping` (lowercase on the wire) | Lifecycle states. |
| `ServerInfo` | `status`, `proxy_host`, `proxy_port`, `api_port`, `local_ip`, `version` | Returned by `GET /server/info`. |

### Helpers on `WorkspaceState`

- `default_seed()` — built when no `tree.json` exists. Creates a single
  `Default` folder with id `f_default`.
- `enabled_folder_ids() -> HashSet<String>` — recursive walk that returns
  the set of folder ids whose ancestor chain (and themselves) are all
  enabled. Used by the proxy to honour the disable-cascade.

**Adding a new field?** Add it on the struct here with the right
`#[serde(rename = ...)]` and a sensible `default`, then mirror it in
`shared/src/commonMain/kotlin/com/mockmaster/shared/Models.kt`.
Existing on-disk JSON keeps working as long as the new field has a
default.

---

## 5. `src/storage.rs` — filesystem persistence

Filesystem is the database. We split it across files so a 200 KB rule body
in folder A doesn't rewrite folder B's rules.

| Constant | Value |
|----------|-------|
| `WORKSPACE_DIR` | `workspace_data` |
| `TREE_FILE` | `workspace_data/tree.json` (folders only, no rules) |
| `FLOWS_FILE` | `workspace_data/flows.json` |
| `STATE_FILE` | `workspace_data/state.json` (`{"activeFlowId":...}`) |
| `CA_CERT_FILE` | `workspace_data/mockmaster-ca.crt` |
| `CA_KEY_FILE` | `workspace_data/mockmaster-ca.key` |

Per-folder rule files live alongside `tree.json` as `f_<uuid>.json`. The
loader picks them up by globbing for `f_*.json`.

| Function | What it does |
|----------|--------------|
| `init_workspace()` | mkdir on `workspace_data/` if missing. |
| `load_db() -> WorkspaceState` | Reads `tree.json`, scans `f_*.json` to populate `rules_map`, reads `flows.json`. **Always resets `active_flow_id` to `None` on startup** — flows don't survive restarts by design (in-flight consumption state is meaningless after a process crash). |
| `save_tree(folders)` | Writes `tree.json`. |
| `save_folder_rules(folder_id, rules)` | Writes `workspace_data/<folder_id>.json`. |
| `delete_folder_rules_file(folder_id)` | Removes that one file. Called when a folder (or any of its descendants) is deleted. |
| `save_flows(flows)` | Writes `flows.json`. |
| `save_active_flow(opt_id)` | Writes `state.json`. |
| `save_all(state)` | Convenience that fires every saver. Used after bulk `PUT /workspace`. |

There is no atomic-write or fsync — these are tiny JSON files. If you want
crash-safety, add a `tempfile + rename` step here.

---

## 6. `src/workspace.rs` — tree mutators (no I/O)

Pure functions on `WorkspaceState` slices. They're the only place that
understands the tree shape.

| Function | Behaviour |
|----------|-----------|
| `with_folder_mut(folders, id, f)` | Recursive find by id, applies `f`. Returns `true` if found. Used by rename + toggle. |
| `insert_folder(folders, parent_id, new_folder)` | If `parent_id` is `None`, push to root; otherwise nest under that parent. Returns `false` if parent not found. |
| `remove_folder(folders, id) -> Vec<String>` | Removes the folder anywhere in the tree and returns the ids of every descendant (including itself), so the caller can clean rule files and the `rules_map` entries. |
| `upsert_rule(rules, rule)` | (1) If a rule with the same `id` exists, replace it. (2) Otherwise, mark every existing rule with the same `(method, path)` as `is_enabled = false` (so the new rule wins) and append. Stamps `created_at = now()` if it's zero. |
| `delete_rule(rules, rule_id) -> bool` | Removes by id. |
| `upsert_flow(flows, flow)` / `delete_flow(flows, id)` | Same pattern for flows. |
| `reset_flow_consumption(flow)` | Sets every step's `is_consumed = false`. Called when a flow starts/stops. |
| `persist_workspace(state)` | Calls every storage writer. Currently used after the bulk `PUT /workspace`. |

If you want different "duplicate" semantics (e.g. error instead of
auto-disable older), change `upsert_rule`.

---

## 7. `src/main.rs` — binary entrypoint

### Structs

```rust
pub enum ProxyCommand { Start, Stop }

pub struct AppState {
    pub vault:        Arc<Mutex<WorkspaceState>>,    // live workspace
    pub log_tx:       broadcast::Sender<InterceptedCall>, // SSE fan-out
    pub log_buffer:   Arc<Mutex<VecDeque<InterceptedCall>>>, // last 500 calls
    pub proxy_status: Arc<Mutex<ProxyStatus>>,
    pub proxy_cmd:    mpsc::Sender<ProxyCommand>,    // to supervisor
    pub proxy_port:   u16,
    pub api_port:     u16,
}
```

`AppState` is `Clone` (everything inside is `Arc<…>` or a sender) so axum
copies it cheaply per request.

### Constants

```rust
const API_PORT: u16 = 3000;
const PROXY_PORT: u16 = 8080;
```

### Functions

- `ensure_ca_certificate()` — generates a 2048-bit Root CA on first run
  using rcgen; writes the CRT and KEY into `workspace_data/`. On
  subsequent runs it just logs "Loaded existing".
- `build_router(app: AppState) -> Router` — registers every HTTP route.
  This is the single source of truth for the URL space (see §9).
- `build_and_spawn_proxy(addr, handler, shutdown_rx, status)` — re-loads
  the CA from disk, builds a `Proxy` via Hudsucker's builder pattern, and
  spawns it on Tokio. Wires the oneshot shutdown receiver into
  `with_graceful_shutdown`. Updates `proxy_status` to `Running` on entry
  and `Stopped` on exit.

### `#[tokio::main] async fn main()`

1. Install rustls' ring crypto provider as the global default.
2. `ensure_ca_certificate()`.
3. Build `vault` from `storage::load_db()`.
4. Create the broadcast channel and the ring buffer.
5. Create the `mpsc<ProxyCommand>` channel.
6. Build `AppState`, spawn the axum server in a Tokio task on `:3000`.
7. Send `ProxyCommand::Start` so the proxy comes up automatically.
8. **Supervisor loop** — receives commands forever:
   - `Start`: if not already running, create a new `oneshot` shutdown
     channel, instantiate a `MockHandler`, call `build_and_spawn_proxy`.
   - `Stop`: send `()` on the stored shutdown sender; the spawned task
     returns and writes `Stopped` into `proxy_status`.

The supervisor is single-threaded by virtue of the loop, so there's no
race between successive commands.

---

## 8. `src/proxy.rs` — the matcher

### `MockHandler`

Holds clones of `vault`, `log_tx`, and `log_buffer`. Implements
`hudsucker::HttpHandler::handle_request`.

### `push_log(call)`

Inserts into the ring buffer (drops the oldest when at `LOG_BUFFER_CAPACITY`
= 500), then `send`s on the broadcast channel. SSE subscribers receive it
within one tick.

### `resolve_match(state, method, path) -> Option<MatchResult>`

This is the entire mocking algorithm — read carefully if you're tweaking
priorities.

```
if state.active_flow_id is Some:
    folder rules are paused (we never look at them)
    let matches = steps in active flow with (method, path) match
    if matches is empty: return None  → passthrough
    for each match in original order:
        if not consumed: mark consumed, return it
    // all matched steps consumed → "sticky"
    return the LAST matching step

else (folder mode):
    enabled = state.enabled_folder_ids()  // honours cascade
    best = None
    for (folder_id, rules) in state.rules_map:
        skip if folder_id not in enabled
        for rule in rules:
            skip if !rule.is_enabled
            skip if method mismatch (case-insensitive)
            skip if path != rule.path  (exact match)
            if rule.created_at > best.created_at:  best = rule
    return best
```

Important properties:

- **Path matching is exact**. There's no glob/regex — a request for
  `/v4/drivers/active_bookings` will only match a rule whose `path` is
  literally that. (Easy to extend; see §11.)
- **Method matching is case-insensitive**, both directions.
- **HashMap iteration order is non-deterministic** — that's fine because
  we always pick the rule with the highest `created_at`, which is unique
  enough.
- **Flow mutation happens inside the lock** taken in `handle_request`, so
  even a 100-rps burst gets monotonic step consumption.

### `find_folder_name(folders, id) -> Option<String>`

Recursive tree walk used to populate `MatchResult.match_label` for the log
entry shown in the UI.

### `impl HttpHandler for MockHandler`

`handle_request` does, in order:

1. Pull `method`, `url`, `path` out of the request.
2. Copy headers into a `HashMap<String, String>` (loses non-UTF-8
   headers — fine for our use case).
3. **Buffer the request body** with `body.collect().await`. We need this
   for the log; passthrough requests get a fresh `Body` rebuilt from the
   bytes.
4. Lock `vault`, call `resolve_match`, drop the lock.
5. **If matched**: build the log entry, push it, then briefly re-lock to
   snapshot `flows` and call `storage::save_flows` (so flow consumption
   survives a process restart in the middle of a flow run). Build a
   `Response` with `Content-Type: application/json` and two diagnostic
   headers — `X-MockMaster-Intercepted: true` and
   `X-MockMaster-Source: folder|flow`. Return as
   `RequestOrResponse::Response`.
6. **If passthrough**: build a passthrough log entry (status 0,
   matched=false), push it, rebuild the request from its parts, return
   `RequestOrResponse::Request(req)` so Hudsucker forwards upstream.

To **cap request body size**, add a guard before `body.collect()`. To
**also log responses** for passthroughs, implement `handle_response` on
`HttpHandler` — Hudsucker passes the upstream response through the same
trait.

---

## 9. `src/api.rs` — REST handlers

Every route registered in `build_router`, with one handler function per
route. Path patterns use `:id`, `:rule_id`, etc.

### Workspace bulk

| Method | Path | Handler | Notes |
|--------|------|---------|-------|
| GET | `/workspace` | `get_workspace` | Returns the entire `WorkspaceState`. The UI calls this on boot and after every mutation. |
| PUT | `/workspace` | `put_workspace` | Replaces the in-memory state with the body and persists everything. Useful for import/export. |

### Folders

| Method | Path | Handler | Body | Returns |
|--------|------|---------|------|---------|
| POST | `/folders` | `create_folder` | `{name, parentId?}` | the new `FolderNode`. Auto-creates an empty `rules_map[id]` entry and writes an empty rule file. |
| PUT | `/folders/:id` | `rename_folder` | `{name}` | `{ok}` |
| DELETE | `/folders/:id` | `delete_folder` | — | `{ok}`. Recursive: removes every descendant from `rules_map` and deletes their rule files. |
| POST | `/folders/:id/toggle` | `toggle_folder` | `{enabled}` | `{ok}`. Cascade is computed at match time — we don't pre-compute disabled states. |

### Rules

| Method | Path | Handler | Body | Returns |
|--------|------|---------|------|---------|
| GET | `/folders/:id/rules` | `list_rules` | — | `[MockRule]` for that folder only |
| POST | `/folders/:id/rules` | `upsert_rule` | `UpsertRuleReq` | the saved `MockRule`. If `id` is in the body it replaces; otherwise adds. |
| PUT | `/folders/:id/rules/:rule_id` | `put_rule` | `UpsertRuleReq` | the saved `MockRule`. **The path's `rule_id` overrides whatever's in the body** (we set `req.id = Some(rule_id)`). |
| DELETE | `/folders/:id/rules/:rule_id` | `delete_rule` | — | `{ok}` |
| POST | `/folders/:id/rules/:rule_id/toggle` | `toggle_rule` | `{enabled}` | `{ok}` |

### Flows

| Method | Path | Handler | Body | Returns |
|--------|------|---------|------|---------|
| GET | `/flows` | `list_flows` | — | `[Flow]` |
| POST | `/flows` | `upsert_flow` | `UpsertFlowReq` | the saved `Flow`. Steps come back with assigned ids. |
| PUT | `/flows/:id` | `put_flow` | `UpsertFlowReq` | same as POST but path id wins. |
| DELETE | `/flows/:id` | `delete_flow` | — | `{ok}`. **Auto-stops the flow if it was active**. |
| POST | `/flows/start` | `start_flow` | `{flowId}` | `{ok}`. Resets every step's `is_consumed`, sets `active_flow_id`. |
| POST | `/flows/stop` | `end_flow` | — | `{ok}`. Resets the active flow's consumption and clears `active_flow_id`. |

### Server lifecycle

| Method | Path | Handler | Returns |
|--------|------|---------|---------|
| GET | `/server/info` | `server_info` | `ServerInfo` (status, ports, local IP, version) |
| POST | `/server/connect` | `connect_proxy` | `{ok}`. Sends `ProxyCommand::Start` to the supervisor. |
| POST | `/server/disconnect` | `disconnect_proxy` | `{ok}`. Sends `ProxyCommand::Stop`. |

### Cert

| Method | Path | Handler | Notes |
|--------|------|---------|-------|
| GET | `/cert` | `download_cert` | Streams `mockmaster-ca.crt` with `Content-Disposition: attachment` so the browser saves it. |

### Logs

| Method | Path | Handler | Notes |
|--------|------|---------|-------|
| GET | `/logs` | `list_logs` | Returns the in-memory ring buffer (oldest first). |
| DELETE | `/logs/clear` | `clear_logs` | Empties the ring buffer. Doesn't touch SSE subscribers. |
| GET | `/logs/stream` | `stream_logs` | SSE. Each event is one `InterceptedCall` JSON. Browser auto-reconnects on drop. Sends a comment line on `RecvError::Lagged` so slow clients don't crash. |

### Request/response payloads (also serde structs in this file)

```rust
struct CreateFolderReq { name, parentId? }
struct RenameFolderReq { name }
struct ToggleReq       { enabled: bool }
struct UpsertRuleReq   { id?, path, method, statusCode, body, isEnabled }
struct UpsertFlowReq   { id?, name, description, steps: [FlowStepReq] }
struct FlowStepReq     { id?, path, method, statusCode, body }
struct StartFlowReq    { flowId }
struct ApiResult       { ok: bool, message?: String }
```

Default values match what the UI sends, so missing fields are tolerated.

---

## 10. Concurrency model & invariants

- **One mutex protects the whole workspace.** Every API handler does
  `vault.lock().unwrap()`, mutates, drops the lock, then optionally calls
  storage. This is fine: workloads are tiny (kilobytes) and the proxy
  reads under the same lock, so a request never sees torn state.
- **Logs are unbounded by sender, bounded by buffer.** The broadcast
  channel has a 256-message backlog; if a slow SSE client lags, it
  receives a `Lagged(n)` and the SSE adapter emits a comment line — no
  panics.
- **Flow consumption is durable.** After the proxy mutates `is_consumed`
  it briefly re-locks to snapshot `flows` and persists. A crash mid-flow
  resumes consumption correctly, but `active_flow_id` is cleared on
  startup so you'll have to manually re-start the flow.
- **Proxy lifecycle is serialised by `mpsc<ProxyCommand>`.** Two clients
  hammering Connect/Disconnect can't fork two proxies on the same port.

---

## 11. How to extend (recipes)

### Add a new HTTP route on the API

1. Add a serde request struct in `api.rs` next to the others.
2. Write `pub async fn my_handler(State(app): State<AppState>, …) -> Json<…>`.
3. Wire it in `main.rs::build_router` with the right method.
4. If it mutates state, lock `vault`, drop the lock before calling
   `storage::*`.

### Change matching semantics (e.g. add prefix or query-string match)

Edit `resolve_match` in `proxy.rs`. The `for rule in rules` block is the
inner predicate — change `if rule.path != path` to whatever you need.
Optionally add a `match_kind: MatchKind` enum on `MockRule` to make it a
per-rule choice.

### Add response headers / latency simulation

Extend `MockRule` in `models.rs` with e.g. `delay_ms: u64` and
`headers: HashMap<String,String>`. Add them to `UpsertRuleReq`. In
`proxy.rs` after `resolve_match`, `tokio::time::sleep(delay_ms).await` and
loop the headers into `Response::builder()`. Mirror the field in the
Kotlin shared model and the Compose `RuleEditorDialog`.

### Capture upstream responses on passthrough

Add a `handle_response` impl on `HttpHandler` (Hudsucker calls it for
passthrough responses). Buffer the body the same way we do requests, push
a second `InterceptedCall` (or update the existing one — you'll need a
correlation id). Be careful with response sizes; you'll want a cap.

### Auth / multi-user

Drop a `tower_http::auth` layer or a custom middleware in `build_router`,
and tag rules with `owner: Option<String>` in `models.rs`. Auth is
deliberately not present today.

### Use a real database

Replace `storage.rs`. The signatures are isolated (`save_tree`,
`save_folder_rules`, etc.) so the rest of the code only sees the new
backend.

---

## 12. CA certificate notes

- Generated once into `workspace_data/mockmaster-ca.crt` +
  `mockmaster-ca.key` (the `.key` is gitignored).
- 10-year self-signed CA, `CN=MockMaster Proxy CA`, key usages
  KeyCertSign + DigitalSignature + CrlSign.
- Hudsucker forges per-host certs on the fly, signed by this CA, with a
  1000-entry LRU cache (`RcgenAuthority::new(.., 1_000, …)`).
- Trust on macOS: the `mockmaster.sh cert` command runs
  `security add-trusted-cert -d -r trustRoot …`.
- **Never commit the `.key` file**; if it leaks an attacker can
  intercept HTTPS for anyone who trusted the corresponding `.crt`.

---

## 13. Build & run cheatsheet

```bash
cargo build               # compile
cargo run                 # run; auto-creates workspace_data + CA
cargo run --release       # production-ish

# Direct binary (script uses this so the recorded PID is real)
./target/debug/mockmaster_backend
```

Logs go to stdout. The `mockmaster.sh` launcher captures them to
`/tmp/mockmaster/be.log`.

---

## 14. Known issues / non-goals

- No request size cap on body capture (see §11 to add one).
- No HTTP/2 mocking — Hudsucker speaks H2 to upstream but the response we
  forge is H1.
- No per-user / per-team isolation; this is a local team tool.
- `path` matching is exact, no globs.
- Active flow does not survive backend restart (intentional; consumption
  state would be stale anyway).
- HashMap iteration order means tied `created_at` is non-deterministic;
  in practice `created_at` is millisecond-precise so collisions are rare.
