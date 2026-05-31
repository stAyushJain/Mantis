#!/usr/bin/env bash
# Mantis launcher — starts the Rust backend and serves the Compose UI.
# Re-run safely: it kills any previous instance it owns before starting.

set -euo pipefail

# Paths default to this repo (script's own directory). Override with env vars.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
BE_DIR="${MANTIS_BE_DIR:-${MOCKMASTER_BE_DIR:-$SCRIPT_DIR/backend}}"
UI_DIR="${MANTIS_UI_DIR:-${MOCKMASTER_UI_DIR:-$SCRIPT_DIR}}"
UI_PORT="${MANTIS_UI_PORT:-${MOCKMASTER_UI_PORT:-5173}}"
LOG_DIR="${MANTIS_LOG_DIR:-${MOCKMASTER_LOG_DIR:-/tmp/mantis}}"
PID_DIR="$LOG_DIR"

mkdir -p "$LOG_DIR"

C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_CYAN=$'\033[36m'

log()  { printf "%s[mantis]%s %s\n" "$C_CYAN" "$C_RESET" "$*"; }
warn() { printf "%s[mantis]%s %s\n" "$C_YELLOW" "$C_RESET" "$*"; }
err()  { printf "%s[mantis]%s %s\n" "$C_RED" "$C_RESET" "$*"; }
ok()   { printf "%s[mantis]%s %s\n" "$C_GREEN" "$C_RESET" "$*"; }

usage() {
    cat <<EOF
${C_BOLD}Mantis launcher${C_RESET}

Usage: $(basename "$0") <command>

Commands:
  start         Build UI (if needed), launch backend + UI server, open browser
  stop          Stop backend + UI server started by this script
  restart       stop + start
  status        Show what's running and key URLs
  logs [be|ui]  Tail backend or UI server logs (default: backend)
  rebuild       Force a full UI rebuild, then start
  cert          Download the root CA and tell macOS to trust it
  test          Quick end-to-end smoke test through the live proxy
  uninstall-cert  Remove the Mantis CA from the macOS System keychain

Environment overrides:
  MANTIS_BE_DIR    default: $BE_DIR
  MANTIS_UI_DIR    default: $UI_DIR
  MANTIS_UI_PORT   default: $UI_PORT
  MANTIS_LOG_DIR   default: $LOG_DIR
EOF
}

# ----- helpers -----

is_port_open() {
    local port="$1"
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
}

wait_for_port() {
    local port="$1" timeout="${2:-30}" name="${3:-service}"
    local i=0
    while ! is_port_open "$port"; do
        i=$((i + 1))
        if [ "$i" -ge "$timeout" ]; then
            err "$name on :$port did not come up in ${timeout}s"
            return 1
        fi
        sleep 1
    done
    ok "$name on :$port is up"
}

