use crate::models::{Flow, FolderNode, MockRule, WorkspaceState};
use crate::storage;

/// Recursively find a folder by id and apply a mutator. Returns true if found.
pub fn with_folder_mut<F>(folders: &mut [FolderNode], id: &str, mut f: F) -> bool
where
    F: FnMut(&mut FolderNode),
{
    fn walk<F: FnMut(&mut FolderNode)>(
        nodes: &mut [FolderNode],
        id: &str,
        f: &mut F,
    ) -> bool {
        for n in nodes.iter_mut() {
            if n.id == id {
                f(n);
                return true;
            }
            if walk(&mut n.sub_folders, id, f) {
                return true;
            }
        }
        false
    }
    walk(folders, id, &mut f)
}

/// Try to insert a child folder under parent_id. If parent_id is None, push to root.
pub fn insert_folder(
    folders: &mut Vec<FolderNode>,
    parent_id: Option<&str>,
    new_folder: FolderNode,
) -> bool {
    match parent_id {
        None => {
            folders.push(new_folder);
            true
        }
        Some(pid) => with_folder_mut(folders, pid, |parent| {
            parent.sub_folders.push(new_folder.clone());
        }),
    }
}

/// Remove a folder anywhere in the tree by id, returning all descendant ids
/// (so callers can clean up rule files).
pub fn remove_folder(folders: &mut Vec<FolderNode>, id: &str) -> Vec<String> {
    let mut removed_ids: Vec<String> = Vec::new();

    fn collect_ids(node: &FolderNode, out: &mut Vec<String>) {
        out.push(node.id.clone());
        for c in &node.sub_folders {
            collect_ids(c, out);
        }
    }

    fn remove(nodes: &mut Vec<FolderNode>, id: &str, removed_ids: &mut Vec<String>) -> bool {
        if let Some(pos) = nodes.iter().position(|n| n.id == id) {
            collect_ids(&nodes[pos], removed_ids);
            nodes.remove(pos);
            return true;
        }
        for n in nodes.iter_mut() {
            if remove(&mut n.sub_folders, id, removed_ids) {
                return true;
            }
        }
        false
    }

    remove(folders, id, &mut removed_ids);
    removed_ids
}

// ---- Uniqueness helpers ----

/// Returns true if a sibling under `parent_id` (or root if None) already has
/// the given `name` (case-insensitive). The optional `excluding_id` lets a
/// rename ignore the folder being renamed itself.
pub fn sibling_name_taken(
    folders: &[FolderNode],
    parent_id: Option<&str>,
    name: &str,
    excluding_id: Option<&str>,
) -> bool {
    let needle = name.trim().to_lowercase();
    if needle.is_empty() {
        return false;
    }
    match parent_id {
        None => folders.iter().any(|f| {
            f.name.trim().to_lowercase() == needle && Some(f.id.as_str()) != excluding_id
        }),
        Some(pid) => {
            // Walk down to find the parent, then check its direct children.
            fn walk(
                nodes: &[FolderNode],
                pid: &str,
                needle: &str,
                excluding_id: Option<&str>,
            ) -> Option<bool> {
                for n in nodes {
                    if n.id == pid {
                        return Some(n.sub_folders.iter().any(|c| {
                            c.name.trim().to_lowercase() == needle
                                && Some(c.id.as_str()) != excluding_id
                        }));
                    }
                    if let Some(found) = walk(&n.sub_folders, pid, needle, excluding_id) {
                        return Some(found);
                    }
                }
                None
            }
            walk(folders, pid, &needle, excluding_id).unwrap_or(false)
        }
    }
}

/// Find the parent of a given folder id, if any. Returns Some(None) if it's a
/// root-level folder, Some(Some(parent_id)) if it's nested, None if the id
/// doesn't exist.
pub fn parent_of(folders: &[FolderNode], id: &str) -> Option<Option<String>> {
    if folders.iter().any(|f| f.id == id) {
        return Some(None);
    }
    fn walk(nodes: &[FolderNode], id: &str) -> Option<String> {
        for n in nodes {
            if n.sub_folders.iter().any(|c| c.id == id) {
                return Some(n.id.clone());
            }
            if let Some(p) = walk(&n.sub_folders, id) {
                return Some(p);
            }
        }
        None
    }
    walk(folders, id).map(Some)
}

/// True iff a flow with this name already exists (case-insensitive),
/// optionally excluding a given id (for rename/edit).
pub fn flow_name_taken(flows: &[Flow], name: &str, excluding_id: Option<&str>) -> bool {
    let needle = name.trim().to_lowercase();
    if needle.is_empty() {
        return false;
    }
    flows.iter().any(|f| {
        f.name.trim().to_lowercase() == needle && Some(f.id.as_str()) != excluding_id
    })
}

// ---- Rules ----

/// Upsert a rule into a folder. Duplicate-by-(method,path) policy:
/// the most recently added/updated rule wins. We achieve "last-one-wins"
/// at lookup time by sorting matches by created_at desc — but we also
/// auto-disable older duplicates here so the UI surface stays clean.
pub fn upsert_rule(rules: &mut Vec<MockRule>, mut rule: MockRule) {
    if rule.created_at == 0 {
        rule.created_at = chrono::Utc::now().timestamp_millis();
    }

    if let Some(pos) = rules.iter().position(|r| r.id == rule.id) {
        rules[pos] = rule;
        return;
    }

    for existing in rules.iter_mut() {
        if existing.method.eq_ignore_ascii_case(&rule.method) && existing.path == rule.path {
            existing.is_enabled = false;
        }
    }

    rules.push(rule);
}

pub fn delete_rule(rules: &mut Vec<MockRule>, rule_id: &str) -> bool {
    if let Some(pos) = rules.iter().position(|r| r.id == rule_id) {
        rules.remove(pos);
        true
    } else {
        false
    }
}

// --- Flow helpers ---

pub fn upsert_flow(flows: &mut Vec<Flow>, mut flow: Flow) {
    if flow.created_at == 0 {
        flow.created_at = chrono::Utc::now().timestamp_millis();
    }
    if let Some(pos) = flows.iter().position(|f| f.id == flow.id) {
        flows[pos] = flow;
    } else {
        flows.push(flow);
    }
}

pub fn delete_flow(flows: &mut Vec<Flow>, flow_id: &str) -> bool {
    if let Some(pos) = flows.iter().position(|f| f.id == flow_id) {
        flows.remove(pos);
        true
    } else {
        false
    }
}

pub fn reset_flow_consumption(flow: &mut Flow) {
    for step in flow.steps.iter_mut() {
        step.is_consumed = false;
    }
}

/// Persist the parts of state that changed for a specific user.
pub fn persist_workspace(username: &str, state: &WorkspaceState) {
    storage::save_tree(username, &state.folders);
    for (folder_id, rules) in &state.rules_map {
        storage::save_folder_rules(username, folder_id, rules);
    }
    storage::save_flows(username, &state.flows);
    storage::save_active_flow(username, &state.active_flow_id);
}
