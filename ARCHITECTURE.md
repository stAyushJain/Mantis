# MockMaster UI — Architecture & File Reference

Companion to `mockmaster_backend/ARCHITECTURE.md`. This is the Compose
Multiplatform (wasmJs) UI that talks to the Rust backend.

---

## 1. What this app is

A single-page Compose Multiplatform app, compiled to WebAssembly via the
Kotlin `wasmJs` target, served as static files over plain HTTP.

It is a thin client over the backend's REST API. It holds a snapshot of
`WorkspaceState` in memory, mutates it locally for snappy UX, then
re-fetches on every successful API call to stay authoritative.

There is **no local persistence** — close the tab and you lose nothing,
because the backend is the source of truth.

---

## 2. Top-level layout

```
mantis/
├── README.md
├── ARCHITECTURE.md                 (this file)
├── mantis.sh                       launcher (start/stop/status/test/cert)
├── settings.gradle.kts             includes :shared and :composeApp only
├── build.gradle.kts                applies plugins to subprojects
├── gradle.properties               Xmx=4g for the wasmJs IR pass
├── gradle/libs.versions.toml       Kotlin 2.1.10, Compose 1.7.3, Ktor 3.0.3
├── gradle/wrapper/                 Gradle 8.10.2
│
├── shared/                         pure Kotlin module (no Compose)
│   ├── build.gradle.kts            wasmJs target, kotlinx-serialization
│   └── src/commonMain/kotlin/com/mockmaster/shared/
│       └── Models.kt               every @Serializable type
│
└── composeApp/
    ├── build.gradle.kts            Compose UI module, depends on :shared
    └── src/
        ├── commonMain/kotlin/com/mockmaster/app/
        │   ├── App.kt              top-level @Composable + bootstrap
        │   ├── Theme.kt            colour tokens + MaterialTheme wrapper
        │   ├── api/
        │   │   └── MockMasterApi.kt   typed Ktor wrapper of every BE route
        │   ├── state/
        │   │   └── AppState.kt        single mutable state holder
        │   └── ui/
        │       ├── AppScaffold.kt     top bar + side nav
        │       ├── Common.kt          shared widgets + JSON formatter
        │       ├── FoldersScreen.kt   tree + endpoints pane
        │       ├── RuleEditorDialog.kt endpoint authoring dialog
        │       ├── FlowsScreen.kt     flow list + detail + editor dialog
        │       ├── LogsScreen.kt      intercepted-call live view
        │       ├── DocsScreen.kt      "How to Use" content
        │       └── SettingsScreen.kt  backend URL + workspace stats
        │
        └── wasmJsMain/
            ├── kotlin/com/mockmaster/app/
            │   ├── Main.kt                 ComposeViewport entry
            │   └── LiveLogStream.wasmJs.kt actual of installLiveLogStream()
            └── resources/
                └── index.html              host page that loads composeApp.js
```

---

## 3. Build toolchain (gradle/libs.versions.toml)

| Tool | Version | Why |
|------|---------|-----|
| Kotlin | 2.1.10 | First stable line where wasmJs + Compose 1.7 + kotlinx-serialization plays nicely. 2.0.20 hit FIR/K2 NPEs. |
| Compose Multiplatform | 1.7.3 | First version with stable wasmJs target + materialIconsExtended for wasm. |
| Ktor | 3.0.3 | Multiplatform HTTP client with wasmJs support out of the box. |
| kotlinx-serialization | 1.7.3 | Matches Kotlin 2.1.10. |
| Gradle | 8.10.2 | The earlier 9.0-milestone-1 was unstable with this toolchain. |
| JVM heap | `-Xmx4g` | wasmJs IR pass needs it. Set in `gradle.properties`. |

Bumping any of these is a coordinated change — Compose, kotlinx, and
Kotlin pins move together.

---

## 4. `shared/` module

Plain Kotlin Multiplatform module, **wasmJs target only** (and JVM
implicitly via stdlib). No Compose deps. Exists so the data classes are
reused without reaching into the UI module.

