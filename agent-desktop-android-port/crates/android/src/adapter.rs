//! The Android adapter: a thin dispatcher over the bridge, mirroring the
//! shape of the Linux adapter. Methods the bridge cannot yet serve keep the
//! core defaults, which answer with an honest platform-not-supported error.

use agent_desktop_core::{
    ActionOps, Deadline, InputOps, ObservationOps, PermissionReport, PermissionState, SnapshotSurface,
    SystemOps,
};

use crate::bridge::SharedBridge;

/// Holds one shared bridge connection to the companion APK, mirroring the role
/// of the shared bus handle in the Linux adapter.
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
}

impl Default for AndroidAdapter {
    fn default() -> Self {
        Self::new()
    }
}

impl ObservationOps for AndroidAdapter {}

impl ActionOps for AndroidAdapter {}

impl InputOps for AndroidAdapter {}

impl SystemOps for AndroidAdapter {
    fn permission_report(
        &self,
        _deadline: Deadline,
    ) -> Result<PermissionReport, agent_desktop_core::AdapterError> {
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
}
