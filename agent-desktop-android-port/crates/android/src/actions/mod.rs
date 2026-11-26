//! Actions layer: maps core action requests onto bridge calls. Ref actions
//! travel as capture proofs and are re-matched inside the bridge app in the
//! same request that performs them, so a stale node path can never be acted
//! on by mistake.

use agent_desktop_core::{
    Action, ActionResult, ActionStep, AdapterError, Direction, ErrorCode,
    actions::action_request::ActionRequest,
};
use serde_json::{Value, json};

use crate::bridge::{BridgeActResult, SharedBridge};
use crate::tree::AndroidElementHandle;

/// Executes one core action against the element a handle names, through
/// the bridge's proof-matched act call.
pub(crate) fn execute_action(
    bridge: &SharedBridge,
    handle: &AndroidElementHandle,
    request: &ActionRequest,
) -> Result<ActionResult, AdapterError> {
    let (method, arguments) = bridge_action(&request.action, handle)?;
    let result = bridge
        .act_on_match(&handle.proof, method, arguments)
        .map_err(|error| error.into_adapter_error())?;
    require_single_match(&result)?;
    if !result.performed {
        return Err(AdapterError::new(
            ErrorCode::ActionFailed,
            "The bridge matched the element but could not perform the action",
        ));
    }
    Ok(ActionResult::from_execution(
        &request.action,
        vec![ActionStep::succeeded("bridge_action")],
    ))
}

/// Scrolls the element a handle names into view through the bridge, which
/// walks to the nearest scrollable ancestor and scrolls it until the
/// element reports itself visible.
pub(crate) fn scroll_into_view(
    bridge: &SharedBridge,
    handle: &AndroidElementHandle,
) -> Result<(), AdapterError> {
    let result = bridge
        .act_on_match(&handle.proof, "scroll_into_view", json!({}))
        .map_err(|error| error.into_adapter_error())?;
    require_single_match(&result)?;
    if !result.performed {
        return Err(AdapterError::new(
            ErrorCode::ActionFailed,
            "The bridge could not scroll the element into view",
        ));
    }
    Ok(())
}

/// Maps a matched count onto the upstream verdicts: zero matches is a
/// stale ref, more than one is an ambiguous target.
pub(crate) fn require_single_match(result: &BridgeActResult) -> Result<(), AdapterError> {
    match result.matched {
        1 => Ok(()),
        0 => Err(AdapterError::stale_ref_because(
            "The element no longer matches the proof its snapshot recorded",
        )),
        _ => Err(AdapterError::ambiguous_target(
            "More than one live element matches the snapshot proof",
        )),
    }
}

fn bridge_action(
    action: &Action,
    handle: &AndroidElementHandle,
) -> Result<(&'static str, Value), AdapterError> {
    let mapped = match action {
        Action::Click => ("click", json!({})),
        Action::DoubleClick => ("double_click", json!({})),
        Action::TripleClick => ("triple_click", json!({})),
        Action::RightClick => ("long_click", json!({})),
        Action::SetValue(value) => {
            if handle.proof.role == "slider" {
                ("set_progress", json!({ "value": value }))
            } else {
                ("set_text", json!({ "text": value }))
            }
        }
        Action::SetFocus => ("focus", json!({})),
        Action::Expand => ("expand", json!({})),
        Action::Collapse => ("collapse", json!({})),
        Action::Toggle | Action::Check | Action::Uncheck => ("click", json!({})),
        Action::Scroll(direction, amount) => match direction {
            Direction::Down | Direction::Right => {
                ("scroll_forward", json!({ "amount": amount }))
            }
            Direction::Up | Direction::Left => {
                ("scroll_backward", json!({ "amount": amount }))
            }
        },
        Action::ScrollTo => ("scroll_into_view", json!({})),
        Action::TypeText(text) => ("type_text", json!({ "text": text })),
        Action::Clear => ("set_text", json!({ "text": "" })),
        Action::Select(_)
        | Action::PressKey(_)
        | Action::KeyDown(_)
        | Action::KeyUp(_)
        | Action::Hover
        | Action::Drag(_) => {
            return Err(AdapterError::not_supported(action.name()));
        }
    };
    Ok(mapped)
}
