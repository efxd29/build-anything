//! Tree layer: converts the bridge app's serialised Android nodes into the
//! trees core observes, and resolves saved refs against a fresh tree by
//! capture proof (role, view id, name and description), never by cached
//! node handles.

use crate::bridge::BridgeNode;

/// Maps an Android node onto the upstream role vocabulary from its class
/// and advertised capabilities, never from its package or app identity.
pub(crate) fn role_for(node: &BridgeNode) -> &'static str {
    match node.class_name.as_str() {
        "android.widget.Button" | "android.widget.ImageButton" => "button",
        "android.widget.EditText" => "textbox",
        "android.widget.CheckBox" => "checkbox",
        "android.widget.Switch" => "switch",
        "android.widget.RadioButton" => "radio",
        "android.widget.SeekBar" => "slider",
        "android.widget.ProgressBar" => "progressbar",
        "android.widget.Spinner" => "combobox",
        "android.widget.ListView" | "androidx.recyclerview.widget.RecyclerView" => "list",
        "android.widget.TextView" => "text",
        _ if node.state.editable => "textbox",
        _ if node.state.checkable => "checkbox",
        _ if node.state.clickable => "button",
        _ => "group",
    }
}

/// The accessible name of a node: its text, falling back to its content
/// description, per the upstream accname ordering.
pub(crate) fn name_for(node: &BridgeNode) -> Option<&str> {
    if !node.text.is_empty() {
        return Some(node.text.as_str());
    }
    if !node.content_desc.is_empty() {
        return Some(node.content_desc.as_str());
    }
    None
}

/// Counts every node in a serialised tree, root included.
pub(crate) fn count_nodes(node: &BridgeNode) -> usize {
    1 + node.children.iter().map(count_nodes).sum::<usize>()
}
