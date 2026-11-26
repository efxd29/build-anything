//! Bridge client to the companion APK, replacing the D-Bus layer of the
//! Linux adapter: a lazily connected, reconnecting client for the abstract
//! Unix socket the bridge app serves, speaking newline-delimited JSON.

mod connection;
mod errors;
mod protocol;

use std::path::PathBuf;
use std::sync::Mutex;

use serde_json::{Value, json};

pub use errors::BridgeError;
pub use protocol::{BridgeNode, BridgeStatus};

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

    /// Answers whether the bridge app answers and what it reports.
    pub fn ping(&self) -> Result<BridgeStatus, BridgeError> {
        let value = self.call("ping", json!({}))?;
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
