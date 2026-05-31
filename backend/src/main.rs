mod api;
mod models;
mod proxy;
mod storage;
mod workspace;

use std::collections::{HashMap, VecDeque};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use axum::{
    http::Method,
    routing::{delete, get, post, put},
    Router,
};
use hudsucker::{
    certificate_authority::RcgenAuthority, Proxy,
};
use rcgen::{BasicConstraints, CertificateParams, IsCa};
use tokio::sync::{broadcast, mpsc};
use tower_http::cors::{Any, CorsLayer};

use crate::models::{InterceptedCall, ProxyStatus, WorkspaceState};
use crate::storage::{CA_CERT_FILE, CA_KEY_FILE, DEFAULT_USER};

#[derive(Clone)]
pub enum ProxyCommand {
    Start,
    Stop,
}

/// Per-user vaults are loaded lazily and kept in memory. The map itself is
/// behind one Mutex; each user's WorkspaceState gets its own Mutex so users
/// don't block each other once loaded.
pub type UserVault = Arc<Mutex<WorkspaceState>>;
pub type VaultRegistry = Arc<Mutex<HashMap<String, UserVault>>>;

#[derive(Clone)]
pub struct AppState {
    /// Map of sanitized username -> vault.
    pub vaults: VaultRegistry,
    /// The user the proxy currently impersonates. The UI sets this on login.
    pub active_user: Arc<Mutex<String>>,
    pub log_tx: broadcast::Sender<InterceptedCall>,
    pub log_buffer: Arc<Mutex<VecDeque<InterceptedCall>>>,
    pub proxy_status: Arc<Mutex<ProxyStatus>>,
    pub proxy_cmd: mpsc::Sender<ProxyCommand>,
    pub proxy_port: u16,
    pub api_port: u16,
}

impl AppState {
    /// Get (or lazily load) the vault for a username. Always returns a vault.
    pub fn vault_for(&self, username: &str) -> UserVault {
        let user = storage::sanitize_username(username);
        let mut map = self.vaults.lock().unwrap();
        if let Some(v) = map.get(&user) {
            return v.clone();
        }
        let loaded = storage::load_user_workspace(&user);
        let arc = Arc::new(Mutex::new(loaded));
        map.insert(user, arc.clone());
        arc
    }
}

const API_PORT: u16 = 3000;
const PROXY_PORT: u16 = 8080;

fn ensure_ca_certificate() {
    use std::fs;
    use std::path::Path;
    if Path::new(CA_CERT_FILE).exists() && Path::new(CA_KEY_FILE).exists() {
        println!("Loaded existing MockMaster Root CA");
        return;
    }
    println!("Generating new MockMaster Root CA...");
    storage::init_workspace_root();

    let mut params = CertificateParams::new(Vec::<String>::new()).expect("init params");
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, "MockMaster Proxy CA");
    params
        .distinguished_name
        .push(rcgen::DnType::OrganizationName, "MockMaster Local");
    params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    params.key_usages.push(rcgen::KeyUsagePurpose::KeyCertSign);
    params.key_usages.push(rcgen::KeyUsagePurpose::DigitalSignature);
    params.key_usages.push(rcgen::KeyUsagePurpose::CrlSign);

    let key_pair = rcgen::KeyPair::generate().expect("keypair");
    let cert = params.self_signed(&key_pair).expect("ca cert");
    fs::write(CA_CERT_FILE, cert.pem()).expect("write cert");
    fs::write(CA_KEY_FILE, key_pair.serialize_pem()).expect("write key");
    println!("Root CA created.");
}

fn build_router(app: AppState) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods([
            Method::GET,
            Method::POST,
            Method::PUT,
            Method::DELETE,
            Method::OPTIONS,
        ])
        .allow_headers(Any);

    Router::new()
        // Users (pseudo-auth)
        .route("/users", get(api::list_users).post(api::ensure_user))
        .route("/users/:name", get(api::user_exists))
        // Workspace bulk
        .route("/workspace", get(api::get_workspace).put(api::put_workspace))
        // Folders
        .route("/folders", post(api::create_folder))
        .route(
            "/folders/:id",
            put(api::rename_folder).delete(api::delete_folder),
        )
        .route("/folders/:id/toggle", post(api::toggle_folder))
        // Rules
        .route(
            "/folders/:id/rules",
            get(api::list_rules).post(api::upsert_rule),
        )
        .route(
            "/folders/:id/rules/:rule_id",
            put(api::put_rule).delete(api::delete_rule),
        )
        .route(
            "/folders/:id/rules/:rule_id/toggle",
            post(api::toggle_rule),
        )
        // Flows
        .route("/flows", get(api::list_flows).post(api::upsert_flow))
        .route("/flows/:id", put(api::put_flow).delete(api::delete_flow))
        .route("/flows/:id/share", post(api::share_flow))
        .route("/flows/start", post(api::start_flow))
        .route("/flows/stop", post(api::end_flow))
        // Server lifecycle
        .route("/server/info", get(api::server_info))
        .route("/server/connect", post(api::connect_proxy))
        .route("/server/disconnect", post(api::disconnect_proxy))
        .route("/server/active-user", post(api::set_active_user))
        // Cert
        .route("/cert", get(api::download_cert))
        // Logs
        .route("/logs", get(api::list_logs))
        .route("/logs/clear", delete(api::clear_logs))
        .route("/logs/stream", get(api::stream_logs))
        .layer(cors)
        .with_state(app)
}

