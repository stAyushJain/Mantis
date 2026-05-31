use axum::{
    extract::{Path, State},
    http::{header, HeaderMap, StatusCode},
    response::{sse::{Event, KeepAlive, Sse}, IntoResponse},
    Json,
};
use futures_util::stream::{self, Stream};
use serde::{Deserialize, Serialize};
use std::convert::Infallible;
use std::fs;
use tokio::sync::broadcast;

use crate::models::{
    Flow, FlowStep, FolderNode, InterceptedCall, MockRule, ServerInfo, WorkspaceState,
};
use crate::storage::{self, CA_CERT_FILE, DEFAULT_USER};
use crate::workspace as ws;
use crate::AppState;

const USER_HEADER: &str = "x-mockmaster-user";

/// Extract the username from the X-MockMaster-User header, falling back to
/// the "default" user. Names are sanitized so filesystem paths are safe.
fn user_from_headers(headers: &HeaderMap) -> String {
    let raw = headers
        .get(USER_HEADER)
        .and_then(|v| v.to_str().ok())
        .unwrap_or(DEFAULT_USER);
    storage::sanitize_username(raw)
}

// ---------- payloads ----------

#[derive(Deserialize)]
pub struct CreateFolderReq {
    pub name: String,
    #[serde(rename = "parentId")]
    pub parent_id: Option<String>,
}

#[derive(Deserialize)]
pub struct RenameFolderReq {
    pub name: String,
}

#[derive(Deserialize)]
pub struct ToggleReq {
    pub enabled: bool,
}

#[derive(Deserialize)]
pub struct UpsertRuleReq {
    pub id: Option<String>,
    pub path: String,
    #[serde(default = "default_method")]
    pub method: String,
    #[serde(rename = "statusCode")]
    pub status_code: u16,
    pub body: String,
    #[serde(rename = "isEnabled", default = "default_true")]
    pub is_enabled: bool,
}

#[derive(Deserialize)]
pub struct UpsertFlowReq {
    pub id: Option<String>,
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub steps: Vec<FlowStepReq>,
}

#[derive(Deserialize)]
pub struct FlowStepReq {
    pub id: Option<String>,
    pub path: String,
    #[serde(default = "default_method")]
    pub method: String,
    #[serde(rename = "statusCode")]
    pub status_code: u16,
    pub body: String,
}

fn default_method() -> String {
    "GET".to_string()
}
fn default_true() -> bool {
    true
}

#[derive(Serialize)]
pub struct ApiResult {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl ApiResult {
    pub fn ok() -> Self {
        Self { ok: true, message: None }
    }
    pub fn err(msg: impl Into<String>) -> Self {
        Self { ok: false, message: Some(msg.into()) }
    }
}

fn conflict(msg: impl Into<String>) -> (StatusCode, Json<ApiResult>) {
    (StatusCode::CONFLICT, Json(ApiResult::err(msg)))
}
fn not_found(msg: impl Into<String>) -> (StatusCode, Json<ApiResult>) {
    (StatusCode::NOT_FOUND, Json(ApiResult::err(msg)))
}

// ---------- Users (pseudo-auth) ----------

#[derive(Serialize)]
pub struct UsersList {
    pub users: Vec<String>,
    pub active: String,
}

pub async fn list_users(State(app): State<AppState>) -> Json<UsersList> {
    let users = storage::list_users();
    let active = app.active_user.lock().unwrap().clone();
    Json(UsersList { users, active })
}

#[derive(Deserialize)]
pub struct EnsureUserReq {
    pub username: String,
}

#[derive(Serialize)]
pub struct EnsureUserResp {
    pub username: String,
    pub created: bool,
}

/// Single-field create-on-first-use: idempotent. If the user exists, returns
/// `created: false`; otherwise creates the workspace dir and returns `created: true`.
pub async fn ensure_user(
    State(app): State<AppState>,
    Json(req): Json<EnsureUserReq>,
) -> Result<Json<EnsureUserResp>, (StatusCode, Json<ApiResult>)> {
    let user = storage::sanitize_username(&req.username);
    if user.is_empty() {
        return Err((StatusCode::BAD_REQUEST, Json(ApiResult::err("username cannot be empty"))));
    }
    let existed = storage::user_exists(&user);
    if !existed {
        storage::ensure_user_dir(&user);
    }
    // Make sure it's loaded into the registry so subsequent calls are fast.
    let _ = app.vault_for(&user);
    Ok(Json(EnsureUserResp {
        username: user,
        created: !existed,
    }))
}

#[derive(Serialize)]
pub struct UserExistsResp {
    pub exists: bool,
    pub username: String,
}

pub async fn user_exists(Path(name): Path<String>) -> Json<UserExistsResp> {
    let user = storage::sanitize_username(&name);
    Json(UserExistsResp {
        exists: storage::user_exists(&user),
        username: user,
    })
}

#[derive(Deserialize)]
pub struct SetActiveUserReq {
    pub username: String,
}

/// Tell the proxy which user it should match against. The UI calls this on
/// login so that the on-device proxy serves that user's mocks.
pub async fn set_active_user(
    State(app): State<AppState>,
    Json(req): Json<SetActiveUserReq>,
) -> Json<ApiResult> {
    let user = storage::sanitize_username(&req.username);
    storage::ensure_user_dir(&user);
    let _ = app.vault_for(&user);
    *app.active_user.lock().unwrap() = user;
    Json(ApiResult::ok())
}

// ---------- Workspace ----------

pub async fn get_workspace(
    State(app): State<AppState>,
    headers: HeaderMap,
) -> Json<WorkspaceState> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let state = vault.lock().unwrap();
    Json(state.clone())
}

