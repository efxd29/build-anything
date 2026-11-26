//! Tree layer: converts the bridge app's serialised Android nodes into the
//! trees core observes, and resolves saved refs against a fresh tree by
//! capture proof (role, view id, name and description), never by cached
//! node handles.

use agent_desktop_core::{
    AccessibilityNode, ElementIdentifier, IdentifierEvidence, IdentifierKind, LocatorEvidence,
    LocatorField, LocatorRefEvidence, NodeIdentity, NodePresentation, ObservedSubtree, Rect,
    RefEntry,
};

use crate::bridge::{BridgeNode, BridgeProof};

/// The payload a resolved ref carries inside a core native handle: the
/// capture proof the bridge re-matches before acting, plus the child-index
/// path the anchor landed on, which live reads descend directly because
/// core's resolution already counted the proof over the whole window.
#[derive(Debug, Clone)]
pub(crate) struct AndroidElementHandle {
    pub proof: BridgeProof,
    pub path: Vec<usize>,
}

/// Maps an Android node onto the upstream role vocabulary from its class
/// and advertised capabilities, never from its package or app identity.
pub(crate) fn role_for(node: &BridgeNode) -> &'static str {
    match node.class_name.as_str() {
        "android.widget.Button" | "android.widget.ImageButton" => "button",
        "android.widget.EditText" => "textfield",
        "android.widget.CheckBox" => "checkbox",
        "android.widget.Switch" => "switch",
        "android.widget.RadioButton" => "radiobutton",
        "android.widget.SeekBar" => "slider",
        "android.widget.ProgressBar" => "progressbar",
        "android.widget.Spinner" => "combobox",
        "android.widget.ListView" | "androidx.recyclerview.widget.RecyclerView" => "list",
        "android.widget.TextView" => "statictext",
        "android.widget.ImageView" => "image",
        _ if node.state.editable => "textfield",
        _ if node.state.checkable => "checkbox",
        _ if node.state.clickable => "button",
        _ if node.state.scrollable => "scrollarea",
        _ => "group",
    }
}

/// The accessible name of a node: its text, falling back to its content
/// description, per the upstream accname ordering.
pub(crate) fn name_for(node: &BridgeNode) -> Option<String> {
    if !node.text.is_empty() {
        return Some(node.text.clone());
    }
    if !node.content_desc.is_empty() {
        return Some(node.content_desc.clone());
    }
    None
}

/// The description of a node: its content description, when the text does
/// not already carry it as the name.
pub(crate) fn description_for(node: &BridgeNode) -> Option<String> {
    if !node.text.is_empty()
        && !node.content_desc.is_empty()
        && node.content_desc != node.text
    {
        return Some(node.content_desc.clone());
    }
    None
}

/// The control value of a node: the text of an editable element. Other
/// Android text is a name, not a value, and values are never identity.
pub(crate) fn value_for(node: &BridgeNode) -> Option<String> {
    if node.state.editable && !node.text.is_empty() {
        return Some(node.text.clone());
    }
    None
}

/// The upstream state tokens a node's flags prove.
pub(crate) fn states_for(node: &BridgeNode) -> Vec<String> {
    let mut states = Vec::new();
    if node.state.focused {
        states.push("focused".to_owned());
    }
    if !node.state.enabled {
        states.push("disabled".to_owned());
    }
    if node.state.checked {
        states.push("checked".to_owned());
    }
    if node.state.selected {
        states.push("selected".to_owned());
    }
    if node.state.password {
        states.push("secure".to_owned());
    }
    if !node.state.visible {
        states.push("hidden".to_owned());
    }
    states
}

/// The upstream action names a node advertises, derived from its state
/// flags and the action list the bridge serialised.
pub(crate) fn actions_for(node: &BridgeNode) -> Vec<String> {
    let mut actions: Vec<String> = Vec::new();
    let push = |name: &str, actions: &mut Vec<String>| {
        if !actions.iter().any(|known| known == name) {
            actions.push(name.to_owned());
        }
    };
    if node.state.clickable {
        push("Click", &mut actions);
    }
    if node.state.long_clickable || node.actions.iter().any(|action| action == "long_click") {
        push("RightClick", &mut actions);
    }
    if node.state.focusable {
        push("SetFocus", &mut actions);
    }
    if node.state.editable {
        push("SetValue", &mut actions);
        push("TypeText", &mut actions);
    }
    if node.state.checkable {
        push("Toggle", &mut actions);
        push("Check", &mut actions);
        push("Uncheck", &mut actions);
    }
    if node.state.scrollable {
        push("Scroll", &mut actions);
        push("ScrollTo", &mut actions);
    }
    if node.actions.iter().any(|action| action == "expand") {
        push("Expand", &mut actions);
    }
    if node.actions.iter().any(|action| action == "collapse") {
        push("Collapse", &mut actions);
    }
    if node.actions.iter().any(|action| action == "select") {
        push("Select", &mut actions);
    }
    actions
}

/// The screen rectangle of a node, from its serialised bounds.
pub(crate) fn bounds_for(node: &BridgeNode) -> Rect {
    Rect {
        x: f64::from(node.bounds[0]),
        y: f64::from(node.bounds[1]),
        width: f64::from(node.bounds[2] - node.bounds[0]),
        height: f64::from(node.bounds[3] - node.bounds[1]),
    }
}

