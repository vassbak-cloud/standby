package gr.vassbak.standby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings

/** Κοινή κατάσταση: αν φαίνεται τώρα το StandBy και πότε έκλεισε τελευταία φορά. */
object State {
    @Volatile var showing = false
    @Volatile var lastStop = 0L
    /** Έκλεισε μόνο του λόγω αδράνειας εκτός φόρτισης: να μην ξανανοίξει στο επόμενο σβήσιμο. */
    @Volatile var timedOut = false
}

/**
 * Μένει ενεργή στο παρασκήνιο και, όταν σβήνει η οθόνη, ανοίγει το StandBy
 * (ανάλογα με τη ρύθμιση: στη φόρτιση, στην πλοήγηση ή πάντα).
 */
class StandbyService : Service() {

    companion object {
        private const val CHANNEL = "standby_service"

        fun needed(ctx: Context) = Prefs.autoMode(ctx) != Prefs.AUTO_OFF || Prefs.wakeOnCharging(ctx)

        private fun charging(c: Context) = (c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging

        private fun launch(c: Context) {
            if (!Settings.canDrawOverlays(c)) return
            c.startActivity(Intent(c, PreviewActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ))
        }

        fun sync(ctx: Context) {
            val i = Intent(ctx, StandbyService::class.java)
            if (!needed(ctx)) ctx.stopService(i)
            else ctx.startForegroundService(i)
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Αν ο χρήστης έσβησε την οθόνη ενώ έβλεπε το StandBy, την αφήνουμε σβηστή
            if (State.showing || State.timedOut || System.currentTimeMillis() - State.lastStop < 3000) return
            if (!shouldShow(c)) return
            launch(c)
        }
    }

    /** Ανάβει η οθόνη (κουμπί ή διπλό ταπ του κινητού) ενώ φορτίζει → StandBy. */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            State.timedOut = false
            if (State.showing || !Prefs.wakeOnCharging(c) || !charging(c)) return
            launch(c)
        }
    }

    private fun shouldShow(c: Context): Boolean = when (Prefs.autoMode(c)) {
        Prefs.AUTO_CHARGING -> charging(c)
        Prefs.AUTO_NAV -> NotifListener.nav(c) != null
        Prefs.AUTO_ALWAYS -> true
        else -> false
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "StandBy σε αναμονή", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("StandBy σε αναμονή")
            .setContentText("Ανοίγει αυτόματα σύμφωνα με τις ρυθμίσεις")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!needed(this)) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        try { unregisterReceiver(screenOn) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Ξεκινά ξανά την υπηρεσία μετά από επανεκκίνηση του κινητού. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try { StandbyService.sync(c) } catch (_: Exception) {}
        }
    }
}
