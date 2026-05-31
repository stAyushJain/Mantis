use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

use hudsucker::{
    Body as HudBody, HttpContext, HttpHandler, RequestOrResponse,
};
use hudsucker::hyper::{Request, Response, StatusCode};
use http_body_util::BodyExt;
use tokio::sync::broadcast;

use crate::models::{InterceptedCall, WorkspaceState};

pub const LOG_BUFFER_CAPACITY: usize = 500;

#[derive(Clone)]
pub struct MockHandler {
    pub vaults: Arc<Mutex<HashMap<String, Arc<Mutex<WorkspaceState>>>>>,
    pub active_user: Arc<Mutex<String>>,
    pub log_tx: broadcast::Sender<InterceptedCall>,
    pub log_buffer: Arc<Mutex<VecDeque<InterceptedCall>>>,
}

impl MockHandler {
    fn push_log(&self, call: InterceptedCall) {
        {
            let mut buf = self.log_buffer.lock().unwrap();
            if buf.len() >= LOG_BUFFER_CAPACITY {
                buf.pop_front();
            }
            buf.push_back(call.clone());
        }
        let _ = self.log_tx.send(call);
    }

    /// Resolve the active user's vault, lazy-loading it if it isn't in the
    /// registry yet. The proxy can run before any user has been "logged in",
    /// in which case we fall back to the default workspace.
    fn active_vault(&self) -> Arc<Mutex<WorkspaceState>> {
        let user = self.active_user.lock().unwrap().clone();
        let mut map = self.vaults.lock().unwrap();
        if let Some(v) = map.get(&user) {
            return v.clone();
        }
        let loaded = crate::storage::load_user_workspace(&user);
        let arc = Arc::new(Mutex::new(loaded));
        map.insert(user, arc.clone());
        arc
    }
}

fn resolve_match(
    state: &mut WorkspaceState,
    method: &str,
    path: &str,
) -> Option<MatchResult> {
    if let Some(active_id) = state.active_flow_id.clone() {
        let flow_name = state
            .flows
            .iter()
            .find(|f| f.id == active_id)
            .map(|f| f.name.clone())
            .unwrap_or_default();

        if let Some(flow) = state.flows.iter_mut().find(|f| f.id == active_id) {
            let matching_indexes: Vec<usize> = flow
                .steps
                .iter()
                .enumerate()
                .filter(|(_, s)| {
                    s.method.eq_ignore_ascii_case(method) && s.path == path
                })
                .map(|(i, _)| i)
                .collect();

            if matching_indexes.is_empty() {
                return None;
            }

            for idx in &matching_indexes {
                if !flow.steps[*idx].is_consumed {
                    flow.steps[*idx].is_consumed = true;
                    let s = &flow.steps[*idx];
                    return Some(MatchResult {
                        status_code: s.status_code,
                        body: s.body.clone(),
                        match_source: "flow".to_string(),
                        match_label: flow_name,
                    });
                }
            }

            let last = *matching_indexes.last().unwrap();
            let s = &flow.steps[last];
            return Some(MatchResult {
                status_code: s.status_code,
                body: s.body.clone(),
                match_source: "flow".to_string(),
                match_label: flow_name,
            });
        }
        return None;
    }

    let enabled_folder_ids = state.enabled_folder_ids();
    let mut best: Option<(i64, MatchResult)> = None;

    for (folder_id, rules) in &state.rules_map {
        if !enabled_folder_ids.contains(folder_id) {
            continue;
        }
        let folder_name = find_folder_name(&state.folders, folder_id).unwrap_or_default();

        for rule in rules {
            if !rule.is_enabled {
                continue;
            }
            if !rule.method.eq_ignore_ascii_case(method) {
                continue;
            }
            if rule.path != path {
                continue;
            }
            let candidate = MatchResult {
                status_code: rule.status_code,
                body: rule.body.clone(),
                match_source: "folder".to_string(),
                match_label: folder_name.clone(),
            };
            match &best {
                None => best = Some((rule.created_at, candidate)),
                Some((ts, _)) if rule.created_at > *ts => {
                    best = Some((rule.created_at, candidate));
                }
                _ => {}
            }
        }
    }

    best.map(|(_, m)| m)
}

fn find_folder_name(folders: &[crate::models::FolderNode], id: &str) -> Option<String> {
    for f in folders {
        if f.id == id {
            return Some(f.name.clone());
        }
        if let Some(n) = find_folder_name(&f.sub_folders, id) {
            return Some(n);
        }
    }
    None
}

pub struct MatchResult {
    pub status_code: u16,
    pub body: String,
    pub match_source: String,
    pub match_label: String,
}

impl HttpHandler for MockHandler {
    async fn handle_request(
        &mut self,
        _ctx: &HttpContext,
        req: Request<HudBody>,
    ) -> RequestOrResponse {
        let path = req.uri().path().to_string();
        let url = req.uri().to_string();
        let method = req.method().to_string();

        let mut req_headers: std::collections::HashMap<String, String> = std::collections::HashMap::new();
        for (k, v) in req.headers().iter() {
            if let Ok(s) = v.to_str() {
                req_headers.insert(k.to_string(), s.to_string());
            }
        }

        let (parts, body) = req.into_parts();
        let body_bytes = match body.collect().await {
            Ok(c) => c.to_bytes(),
            Err(_) => bytes::Bytes::new(),
        };
        let req_body_str = String::from_utf8_lossy(&body_bytes).to_string();

        let vault = self.active_vault();
        let active_user = self.active_user.lock().unwrap().clone();

        let matched = {
            let mut state = vault.lock().unwrap();
            resolve_match(&mut state, &method, &path)
        };

        if let Some(m) = matched {
            let intercepted = InterceptedCall {
                id: uuid::Uuid::new_v4().to_string(),
                method: method.clone(),
                url: url.clone(),
                path: path.clone(),
                status_code: m.status_code,
                matched: true,
                match_source: m.match_source.clone(),
                match_label: m.match_label.clone(),
                request_headers: req_headers,
                request_body: req_body_str,
                response_body: m.body.clone(),
                timestamp: chrono::Utc::now().to_rfc3339(),
            };
            self.push_log(intercepted);

            // Persist any flow-step consumption that happened.
            {
                let state = vault.lock().unwrap();
                let flows_snapshot = state.flows.clone();
                drop(state);
                crate::storage::save_flows(&active_user, &flows_snapshot);
            }

            let response = Response::builder()
                .status(StatusCode::from_u16(m.status_code).unwrap_or(StatusCode::OK))
                .header("Content-Type", "application/json")
                .header("X-MockMaster-Intercepted", "true")
                .header("X-MockMaster-Source", m.match_source)
                .body(HudBody::from(m.body))
                .unwrap();
            return RequestOrResponse::Response(response);
        }

        let intercepted = InterceptedCall {
            id: uuid::Uuid::new_v4().to_string(),
            method: method.clone(),
            url: url.clone(),
            path: path.clone(),
            status_code: 0,
            matched: false,
            match_source: "passthrough".to_string(),
            match_label: String::new(),
            request_headers: req_headers,
            request_body: req_body_str,
            response_body: String::new(),
            timestamp: chrono::Utc::now().to_rfc3339(),
        };
        self.push_log(intercepted);

        let req = Request::from_parts(parts, HudBody::from(body_bytes));
        RequestOrResponse::Request(req)
    }
}