pub async fn put_workspace(
    State(app): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<WorkspaceState>,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    {
        let mut state = vault.lock().unwrap();
        *state = payload.clone();
    }
    ws::persist_workspace(&user, &payload);
    Json(ApiResult::ok())
}

// ---- Folders ----

pub async fn create_folder(
    State(app): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<CreateFolderReq>,
) -> Result<Json<FolderNode>, (StatusCode, Json<ApiResult>)> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);

    let trimmed = req.name.trim();
    if trimmed.is_empty() {
        return Err((StatusCode::BAD_REQUEST, Json(ApiResult::err("name cannot be empty"))));
    }

    let new_folder = FolderNode {
        id: format!("f_{}", uuid::Uuid::new_v4()),
        name: trimmed.to_string(),
        is_enabled: true,
        sub_folders: vec![],
    };
    {
        let mut state = vault.lock().unwrap();
        if ws::sibling_name_taken(
            &state.folders,
            req.parent_id.as_deref(),
            trimmed,
            None,
        ) {
            return Err(conflict(format!(
                "A folder named '{}' already exists at this level",
                trimmed
            )));
        }
        let inserted = ws::insert_folder(
            &mut state.folders,
            req.parent_id.as_deref(),
            new_folder.clone(),
        );
        if !inserted {
            return Err(not_found("parent folder not found"));
        }
        state
            .rules_map
            .entry(new_folder.id.clone())
            .or_insert_with(Vec::new);
        storage::save_tree(&user, &state.folders);
        storage::save_folder_rules(&user, &new_folder.id, &[]);
    }
    Ok(Json(new_folder))
}

pub async fn rename_folder(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(req): Json<RenameFolderReq>,
) -> Result<Json<ApiResult>, (StatusCode, Json<ApiResult>)> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);

    let trimmed = req.name.trim();
    if trimmed.is_empty() {
        return Err((StatusCode::BAD_REQUEST, Json(ApiResult::err("name cannot be empty"))));
    }

    let mut state = vault.lock().unwrap();
    let parent = match ws::parent_of(&state.folders, &id) {
        Some(p) => p,
        None => return Err(not_found("folder not found")),
    };
    if ws::sibling_name_taken(&state.folders, parent.as_deref(), trimmed, Some(&id)) {
        return Err(conflict(format!(
            "A folder named '{}' already exists at this level",
            trimmed
        )));
    }
    let renamed = ws::with_folder_mut(&mut state.folders, &id, |f| f.name = trimmed.to_string());
    if renamed {
        storage::save_tree(&user, &state.folders);
        Ok(Json(ApiResult::ok()))
    } else {
        Err(not_found("folder not found"))
    }
}

pub async fn toggle_folder(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(req): Json<ToggleReq>,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let ok = {
        let mut state = vault.lock().unwrap();
        let ok = ws::with_folder_mut(&mut state.folders, &id, |f| f.is_enabled = req.enabled);
        if ok {
            storage::save_tree(&user, &state.folders);
        }
        ok
    };
    Json(if ok { ApiResult::ok() } else { ApiResult::err("folder not found") })
}

pub async fn delete_folder(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let removed_ids = {
        let mut state = vault.lock().unwrap();
        let removed_ids = ws::remove_folder(&mut state.folders, &id);
        for rid in &removed_ids {
            state.rules_map.remove(rid);
        }
        storage::save_tree(&user, &state.folders);
        removed_ids
    };
    for rid in &removed_ids {
        storage::delete_folder_rules_file(&user, rid);
    }
    Json(if removed_ids.is_empty() {
        ApiResult::err("folder not found")
    } else {
        ApiResult::ok()
    })
}

