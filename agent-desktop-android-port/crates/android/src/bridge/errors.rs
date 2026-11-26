//! Bridge failure kinds and their mapping onto upstream adapter errors.

use agent_desktop_core::{AdapterError, ErrorCode};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum BridgeError {
    #[error("companion bridge app is not reachable: {0}")]
    Unavailable(String),
    #[error("bridge connection failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("bridge protocol error: {0}")]
    Protocol(String),
    #[error("bridge rejected the hello token")]
    TokenRejected,
    #[error("bridge call timed out")]
    Timeout,
}

impl BridgeError {
    pub fn into_adapter_error(self) -> AdapterError {
        match self {
            Self::Unavailable(detail) => AdapterError::new(
                ErrorCode::PermDenied,
                "Android accessibility bridge is not available",
            )
            .with_suggestion(
                "Install the agent-desktop bridge app and enable its AccessibilityService in Android Settings",
            )
            .with_platform_detail(detail),
            Self::TokenRejected => AdapterError::new(
                ErrorCode::PermDenied,
                "Android accessibility bridge rejected the client token",
            )
            .with_suggestion("Copy the current bridge token from the bridge app into ~/.agent-desktop/android-bridge-token"),
            Self::Timeout => AdapterError::new(ErrorCode::Timeout, "Bridge call timed out"),
            Self::Io(error) => {
                AdapterError::new(ErrorCode::Internal, format!("Bridge connection failed: {error}"))
            }
            Self::Protocol(detail) => {
                AdapterError::new(ErrorCode::Internal, "Bridge protocol error")
                    .with_platform_detail(detail)
            }
        }
    }
}
