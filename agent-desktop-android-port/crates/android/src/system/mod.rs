//! System layer: app inventory and launch, the single-window model,
//! screenshots, permissions and wait baselines, all served by the bridge
//! app over its Android framework access.

use agent_desktop_core::{
    AdapterError, AppInfo, AppPresentation, ErrorCode, ImageBuffer, ImageFormat, ProcessId,
    WindowInfo, WindowState, parse_png_dimensions,
    system::launch_result::LaunchResult,
};

use crate::bridge::{BridgeTree, SharedBridge};
use crate::tree::bounds_for;

/// The band synthetic pids occupy: at or above 2^30. Android's pid_max
/// stays below 2^22, so no real process id can collide with one.
const SYNTHETIC_PID_BASE: u32 = 0x4000_0000;

/// The process id this adapter reports for one package. Android gives an
/// accessibility service no way to read another app's real pid:
/// AccessibilityNodeInfo and AccessibilityWindowInfo carry none,
/// ActivityManager has shown only the caller's own process since API 22,
/// and SELinux keeps other apps' /proc entries unreadable. This is
/// therefore a SYNTHETIC id, not the app's pid: an FNV-1a hash of the
/// package name folded into the band above. It is stable for a package
/// across observations, differs between packages, and is only ever
/// compared for equality inside ref and window identity, the same use
/// core makes of a real pid on the desktop platforms.
pub(crate) fn synthetic_pid(package: &str) -> ProcessId {
    let mut hash: u32 = 0x811c_9dc5;
    for byte in package.bytes() {
        hash ^= u32::from(byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    ProcessId::new(SYNTHETIC_PID_BASE | (hash & 0x3fff_ffff))
}

/// The process-instance token for one observation: the presence-epoch
/// token the bridge minted for the root package (see BridgeServer). An
/// empty report means an old bridge that sends none, and maps to no
/// instance rather than an invented one.
pub(crate) fn process_instance(tree: &BridgeTree) -> Option<String> {
    if tree.process_instance.is_empty() {
        None
    } else {
        Some(tree.process_instance.clone())
    }
}

/// The single window Android presents: the active window of the foreground
/// application, identified by its package.
pub(crate) fn window_info(tree: &BridgeTree) -> WindowInfo {
    let root = &tree.root;
    let package = if root.package.is_empty() {
        "unknown".to_owned()
    } else {
        root.package.clone()
    };
    WindowInfo {
        id: format!("android:{package}"),
        title: package.clone(),
        app: package.clone(),
        pid: synthetic_pid(&package),
        process_instance: process_instance(tree),
        bounds: Some(bounds_for(root)),
        state: WindowState {
            is_focused: true,
            accessible: true,
            minimized: Some(false),
            visible: Some(true),
        },
    }
}

/// The foreground application a window root belongs to.
pub(crate) fn app_info(tree: &BridgeTree) -> AppInfo {
    let root = &tree.root;
    AppInfo {
        name: root.package.clone(),
        pid: synthetic_pid(&root.package),
        bundle_id: if root.package.is_empty() {
            None
        } else {
            Some(root.package.clone())
        },
        process_instance: process_instance(tree),
        presentation: Some(AppPresentation::Foreground),
    }
}

/// Launches an application the caller named by package or by label,
/// resolving a label through the bridge's own inventory first.
pub(crate) fn launch_app(
    bridge: &SharedBridge,
    id: &str,
) -> Result<LaunchResult, AdapterError> {
    let package = resolve_package(bridge, id)?;
    let launched = bridge
        .launch_app(&package)
        .map_err(|error| error.into_adapter_error())?;
    if !launched {
        return Err(AdapterError::new(
            ErrorCode::AppNotFound,
            format!("No launchable activity was found for {package}"),
        ));
    }
    Ok(LaunchResult {
        app: package.clone(),
        pid: synthetic_pid(&package),
        process_instance: None,
        window: None,
        cdp: None,
        renderer: None,
        suggestion: None,
    })
}

fn resolve_package(bridge: &SharedBridge, id: &str) -> Result<String, AdapterError> {
    if id.contains('.') {
        return Ok(id.to_owned());
    }
    let apps = bridge
        .list_apps()
        .map_err(|error| error.into_adapter_error())?;
    apps.iter()
        .find(|app| app.label.eq_ignore_ascii_case(id) || app.package.eq_ignore_ascii_case(id))
        .map(|app| app.package.clone())
        .ok_or_else(|| {
            AdapterError::new(
                ErrorCode::AppNotFound,
                format!("No installed application matches {id}"),
            )
        })
}

/// Captures the display through the bridge and decodes its PNG payload.
pub(crate) fn screenshot(bridge: &SharedBridge) -> Result<ImageBuffer, AdapterError> {
    let shot = bridge
        .screenshot()
        .map_err(|error| error.into_adapter_error())?;
    let data = decode_base64(&shot.png_base64)?;
    let (width, height) = parse_png_dimensions(&data).ok_or_else(|| {
        AdapterError::new(ErrorCode::Internal, "Bridge screenshot was not a valid PNG")
    })?;
    Ok(ImageBuffer {
        data,
        format: ImageFormat::Png,
        width,
        height,
        scale_factor: 1.0,
    })
}

fn decode_base64(payload: &str) -> Result<Vec<u8>, AdapterError> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(payload.as_bytes())
        .map_err(|error| {
            AdapterError::new(ErrorCode::Internal, "Bridge screenshot was not valid base64")
                .with_platform_detail(error.to_string())
        })
}

#[cfg(test)]
#[path = "system_tests.rs"]
mod tests;
