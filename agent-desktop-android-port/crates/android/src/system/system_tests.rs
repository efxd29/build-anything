use super::{SYNTHETIC_PID_BASE, app_info, synthetic_pid, window_info};
use crate::bridge::{BridgeNode, BridgeNodeState, BridgeTree};

fn node(package: &str) -> BridgeNode {
    BridgeNode {
        class_name: "android.widget.FrameLayout".to_owned(),
        package: package.to_owned(),
        view_id: String::new(),
        text: String::new(),
        content_desc: String::new(),
        bounds: [0, 0, 1080, 2400],
        value: String::new(),
        actions: Vec::new(),
        state: BridgeNodeState {
            clickable: false,
            enabled: true,
            focused: true,
            checked: false,
            checkable: false,
            selected: false,
            scrollable: false,
            editable: false,
            password: false,
            visible: true,
            focusable: false,
            long_clickable: false,
        },
        subtree_truncated: false,
        children: Vec::new(),
    }
}

fn tree(package: &str, process_instance: &str) -> BridgeTree {
    BridgeTree {
        root: node(package),
        truncated: false,
        process_instance: process_instance.to_owned(),
    }
}

#[test]
fn synthetic_pid_is_positive_and_inside_the_reserved_band() {
    let pid = synthetic_pid("com.termux").get();
    assert!(pid >= SYNTHETIC_PID_BASE);
    assert!(pid < 0x8000_0000);
}

#[test]
fn synthetic_pid_is_stable_for_one_package() {
    assert_eq!(synthetic_pid("com.termux"), synthetic_pid("com.termux"));
}

#[test]
fn synthetic_pid_differs_between_packages() {
    let packages = [
        "com.termux",
        "com.android.settings",
        "org.mozilla.firefox",
        "com.whatsapp",
        "dev.agentdesktop.bridge",
    ];
    let mut seen = std::collections::HashSet::new();
    for package in packages {
        assert!(
            seen.insert(synthetic_pid(package)),
            "collision for {package}"
        );
    }
}

#[test]
fn window_and_app_identity_agree_for_one_tree() {
    let observed = tree("com.termux", "12345:3");
    let window = window_info(&observed);
    let app = app_info(&observed);
    assert_eq!(window.pid, synthetic_pid("com.termux"));
    assert_eq!(window.pid, app.pid);
    assert_eq!(window.process_instance.as_deref(), Some("12345:3"));
    assert_eq!(app.process_instance, window.process_instance);
    assert_eq!(window.id, "android:com.termux");
}

#[test]
fn an_empty_reported_instance_maps_to_none() {
    let observed = tree("com.termux", "");
    assert_eq!(window_info(&observed).process_instance, None);
    assert_eq!(app_info(&observed).process_instance, None);
}