fn build_and_spawn_proxy(
    addr: SocketAddr,
    handler: proxy::MockHandler,
    shutdown: tokio::sync::oneshot::Receiver<()>,
    proxy_status: Arc<Mutex<ProxyStatus>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    use std::fs;
    let ca_key_pem = fs::read_to_string(CA_KEY_FILE)?;
    let key_pair = rcgen::KeyPair::from_pem(&ca_key_pem)?;
    let mut params = CertificateParams::new(Vec::<String>::new())?;
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, "MockMaster Proxy CA");
    params
        .distinguished_name
        .push(rcgen::DnType::OrganizationName, "MockMaster Local");
    params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    params.key_usages.push(rcgen::KeyUsagePurpose::KeyCertSign);
    params.key_usages.push(rcgen::KeyUsagePurpose::DigitalSignature);
    params.key_usages.push(rcgen::KeyUsagePurpose::CrlSign);

    let issuer = rcgen::Issuer::new(params, key_pair);
    let ca = RcgenAuthority::new(issuer, 1_000_u64, rustls::crypto::ring::default_provider());

    let proxy = Proxy::builder()
        .with_addr(addr)
        .with_ca(ca)
        .with_rustls_connector(rustls::crypto::ring::default_provider())
        .with_http_handler(handler)
        .with_graceful_shutdown(async move {
            let _ = shutdown.await;
        })
        .build()?;

    tokio::spawn(async move {
        *proxy_status.lock().unwrap() = ProxyStatus::Running;
        println!("MITM proxy on http://0.0.0.0:{}", addr.port());
        if let Err(e) = proxy.start().await {
            eprintln!("Proxy ended: {e}");
        }
        *proxy_status.lock().unwrap() = ProxyStatus::Stopped;
        println!("Proxy stopped.");
    });
    Ok(())
}

#[tokio::main]
async fn main() {
    rustls::crypto::ring::default_provider()
        .install_default()
        .ok();

    ensure_ca_certificate();

    // Pre-load every existing user so they're hot in memory, plus the default.
    let vaults: VaultRegistry = Arc::new(Mutex::new(HashMap::new()));
    {
        let mut map = vaults.lock().unwrap();
        let mut users = storage::list_users();
        if !users.iter().any(|u| u == DEFAULT_USER) {
            users.push(DEFAULT_USER.to_string());
        }
        for user in users {
            let ws = storage::load_user_workspace(&user);
            map.insert(user, Arc::new(Mutex::new(ws)));
        }
    }

    let active_user = Arc::new(Mutex::new(DEFAULT_USER.to_string()));
    let (log_tx, _rx) = broadcast::channel::<InterceptedCall>(256);
    let log_buffer = Arc::new(Mutex::new(VecDeque::with_capacity(proxy::LOG_BUFFER_CAPACITY)));
    let proxy_status = Arc::new(Mutex::new(ProxyStatus::Stopped));

    let (cmd_tx, mut cmd_rx) = mpsc::channel::<ProxyCommand>(8);

    let app_state = AppState {
        vaults: vaults.clone(),
        active_user: active_user.clone(),
        log_tx: log_tx.clone(),
        log_buffer: log_buffer.clone(),
        proxy_status: proxy_status.clone(),
        proxy_cmd: cmd_tx.clone(),
        proxy_port: PROXY_PORT,
        api_port: API_PORT,
    };

    let api_app = build_router(app_state.clone());
    let api_addr = SocketAddr::from(([0, 0, 0, 0], API_PORT));
    tokio::spawn(async move {
        let listener = tokio::net::TcpListener::bind(api_addr).await.unwrap();
        println!("API server on http://0.0.0.0:{API_PORT}");
        axum::serve(listener, api_app).await.unwrap();
    });

    let _ = cmd_tx.send(ProxyCommand::Start).await;

    let proxy_addr = SocketAddr::from(([0, 0, 0, 0], PROXY_PORT));
    let mut shutdown_tx: Option<tokio::sync::oneshot::Sender<()>> = None;

    while let Some(cmd) = cmd_rx.recv().await {
        match cmd {
            ProxyCommand::Start => {
                let cur = proxy_status.lock().unwrap().clone();
                if matches!(cur, ProxyStatus::Running | ProxyStatus::Starting) {
                    println!("Proxy already running.");
                    continue;
                }
                *proxy_status.lock().unwrap() = ProxyStatus::Starting;

                let (sd_tx, sd_rx) = tokio::sync::oneshot::channel::<()>();
                shutdown_tx = Some(sd_tx);

                let handler = proxy::MockHandler {
                    vaults: vaults.clone(),
                    active_user: active_user.clone(),
                    log_tx: log_tx.clone(),
                    log_buffer: log_buffer.clone(),
                };

                if let Err(e) = build_and_spawn_proxy(proxy_addr, handler, sd_rx, proxy_status.clone()) {
                    eprintln!("Failed to build proxy: {e}");
                    *proxy_status.lock().unwrap() = ProxyStatus::Stopped;
                }
            }
            ProxyCommand::Stop => {
                let cur = proxy_status.lock().unwrap().clone();
                if !matches!(cur, ProxyStatus::Running | ProxyStatus::Starting) {
                    println!("Proxy already stopped.");
                    continue;
                }
                *proxy_status.lock().unwrap() = ProxyStatus::Stopping;
                if let Some(tx) = shutdown_tx.take() {
                    let _ = tx.send(());
                }
            }
        }
    }
}
