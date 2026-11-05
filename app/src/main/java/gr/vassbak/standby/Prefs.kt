package gr.vassbak.standby

import android.content.Context

object Prefs {
    private const val FILE = "standby"
    const val KEY_ROTATION = "rotation"      // 0 = ακολουθεί το κινητό, 90 ή 270, -1 = κάθετη
    const val PORTRAIT = -1
    const val KEY_NIGHT = "night_mode"       // αυτόματη νυχτερινή λειτουργία
    const val KEY_LAT = "lat"
    const val KEY_LON = "lon"

    const val COROS_EMAIL = "coros_email"
    const val COROS_PWD_MD5 = "coros_pwd_md5" // μόνο το hash, ποτέ ο κωδικός
    const val COROS_REGION = "coros_region"   // "eu" ή "us"
    const val COROS_TOKEN = "coros_token"
    const val COROS_UID = "coros_uid"
    const val COROS_HOST = "coros_host"

    const val KEY_AUTO = "auto_mode"
    const val AUTO_OFF = 0
    const val AUTO_CHARGING = 1
    const val AUTO_NAV = 2
    const val AUTO_ALWAYS = 3

    fun get(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rotation(ctx: Context) = get(ctx).getInt(KEY_ROTATION, 90)
    fun portrait(ctx: Context) = rotation(ctx) == PORTRAIT
    fun followsPhone(ctx: Context) = rotation(ctx) == 0

    /** Κάθετη διάταξη; Στο «αυτόματα» κρίνεται από το πώς κρατάς το κινητό αυτή τη στιγμή. */
    fun portraitNow(ctx: Context) = portrait(ctx) || (followsPhone(ctx) &&
        ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
    const val KEY_WAKE_CHARGING = "wake_charging"
    fun wakeOnCharging(ctx: Context) = get(ctx).getBoolean(KEY_WAKE_CHARGING, false)
    const val KEY_TILE_ORDER = "tile_order"  // σειρά πλακιδίων στην αρχική, π.χ. "WEATHER,BATTERY,…"
    const val KEY_AOD = "always_on"
    fun alwaysOn(ctx: Context) = get(ctx).getBoolean(KEY_AOD, false)
    const val KEY_POCKET = "pocket"
    fun pocket(ctx: Context) = get(ctx).getBoolean(KEY_POCKET, true)
    fun autoMode(ctx: Context) = get(ctx).getInt(KEY_AUTO, AUTO_OFF)
    fun nightMode(ctx: Context) = get(ctx).getBoolean(KEY_NIGHT, true)
}
