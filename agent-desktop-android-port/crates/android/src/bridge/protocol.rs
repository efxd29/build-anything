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

/// The `result` of a `get_tree` call: the serialised active window plus the
/// flag the bridge sets when its node or depth cap cut the walk short. The
/// bridge also reports the process-instance token it minted for the root
/// package's current presence epoch; see `crate::system` for how the token
/// is produced and what it can and cannot prove.
#[derive(Debug, Clone, Deserialize)]
pub struct BridgeTree {
    pub root: BridgeNode,
    pub truncated: bool,
    #[serde(default)]
    pub process_instance: String,
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
    pub subtree_truncated: bool,
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
    #[serde(default)]
    pub focusable: bool,
    #[serde(default)]
    pub long_clickable: bool,
}

/// The capture proof an `act` request carries: the identity fields core
/// recorded for a ref, re-matched inside the bridge app against the live
/// tree in the same request that performs the action. A field that is
/// absent records no constraint, exactly as core's own predicate treats it.
#[derive(Debug, Clone, Serialize)]
pub struct BridgeProof {
    pub role: String,
    #[serde(rename = "class")]
    pub class_name: String,
    pub view_id: Option<String>,
    pub name: Option<String>,
    pub description: Option<String>,
}

/// The `result` of an `act` call: how many live nodes matched the proof and
/// whether the single matched node performed the action.
#[derive(Debug, Clone, Deserialize)]
pub struct BridgeActResult {
    pub matched: u32,
    pub performed: bool,
}

/// One launchable application in a `list_apps` result.
#[derive(Debug, Clone, Deserialize)]
pub struct BridgeApp {
    pub package: String,
    pub label: String,
}

/// The `result` of a `screenshot` call: a PNG of the display, base64
/// encoded for the line-delimited protocol.
#[derive(Debug, Clone, Deserialize)]
pub struct BridgeScreenshot {
    pub png_base64: String,
    pub width: u32,
    pub height: u32,
}
