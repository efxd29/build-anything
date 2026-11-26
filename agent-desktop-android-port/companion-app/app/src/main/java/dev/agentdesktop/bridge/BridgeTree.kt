package dev.agentdesktop.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tree serialisation, capture-proof matching and node actions for the
 * bridge. The serialised shape is the contract the Rust adapter consumes:
 * class, package, view_id, text, content_desc, bounds, value, action
 * names, state flags and children, walked with a node cap and a depth cap
 * so a pathological window cannot exhaust the bridge.
 *
 * Node discipline: every AccessibilityNodeInfo obtained here is recycled
 * exactly once by the code that obtained it, inside the same request.
 * Nodes are never cached across requests.
 */
object BridgeTree {

    private const val MAX_NODES = 2000
    private const val MAX_DEPTH = 50
    private const val MAX_SCROLL_ATTEMPTS = 10

    /** The identity fields a Rust capture proof constrains. A null field
     * records no constraint, exactly as the Rust predicate treats it. */
    data class Proof(
        val role: String,
        val className: String,
        val viewId: String?,
        val name: String?,
        val description: String?
    )

    private class Walk {
        var count = 0
        var truncated = false
    }

    fun serialize(root: AccessibilityNodeInfo): JSONObject {
        val walk = Walk()
        val tree = nodeJson(root, 0, walk)
        return JSONObject()
            .put("root", tree)
            .put("truncated", walk.truncated)
    }