/// The identifier evidence of a node: its view id resource name, the one
/// stable identifier Android exposes, complete on every read.
pub(crate) fn identifiers_for(node: &BridgeNode) -> IdentifierEvidence {
    if node.view_id.is_empty() {
        return IdentifierEvidence::absent();
    }
    IdentifierEvidence::typed(
        [ElementIdentifier {
            kind: IdentifierKind::Unknown,
            value: node.view_id.clone(),
        }],
        Some(0),
        true,
    )
}

fn field(value: Option<String>) -> LocatorField<String> {
    value.map_or(LocatorField::Absent, LocatorField::Known)
}

/// The locator evidence one bridge node proves, in the shape core counts
/// capture proofs and locator matches over.
pub(crate) fn evidence_for(node: &BridgeNode, role: &str) -> LocatorEvidence {
    LocatorEvidence {
        role: LocatorField::Known(role.to_owned()),
        name: field(name_for(node)),
        description: field(description_for(node)),
        value: field(value_for(node)),
        identifiers: identifiers_for(node),
        states: LocatorField::Known(states_for(node)),
        ref_evidence: LocatorRefEvidence {
            bounds: LocatorField::Known(bounds_for(node)),
            window_bounds: LocatorField::Absent,
            available_actions: LocatorField::Known(actions_for(node)),
            descriptors: Default::default(),
        },
    }
}

/// Converts a bridge subtree into an observed subtree, cutting children at
/// the request's raw depth the way a platform walk does: a cut node keeps
/// its child count and stays complete, because the cut is the walk's own.
pub(crate) fn observed_subtree(
    node: &BridgeNode,
    role: &str,
    depth: u8,
    max_raw_depth: u8,
) -> ObservedSubtree {
    if depth >= max_raw_depth {
        let count = u32::try_from(node.children.len()).ok().filter(|count| *count > 0);
        return ObservedSubtree::new(evidence_for(node, role), Vec::new(), !node.subtree_truncated, count);
    }
    let children = node
        .children
        .iter()
        .map(|child| observed_subtree(child, role_for(child), depth.saturating_add(1), max_raw_depth))
        .collect();
    ObservedSubtree::new(evidence_for(node, role), children, !node.subtree_truncated, None)
}

/// Converts a bridge subtree into a plain accessibility tree, for the
/// adapter's direct `get_tree` reads.
pub(crate) fn accessibility_node(node: &BridgeNode, role: &str) -> AccessibilityNode {
    AccessibilityNode {
        ref_id: None,
        role: role.to_owned(),
        identity: NodeIdentity {
            retained_object: None,
            name: name_for(node),
            value: value_for(node),
            description: description_for(node),
            native_id: if node.view_id.is_empty() {
                None
            } else {
                Some(ElementIdentifier {
                    kind: IdentifierKind::Unknown,
                    value: node.view_id.clone(),
                })
            },
        },
        presentation: NodePresentation {
            hint: None,
            states: states_for(node),
            available_actions: actions_for(node),
            bounds: Some(bounds_for(node)),
            ref_bounds: None,
            descriptors: Default::default(),
        },
        children_count: None,
        subtree_truncated: node.subtree_truncated,
        children: node
            .children
            .iter()
            .map(|child| accessibility_node(child, role_for(child)))
            .collect(),
    }
}

/// Descends a child-index path from a tree root, returning the node it
/// lands on when every index is in range.
pub(crate) fn find_by_path<'a>(root: &'a BridgeNode, path: &[usize]) -> Option<&'a BridgeNode> {
    let mut current = root;
    for index in path {
        current = current.children.get(*index)?;
    }
    Some(current)
}

/// Builds the bridge proof for a saved ref from the entry core recorded and
/// the live node its path landed on, which supplies the class name the
/// entry itself does not store.
pub(crate) fn proof_for(entry: &RefEntry, node: &BridgeNode) -> BridgeProof {
    BridgeProof {
        role: entry.identity.role.clone(),
        class_name: node.class_name.clone(),
        view_id: entry
            .identity
            .native_id
            .as_ref()
            .map(|identifier| identifier.value.clone())
            .or_else(|| {
                if node.view_id.is_empty() {
                    None
                } else {
                    Some(node.view_id.clone())
                }
            }),
        name: stable_proof_name(entry),
        description: entry.identity.description.clone(),
    }
}

/// The name a proof may constrain: for the mutable-value roles core drops
/// a name that merely restates the control's current value, because the
/// value changes under normal interaction and is never identity.
fn stable_proof_name(entry: &RefEntry) -> Option<String> {
    let mutable_value_role = matches!(
        entry.identity.role.as_str(),
        "combobox"
            | "checkbox"
            | "handle"
            | "incrementor"
            | "listbox"
            | "radiobutton"
            | "scrollbar"
            | "slider"
            | "switch"
            | "textfield"
    );
    if mutable_value_role
        && entry.identity.name.is_some()
        && entry.identity.name == entry.identity.value
    {
        return None;
    }
    entry.identity.name.clone()
}

/// Counts every node in a serialised tree, root included.
pub(crate) fn count_nodes(node: &BridgeNode) -> usize {
    1 + node.children.iter().map(count_nodes).sum::<usize>()
}
