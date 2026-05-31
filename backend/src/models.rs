use serde::{Deserialize, Serialize};
use std::collections::HashMap;

fn default_true() -> bool {
    true
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct FolderNode {
    pub id: String,
    pub name: String,
    #[serde(rename = "isEnabled", default = "default_true")]
    pub is_enabled: bool,
    #[serde(rename = "subFolders", default)]
    pub sub_folders: Vec<FolderNode>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MockRule {
    pub id: String,
    pub path: String,
    #[serde(default = "default_method")]
    pub method: String,
    #[serde(rename = "statusCode")]
    pub status_code: u16,
    pub body: String,
    #[serde(rename = "isEnabled", default = "default_true")]
    pub is_enabled: bool,
    #[serde(rename = "createdAt", default)]
    pub created_at: i64,
}

fn default_method() -> String {
    "GET".to_string()
}

/// A flow is a named sequence of mock rules. Within a flow, multiple rules
/// can target the same path+method. They are consumed left-to-right: the
/// first not-yet-consumed rule that matches an intercepted call wins; once
/// consumed it auto-disables itself so the next call hits the next rule.
/// If only one rule matches, it stays active for every call.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct FlowStep {
    pub id: String,
    pub path: String,
    #[serde(default = "default_method")]
    pub method: String,
    #[serde(rename = "statusCode")]
    pub status_code: u16,
    pub body: String,
    /// Whether this step has been consumed during the current run.
    /// Reset to false each time a flow starts.
    #[serde(rename = "isConsumed", default)]
    pub is_consumed: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Flow {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub steps: Vec<FlowStep>,
    #[serde(rename = "createdAt", default)]
    pub created_at: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct WorkspaceState {
    pub folders: Vec<FolderNode>,
    #[serde(rename = "rulesMap", default)]
    pub rules_map: HashMap<String, Vec<MockRule>>,
    #[serde(default)]
    pub flows: Vec<Flow>,
    /// Currently running flow id (None when no flow is active).
    #[serde(rename = "activeFlowId", default)]
    pub active_flow_id: Option<String>,
}

impl WorkspaceState {
    pub fn default_seed() -> Self {
        WorkspaceState {
            folders: vec![FolderNode {
                id: "f_default".to_string(),
                name: "Default".to_string(),
                is_enabled: true,
                sub_folders: vec![],
            }],
            rules_map: HashMap::new(),
            flows: vec![],
            active_flow_id: None,
        }
    }

    /// Build a set of all folder ids whose ancestry chain (and themselves)
    /// are enabled. A rule is only matchable if its folder id is in this set.
    pub fn enabled_folder_ids(&self) -> std::collections::HashSet<String> {
        let mut out = std::collections::HashSet::new();
        fn walk(
            nodes: &[FolderNode],
            parent_enabled: bool,
            out: &mut std::collections::HashSet<String>,
        ) {
            for n in nodes {
                let effective = parent_enabled && n.is_enabled;
                if effective {
                    out.insert(n.id.clone());
                }
                walk(&n.sub_folders, effective, out);
            }
        }
        walk(&self.folders, true, &mut out);
        out
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct InterceptedCall {
    pub id: String,
    pub method: String,
    pub url: String,
    pub path: String,
    #[serde(rename = "statusCode")]
    pub status_code: u16,
    pub matched: bool,
    #[serde(rename = "matchSource")]
    pub match_source: String, // "folder", "flow", "passthrough"
    #[serde(rename = "matchLabel")]
    pub match_label: String, // folder name or flow name
    #[serde(rename = "requestHeaders", default)]
    pub request_headers: HashMap<String, String>,
    #[serde(rename = "requestBody", default)]
    pub request_body: String,
    #[serde(rename = "responseBody", default)]
    pub response_body: String,
    pub timestamp: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "lowercase")]
pub enum ProxyStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ServerInfo {
    pub status: ProxyStatus,
    #[serde(rename = "proxyHost")]
    pub proxy_host: String,
    #[serde(rename = "proxyPort")]
    pub proxy_port: u16,
    #[serde(rename = "apiPort")]
    pub api_port: u16,
    #[serde(rename = "localIp")]
    pub local_ip: Option<String>,
    pub version: String,
}
