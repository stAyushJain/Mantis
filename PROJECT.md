# Mantis — Project Overview

This document is the entry point for the whole Mantis project. It's a
single monorepo with three pieces:

| Module | Path | Role |
|--------|------|------|
| `backend/` | Rust binary | REST/SSE API on `:3000`, MITM HTTPS proxy on `:8080`. The single source of truth for all state. |
| `composeApp/` | Compose Multiplatform (wasmJs) | Web UI. A thin client served as static files; talks to the backend over HTTP + SSE. |
| `shared/` | Kotlin Multiplatform | kotlinx-serializable data models that match the backend's wire format byte-for-byte. |

---

## How they connect

```
┌──────────────────────────────────────────────────────────────────────┐
│ Browser  →  http://127.0.0.1:5173                                    │
│              served by python -m http.server                         │
│                    │                                                 │
│                    └── composeApp.js + .wasm  (Compose UI)           │
│                              │                                       │
│                              │ Ktor JSON over HTTP / EventSource SSE │
│                              ▼                                       │
│ Backend  →  http://127.0.0.1:3000                                    │
│              REST + SSE (axum)                                       │
│                    │                                                 │
│                    └── proxy supervisor (start/stop)                 │
│                              │                                       │
│                              ▼                                       │
│ Backend  →  http://0.0.0.0:8080  (MITM proxy)                        │
│              Hudsucker; HTTPS forged by Mantis Root CA               │
│                    │                                                 │
│                    ▼                                                 │
│              Phones / browsers / simulators                          │
└──────────────────────────────────────────────────────────────────────┘
```

Both API and proxy ports bind to `0.0.0.0` so phones on the same Wi-Fi
can reach them. The UI itself stays on `127.0.0.1` for safety; if you
want to view the dashboard from another device, serve the dist on
`0.0.0.0` (e.g. `python -m http.server 5173 --bind 0.0.0.0`) and point
its Settings tab at the LAN IP for the API.

---

## Read these next, in order

1. **`ARCHITECTURE.md`** — the UI file by file: shared models, theme,
   API client, state holder, every screen and dialog. Includes recipes
   for "add a new tab", "add a field on MockRule", "wire up a JVM
   target". Read this if you're touching anything Kotlin.

2. **`backend/src/`** — the backend has no separate doc yet; the cross
   reference below tells you which file owns each concept.

3. **`README.md`** — the user-facing "how to run it" doc, kept short on
   purpose. Mostly mirrors what `mantis.sh` does.

---

## File-level cross-reference

| Concept | Backend file | Frontend file |
|---------|--------------|---------------|
| Folder tree | `backend/src/models.rs` (`FolderNode`) + `backend/src/workspace.rs` | `shared/.../Models.kt` (`FolderNode`) + `composeApp/.../ui/FoldersScreen.kt` |
| Mock rule | `backend/src/models.rs` (`MockRule`) | `shared/.../Models.kt` (`MockRule`) + `RuleEditorDialog.kt` |
| Flow | `backend/src/models.rs` (`Flow`, `FlowStep`) + `backend/src/proxy.rs` matcher | `shared/.../Models.kt` (`Flow`, `FlowStep`) + `FlowsScreen.kt` |
| Intercepted call log | `backend/src/proxy.rs` (`push_log`, broadcast channel) + `backend/src/api.rs` (`stream_logs`) | `shared/.../Models.kt` (`InterceptedCall`) + `LogsScreen.kt` + `LiveLogStream.wasmJs.kt` |
| Per-user vault | `backend/src/storage.rs` (`load_user_workspace`) + `backend/src/main.rs` (`AppState.vault_for`) | `LoginScreen.kt` + `MockMasterApi.kt` (`X-MockMaster-User` header) |
| Proxy lifecycle | `backend/src/main.rs` (supervisor + `ProxyCommand`) | `AppScaffold.kt` Connect/Disconnect button |
| Cert | `backend/src/main.rs::ensure_ca_certificate` + `/cert` endpoint | "Download cert" button in `AppScaffold.kt` and `DocsScreen.kt` |
| Local IP for "share with phone" | `local-ip-address` crate in `backend/src/api.rs::server_info` | top-bar chip in `AppScaffold.kt`, instructions in `DocsScreen.kt` |

---

## Wire format invariants

- All UI-facing JSON uses **camelCase** (e.g. `isEnabled`, `statusCode`,
  `subFolders`, `rulesMap`, `activeFlowId`). The backend uses serde
  rename to translate from `snake_case` Rust fields; the Kotlin side uses
  `@SerialName("...")`.
- **Both ends must add a default for every new field** so old saved JSON
  still loads.
- Timestamps are RFC-3339 UTC strings (`InterceptedCall.timestamp`) or
  millisecond epoch `Long` (`MockRule.createdAt`). Stick to one of those
  for any new time fields.

---

## Adding a feature end-to-end (template)

Suppose you want to add a `delayMs: Int` field to a `MockRule` so the
proxy waits before responding.

1. **Backend models**: add `pub delay_ms: u32` to `MockRule` in
   `backend/src/models.rs` with `#[serde(rename = "delayMs", default)]`.
2. **Backend matcher**: in `backend/src/proxy.rs`, after `resolve_match`,
   do `tokio::time::sleep(Duration::from_millis(m.delay_ms.into())).await`
   if you propagate the field on `MatchResult`.
3. **Backend API**: add `delay_ms: u32` to `UpsertRuleReq` in
   `backend/src/api.rs` with the same `default` and rename. Set it on the
   `MockRule` you build in `upsert_rule` / `put_rule`.
4. **Shared model**: add `@SerialName("delayMs") val delayMs: Int = 0`
   to both `MockRule` and `UpsertRuleReq` in `shared/.../Models.kt`.
5. **UI editor**: drop an `OutlinedTextField` for "Delay (ms)" in
   `RuleEditorDialog.kt`, default-populated from `rule?.delayMs ?: 0`.
6. **UI display** (optional): show a clock icon + duration in
   `RuleRow` inside `FoldersScreen.kt`.
7. **Smoke test**: restart the backend, add a rule with delay 2000, hit
   it through the proxy, observe the pause.

---

## Useful scripts

- `./mantis.sh start|stop|status|test|cert|logs|rebuild` — driver for
  everything. Records real PIDs, falls back to name-based pkill, runs
  `lsof` checks before binding ports.

That's the whole project. Two cargo / Gradle modules, two architecture
docs, one launcher script. If something in either codebase isn't
explained in those files, that's a documentation gap — please fix.
