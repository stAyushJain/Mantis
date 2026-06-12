use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

use hudsucker::{
    Body as HudBody, HttpContext, HttpHandler, RequestOrResponse,
};
use hudsucker::hyper::{Request, Response, StatusCode};
use hudsucker::hyper_util::client::legacy::Error as UpstreamError;
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
    /// Passthrough request waiting for its upstream response so we can attach
    /// the real status + body to the log entry. Set in `handle_request`,
    /// drained in `handle_response`. Per the hudsucker contract each
    /// request/response pair is served by the same handler instance, so a
    /// plain `Option` is sufficient (the connection processes one request at
    /// a time).
    pub pending_passthrough: Option<InterceptedCall>,
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

        // Passthrough: stash a partially-filled log entry. `handle_response`
        // will fill in the real status + body and emit it. We log nothing here
        // so users always see the final outcome (including errors handled by
        // `handle_error`).
        self.pending_passthrough = Some(InterceptedCall {
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
        });

        let req = Request::from_parts(parts, HudBody::from(body_bytes));
        RequestOrResponse::Request(req)
    }

    async fn handle_response(
        &mut self,
        _ctx: &HttpContext,
        res: Response<HudBody>,
    ) -> Response<HudBody> {
        // Mocked responses we constructed ourselves carry this marker — we
        // already logged them in `handle_request`, so just pass through.
        if res.headers().get("X-MockMaster-Intercepted").is_some() {
            return res;
        }

        let Some(mut pending) = self.pending_passthrough.take() else {
            return res;
        };

        let status = res.status().as_u16();
        // Pull the headers we need for decoding *before* we consume `res`:
        // `Content-Encoding` tells us which compression algo (gzip / br /
        // deflate) was applied; `Content-Type` tells us whether the body is
        // text at all (images, audio, protobuf, etc. shouldn't be rendered
        // as a string).
        let content_encoding = res
            .headers()
            .get(hudsucker::hyper::header::CONTENT_ENCODING)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_ascii_lowercase())
            .unwrap_or_default();
        let content_type = res
            .headers()
            .get(hudsucker::hyper::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_ascii_lowercase())
            .unwrap_or_default();

        let (parts, body) = res.into_parts();
        let body_bytes = match body.collect().await {
            Ok(c) => c.to_bytes(),
            Err(_) => bytes::Bytes::new(),
        };

        let response_body = decode_for_log(&body_bytes, &content_encoding, &content_type);

        pending.status_code = status;
        pending.response_body = response_body;
        self.push_log(pending);

        // Forward the ORIGINAL (still-compressed) bytes downstream so the
        // client app sees an unchanged byte-for-byte response — we only
        // decoded a copy for our log panel.
        Response::from_parts(parts, HudBody::from(body_bytes))
    }

    async fn handle_error(
        &mut self,
        _ctx: &HttpContext,
        err: UpstreamError,
    ) -> Response<HudBody> {
        // Upstream failed (DNS, TLS, connect refused, …). Still emit the log
        // so the user sees the call that bombed, with a synthetic 502 status
        // and the error string as the response body.
        if let Some(mut pending) = self.pending_passthrough.take() {
            pending.status_code = 502;
            pending.response_body = format!("Upstream error: {err}");
            self.push_log(pending);
        }
        Response::builder()
            .status(StatusCode::BAD_GATEWAY)
            .body(HudBody::from("upstream error"))
            .unwrap()
    }
}

