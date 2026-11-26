//! Bridge client to the companion APK, replacing the D-Bus layer of the
//! Linux adapter: a lazily connected, reconnecting client for the local
//! sockets the bridge app serves (the abstract Unix socket first, a
//! loopback TCP fallback when SELinux denies the abstract connect),
//! speaking newline-delimited JSON.

mod connection;
mod errors;
mod protocol;

use std::path::PathBuf;
use std::sync::Mutex;

use serde_json::{Value, json};

pub use errors::BridgeError;
pub use protocol::{
    BridgeActResult, BridgeApp, BridgeNode, BridgeProof, BridgeScreenshot, BridgeStatus,
    BridgeTree,
};

#[cfg(test)]
pub use protocol::BridgeNodeState;

use connection::{BridgeConnection, DEFAULT_TIMEOUT};

/// Lazily connected, mutex-guarded bridge handle shared by all adapter layers.
#[derive(Default)]
pub struct SharedBridge {
    connection: Mutex<Option<BridgeConnection>>,
}

impl SharedBridge {
    /// Sends one protocol request, reconnecting once if the cached
    /// connection has gone stale.
    pub fn call(&self, method: &str, params: Value) -> Result<Value, BridgeError> {
        let mut guard = match self.connection.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        if guard.is_none() {
            *guard = Some(connect_with_hello()?);
        }
        let connection = match guard.as_mut() {
            Some(connection) => connection,
            None => {
                return Err(BridgeError::Unavailable(
                    "bridge connection was not established".to_owned(),
                ));
            }
        };
        match connection.call(method, params.clone()) {
            Ok(result) => Ok(result),
            Err(error) => {
                if matches!(error, BridgeError::TokenRejected) {
                    return Err(error);
                }
                *guard = Some(connect_with_hello()?);
                match guard.as_mut() {
                    Some(connection) => connection.call(method, params),
                    None => Err(BridgeError::Unavailable(
                        "bridge connection was not established".to_owned(),
                    )),
                }
            }
        }
    }

    /// Sends one request on the cached connection without reconnecting or
    /// retrying, for calls whose effect must never be delivered twice.
    pub fn call_once(&self, method: &str, params: Value) -> Result<Value, BridgeError> {
        let mut guard = match self.connection.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        if guard.is_none() {
            *guard = Some(connect_with_hello()?);
        }
        match guard.as_mut() {
            Some(connection) => connection.call(method, params),
            None => Err(BridgeError::Unavailable(
                "bridge connection was not established".to_owned(),
            )),
        }
    }

    /// Answers whether the bridge app answers and what it reports.
    pub fn ping(&self) -> Result<BridgeStatus, BridgeError> {
        let value = self.call("ping", json!({}))?;
        serde_json::from_value(value)
            .map_err(|error| BridgeError::Protocol(error.to_string()))
    }

    /// Reads the active window's accessibility tree from the bridge app.
    pub fn get_tree(&self) -> Result<BridgeTree, BridgeError> {
        let value = self.call("get_tree", json!({}))?;
        serde_json::from_value(value)
            .map_err(|error| BridgeError::Protocol(error.to_string()))
    }

    /// Asks the bridge app to re-match a capture proof against the live
    /// tree and perform the action on the single match, in one request.
    pub fn act_on_match(
        &self,
        proof: &BridgeProof,
        action: &str,
        arguments: Value,
    ) -> Result<BridgeActResult, BridgeError> {
        let params = json!({
            "proof": serde_json::to_value(proof)
                .map_err(|error| BridgeError::Protocol(error.to_string()))?,
            "action": action,
            "arguments": arguments,
        });
        let value = self.call_once("act", params)?;
        serde_json::from_value(value)
            .map_err(|error| BridgeError::Protocol(error.to_string()))
    }

    /// Lists the launchable applications the bridge app can see.
    pub fn list_apps(&self) -> Result<Vec<BridgeApp>, BridgeError> {
        let value = self.call("list_apps", json!({}))?;
        let apps = value
            .get("apps")
            .cloned()
            .ok_or_else(|| BridgeError::Protocol("list_apps result has no apps".to_owned()))?;
        serde_json::from_value(apps)
            .map_err(|error| BridgeError::Protocol(error.to_string()))
    }

    /// Launches an application by package name through the bridge app.
    pub fn launch_app(&self, package: &str) -> Result<bool, BridgeError> {
        let value = self.call_once("launch_app", json!({ "package": package }))?;
        Ok(value
            .get("launched")
            .and_then(Value::as_bool)
            .unwrap_or(false))
    }

    /// Captures the display through the bridge app's screenshot facility.
    pub fn screenshot(&self) -> Result<BridgeScreenshot, BridgeError> {
        let value = self.call("screenshot", json!({}))?;
        serde_json::from_value(value)
            .map_err(|error| BridgeError::Protocol(error.to_string()))
    }
}

fn connect_with_hello() -> Result<BridgeConnection, BridgeError> {
    let mut connection = BridgeConnection::connect(DEFAULT_TIMEOUT)?;
    let token = read_bridge_token().unwrap_or_default();
    connection.hello(&token)?;
    Ok(connection)
}

fn read_bridge_token() -> Option<String> {
    let path = match std::env::var_os("AGENT_DESKTOP_HOME") {
        Some(root) => PathBuf::from(root).join("android-bridge-token"),
        None => PathBuf::from(std::env::var_os("HOME")?).join(".agent-desktop/android-bridge-token"),
    };
    let raw = std::fs::read_to_string(path).ok()?;
    let token = raw.trim().to_owned();
    if token.is_empty() { None } else { Some(token) }
}