// ---- Rules ----

pub async fn list_rules(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(folder_id): Path<String>,
) -> Json<Vec<MockRule>> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let state = vault.lock().unwrap();
    Json(state.rules_map.get(&folder_id).cloned().unwrap_or_default())
}

pub async fn upsert_rule(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(folder_id): Path<String>,
    Json(req): Json<UpsertRuleReq>,
) -> Json<MockRule> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let rule = MockRule {
        id: req.id.unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
        path: req.path,
        method: req.method.to_uppercase(),
        status_code: req.status_code,
        body: req.body,
        is_enabled: req.is_enabled,
        created_at: chrono::Utc::now().timestamp_millis(),
    };
    {
        let mut state = vault.lock().unwrap();
        let bucket = state.rules_map.entry(folder_id.clone()).or_insert_with(Vec::new);
        ws::upsert_rule(bucket, rule.clone());
        let snapshot = bucket.clone();
        drop(state);
        storage::save_folder_rules(&user, &folder_id, &snapshot);
    }
    Json(rule)
}

pub async fn put_rule(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path((folder_id, rule_id)): Path<(String, String)>,
    Json(mut req): Json<UpsertRuleReq>,
) -> Json<MockRule> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    req.id = Some(rule_id);
    let rule = MockRule {
        id: req.id.unwrap(),
        path: req.path,
        method: req.method.to_uppercase(),
        status_code: req.status_code,
        body: req.body,
        is_enabled: req.is_enabled,
        created_at: chrono::Utc::now().timestamp_millis(),
    };
    {
        let mut state = vault.lock().unwrap();
        let bucket = state.rules_map.entry(folder_id.clone()).or_insert_with(Vec::new);
        ws::upsert_rule(bucket, rule.clone());
        let snapshot = bucket.clone();
        drop(state);
        storage::save_folder_rules(&user, &folder_id, &snapshot);
    }
    Json(rule)
}

pub async fn delete_rule(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path((folder_id, rule_id)): Path<(String, String)>,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let ok = {
        let mut state = vault.lock().unwrap();
        let bucket = state.rules_map.entry(folder_id.clone()).or_insert_with(Vec::new);
        let ok = ws::delete_rule(bucket, &rule_id);
        let snapshot = bucket.clone();
        drop(state);
        if ok {
            storage::save_folder_rules(&user, &folder_id, &snapshot);
        }
        ok
    };
    Json(if ok { ApiResult::ok() } else { ApiResult::err("rule not found") })
}

pub async fn toggle_rule(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path((folder_id, rule_id)): Path<(String, String)>,
    Json(req): Json<ToggleReq>,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let ok = {
        let mut state = vault.lock().unwrap();
        let bucket = state.rules_map.entry(folder_id.clone()).or_insert_with(Vec::new);
        let mut found = false;
        for r in bucket.iter_mut() {
            if r.id == rule_id {
                r.is_enabled = req.enabled;
                found = true;
                break;
            }
        }
        let snapshot = bucket.clone();
        drop(state);
        if found {
            storage::save_folder_rules(&user, &folder_id, &snapshot);
        }
        found
    };
    Json(if ok { ApiResult::ok() } else { ApiResult::err("rule not found") })
}

// ---- Flows ----

pub async fn list_flows(
    State(app): State<AppState>,
    headers: HeaderMap,
) -> Json<Vec<Flow>> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let state = vault.lock().unwrap();
    Json(state.flows.clone())
}

pub async fn upsert_flow(
    State(app): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<UpsertFlowReq>,
) -> Result<Json<Flow>, (StatusCode, Json<ApiResult>)> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);

    let trimmed_name = req.name.trim();
    if trimmed_name.is_empty() {
        return Err((StatusCode::BAD_REQUEST, Json(ApiResult::err("flow name cannot be empty"))));
    }

    let flow_id = req.id.clone().unwrap_or_else(|| uuid::Uuid::new_v4().to_string());

    {
        let state = vault.lock().unwrap();
        if ws::flow_name_taken(&state.flows, trimmed_name, Some(&flow_id)) {
            return Err(conflict(format!(
                "A flow named '{}' already exists",
                trimmed_name
            )));
        }
    }

    let steps: Vec<FlowStep> = req
        .steps
        .into_iter()
        .map(|s| FlowStep {
            id: s.id.unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
            path: s.path,
            method: s.method.to_uppercase(),
            status_code: s.status_code,
            body: s.body,
            is_consumed: false,
        })
        .collect();

    let flow = Flow {
        id: flow_id,
        name: trimmed_name.to_string(),
        description: req.description,
        steps,
        created_at: chrono::Utc::now().timestamp_millis(),
    };

    {
        let mut state = vault.lock().unwrap();
        ws::upsert_flow(&mut state.flows, flow.clone());
        let snapshot = state.flows.clone();
        drop(state);
        storage::save_flows(&user, &snapshot);
    }
    Ok(Json(flow))
}

