//! Newline-delimited JSON protocol spoken with the companion bridge app.
//!
//! One request object per line, one response object per line, correlated by
//! the monotonically increasing `id`. The first request on a connection is
//! always `hello`; every later request rides the same connection.

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Serialize)]
pub struct BridgeRequest {
    pub id: u64,
    pub method: String,
    pub params: Value,
}

#[derive(Debug, Deserialize)]
pub struct BridgeResponse {
    pub id: u64,
    pub ok: bool,
    pub result: Option<Value>,
    pub error: Option<BridgeFailure>,
}

#[derive(Debug, Deserialize)]
pub struct BridgeFailure {
    pub code: String,
    pub message: String,
}

/// The `result` of a successful `hello` or `ping` call.
#[derive(Debug, Clone, Deserialize)]
pub struct BridgeStatus {
    pub protocol: u32,
    pub android_release: String,
    pub sdk: u32,
    pub accessibility: bool,
    pub notification_listener: bool,
}

/// One node of the tree the bridge app serialises for `get_tree`.
#[derive(Debug, Clone, Deserialize)]
pub struct BridgeNode {
    #[serde(rename = "class")]
    pub class_name: String,
    pub package: String,
    pub view_id: String,
    pub text: String,
    pub content_desc: String,
    pub bounds: [i32; 4],
    pub value: String,
    pub actions: Vec<String>,
    pub state: BridgeNodeState,
    #[serde(default)]
    pub children: Vec<BridgeNode>,
}

/// The state flags of a serialised bridge node.
#[derive(Debug, Clone, Deserialize)]
pub struct BridgeNodeState {
    pub clickable: bool,
    pub enabled: bool,
    pub focused: bool,
    pub checked: bool,
    pub checkable: bool,
    pub selected: bool,
    pub scrollable: bool,
    pub editable: bool,
    pub password: bool,
    pub visible: bool,
}