### `Models.kt`

Mirror of the Rust `models.rs`. Every `@Serializable` field that's in
camelCase on the wire matches the Rust `#[serde(rename = ...)]`.

| Type | Notes |
|------|-------|
| `FolderNode` | Recursive `subFolders`. |
| `MockRule` | `createdAt: Long` is millis epoch. Default `method = "GET"`. |
| `FlowStep` | `isConsumed: Boolean` toggled by the BE; the UI treats it as read-only. |
| `Flow` | Container of steps. |
| `WorkspaceState` | What `GET /workspace` returns. |
| `InterceptedCall` | Payload of a log entry, also of one SSE event. |
| `ServerInfo` | `status` is one of `"stopped"|"starting"|"running"|"stopping"`. |
| `ApiResult` | Generic `{ok, message?}`. |
| `CreateFolderReq`, `RenameReq`, `ToggleReq`, `UpsertRuleReq`, `UpsertFlowReq`, `UpsertFlowStepReq`, `StartFlowReq` | API request payloads. Kept here (rather than in `composeApp/api/`) so only `:shared` needs the kotlinx-serialization plugin — that sidesteps a FIR/K2 NPE we hit on `composeApp` wasmJs. |

**Adding a field?** Add it on the corresponding type with the right
`@SerialName(...)` and a default, then mirror in
`mockmaster_backend/src/models.rs`. As long as both ends have a default,
the change is forward/backward compatible.

---

## 5. `composeApp/` module

### 5.1 `Theme.kt` — design tokens

```
MockColors = {
  accent          = #4F46E5  (indigo)
  accentDark      = #3730A3
  surface         = #FFFFFF
  surfaceMuted    = #F1F5F9
  surfaceMuted2   = #F8FAFC
  border          = #E2E8F0
  textPrimary     = #0F172A
  textSecondary   = #334155
  success         = #10B981  (emerald)
  danger          = #E11D48  (rose)
  warning         = #F59E0B  (amber)
}
```

`MockMasterTheme { content }` wraps `MaterialTheme` with our
`MockColorScheme` (light) and `MockTypography`. Every screen in `ui/`
uses `MaterialTheme.typography` for fonts and `MockColors.*` for colours.

Want dark mode? Add a `darkColorScheme(...)` + a flag on `AppState` that
the user can toggle from Settings. The screens already compose against
`MaterialTheme`, so most of them flip automatically; the few `MockColors.*`
references would need a "dark or light" lookup helper.

### 5.2 `api/MockMasterApi.kt` — backend client

`object ApiConfig { var baseUrl }` — defaults to `http://127.0.0.1:3000`,
mutable so the Settings screen can repoint it.

A single shared `HttpClient { install(ContentNegotiation) { json(Json {ignoreUnknownKeys=true; encodeDefaults=true}) } }`. No platform-specific
engine block — Ktor 3 picks `js` for wasmJs automatically.

Every BE endpoint has a typed suspend function:

| Function | Hits | Returns |
|----------|------|---------|
| `fetchWorkspace()` | `GET /workspace` | `WorkspaceState` |
| `serverInfo()` | `GET /server/info` | `ServerInfo` |
| `connectProxy()` / `disconnectProxy()` | `POST /server/connect|disconnect` | `ApiResult` |
| `createFolder(name, parentId)` | `POST /folders` | `FolderNode` |
| `renameFolder(id, name)` | `PUT /folders/:id` | `ApiResult` |
| `toggleFolder(id, enabled)` | `POST /folders/:id/toggle` | `ApiResult` |
| `deleteFolder(id)` | `DELETE /folders/:id` | `ApiResult` |
| `listRules(folderId)` | `GET /folders/:id/rules` | `[MockRule]` |
| `upsertRule(folderId, req)` | `POST /folders/:id/rules` | `MockRule` |
| `deleteRule(folderId, ruleId)` | `DELETE /folders/:id/rules/:rule_id` | `ApiResult` |
| `toggleRule(folderId, ruleId, enabled)` | `POST .../toggle` | `ApiResult` |
| `listFlows()` | `GET /flows` | `[Flow]` |
| `upsertFlow(req)` | `POST /flows` | `Flow` |
| `deleteFlow(id)` | `DELETE /flows/:id` | `ApiResult` |
| `startFlow(id)` / `stopFlow()` | `POST /flows/start|stop` | `ApiResult` |
| `listLogs()` | `GET /logs` | `[InterceptedCall]` |
| `clearLogs()` | `DELETE /logs/clear` | `ApiResult` |
| `streamLogsUrl()` | returns the URL string for SSE — opened by the wasmJs platform side |
| `certUrl()` | returns the URL string for the cert download |