    private fun nodeJson(node: AccessibilityNodeInfo, depth: Int, walk: Walk): JSONObject {
        walk.count += 1
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val json = JSONObject()
            .put("class", node.className?.toString() ?: "")
            .put("package", node.packageName?.toString() ?: "")
            .put("view_id", node.viewIdResourceName ?: "")
            .put("text", node.text?.toString() ?: "")
            .put("content_desc", node.contentDescription?.toString() ?: "")
            .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
            .put("value", if (node.isEditable) node.text?.toString() ?: "" else "")
            .put("actions", actionNames(node))
            .put(
                "state", JSONObject()
                    .put("clickable", node.isClickable)
                    .put("enabled", node.isEnabled)
                    .put("focused", node.isFocused)
                    .put("checked", node.isChecked)
                    .put("checkable", node.isCheckable)
                    .put("selected", node.isSelected)
                    .put("scrollable", node.isScrollable)
                    .put("editable", node.isEditable)
                    .put("password", node.isPassword)
                    .put("visible", node.isVisibleToUser)
                    .put("focusable", node.isFocusable)
                    .put("long_clickable", node.isLongClickable)
            )
        val children = JSONArray()
        val cut = depth >= MAX_DEPTH || walk.count >= MAX_NODES
        if (cut) {
            if (node.childCount > 0) {
                walk.truncated = true
                json.put("subtree_truncated", true)
            }
        } else {
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                children.put(nodeJson(child, depth + 1, walk))
                child.recycle()
            }
        }
        json.put("children", children)
        return json
    }

    private fun actionNames(node: AccessibilityNodeInfo): JSONArray {
        val names = JSONArray()
        for (action in node.actionList) {
            names.put(
                when (action.id) {
                    AccessibilityNodeInfo.ACTION_CLICK -> "click"
                    AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
                    AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
                    AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
                    AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
                    AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
                    AccessibilityNodeInfo.ACTION_SELECT -> "select"
                    else -> "action_${action.id}"
                }
            )
        }
        return names
    }

    /** The upstream role vocabulary for a node, mirroring the Rust
     * adapter's mapping table exactly. */
    fun roleFor(node: AccessibilityNodeInfo): String {
        return when (node.className?.toString()) {
            "android.widget.Button", "android.widget.ImageButton" -> "button"
            "android.widget.EditText" -> "textfield"
            "android.widget.CheckBox" -> "checkbox"
            "android.widget.Switch" -> "switch"
            "android.widget.RadioButton" -> "radiobutton"
            "android.widget.SeekBar" -> "slider"
            "android.widget.ProgressBar" -> "progressbar"
            "android.widget.Spinner" -> "combobox"
            "android.widget.ListView", "androidx.recyclerview.widget.RecyclerView" -> "list"
            "android.widget.TextView" -> "statictext"
            "android.widget.ImageView" -> "image"
            else -> when {
                node.isEditable -> "textfield"
                node.isCheckable -> "checkbox"
                node.isClickable -> "button"
                node.isScrollable -> "scrollarea"
                else -> "group"
            }
        }
    }

    private fun nameFor(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty()) return text
        val description = node.contentDescription?.toString() ?: ""
        return description.ifEmpty { null }
    }

    private fun descriptionFor(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: ""
        val description = node.contentDescription?.toString() ?: ""
        return if (text.isNotEmpty() && description.isNotEmpty() && description != text) {
            description
        } else {
            null
        }
    }

    private fun matchesProof(node: AccessibilityNodeInfo, proof: Proof): Boolean {
        if (proof.className.isNotEmpty() && (node.className?.toString() ?: "") != proof.className) {
            return false
        }
        if (proof.role.isNotEmpty() && roleFor(node) != proof.role) {
            return false
        }
        if (proof.viewId != null && (node.viewIdResourceName ?: "") != proof.viewId) {
            return false
        }
        if (proof.name != null && nameFor(node) != proof.name) {
            return false
        }
        if (proof.description != null && descriptionFor(node) != proof.description) {
            return false
        }
        return true
    }

    /**
     * Collects every live node matching the proof. Matched nodes are
     * handed to the caller, which recycles them; every other node obtained
     * during the walk is recycled here. The root is the caller's to
     * recycle and is never recycled by this walk.
     */
    fun collectMatches(
        node: AccessibilityNodeInfo,
        proof: Proof,
        out: MutableList<AccessibilityNodeInfo>,
        isRoot: Boolean
    ) {
        val matched = matchesProof(node, proof)
        if (matched) {
            out.add(node)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectMatches(child, proof, out, false)
        }
        if (!matched && !isRoot) {
            node.recycle()
        }
    }

    /** Performs one bridge action on a matched node. */
    fun perform(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        action: String,
        arguments: JSONObject
    ): Boolean {
        return when (action) {
            "click" -> clickNode(service, node)
            "double_click" -> repeatClick(service, node, 2)
            "triple_click" -> repeatClick(service, node, 3)
            "long_click" -> longClickNode(service, node)
            "set_text" -> setText(node, arguments.optString("text", ""))
            "type_text" -> typeText(node, arguments.optString("text", ""))
            "set_progress" -> setProgress(node, arguments.optString("value", ""))
            "focus" -> node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            "expand" -> performOrClick(service, node, AccessibilityNodeInfo.ACTION_EXPAND)
            "collapse" -> performOrClick(service, node, AccessibilityNodeInfo.ACTION_COLLAPSE)
            "scroll_forward" -> scrollNode(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, arguments)
            "scroll_backward" -> scrollNode(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, arguments)
            else -> false
        }
    }

    private fun clickNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        return gestureTap(service, node, 50)
    }

    private fun repeatClick(service: AccessibilityService, node: AccessibilityNodeInfo, times: Int): Boolean {
        var performed = false
        repeat(times) {
            if (clickNode(service, node)) {
                performed = true
            }
        }
        return performed
    }

    private fun longClickNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return true
        }
        return gestureTap(service, node, 600)
    }

    private fun gestureTap(service: AccessibilityService, node: AccessibilityNodeInfo, durationMs: Long): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            return false
        }
        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val current = node.text?.toString() ?: ""
        return setText(node, current + text)
    }

    private fun setProgress(node: AccessibilityNodeInfo, value: String): Boolean {
        val progress = value.toFloatOrNull() ?: return false
        val arguments = Bundle()
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, progress)
        return node.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            arguments
        )
    }

    private fun performOrClick(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        action: Int
    ): Boolean {
        val advertised = node.actionList.any { it.id == action }
        if (advertised && node.performAction(action)) {
            return true
        }
        return clickNode(service, node)
    }

    /**
     * The node itself when scrollable, else its nearest scrollable
     * ancestor, else null. Intermediate parents are recycled as the walk
     * moves up; the returned node is the caller's to recycle, and the
     * starting node is never recycled here.
     */
    private fun scrollableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo = node
        while (!current.isScrollable) {
            val parent = current.parent
            if (parent == null) {
                if (current !== node) {
                    current.recycle()
                }
                return null
            }
            if (current !== node) {
                current.recycle()
            }
            current = parent
        }
        return current
    }

    /**
     * Scrolls the nearest scrollable element at or above the matched node,
     * as many times as the caller asked for (bounded).
     */
    private fun scrollNode(node: AccessibilityNodeInfo, action: Int, arguments: JSONObject): Boolean {
        val amount = arguments.optInt("amount", 1).coerceIn(1, MAX_SCROLL_ATTEMPTS)
        val target = scrollableTarget(node) ?: return false
        var performed = false
        repeat(amount) {
            if (target.performAction(action)) {
                performed = true
            }
        }
        if (target !== node) {
            target.recycle()
        }
        return performed
    }

    /**
     * Scrolls a matched element into view: already visible is success,
     * otherwise the nearest scrollable ancestor scrolls forward and the
     * proof is re-matched against a fresh tree until the element reports
     * itself visible or the attempt budget runs out.
     */
    fun scrollIntoView(
        service: AgentAccessibilityService,
        node: AccessibilityNodeInfo,
        proof: Proof
    ): Boolean {
        if (node.isVisibleToUser) {
            return true
        }
        val scroller = scrollableTarget(node) ?: return false
        var visible = false
        var attempts = 0
        while (!visible && attempts < MAX_SCROLL_ATTEMPTS) {
            if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                break
            }
            attempts += 1
            val freshRoot = service.rootInActiveWindow ?: break
            val matches = mutableListOf<AccessibilityNodeInfo>()
            collectMatches(freshRoot, proof, matches, true)
            val candidate = matches.firstOrNull()
            if (matches.size == 1 && candidate != null) {
                visible = candidate.isVisibleToUser
            }
            for (match in matches) {
                if (match !== freshRoot) {
                    match.recycle()
                }
            }
            freshRoot.recycle()
        }
        if (scroller !== node) {
            scroller.recycle()
        }
        return visible
    }
}
