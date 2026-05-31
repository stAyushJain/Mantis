# Mantis

> A self-hosted HTTPS mocking tool for mobile and web testing.

Mantis is a MITM proxy plus a web UI: organise mocks into folders, build
sequential "flows" for stateful user journeys, and watch every intercepted
request live as you tap through your app. Each user gets their own
isolated workspace so multiple developers' mocks don't interfere with
each other.

- Folder-organised mocks with sub-folders, nested enable/disable
  cascading, and last-write-wins on duplicate endpoints.
- **Flows** — ordered sequences of mocks for stateful journeys (booking,
  signup, etc.). Steps consume one-per-call; the last step replays.
  While a flow is active, folder mocks are paused.
- **Per-user workspaces** — sign in with any username on first launch;
  state persists under `workspace_data/<user>/`.
- **Flow sharing** — copy a flow into another user's workspace from the
  Flows screen.
- **Live intercepted-call viewer** with full request headers, request
  body and the mocked response, streamed via SSE.
- **One-click root CA** download for installing on phones and laptops.

![Status](https://img.shields.io/badge/status-beta-yellow)
![License](https://img.shields.io/badge/license-MIT-blue)

## Quick start

You need: **Git**, **Rust toolchain** (`rustup`), **JDK 17+**, **Python 3**
(macOS ships it; Linux usually does too).

```bash
git clone https://github.com/stAyushJain/mantis.git
cd mantis
./mantis.sh start
```

That's it. The script:

1. Builds the Rust backend (first run: ~2 min while crates download).
2. Builds the Compose Wasm UI (first run: ~3–5 min).
3. Runs both, opens http://localhost:5173 in your browser.

Open the **Docs** tab inside the app for phone proxy + certificate
trust steps for Android and iOS.

### Run with Docker (no Rust/JDK needed)

```bash
docker compose up --build
```

This builds a single image containing both the Rust backend and the
Compose Wasm UI, runs them under `supervisord`, and exposes ports
`3000` (API), `5173` (UI), and `8080` (proxy). Workspace data is
mounted from `./mantis-data` on the host.

## Day-to-day commands

```bash
./mantis.sh start         # build (if needed) + run BE + UI + open browser
./mantis.sh status        # what's running and where
./mantis.sh logs be       # tail backend log
./mantis.sh logs ui       # tail UI server log
./mantis.sh restart       # quick cycle
./mantis.sh stop          # shut everything down
./mantis.sh rebuild       # force a clean UI rebuild
./mantis.sh test          # end-to-end smoke test through the live proxy
./mantis.sh cert          # download CA and trust it in macOS keychain (sudo)
```

## Project layout

```
mantis/
├── backend/          Rust MITM proxy + Axum REST/SSE control API
├── composeApp/       Compose Multiplatform (wasmJs) UI
├── shared/           kotlinx-serializable models (UI ↔ BE wire format)
├── docker/           supervisord config for the runtime image
├── Dockerfile        multi-stage: Rust build → Wasm UI build → slim runtime
├── docker-compose.yml
├── ARCHITECTURE.md   file-by-file UI reference
├── PROJECT.md        cross-stack overview (UI ↔ API ↔ proxy)
└── mantis.sh         local launcher
```

## Ports

| Port | Service                                         |
|------|-------------------------------------------------|
| 3000 | Backend REST/SSE API                            |
| 5173 | UI (static-served Compose Wasm bundle)          |
| 8080 | MITM HTTPS proxy — point your phone at this     |

Override with `MANTIS_UI_PORT`, `MANTIS_API_PORT`, `MANTIS_PROXY_PORT`,
or for the launcher: `MOCKMASTER_UI_PORT` (legacy name, still works).

## Toolchain

Kotlin 2.1.10 · Compose Multiplatform 1.7.3 · Ktor 3.0.3 ·
kotlinx-serialization 1.7.3 · Gradle 8.10.2 · Rust stable.

## SS
<img width="2543" height="1293" alt="Screenshot 2026-05-31 at 2 20 04 PM" src="https://github.com/user-attachments/assets/2066a361-1658-41d4-91fb-bd889710aadb" />
<img width="2545" height="1314" alt="Screenshot 2026-05-31 at 2 19 52 PM" src="https://github.com/user-attachments/assets/4d2f5dd4-9c86-461a-b33c-8e25fefa9e55" />
<img width="2537" height="1315" alt="Screenshot 2026-05-31 at 2 19 30 PM" src="https://github.com/user-attachments/assets/6b9a5b74-8322-43df-95c3-771a16942277" />
<img width="2547" height="1313" alt="Screenshot 2026-05-31 at 2 19 08 PM" src="https://github.com/user-attachments/assets/705c5e62-7c46-4a11-8200-0ce17c7a764f" />
<img width="802" height="703" alt="Screenshot 2026-05-31 at 2 07 39 PM" src="https://github.com/user-attachments/assets/bcf45498-9940-4ee8-bda8-e2222a68d772" />


## Status

This is a **beta**. The protocol, API, and storage format may change
without warning. Bug reports and PRs welcome.

## License

[MIT](./LICENSE).