The UI **always uses POST** for upsert (create + edit) — only the path is
different. The PUT-with-id route exists on the BE for direct API users.

### 5.3 `state/AppState.kt` — single source of UI truth

A class held in a `remember { AppState(scope) }` at the root of `App()`.
It owns:

- `var workspace: WorkspaceState` — refreshed after every mutation.
- `var serverInfo: ServerInfo?` — polled every 4 s.
- `val logs = mutableStateListOf<InterceptedCall>()` — filled by the SSE
  stream; capped at 500 (oldest dropped).
- `var loading: Boolean` — true during `bootstrap()`.
- `var lastError: String?` — shown as the red toast at the bottom.
- `var toast: String?` — auto-clears after 2.5 s.
- Selection: `selectedFolderId`, `selectedFlowId`.
- Dialog state for FoldersScreen: `createFolderDialogParentId`
  (`"__root__"` means create a top-level folder), `editingRule:
  Pair<String, MockRule?>?`, `deleteFolderConfirmId`, `deleteRuleConfirm`,
  `renamingFolderId`, `renamingFolderInitial`. Each has an open/close
  helper that the UI calls.

Operations are fire-and-forget `scope.launch { … }` blocks that:

1. Call the typed API.
2. On success, `refreshWorkspace()` and show a toast.
3. On failure, show an error toast.

Order of mutations matters less than you might think because every
operation re-fetches the entire workspace; the UI is therefore eventually
consistent within one round-trip.

The two pure helpers are also here:

- `rulesFor(workspace, folderId): List<MockRule>` — lookup with empty
  fallback.
- `effectivelyEnabled(folders, id): Boolean` — exact mirror of the
  backend's `enabled_folder_ids`. Used to grey out endpoint rows when an
  ancestor is disabled.

### 5.4 `App.kt` — bootstrap

```
@Composable
fun App() {
  MockMasterTheme {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }

    LaunchedEffect(Unit) {
      state.bootstrap()              // GET /workspace + GET /server/info + GET /logs
      installLiveLogStream(state)    // expect/actual: opens SSE on wasmJs
    }

    LaunchedEffect(Unit) {
      while (true) { delay(4000); state.refreshServerInfo() }
    }

    if (state.loading) <CircularProgressIndicator + msg>
    else AppScaffold(state)

    state.lastError?.let { <red toast> }
  }
}

expect fun installLiveLogStream(state: AppState)
```

The `expect` makes it possible to swap in a JVM/desktop platform later
without touching common code.