pid_alive() {
    local pid_file="$1"
    [ -f "$pid_file" ] || return 1
    local pid; pid=$(cat "$pid_file" 2>/dev/null || true)
    [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1
}

stop_pid_file() {
    local pid_file="$1" name="$2"
    if pid_alive "$pid_file"; then
        local pid; pid=$(cat "$pid_file")
        log "stopping $name (pid $pid)"
        kill "$pid" 2>/dev/null || true
        # give it 5s, then SIGKILL
        for _ in 1 2 3 4 5; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pid_file"
}

local_ip() {
    # First non-loopback IPv4
    ipconfig getifaddr en0 2>/dev/null \
        || ipconfig getifaddr en1 2>/dev/null \
        || hostname -I 2>/dev/null | awk '{print $1}' \
        || echo "127.0.0.1"
}

# ----- backend -----

start_be() {
    if pid_alive "$PID_DIR/be.pid"; then
        ok "backend already running (pid $(cat "$PID_DIR/be.pid"))"
        return
    fi
    if is_port_open 3000; then
        warn "port 3000 already in use by another process — will not start a second backend"
        warn "free it with: lsof -i :3000   then re-run"
        return 1
    fi
    if [ ! -d "$BE_DIR" ]; then
        err "backend dir not found: $BE_DIR"
        return 1
    fi

    log "building backend (cargo build, may take a minute on first run)..."
    (cd "$BE_DIR" && cargo build --quiet) >>"$LOG_DIR/be-build.log" 2>&1

    log "starting backend..."
    # Run the prebuilt binary directly so the PID we record is the real
    # process (cargo exec's into the binary, leaving the cargo PID dead).
    local bin="$BE_DIR/target/debug/mockmaster_backend"
    if [ ! -x "$bin" ]; then
        err "backend binary not found at $bin"
        return 1
    fi
    (cd "$BE_DIR" && nohup "$bin" >"$LOG_DIR/be.log" 2>&1 </dev/null &
        echo $! >"$PID_DIR/be.pid")
    disown >/dev/null 2>&1 || true

    wait_for_port 3000 60 "backend API"
    wait_for_port 8080 5  "MITM proxy" || warn "proxy didn't come up — check $LOG_DIR/be.log"
}

stop_be() {
    stop_pid_file "$PID_DIR/be.pid" "backend"
    # Belt-and-braces: kill anything still bound to our ports.
    pkill -f "target/debug/mockmaster_backend" >/dev/null 2>&1 || true
}

# ----- UI -----

ui_dist_dir() {
    # Prefer production output (smaller, what we ship), fall back to dev for back-compat.
    local prod="$UI_DIR/composeApp/build/dist/wasmJs/productionExecutable"
    local dev="$UI_DIR/composeApp/build/dist/wasmJs/developmentExecutable"
    if [ -f "$prod/composeApp.js" ]; then echo "$prod"
    elif [ -f "$dev/composeApp.js" ]; then echo "$dev"
    else echo "$prod"  # not built yet — return the path we'll build into
    fi
}

build_ui() {
    if [ ! -d "$UI_DIR" ]; then
        err "UI dir not found: $UI_DIR"
        return 1
    fi
    log "building UI (Compose wasmJs production distribution, takes a few minutes the first time)..."
    (cd "$UI_DIR" && ./gradlew :composeApp:wasmJsBrowserDistribution) \
        2>&1 | tee "$LOG_DIR/ui-build.log" | grep -E "BUILD|FAIL|error:" || true
    if [ ! -f "$(ui_dist_dir)/composeApp.js" ]; then
        err "UI build did not produce composeApp.js — see $LOG_DIR/ui-build.log"
        return 1
    fi
    ok "UI build done: $(ui_dist_dir)"
}

start_ui() {
    if pid_alive "$PID_DIR/ui.pid"; then
        ok "UI server already running on http://127.0.0.1:$UI_PORT (pid $(cat "$PID_DIR/ui.pid"))"
        return
    fi
    if is_port_open "$UI_PORT"; then
        warn "port $UI_PORT in use; serving via that existing process"
        return
    fi

    if [ ! -f "$(ui_dist_dir)/composeApp.js" ]; then
        build_ui
    fi

    log "serving UI on http://127.0.0.1:$UI_PORT"
    (cd "$(ui_dist_dir)" && nohup python3 -m http.server "$UI_PORT" >"$LOG_DIR/ui.log" 2>&1 </dev/null &
        echo $! >"$PID_DIR/ui.pid")
    disown >/dev/null 2>&1 || true

    wait_for_port "$UI_PORT" 10 "UI server"
}

stop_ui() {
    stop_pid_file "$PID_DIR/ui.pid" "UI server"
    pkill -f "http.server $UI_PORT" >/dev/null 2>&1 || true
}

# ----- commands -----

cmd_start() {
    start_be
    start_ui
    local ip; ip=$(local_ip)
    cat <<EOF

${C_BOLD}${C_GREEN}Mantis is up.${C_RESET}

  UI         http://127.0.0.1:$UI_PORT
  API        http://127.0.0.1:3000
  Proxy      http://$ip:8080  (point your phone at this)
  Cert       http://127.0.0.1:3000/cert
  Logs       $LOG_DIR/be.log   $LOG_DIR/ui.log

Open the UI now? (it'll open in your default browser)
EOF
    if command -v open >/dev/null 2>&1; then
        open "http://127.0.0.1:$UI_PORT" >/dev/null 2>&1 || true
    fi
}

cmd_stop() {
    stop_ui
    stop_be
    ok "stopped"
}

cmd_restart() {
    cmd_stop
    cmd_start
}

cmd_status() {
    local ip; ip=$(local_ip)
    printf "%s%s%s\n" "$C_BOLD" "Mantis status" "$C_RESET"
    if pid_alive "$PID_DIR/be.pid"; then
        ok "backend  pid=$(cat "$PID_DIR/be.pid")  api=:3000  proxy=:8080  ip=$ip"
    else
        warn "backend  not running"
    fi
    if pid_alive "$PID_DIR/ui.pid"; then
        ok "UI       pid=$(cat "$PID_DIR/ui.pid")  http://127.0.0.1:$UI_PORT"
    else
        warn "UI       not running"
    fi
    if is_port_open 3000; then
        local info; info=$(curl -s --max-time 1 http://127.0.0.1:3000/server/info || true)
        [ -n "$info" ] && printf "%s\n" "  $info"
    fi
}

cmd_logs() {
    local which="${1:-be}"
    case "$which" in
        be|backend) tail -f "$LOG_DIR/be.log" ;;
        ui)         tail -f "$LOG_DIR/ui.log" ;;
        build)      tail -f "$LOG_DIR/ui-build.log" ;;
        *) err "unknown log target: $which (use be|ui|build)"; exit 1 ;;
    esac
}

cmd_rebuild() {
    cmd_stop
    rm -rf "$UI_DIR/composeApp/build/dist"
    build_ui
    cmd_start
}

cmd_cert() {
    if ! is_port_open 3000; then
        err "backend not running — run \`$(basename "$0") start\` first"
        exit 1
    fi
    local out="$LOG_DIR/mockmaster-ca.crt"
    log "downloading cert..."
    curl -fsS http://127.0.0.1:3000/cert -o "$out"
    ok "saved $out"
    if command -v security >/dev/null 2>&1; then
        log "installing into the macOS System keychain (you'll be prompted for sudo)..."
        if sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain "$out"; then
            ok "cert trusted system-wide"
        else
            warn "automatic install failed — drag $out into Keychain Access manually and set 'Always Trust'"
        fi
    else
        warn "no \`security\` command found; install $out manually"
    fi
}

