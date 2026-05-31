# =============================================================================
# Mantis — single-image build.
# Three stages keep the final runtime image small (~80 MB):
#   1. be-build   — compile the Rust backend (release mode).
#   2. ui-build   — compile the Compose Multiplatform Wasm UI.
#   3. runtime    — Debian slim with the BE binary, the static UI, and a
#                   tiny supervisor that runs both processes.
# =============================================================================

# ---------- 1. Backend build (Rust) ----------
FROM rust:1.82-bookworm AS be-build

WORKDIR /src/backend

# Copy manifests first so cargo can cache deps across rebuilds.
COPY backend/Cargo.toml backend/Cargo.lock ./

# Pre-fetch dependencies for faster CI cache warming.
RUN mkdir -p src && echo "fn main() {}" > src/main.rs && \
    cargo build --release && \
    rm -rf src target/release/deps/mockmaster_backend* target/release/mockmaster_backend*

COPY backend/src ./src
RUN cargo build --release

# ---------- 2. UI build (Compose Multiplatform Wasm) ----------
FROM eclipse-temurin:21-jdk-jammy AS ui-build

# Gradle needs Python for some Wasm toolchain steps; install minimal extras.
RUN apt-get update && apt-get install -y --no-install-recommends \
    git curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# Copy Gradle wrapper + build files first so deps cache cleanly.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties local.properties* ./
COPY gradle ./gradle

# Module manifests (without sources yet) — best-effort dep cache.
COPY composeApp/build.gradle.kts ./composeApp/
COPY shared/build.gradle.kts ./shared/

# Pre-warm the Gradle wrapper.
RUN chmod +x ./gradlew && ./gradlew --version

# Now copy the actual source and build.
COPY composeApp ./composeApp
COPY shared ./shared

# Production webpack output → smaller bundle.
RUN ./gradlew :composeApp:wasmJsBrowserDistribution --no-daemon

# ---------- 3. Runtime ----------
FROM debian:bookworm-slim AS runtime

# We need: a process supervisor, ca-certs (for the proxy's outbound TLS), and
# a tiny static-file server for the UI. Python's http.server is already shipped
# in any reasonable Debian; we just install python3-minimal.
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    python3-minimal \
    supervisor \
    tini \
    && rm -rf /var/lib/apt/lists/*

# Non-root user keeps any volume-mount permissions sane.
RUN useradd --system --create-home --shell /usr/sbin/nologin --uid 10001 mantis

WORKDIR /app

# Backend binary.
COPY --from=be-build /src/backend/target/release/mockmaster_backend /app/mantis-backend

# UI static bundle.
COPY --from=ui-build /src/composeApp/build/dist/wasmJs/productionExecutable /app/ui

# Supervisor config (defined below as a heredoc so users can grep it easily).
COPY docker/supervisord.conf /etc/supervisor/conf.d/mantis.conf

# Workspace data lives outside the image so users keep their mocks across
# container re-creations. Compose mounts a host dir here.
RUN mkdir -p /app/workspace_data && chown -R mantis:mantis /app

# Default ports. Override at runtime via -p flags or compose port mappings.
# 3000 = REST/SSE API, 8080 = MITM proxy, 5173 = UI.
EXPOSE 3000 5173 8080

USER mantis

# tini reaps the children supervisord spawns; supervisord runs both BE and UI.
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/mantis.conf"]
