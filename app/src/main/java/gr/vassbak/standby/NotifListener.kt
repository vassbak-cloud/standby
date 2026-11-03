package gr.vassbak.standby

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Διαβάζει τις ενεργές ειδοποιήσεις για τη σελίδα «Ειδοποιήσεις». */
class NotifListener : NotificationListenerService() {

    data class Item(val app: String, val title: String, val text: String, val time: Long)
    data class Nav(val distance: String, val instruction: String, val eta: String, val arrow: Drawable?)

    companion object {
        @Volatile var instance: NotifListener? = null
        var onChange: (() -> Unit)? = null

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            return flat.contains(ctx.packageName)
        }

        private const val MAPS = "com.google.android.apps.maps"

        /** Η τρέχουσα οδηγία του Google Maps, αν τρέχει πλοήγηση. */
        fun nav(ctx: Context): Nav? {
            val svc = instance ?: return null
            val list = try { svc.activeNotifications ?: return null } catch (_: Exception) { return null }
            val sbn = list.filter { it.packageName == MAPS }
                .filter {
                    it.notification.category == Notification.CATEGORY_NAVIGATION ||
                        (it.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                }
                .maxByOrNull { it.postTime } ?: return null
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return null
            val arrow = try { sbn.notification.getLargeIcon()?.loadDrawable(ctx) } catch (_: Exception) { null }
            return Nav(title, text, sub, arrow)
        }

        fun current(ctx: Context): List<Item> {
            val svc = instance ?: return emptyList()
            val list = try { svc.activeNotifications ?: emptyArray() } catch (_: Exception) { emptyArray<StatusBarNotification>() }
            val pm = ctx.packageManager
            return list.asSequence()
                .filter { it.packageName != ctx.packageName && it.packageName != MAPS }
                .filter { (it.notification.flags and Notification.FLAG_ONGOING_EVENT) == 0 }
                .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
                .mapNotNull { sbn ->
                    val ex = sbn.notification.extras
                    val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                    if (title.isBlank() && text.isBlank()) return@mapNotNull null
                    val app = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                    } catch (_: Exception) { sbn.packageName }
                    Item(app, title, text, sbn.postTime)
                }
                .sortedByDescending { it.time }
                .take(12)
                .toList()
        }
    }

    override fun onListenerConnected() { instance = this; onChange?.invoke() }
    override fun onListenerDisconnected() { instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { onChange?.invoke() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { onChange?.invoke() }
}