cmd_uninstall_cert() {
    log "removing MockMaster Proxy CA from System keychain..."
    sudo security delete-certificate -c "MockMaster Proxy CA" /Library/Keychains/System.keychain 2>/dev/null \
        && ok "removed" \
        || warn "no matching cert in System keychain (or you cancelled the prompt)"
}

cmd_test() {
    if ! is_port_open 3000; then
        err "backend not running — run \`$(basename "$0") start\` first"
        exit 1
    fi
    log "running smoke test through the live proxy..."

    # The proxy serves whichever user is active. Run the test under a
    # dedicated user and restore the previous active user when we're done.
    local U="smoke-test"
    PREV_USER=$(curl -s http://127.0.0.1:3000/users \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('active','default'))" \
        2>/dev/null || echo "default")
    log "switching active user to '$U' (was '$PREV_USER')"
    curl -s -X POST http://127.0.0.1:3000/users \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$U\"}" >/dev/null
    curl -s -X POST http://127.0.0.1:3000/server/active-user \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$U\"}" >/dev/null

    restore_user() {
        log "restoring active user to '${PREV_USER:-default}'"
        curl -s -X POST http://127.0.0.1:3000/server/active-user \
            -H 'Content-Type: application/json' \
            -d "{\"username\":\"${PREV_USER:-default}\"}" >/dev/null || true
    }
    trap restore_user EXIT

    # 1. create a folder
    local p_resp p_id
    p_resp=$(curl -s -X POST http://127.0.0.1:3000/folders \
        -H 'Content-Type: application/json' \
        -H "X-MockMaster-User: $U" \
        -d '{"name":"smoke-test","parentId":null}')
    p_id=$(echo "$p_resp" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
    log "created folder $p_id"

    # 2. add a rule
    local r_resp r_id
    r_resp=$(curl -s -X POST "http://127.0.0.1:3000/folders/$p_id/rules" \
        -H 'Content-Type: application/json' \
        -H "X-MockMaster-User: $U" \
        -d '{"path":"/smoke","method":"GET","statusCode":200,"body":"{\"v\":1}","isEnabled":true}')
    r_id=$(echo "$r_resp" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
    log "added rule $r_id"

    # 3. hit it through the proxy
    local body; body=$(curl -s -x http://127.0.0.1:8080 -k https://example.com/smoke)
    if [ "$body" = '{"v":1}' ]; then
        ok "initial mock served: $body"
    else
        err "expected {\"v\":1}, got: $body"
    fi

    # 4. live edit
    cat > "$LOG_DIR/edit.json" <<EOF
{"id":"$r_id","path":"/smoke","method":"GET","statusCode":201,"body":"{\"v\":2}","isEnabled":true}
EOF
    curl -s -X PUT "http://127.0.0.1:3000/folders/$p_id/rules/$r_id" \
        -H 'Content-Type: application/json' \
        -H "X-MockMaster-User: $U" \
        --data-binary @"$LOG_DIR/edit.json" >/dev/null
    body=$(curl -s -x http://127.0.0.1:8080 -k https://example.com/smoke)
    if [ "$body" = '{"v":2}' ]; then
        ok "live edit took effect: $body"
    else
        err "expected {\"v\":2}, got: $body"
    fi

    # 5. toggle off
    curl -s -X POST "http://127.0.0.1:3000/folders/$p_id/rules/$r_id/toggle" \
        -H 'Content-Type: application/json' \
        -H "X-MockMaster-User: $U" \
        -d '{"enabled":false}' >/dev/null
    local headers; headers=$(curl -s -x http://127.0.0.1:8080 -k -D - https://example.com/smoke -o /dev/null --max-time 5 || true)
    if echo "$headers" | grep -qi "X-MockMaster-Intercepted"; then
        err "rule was toggled off but proxy still intercepted"
    else
        ok "toggle off → passthrough confirmed"
    fi

    # 6. clean up
    curl -s -X DELETE "http://127.0.0.1:3000/folders/$p_id" \
        -H "X-MockMaster-User: $U" >/dev/null
    ok "cleaned up smoke-test folder"

    log "open the UI's Intercepted Calls tab — you should see 3 entries (2 mocked + 1 passthrough)"
}

# ----- entrypoint -----

cmd="${1:-}"
case "$cmd" in
    start)           cmd_start ;;
    stop)            cmd_stop ;;
    restart)         cmd_restart ;;
    status)          cmd_status ;;
    logs)            shift || true; cmd_logs "${1:-be}" ;;
    rebuild)         cmd_rebuild ;;
    cert)            cmd_cert ;;
    uninstall-cert)  cmd_uninstall_cert ;;
    test)            cmd_test ;;
    -h|--help|help|"") usage ;;
    *) err "unknown command: $cmd"; usage; exit 1 ;;
esac
