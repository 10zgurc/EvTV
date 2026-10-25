package com.evtv.app

import android.content.Context

/** Hesap bilgilerini, favorileri ve ayarları cihazda saklar. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("evtv", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v).apply()

    var user: String
        get() = p.getString("user", "") ?: ""
        set(v) = p.edit().putString("user", v).apply()

    var pass: String
        get() = p.getString("pass", "") ?: ""
        set(v) = p.edit().putString("pass", v).apply()

    /** Sunucunun izin verdiği yayın biçimi: "ts" veya "m3u8" */
    var streamExt: String
        get() = p.getString("ext", "ts") ?: "ts"
        set(v) = p.edit().putString("ext", v).apply()

    var lastCategory: String
        get() = p.getString("lastCategory", "") ?: ""
        set(v) = p.edit().putString("lastCategory", v).apply()

    var favorites: Set<Int>
        get() = (p.getStringSet("fav", emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = p.edit().putStringSet("fav", v.map { it.toString() }.toSet()).apply()

    val hasAccount: Boolean get() = server.isNotBlank() && user.isNotBlank()

    /** Önbelleğin hangi hesaba ait olduğunu anlamak için. */
    val accountKey: String get() = "$server|$user"

    fun clearAccount() {
        p.edit().remove("server").remove("user").remove("pass").remove("ext").apply()
    }
}
