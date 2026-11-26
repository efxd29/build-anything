package dev.agentdesktop.bridge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * The bridge's eyes and hands. This phase only publishes the live instance
 * and runs the bridge server; tree serialisation, actions and screenshots
 * land in later phases.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BridgeServer.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        BridgeServer.stop()
        return super.onUnbind(intent)
    }
}