### 5.5 `wasmJsMain/Main.kt`

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) { App() }
}
```

`ComposeViewport` mounts the Compose canvas onto `<body>`.

### 5.6 `wasmJsMain/LiveLogStream.wasmJs.kt`

`actual fun installLiveLogStream(state)` opens a browser
`EventSource(url)` via a JS bridge function. Each `onmessage` event is
parsed as `InterceptedCall` and prepended to `state.logs`. Errors are
swallowed because EventSource auto-reconnects.

```kotlin
@JsFun("(url, onMessage, onError) => { ... return new EventSource(url) }")
external fun openEventSource(...)
```

If you want a JVM target later, write `actual fun installLiveLogStream`
in `jvmMain` using `okhttp-sse` or similar.

### 5.7 `wasmJsMain/resources/index.html`

Minimal host page that loads `composeApp.js`. The webpack dev server and
production dist both serve this as `/`.

---

## 6. UI screens

All screens live under `ui/` and take `state: AppState` as input.

### 6.1 `Common.kt` — shared widgets

| Symbol | Purpose |
|--------|---------|
| `formatJsonOrNull(input)` | Pretty-prints JSON via kotlinx.serialization (`prettyPrint = true`, indent = 2 spaces). Returns `null` if invalid. Used by the rule editor and flow editor's "Format JSON" button, and by the log detail panel for nicer display. |
| `isValidJson(input)` | Convenience that returns `formatJsonOrNull != null`. |
| `MethodPill(method)` | Coloured chip for GET/POST/PUT/DELETE/PATCH with method-specific palette. |
| `StatusPill(status)` | Colour-codes by 2xx/3xx/4xx/5xx. |
| `StatusDot(running, label)` | Green/red dot + label, used in the top bar. |
| `Card { … }` | Bordered, rounded, white surface with default padding. |
| `SectionHeader(text)` | Styled title. |

### 6.2 `AppScaffold.kt` — top bar + side nav

```
enum class Tab { Folders, Flows, Logs, Docs, Settings }
```

Top bar has, left to right:

- "M" logo square
- Brand "MockMaster" + tagline
- `StatusDot` reading `serverInfo.status`
- `ServerInfoChip("Proxy", "<localIp>:<proxyPort>")` — copy this onto
  the phone
- `ServerInfoChip("API", "127.0.0.1:<apiPort>")`
- Active flow chip (warning colour) if `state.isFlowActive`
- A spacer
- **Connect / Disconnect** button (toggles `state.connect/disconnectProxy()`)
- **Download cert** button — opens `MockMasterApi.certUrl()` in the
  current tab via `kotlinx.browser.window.open(url, "_self")`

Side nav is a vertical column of the `Tab.entries`. The currently active
tab gets a tinted background.

A toast overlay is rendered on top of everything.

### 6.3 `FoldersScreen.kt` — folder tree + endpoints

Two columns:

- **Tree pane (320 dp wide)**: `FolderTreeNode` recursion with
  expand/collapse, a 16 dp folder icon (filled when effectively enabled,
  outlined when not), the name, the count of rules, and a `Switch`. When
  a node is selected, three action chips appear under it: New subfolder,
  Rename, Delete.
- **Endpoints pane (rest)**: header with folder name + status text, an
  "Add endpoint" button, then a list of `RuleRow`s sorted by
  `createdAt` desc.

`RuleRow` shows: `MethodPill`, `StatusPill`, monospace path, `Switch`,
edit icon, delete icon, then a 160-char body preview. Greyed out at 0.55
opacity when not active (rule disabled, parent disabled, or flow
running).

When `state.isFlowActive` is true, **everything is greyed out** and
mutating affordances are disabled — the user gets a hint at the top of
the pane.

Dialogs (rendered conditionally based on `AppState` dialog fields):

- `CreateFolderDialog(parentId, …)` — prompts for a name. Parent of
  `"__root__"` means new top-level folder.
- `RenameFolderDialog(initial, …)` — prompts for a new name.
- `ConfirmDialog(...)` — used for Delete folder and Delete endpoint. Red
  destructive button.
- `RuleEditorDialog(...)` — opened from `state.editingRule`.

### 6.4 `RuleEditorDialog.kt` — endpoint authoring

A wide modal dialog (`Dialog` with `usePlatformDefaultWidth = false`,
clamped to 600–880 dp). Fields:

- Method dropdown (GET/POST/PUT/DELETE/PATCH).
- URL path (free text).
- Status code (digits only, max 3).
- Response body — large monospace `OutlinedTextField` (360 dp tall).
- "Format JSON" button — reflects the body through
  `formatJsonOrNull`. Bad JSON is reported inline as `jsonError` text.
- Enabled toggle.

On Save it formats once more (so always-valid JSON gets persisted) and
calls `onSave(UpsertRuleReq)` which is `state.upsertRule(folderId, req)`.

If you want to add a "Delay (ms)" or "Custom headers" field, this is
where: add it on `UpsertRuleReq` in shared, drop a new
`OutlinedTextField` here, default-populate from `rule?.delayMs`.

### 6.5 `FlowsScreen.kt` — flows

Two columns:

- **Flow list (320 dp)**: each `FlowListItem` shows the flow name, step
  count, and a `RUNNING` badge if `state.workspace.activeFlowId == flow.id`.
- **Detail pane**: header with name + description, Start/End button,
  Edit + Delete buttons. Below, a vertical list of `FlowStepRow`s, each
  numbered, with method/status pills, path, body preview, and a
  `consumed` text label when the flow is running and that step has been
  hit.

Buttons are state-aware:

- If this flow is running → only "End flow" + delete is shown red.
- If another flow is running → Start is disabled and a warning is shown.
- Otherwise Start is enabled.

`FlowEditorDialog` is the multi-step authoring dialog, scoped 720–1000
dp wide:

- Name + Description fields.
- "Add step" button → `drafts.add(StepDraft(...))`.
- Each `StepDraftRow` is a numbered card with method dropdown, path,
  status, body editor and per-step Format button.
- On Save, every draft is validated (path non-empty, status ∈
  100..599, body parseable as JSON via `formatJsonOrNull`). If any
  draft fails, save is a no-op (the existing dialog stays open). On
  success the dialog calls `onSave(UpsertFlowReq)`.

`StepDraft` is a private data class — not serializable, only used inside
the dialog.

### 6.6 `LogsScreen.kt` — intercepted calls

Two columns: a list (1.2 weight) and a detail pane (1 weight).

The list filters `state.logs` against a search bar (matches `path`,
`url`, or `method`, case-insensitive). Each `LogRow` is colour-edged:

- Green border = matched in folder
- Amber border = matched in active flow
- Grey border = passthrough (`PASS` badge instead of status pill)

Selecting a row pops the detail panel showing method, status, full URL,
timestamp, request headers (one `key: value` line each), request body
in a monospace block, and (if matched) the prettified mocked response.

The "Clear" button calls `state.clearLogs()` which hits
`DELETE /logs/clear` and empties the local list.

### 6.7 `DocsScreen.kt` — "How to Use"

Static-content screen broken into `Card` sections:

- Quick start with live `proxyHost`, `proxyPort`, `apiPort`, status, and
  a Download cert button.
- "What can it do?" bullets.
- Phone setup for Android.
- Phone setup for iOS.
- Laptop / browser setup.
- Folders vs flows guidance.
- Troubleshooting.

Updates to phrasing or extra OS sections go straight in here.

### 6.8 `SettingsScreen.kt`

Three cards:

- **Backend connection**: editable URL field; "Apply" sets
  `ApiConfig.baseUrl` and re-runs `state.bootstrap()`.
- **Server info**: live readout from `state.serverInfo`.
- **Workspace**: counts of folders (recursive via `countAllFolders`),
  total endpoints, flows, active flow, cached log entries.

---

## 7. Data flow examples

### Adding an endpoint

```
RuleEditorDialog (Save)
   └──► state.upsertRule(folderId, UpsertRuleReq(...))
            └──► MockMasterApi.upsertRule(folderId, req)
                     └──► POST /folders/:id/rules           (BE persists)
            └──► state.refreshWorkspace()
                     └──► MockMasterApi.fetchWorkspace()
                              └──► GET /workspace
            └──► state.showToast("Endpoint added")
