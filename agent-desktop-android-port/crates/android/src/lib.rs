#![cfg_attr(not(test), deny(clippy::unwrap_used, clippy::expect_used))]

//! Android platform adapter for agent-desktop.
//!
//! The adapter is a client of the companion bridge app: every Android
//! framework capability (accessibility tree, gestures, screenshots) lives in
//! that app and is reached over a local socket. This crate owns the upstream
//! contract side only: trait implementations, tree conversion and ref
//! resolution semantics.

mod actions;
mod adapter;
mod bridge;
mod input;
mod notifications;
mod system;
mod tree;

pub use adapter::AndroidAdapter;
