//! The Android adapter: a thin dispatcher over the bridge, mirroring the
//! shape of the Linux adapter. Methods the bridge cannot yet serve keep
//! the core defaults, which answer with an honest platform-not-supported
//! error.

use agent_desktop_core::{
    AccessibilityNode, ActionOps, AdapterError, AppInfo, Deadline, ElementState, ImageBuffer,
    InputOps, InteractionLease, LocatorField, NativeHandle, ObservationOps, ObservationRequest,
    ObservationRoot, ObservedTree, PermissionReport, PermissionState, Rect, RefEntry,
    SnapshotSurface, SystemOps, WindowFilter, WindowInfo,
    actions::action_request::ActionRequest, actions::action_result::ActionResult,
    LiveElement, LocatorStats, LocatorTraversalStats, ObservationSource,
    system::display_info::DisplayInfo, system::launch_options::LaunchOptions,
    system::launch_result::LaunchResult, system::screenshot_target::ScreenshotTarget,
    tree::tree_options::TreeOptions,
};

use crate::bridge::{BridgeError, SharedBridge};
use crate::tree::{
    AndroidElementHandle, accessibility_node, actions_for, bounds_for, count_nodes, find_by_path,
    identifiers_for, name_for, observed_subtree, proof_for, role_for, states_for, value_for,
    description_for,
};

/// Holds one shared bridge connection to the companion APK, mirroring the
/// role of the shared bus handle in the Linux adapter.
pub struct AndroidAdapter {
    bridge: SharedBridge,
}

impl AndroidAdapter {
    pub fn new() -> Self {
        Self {
            bridge: SharedBridge::default(),
        }
    }

    pub(crate) fn bridge(&self) -> &SharedBridge {
        &self.bridge
    }

    fn handle_payload<'a>(
        &self,
        handle: &'a NativeHandle,
    ) -> Result<&'a AndroidElementHandle, AdapterError> {
        handle
            .downcast_ref::<AndroidElementHandle>()
            .ok_or_else(|| AdapterError::internal("native handle carries no Android element"))
    }

    fn live_node(
        &self,
        handle: &NativeHandle,
    ) -> Result<crate::bridge::BridgeNode, AdapterError> {
        let payload = self.handle_payload(handle)?;
        let tree = self
            .bridge()
            .get_tree()
            .map_err(|error| error.into_adapter_error())?;
        find_by_path(&tree.root, &payload.path)
            .cloned()
            .ok_or_else(|| {
                AdapterError::stale_ref_because(
                    "The element's recorded path no longer exists in the live tree",
                )
            })
    }
}

impl Default for AndroidAdapter {
    fn default() -> Self {
        Self::new()
    }
}

impl ObservationOps for AndroidAdapter {
    fn focused_app(&self, _deadline: Deadline) -> Result<Option<AppInfo>, AdapterError> {
        match self.bridge().get_tree() {
            Ok(tree) => Ok(Some(crate::system::app_info(&tree))),
            Err(BridgeError::Remote { code, .. }) if code == "no_active_window" => Ok(None),
            Err(error) => Err(error.into_adapter_error()),
        }
    }

    fn observe_tree(
        &self,
        root: ObservationRoot<'_>,
        request: &ObservationRequest,
    ) -> Result<ObservedTree, AdapterError> {
        let tree = self
            .bridge()
            .get_tree()
            .map_err(|error| error.into_adapter_error())?;
        let source = ObservationSource::from_root(&root, request.surface);
        let stats = LocatorStats {
            traversal: LocatorTraversalStats {
                nodes_visited: count_nodes(&tree.root) as u64,
                ..LocatorTraversalStats::default()
            },
            ..LocatorStats::default()
        };
        let subtree = match root {
            ObservationRoot::Element { handle, .. } => {
                let payload = self.handle_payload(handle)?;
                let node = find_by_path(&tree.root, &payload.path).ok_or_else(|| {
                    AdapterError::stale_ref_because(
                        "The element's recorded path no longer exists in the live tree",
                    )
                })?;
                observed_subtree(node, role_for(node), 0, request.max_raw_depth)
            }
            ObservationRoot::Application { .. } | ObservationRoot::Window(_) => {
                observed_subtree(&tree.root, "window", 0, request.max_raw_depth)
            }
        };
        ObservedTree::from_roots(vec![subtree], source, stats, !tree.truncated)
    }

