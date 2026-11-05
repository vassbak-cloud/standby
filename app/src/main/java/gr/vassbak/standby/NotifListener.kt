package gr.vassbak.standby

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Διαβάζει τις ενεργές ειδοποιήσεις για τη σελίδα «Ειδοποιήσεις». */
class NotifListener : NotificationListenerService() {

    data class Item(
        val app: String, val title: String, val text: String, val time: Long, val key: String,
        val full: String,            // όλο το περιεχόμενο (μεγάλο κείμενο, μηνύματα συνομιλίας κ.λπ.)
        val open: PendingIntent?,    // ανοίγει την ειδοποίηση στην εφαρμογή της
        val kind: Kind = Kind.OTHER  // κατηγορία, για το χρώμα της κάρτας
    )
    data class Nav(
        val distance: String, val instruction: String, val eta: String, val arrow: Drawable?,
        val exit: PendingIntent?   // το κουμπί «Έξοδος» της ειδοποίησης του Maps
    )

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
            val actions = sbn.notification.actions ?: emptyArray()
            val words = listOf("exit", "έξοδ", "τερματ", "stop", "close", "κλείσ", "διακοπ", "end")
            val exit = actions.firstOrNull { a ->
                val t = a.title?.toString()?.lowercase() ?: ""
                words.any { t.contains(it) }
            } ?: actions.singleOrNull()
            return Nav(title, text, sub, arrow, exit?.actionIntent)
        }

        /** Διαγράφει μία ειδοποίηση (όπως το swipe στη γραμμή ειδοποιήσεων). */
        fun dismiss(key: String) {
            try { instance?.cancelNotification(key) } catch (_: Exception) {}
        }

        /** Όλο το κείμενο: συνομιλία, γραμμές inbox ή μεγάλο κείμενο, ό,τι υπάρχει. */
        private fun fullText(ex: android.os.Bundle, fallback: String): String {
            try {
                @Suppress("DEPRECATION")
                val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (msgs != null && msgs.isNotEmpty()) {
                    val lines = msgs.mapNotNull { m ->
                        val b = m as? android.os.Bundle ?: return@mapNotNull null
                        val t = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                        val who = b.getCharSequence("sender")?.toString()
                            ?: (b.get("sender_person") as? android.app.Person)?.name?.toString()
                        if (who.isNullOrBlank()) t else "$who: $t"
                    }
                    if (lines.isNotEmpty()) return lines.joinToString("\n\n")
                }
            } catch (_: Exception) {}
            val inbox = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (inbox != null && inbox.isNotEmpty()) return inbox.joinToString("\n") { it.toString() }
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (!big.isNullOrBlank()) return big
            return fallback
        }

        /** Κατηγορία ειδοποίησης: από το category της, αλλιώς από το όνομα της εφαρμογής. */
        private fun kindOf(n: Notification, pkg: String): Kind {
            when (n.category) {
                Notification.CATEGORY_MESSAGE -> return Kind.MESSAGE
                Notification.CATEGORY_EMAIL -> return Kind.EMAIL
                Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL -> return Kind.CALL
                Notification.CATEGORY_EVENT, Notification.CATEGORY_REMINDER, Notification.CATEGORY_ALARM -> return Kind.CALENDAR
                Notification.CATEGORY_SOCIAL -> return Kind.SOCIAL
                Notification.CATEGORY_PROMO, Notification.CATEGORY_RECOMMENDATION -> return Kind.PROMO
                Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_ERROR,
                Notification.CATEGORY_PROGRESS -> return Kind.SYSTEM
            }
            val p = pkg.lowercase()
            return when {
                listOf("whatsapp", "viber", "telegram", "messag", "mms", "sms", "signal", "orca").any { it in p } -> Kind.MESSAGE
                p == "com.google.android.gm" || listOf("mail", "outlook").any { it in p } -> Kind.EMAIL
                listOf("dialer", "phone", "telecom").any { it in p } -> Kind.CALL
                listOf("calendar", "clock", "alarm").any { it in p } -> Kind.CALENDAR
                listOf("instagram", "facebook", "katana", "twitter", "tiktok", "snapchat", "linkedin", "strava").any { it in p } -> Kind.SOCIAL
                listOf("android", "settings", "systemui").any { p.startsWith("com.$it") || p.startsWith(it) } -> Kind.SYSTEM
                else -> Kind.OTHER
            }
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
                    Item(app, title, text, sbn.postTime, sbn.key, fullText(ex, text), sbn.notification.contentIntent,
                        kindOf(sbn.notification, sbn.packageName))
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

/** Κατηγορίες ειδοποιήσεων, η καθεμία με δικό της χρώμα. */
enum class Kind { MESSAGE, EMAIL, CALL, CALENDAR, SOCIAL, PROMO, SYSTEM, OTHER }