pub async fn put_flow(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(flow_id): Path<String>,
    Json(mut req): Json<UpsertFlowReq>,
) -> Result<Json<Flow>, (StatusCode, Json<ApiResult>)> {
    req.id = Some(flow_id);
    upsert_flow(State(app), headers, Json(req)).await
}

pub async fn delete_flow(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(flow_id): Path<String>,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let ok = {
        let mut state = vault.lock().unwrap();
        if state.active_flow_id.as_deref() == Some(&flow_id) {
            state.active_flow_id = None;
            storage::save_active_flow(&user, &None);
        }
        let ok = ws::delete_flow(&mut state.flows, &flow_id);
        let snapshot = state.flows.clone();
        drop(state);
        if ok {
            storage::save_flows(&user, &snapshot);
        }
        ok
    };
    Json(if ok { ApiResult::ok() } else { ApiResult::err("flow not found") })
}

#[derive(Deserialize)]
pub struct ShareFlowReq {
    #[serde(rename = "targetUser")]
    pub target_user: String,
}

#[derive(Serialize)]
pub struct ShareFlowResp {
    pub ok: bool,
    #[serde(rename = "newFlowId")]
    pub new_flow_id: String,
    #[serde(rename = "targetUser")]
    pub target_user: String,
    pub message: Option<String>,
}

/// Snapshot-share: copy a flow from the caller's workspace into the target
/// user's workspace, with brand-new ids so future edits stay independent.
/// If a flow with the same name already exists in the target's workspace we
/// auto-suffix it ("Booking", "Booking (shared)", "Booking (shared 2)") so a
/// share never silently fails.
pub async fn share_flow(
    State(app): State<AppState>,
    headers: HeaderMap,
    Path(flow_id): Path<String>,
    Json(req): Json<ShareFlowReq>,
) -> Result<Json<ShareFlowResp>, (StatusCode, Json<ApiResult>)> {
    let from_user = user_from_headers(&headers);
    let to_user = storage::sanitize_username(&req.target_user);
    if to_user.is_empty() {
        return Err((StatusCode::BAD_REQUEST, Json(ApiResult::err("target user cannot be empty"))));
    }
    if to_user == from_user {
        return Err(conflict("cannot share a flow with yourself"));
    }
    if !storage::user_exists(&to_user) {
        return Err(not_found(format!("user '{}' does not exist", to_user)));
    }

    // Read the source flow.
    let source = {
        let from_vault = app.vault_for(&from_user);
        let state = from_vault.lock().unwrap();
        state.flows.iter().find(|f| f.id == flow_id).cloned()
    };
    let Some(source) = source else {
        return Err(not_found("flow not found"));
    };

    // Deep-copy with fresh ids and reset consumption.
    let new_flow_id = uuid::Uuid::new_v4().to_string();
    let new_steps: Vec<FlowStep> = source
        .steps
        .into_iter()
        .map(|s| FlowStep {
            id: uuid::Uuid::new_v4().to_string(),
            path: s.path,
            method: s.method,
            status_code: s.status_code,
            body: s.body,
            is_consumed: false,
        })
        .collect();

    let to_vault = app.vault_for(&to_user);
    let mut state = to_vault.lock().unwrap();

    // Resolve a non-conflicting name in the target's workspace.
    let mut candidate = source.name.clone();
    if ws::flow_name_taken(&state.flows, &candidate, None) {
        candidate = format!("{} (shared)", source.name);
        let mut n = 2;
        while ws::flow_name_taken(&state.flows, &candidate, None) {
            candidate = format!("{} (shared {})", source.name, n);
            n += 1;
            if n > 999 { break; }
        }
    }

    let new_flow = Flow {
        id: new_flow_id.clone(),
        name: candidate,
        description: if source.description.is_empty() {
            format!("Shared by {}", from_user)
        } else {
            source.description
        },
        steps: new_steps,
        created_at: chrono::Utc::now().timestamp_millis(),
    };
    state.flows.push(new_flow);
    let snapshot = state.flows.clone();
    drop(state);
    storage::save_flows(&to_user, &snapshot);

    Ok(Json(ShareFlowResp {
        ok: true,
        new_flow_id,
        target_user: to_user,
        message: Some(format!("Flow shared with '{}'", req.target_user)),
    }))
}