    fn list_windows(
        &self,
        filter: &WindowFilter,
        _deadline: Deadline,
    ) -> Result<Vec<WindowInfo>, AdapterError> {
        let tree = self
            .bridge()
            .get_tree()
            .map_err(|error| error.into_adapter_error())?;
        let window = crate::system::window_info(&tree);
        if let Some(app) = filter.app.as_deref()
            && !window.app.eq_ignore_ascii_case(app)
        {
            return Ok(Vec::new());
        }
        Ok(vec![window])
    }

    fn list_apps(&self, _deadline: Deadline) -> Result<Vec<AppInfo>, AdapterError> {
        let apps = self
            .bridge()
            .list_apps()
            .map_err(|error| error.into_adapter_error())?;
        Ok(apps
            .into_iter()
            .map(|app| AppInfo {
                name: if app.label.is_empty() {
                    app.package.clone()
                } else {
                    app.label
                },
                pid: crate::system::synthetic_pid(&app.package),
                bundle_id: Some(app.package),
                process_instance: None,
                presentation: Some(agent_desktop_core::AppPresentation::Foreground),
            })
            .collect())
    }

    fn get_tree(
        &self,
        _win: &WindowInfo,
        _opts: &TreeOptions,
        _deadline: Deadline,
    ) -> Result<AccessibilityNode, AdapterError> {
        let tree = self
            .bridge()
            .get_tree()
            .map_err(|error| error.into_adapter_error())?;
        Ok(accessibility_node(&tree.root, "window"))
    }

    fn resolve_locator_anchor(
        &self,
        entry: &RefEntry,
        _deadline: Deadline,
    ) -> Result<NativeHandle, AdapterError> {
        let tree = self
            .bridge()
            .get_tree()
            .map_err(|error| error.into_adapter_error())?;
        let path: Vec<usize> = entry.scope.path.iter().copied().collect();
        let node = find_by_path(&tree.root, &path).ok_or_else(|| {
            AdapterError::stale_ref_because(
                "The element's recorded path no longer exists in the live tree",
            )
        })?;
        let proof = proof_for(entry, node);
        Ok(NativeHandle::new(AndroidElementHandle { proof, path }))
    }

    fn get_live_value(
        &self,
        handle: &NativeHandle,
        _deadline: Deadline,
    ) -> Result<Option<String>, AdapterError> {
        Ok(value_for(&self.live_node(handle)?))
    }

    fn get_live_state(
        &self,
        handle: &NativeHandle,
        _deadline: Deadline,
    ) -> Result<Option<ElementState>, AdapterError> {
        let node = self.live_node(handle)?;
        Ok(Some(ElementState {
            role: role_for(&node).to_owned(),
            states: states_for(&node),
            value: value_for(&node),
            enabled: Some(node.state.enabled),
            hidden: Some(!node.state.visible),
            offscreen: Some(!node.state.visible),
        }))
    }

    fn get_live_actions(
        &self,
        handle: &NativeHandle,
        _deadline: Deadline,
    ) -> Result<Option<Vec<String>>, AdapterError> {
        Ok(Some(actions_for(&self.live_node(handle)?)))
    }

    fn get_live_element(
        &self,
        handle: &NativeHandle,
        _deadline: Deadline,
    ) -> Result<LiveElement, AdapterError> {
        let node = self.live_node(handle)?;
        Ok(LiveElement {
            identity: agent_desktop_core::LiveIdentity {
                name: name_for(&node).map_or(LocatorField::Absent, LocatorField::Known),
                description: description_for(&node)
                    .map_or(LocatorField::Absent, LocatorField::Known),
                identifiers: identifiers_for(&node),
            },
            state: ElementState {
                role: role_for(&node).to_owned(),
                states: states_for(&node),
                value: value_for(&node),
                enabled: Some(node.state.enabled),
                hidden: Some(!node.state.visible),
                offscreen: Some(!node.state.visible),
            },
            states_complete: true,
            bounds: Some(bounds_for(&node)),
            available_actions: actions_for(&node),
        })
    }