```

The next intercepted request through the proxy already sees the new rule
because the BE updates its in-memory `vault` synchronously.

### Live log stream

```
Browser EventSource ──► GET /logs/stream  (held open as SSE)
                          ▲
                          │ broadcast::Sender<InterceptedCall>
                          │
              proxy.MockHandler.handle_request   (fires every intercepted call)

LiveLogStream.wasmJs.kt:
  onmessage(rawJson) -> Json.decodeFromString(InterceptedCall.serializer(), raw)
                     -> state.appendLog(call)   (prepend, cap at 500)
```

### Starting a flow

```
FlowsScreen "Start flow" button
   └──► state.startFlow(flow.id)
           └──► POST /flows/start   { flowId }
                    └──► BE resets is_consumed, sets active_flow_id
           └──► state.refreshWorkspace()
                    └──► UI now sees state.isFlowActive == true
                           └──► FoldersScreen greys out, top bar shows the flow chip,
                                FoldersScreen rows have switches disabled
```

Stop flow is symmetric.

---

## 8. How to extend (recipes)

### Add a new tab

1. Add an entry to the `Tab` enum in `AppScaffold.kt`.
2. Add a new `MyScreen.kt` under `ui/` with `@Composable fun
   MyScreen(state: AppState)`.
3. Add a branch in the `when (tab)` inside `AppScaffold`.

### Add a new field on a mock rule (e.g. response delay)

1. Add `val delayMs: Int = 0` to `MockRule` in `shared/Models.kt` plus
   the matching `delayMs` on `UpsertRuleReq`.
2. Mirror in Rust `models.rs` + `api.rs::UpsertRuleReq` + use it in
   `proxy.rs` (`tokio::time::sleep(Duration::from_millis(delay))`).
3. Add an `OutlinedTextField` for "Delay (ms)" in
   `RuleEditorDialog.kt`.
4. Optionally show it in `RuleRow` next to the method/status pills.

### Add a "Duplicate endpoint" action

1. Add `fun duplicateRule(folderId: String, rule: MockRule)` to
   `AppState.kt` that calls `upsertRule` with `id = null` and `path =
   "${rule.path}-copy"`.
2. Add an icon button in `RuleRow` that calls it.

### Wire up a JVM/desktop target

1. Add a `jvm` target to `composeApp/build.gradle.kts` with
   `application { … }`.
2. Create `composeApp/src/jvmMain/kotlin/.../LiveLogStream.jvm.kt`
   implementing `actual fun installLiveLogStream` with a Ktor SSE
   client.
3. Use `application { mainClass = "...AppKt" }` and add a
   `desktopMain` entry that calls `application { Window { App() } }`.

### Persist UI prefs (e.g. last selected tab)

The wasm runtime has `kotlinx.browser.window.localStorage`. Add a tiny
helper module that reads/writes it from `AppState.bootstrap()` /
mutators.

---

## 9. Build & run

The launcher script is the easy path:

```bash
./mockmaster.sh start    # builds (if needed) + runs BE and UI
./mockmaster.sh test     # smoke test through the live proxy
./mockmaster.sh logs ui  # tail the static-server log
./mockmaster.sh stop
```

Manual:

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentExecutableDistribution
cd composeApp/build/dist/wasmJs/developmentExecutable
python3 -m http.server 5173
# open http://127.0.0.1:5173
```

Hot reload during dev (binds to :8080 by default — careful, that's the
proxy port — pass `--port`):

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun -t \
  -Pcompose.web.dev.port=8090
```

Production-quality build (smaller, takes longer):

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
# output: composeApp/build/dist/wasmJs/productionExecutable/
```

---

## 10. Known issues / non-goals

- No offline / disconnected mode. If the BE is down the UI is just a
  static page with retry buttons.
- No optimistic UI for mutations — every change is "send, wait for ok,
  refresh". Snappy on localhost; would need rework for a remote BE.
- No drag-to-reorder folders or flow steps yet (the structures support
  it; just need a `MOVE` API and a draggable list).
- No keyboard shortcuts (Cmd+K palette, etc.). All-mouse for now.
- The wasm bundle is ~7 MB on first load. Compose+Skia is heavy by
  nature.
- `installLiveLogStream` is wasmJs-only (no JVM/desktop actual). Add it
  if you target desktop.
