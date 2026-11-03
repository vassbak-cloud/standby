package gr.vassbak.standby

import android.content.Context

object Prefs {
    private const val FILE = "standby"
    const val KEY_ROTATION = "rotation"      // 0 = ακολουθεί το κινητό, 90 ή 270
    const val KEY_NIGHT = "night_mode"       // αυτόματη νυχτερινή λειτουργία
    const val KEY_LAT = "lat"
    const val KEY_LON = "lon"

    const val COROS_EMAIL = "coros_email"
    const val COROS_PWD_MD5 = "coros_pwd_md5" // μόνο το hash, ποτέ ο κωδικός
    const val COROS_REGION = "coros_region"   // "eu" ή "us"
    const val COROS_TOKEN = "coros_token"
    const val COROS_UID = "coros_uid"
    const val COROS_HOST = "coros_host"

    fun get(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rotation(ctx: Context) = get(ctx).getInt(KEY_ROTATION, 90)
    fun nightMode(ctx: Context) = get(ctx).getBoolean(KEY_NIGHT, true)
}