    fn get_element_bounds(
        &self,
        handle: &NativeHandle,
        _deadline: Deadline,
    ) -> Result<Option<Rect>, AdapterError> {
        Ok(Some(bounds_for(&self.live_node(handle)?)))
    }
}

impl ActionOps for AndroidAdapter {
    fn execute_action(
        &self,
        handle: &NativeHandle,
        request: ActionRequest,
        _lease: &InteractionLease,
    ) -> Result<ActionResult, AdapterError> {
        let payload = self.handle_payload(handle)?.clone();
        crate::actions::execute_action(self.bridge(), &payload, &request)
    }

    fn scroll_into_view(
        &self,
        handle: &NativeHandle,
        _lease: &InteractionLease,
    ) -> Result<(), AdapterError> {
        let payload = self.handle_payload(handle)?.clone();
        crate::actions::scroll_into_view(self.bridge(), &payload)
    }
}

impl InputOps for AndroidAdapter {}

impl SystemOps for AndroidAdapter {
    fn acquire_interaction_lease(
        &self,
        deadline: Deadline,
    ) -> Result<InteractionLease, AdapterError> {
        InteractionLease::guarded(deadline, ())
    }

    fn permission_report(
        &self,
        _deadline: Deadline,
    ) -> Result<PermissionReport, AdapterError> {
        match self.bridge().ping() {
            Ok(status) => Ok(PermissionReport {
                accessibility: if status.accessibility {
                    PermissionState::Granted
                } else {
                    PermissionState::Denied {
                        suggestion:
                            "Enable the agent-desktop bridge AccessibilityService in Android Settings"
                                .to_owned(),
                    }
                },
                screen_recording: if status.accessibility {
                    PermissionState::Granted
                } else {
                    PermissionState::Unknown
                },
                automation: PermissionState::NotRequired,
            }),
            Err(error) => Err(error.into_adapter_error()),
        }
    }

    fn supported_surfaces(&self) -> Vec<SnapshotSurface> {
        vec![SnapshotSurface::Window, SnapshotSurface::Focused]
    }

    fn focused_window(
        &self,
        _deadline: Deadline,
    ) -> Result<Option<WindowInfo>, AdapterError> {
        match self.bridge().get_tree() {
            Ok(tree) => Ok(Some(crate::system::window_info(&tree))),
            Err(BridgeError::Remote { code, .. }) if code == "no_active_window" => Ok(None),
            Err(error) => Err(error.into_adapter_error()),
        }
    }

    fn launch_app(
        &self,
        id: &str,
        _options: &LaunchOptions,
        _lease: &InteractionLease,
    ) -> Result<LaunchResult, AdapterError> {
        crate::system::launch_app(self.bridge(), id)
    }

    fn screenshot(
        &self,
        _target: ScreenshotTarget,
        _deadline: Deadline,
    ) -> Result<ImageBuffer, AdapterError> {
        crate::system::screenshot(self.bridge())
    }

    fn screenshot_window_frame(
        &self,
        _window: &WindowInfo,
        _deadline: Deadline,
    ) -> Result<ImageBuffer, AdapterError> {
        crate::system::screenshot(self.bridge())
    }

    fn list_displays(&self, _deadline: Deadline) -> Result<Vec<DisplayInfo>, AdapterError> {
        let tree = self
            .bridge()
            .get_tree()
            .map_err(|error| error.into_adapter_error())?;
        Ok(vec![DisplayInfo {
            id: "android-display-0".to_owned(),
            bounds: bounds_for(&tree.root),
            is_primary: true,
            scale: 1.0,
        }])
    }
}