/// Produce a human-readable string for the Logs UI from the raw upstream
/// body bytes. Handles three things that previously made the panel show
/// garbage:
///   1. Compression — most HTTPS APIs respond with `Content-Encoding: gzip`
///      (sometimes `br`/`deflate`). We decompress a copy for the log.
///   2. Non-text content types — images / binary / protobuf etc. are
///      replaced with a `[binary N bytes]` placeholder.
///   3. Bounded size — capped at 256 KB so a large download doesn't
///      blow up the in-memory ring buffer; we keep a prefix + marker.
///
/// IMPORTANT: this is a copy used purely for logging. The original bytes
/// are still forwarded untouched to the client.
fn decode_for_log(body: &bytes::Bytes, content_encoding: &str, content_type: &str) -> String {
    // 1. Short-circuit empty.
    if body.is_empty() {
        return String::new();
    }

    // 2. Replace obviously-non-text content types with a placeholder so the
    //    UI doesn't render binary as `���`. Anything not on this list is
    //    assumed text-ish (json / xml / plain / form / unknown).
    if is_binary_content_type(content_type) {
        return format!("[binary {} bytes — content-type: {}]", body.len(), content_type);
    }

    // 3. Decompress per Content-Encoding. We try; if it fails (truncated
    //    stream, mislabelled body) we fall back to the raw bytes so the
    //    user still sees *something*.
    let decoded: std::borrow::Cow<[u8]> = match content_encoding.as_ref() {
        "gzip" | "x-gzip" => decompress_gzip(body)
            .map(std::borrow::Cow::Owned)
            .unwrap_or(std::borrow::Cow::Borrowed(body.as_ref())),
        "deflate" => decompress_deflate(body)
            .map(std::borrow::Cow::Owned)
            .unwrap_or(std::borrow::Cow::Borrowed(body.as_ref())),
        "br" => decompress_brotli(body)
            .map(std::borrow::Cow::Owned)
            .unwrap_or(std::borrow::Cow::Borrowed(body.as_ref())),
        // No encoding, identity, or one we don't speak (zstd etc.) — just
        // use the raw bytes.
        _ => std::borrow::Cow::Borrowed(body.as_ref()),
    };

    // 4. Cap the size so a 5 MB JSON dump doesn't trash our ring buffer.
    const MAX_LOG_BODY: usize = 256 * 1024;
    let (slice, truncated) = if decoded.len() > MAX_LOG_BODY {
        (&decoded[..MAX_LOG_BODY], true)
    } else {
        (&decoded[..], false)
    };

    let mut s = String::from_utf8_lossy(slice).into_owned();
    if truncated {
        s.push_str("\n…[truncated]");
    }
    s
}

/// True for content types that aren't sensibly rendered as a string. The
/// list is intentionally conservative — anything unrecognised is treated
/// as text so users still see the body (e.g. APIs that forget to set
/// `Content-Type: application/json` on success).
fn is_binary_content_type(ct: &str) -> bool {
    if ct.is_empty() {
        return false;
    }
    // `application/json`, `application/xml`, `application/javascript`,
    // `application/x-www-form-urlencoded`, `application/grpc-web+json`
    // etc. should all be treated as text.
    let textual_app_prefixes = [
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-www-form-urlencoded",
        "application/ld+json",
        "application/problem+json",
        "application/vnd.api+json",
        "application/graphql",
    ];
    for p in textual_app_prefixes {
        if ct.starts_with(p) {
            return false;
        }
    }
    if ct.starts_with("text/") {
        return false;
    }
    // Image, video, audio, font, octet-stream, protobuf, msgpack, etc.
    ct.starts_with("image/")
        || ct.starts_with("video/")
        || ct.starts_with("audio/")
        || ct.starts_with("font/")
        || ct.starts_with("application/octet-stream")
        || ct.starts_with("application/pdf")
        || ct.starts_with("application/zip")
        || ct.starts_with("application/x-protobuf")
        || ct.starts_with("application/protobuf")
        || ct.starts_with("application/grpc")
        || ct.starts_with("application/msgpack")
        || ct.starts_with("application/x-msgpack")
}

fn decompress_gzip(input: &[u8]) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut decoder = flate2::read::GzDecoder::new(input);
    let mut out = Vec::new();
    decoder.read_to_end(&mut out).ok()?;
    Some(out)
}

fn decompress_deflate(input: &[u8]) -> Option<Vec<u8>> {
    use std::io::Read;
    // RFC 7230 "deflate" is ambiguous in the wild — some servers send raw
    // deflate, others send zlib-wrapped. Try zlib first (more common /
    // standards-compliant), fall back to raw.
    let mut zlib = flate2::read::ZlibDecoder::new(input);
    let mut out = Vec::new();
    if zlib.read_to_end(&mut out).is_ok() {
        return Some(out);
    }
    let mut raw = flate2::read::DeflateDecoder::new(input);
    out.clear();
    raw.read_to_end(&mut out).ok()?;
    Some(out)
}

fn decompress_brotli(input: &[u8]) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut decoder = brotli::Decompressor::new(input, 4096);
    let mut out = Vec::new();
    decoder.read_to_end(&mut out).ok()?;
    Some(out)
}
