package dev.agentdesktop.bridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Port skeleton stub — TODO: expose getActiveNotifications(), cancelNotification(key),
 * cancelAllNotifications() and action PendingIntents to BridgeServer.
 */
class AgentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // TODO: notify BridgeServer waiters.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // TODO: notify BridgeServer waiters.
    }
}