#[derive(Deserialize)]
pub struct StartFlowReq {
    #[serde(rename = "flowId")]
    pub flow_id: String,
}

pub async fn start_flow(
    State(app): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<StartFlowReq>,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let mut state = vault.lock().unwrap();

    let Some(flow) = state.flows.iter_mut().find(|f| f.id == req.flow_id) else {
        return Json(ApiResult::err("flow not found"));
    };
    ws::reset_flow_consumption(flow);
    state.active_flow_id = Some(req.flow_id.clone());

    let flows_snapshot = state.flows.clone();
    let active = state.active_flow_id.clone();
    drop(state);

    storage::save_flows(&user, &flows_snapshot);
    storage::save_active_flow(&user, &active);

    // Starting a flow also makes this user the active user on the proxy, so
    // the device immediately sees this flow's mocks.
    *app.active_user.lock().unwrap() = user;

    Json(ApiResult::ok())
}

pub async fn end_flow(
    State(app): State<AppState>,
    headers: HeaderMap,
) -> Json<ApiResult> {
    let user = user_from_headers(&headers);
    let vault = app.vault_for(&user);
    let mut state = vault.lock().unwrap();
    if let Some(active_id) = state.active_flow_id.clone() {
        if let Some(flow) = state.flows.iter_mut().find(|f| f.id == active_id) {
            ws::reset_flow_consumption(flow);
        }
    }
    state.active_flow_id = None;
    let flows_snapshot = state.flows.clone();
    drop(state);

    storage::save_flows(&user, &flows_snapshot);
    storage::save_active_flow(&user, &None);

    Json(ApiResult::ok())
}

// ---- Server / proxy lifecycle ----

pub async fn server_info(State(app): State<AppState>) -> Json<ServerInfo> {
    let status = app.proxy_status.lock().unwrap().clone();
    let local_ip = local_ip_address::local_ip().ok().map(|ip| ip.to_string());
    Json(ServerInfo {
        status,
        proxy_host: "0.0.0.0".to_string(),
        proxy_port: app.proxy_port,
        api_port: app.api_port,
        local_ip,
        version: env!("CARGO_PKG_VERSION").to_string(),
    })
}

pub async fn connect_proxy(State(app): State<AppState>) -> Json<ApiResult> {
    let tx = app.proxy_cmd.clone();
    if tx.send(crate::ProxyCommand::Start).await.is_ok() {
        Json(ApiResult::ok())
    } else {
        Json(ApiResult::err("could not send start command"))
    }
}

pub async fn disconnect_proxy(State(app): State<AppState>) -> Json<ApiResult> {
    let tx = app.proxy_cmd.clone();
    if tx.send(crate::ProxyCommand::Stop).await.is_ok() {
        Json(ApiResult::ok())
    } else {
        Json(ApiResult::err("could not send stop command"))
    }
}

// ---- Cert ----

pub async fn download_cert() -> impl IntoResponse {
    let cert_pem = fs::read_to_string(CA_CERT_FILE)
        .unwrap_or_else(|_| "Certificate not found".to_string());
    (
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "application/x-x509-ca-cert"),
            (
                header::CONTENT_DISPOSITION,
                "attachment; filename=\"mockmaster-ca.crt\"",
            ),
        ],
        cert_pem,
    )
}

// ---- Logs ----

pub async fn list_logs(State(app): State<AppState>) -> Json<Vec<InterceptedCall>> {
    let buf = app.log_buffer.lock().unwrap();
    Json(buf.iter().cloned().collect())
}

pub async fn clear_logs(State(app): State<AppState>) -> Json<ApiResult> {
    app.log_buffer.lock().unwrap().clear();
    Json(ApiResult::ok())
}

pub async fn stream_logs(
    State(app): State<AppState>,
) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let rx = app.log_tx.subscribe();
    let s = stream::unfold(rx, |mut rx| async move {
        match rx.recv().await {
            Ok(call) => {
                let evt = Event::default()
                    .json_data(call)
                    .unwrap_or_else(|_| Event::default());
                Some((Ok::<Event, Infallible>(evt), rx))
            }
            Err(broadcast::error::RecvError::Lagged(_)) => {
                Some((Ok(Event::default().comment("lagged")), rx))
            }
            Err(_) => None,
        }
    });
    Sse::new(s).keep_alive(KeepAlive::default())
}
