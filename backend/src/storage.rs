use crate::models::{Flow, FolderNode, MockRule, WorkspaceState};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

pub const WORKSPACE_DIR: &str = "workspace_data";
pub const CA_CERT_FILE: &str = "workspace_data/mockmaster-ca.crt";
pub const CA_KEY_FILE: &str = "workspace_data/mockmaster-ca.key";
pub const DEFAULT_USER: &str = "default";

/// All workspace JSON now lives at `workspace_data/<username>/...`.
/// The CA cert/key remain at the top-level `workspace_data/` since they're
/// shared across all users (a single trusted root for the device).
fn user_dir(username: &str) -> PathBuf {
    PathBuf::from(WORKSPACE_DIR).join(sanitize_username(username))
}

fn user_file(username: &str, file: &str) -> PathBuf {
    user_dir(username).join(file)
}

/// Lower-case + strip anything that's not a-z 0-9 _ or -. Empty falls back to
/// "default". Keeps filesystem paths predictable and safe.
pub fn sanitize_username(raw: &str) -> String {
    let cleaned: String = raw
        .trim()
        .to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
        .collect();
    if cleaned.is_empty() {
        DEFAULT_USER.to_string()
    } else {
        cleaned
    }
}

pub fn init_workspace_root() {
    if !Path::new(WORKSPACE_DIR).exists() {
        fs::create_dir_all(WORKSPACE_DIR).expect("Failed to create workspace directory");
        println!("Created {WORKSPACE_DIR}/ directory");
    }
}

pub fn ensure_user_dir(username: &str) {
    init_workspace_root();
    let dir = user_dir(username);
    if !dir.exists() {
        fs::create_dir_all(&dir).expect("Failed to create user workspace directory");
        println!("Created workspace for user '{}'", sanitize_username(username));
    }
}

/// Returns true iff the user already has a workspace folder on disk.
pub fn user_exists(username: &str) -> bool {
    user_dir(username).exists()
}

/// List all known usernames (directories under workspace_data/).
pub fn list_users() -> Vec<String> {
    init_workspace_root();
    let mut users = Vec::new();
    if let Ok(entries) = fs::read_dir(WORKSPACE_DIR) {
        for entry in entries.flatten() {
            if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                if let Some(name) = entry.file_name().to_str() {
                    users.push(name.to_string());
                }
            }
        }
    }
    users.sort();
    users
}

pub fn load_user_workspace(username: &str) -> WorkspaceState {
    ensure_user_dir(username);
    let dir = user_dir(username);

    let tree_file = dir.join("tree.json");
    let folders: Vec<FolderNode> = if let Ok(data) = fs::read_to_string(&tree_file) {
        serde_json::from_str(&data).unwrap_or_else(|_| WorkspaceState::default_seed().folders)
    } else {
        WorkspaceState::default_seed().folders
    };

    let mut rules_map: HashMap<String, Vec<MockRule>> = HashMap::new();
    if let Ok(entries) = fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            let Some(filename) = path.file_name().and_then(|f| f.to_str()) else {
                continue;
            };
            if filename.starts_with("f_") && filename.ends_with(".json") {
                let folder_id = filename.trim_end_matches(".json").to_string();
                if let Ok(data) = fs::read_to_string(&path) {
                    if let Ok(rules) = serde_json::from_str::<Vec<MockRule>>(&data) {
                        rules_map.insert(folder_id, rules);
                    }
                }
            }
        }
    }

    let flows_file = dir.join("flows.json");
    let flows: Vec<Flow> = if let Ok(data) = fs::read_to_string(&flows_file) {
        serde_json::from_str(&data).unwrap_or_default()
    } else {
        vec![]
    };

    println!(
        "Workspace loaded for '{}': {} folders, {} rule files, {} flows",
        sanitize_username(username),
        folders.len(),
        rules_map.len(),
        flows.len()
    );

    WorkspaceState {
        folders,
        rules_map,
        flows,
        active_flow_id: None,
    }
}

pub fn save_tree(username: &str, folders: &[FolderNode]) {
    ensure_user_dir(username);
    if let Ok(json) = serde_json::to_string_pretty(folders) {
        let _ = fs::write(user_file(username, "tree.json"), json);
    }
}

pub fn save_folder_rules(username: &str, folder_id: &str, rules: &[MockRule]) {
    ensure_user_dir(username);
    if let Ok(json) = serde_json::to_string_pretty(rules) {
        let _ = fs::write(user_file(username, &format!("{}.json", folder_id)), json);
    }
}

pub fn delete_folder_rules_file(username: &str, folder_id: &str) {
    let _ = fs::remove_file(user_file(username, &format!("{}.json", folder_id)));
}

pub fn save_flows(username: &str, flows: &[Flow]) {
    ensure_user_dir(username);
    if let Ok(json) = serde_json::to_string_pretty(flows) {
        let _ = fs::write(user_file(username, "flows.json"), json);
    }
}

pub fn save_active_flow(username: &str, active_flow_id: &Option<String>) {
    ensure_user_dir(username);
    let val = serde_json::json!({ "activeFlowId": active_flow_id });
    if let Ok(json) = serde_json::to_string_pretty(&val) {
        let _ = fs::write(user_file(username, "state.json"), json);
    }
}

#[allow(dead_code)]
pub fn save_all(username: &str, state: &WorkspaceState) {
    save_tree(username, &state.folders);
    for (folder_id, rules) in &state.rules_map {
        save_folder_rules(username, folder_id, rules);
    }
    save_flows(username, &state.flows);
    save_active_flow(username, &state.active_flow_id);
}
