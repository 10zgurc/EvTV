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

    var lastSection: String
        get() = p.getString("section", "Live") ?: "Live"
        set(v) = p.edit().putString("section", v).apply()

    var lastMovieCat: String
        get() = p.getString("movieCat", "") ?: ""
        set(v) = p.edit().putString("movieCat", v).apply()

    var lastSeriesCat: String
        get() = p.getString("seriesCat", "") ?: ""
        set(v) = p.edit().putString("seriesCat", v).apply()

    /** Tercih edilen altyazı dili: "tr", "en" veya "off" */
    var subtitleLang: String
        get() = p.getString("subLang", "tr") ?: "tr"
        set(v) = p.edit().putString("subLang", v).apply()

    /** Birinci dilde altyazı yoksa denenecek yedek dil ("off" = yok) */
    var subtitleLang2: String
        get() = p.getString("subLang2", "en") ?: "en"
        set(v) = p.edit().putString("subLang2", v).apply()

    /** Sırayla denenecek altyazı dilleri (en az bir dil; kapalıysa boş) */
    val subtitleLangs: List<String>
        get() = if (subtitleLang == "off") emptyList()
        else listOf(subtitleLang, subtitleLang2).filter { it != "off" }.distinct()

    /** Altyazı boyutu çarpanı: 0 = küçük, 1 = normal, 2 = büyük */
    var subtitleSize: Int
        get() = p.getInt("subSize", 1)
        set(v) = p.edit().putInt("subSize", v).apply()

    // ---- İnternetten altyazı (OpenSubtitles.com) ----
    var osKey: String
        get() = p.getString("osKey", "") ?: ""
        set(v) = p.edit().putString("osKey", v.trim()).remove("osToken").apply()

    var osUser: String
        get() = p.getString("osUser", "") ?: ""
        set(v) = p.edit().putString("osUser", v.trim()).remove("osToken").apply()

    var osPass: String
        get() = p.getString("osPass", "") ?: ""
        set(v) = p.edit().putString("osPass", v).remove("osToken").apply()

    /** Videoda istenen dilde altyazı yoksa otomatik indir */
    var osAuto: Boolean
        get() = p.getBoolean("osAuto", true)
        set(v) = p.edit().putBoolean("osAuto", v).apply()

    var osToken: String
        get() = p.getString("osToken", "") ?: ""
        set(v) = p.edit().putString("osToken", v).putLong("osTokenTime", System.currentTimeMillis()).apply()

    val osTokenAge: Long get() = System.currentTimeMillis() - p.getLong("osTokenTime", 0)

    val osConfigured: Boolean get() = osKey.isNotBlank() && osUser.isNotBlank() && osPass.isNotBlank()

    /** Tampon (donma önleme): 0 = normal, 1 = yüksek, 2 = çok yüksek */
    var bufferLevel: Int
        get() = p.getInt("buffer", 1)
        set(v) = p.edit().putInt("buffer", v).apply()

    val hasAccount: Boolean get() = server.isNotBlank() && user.isNotBlank()

    /** Önbelleğin hangi hesaba ait olduğunu anlamak için. */
    val accountKey: String get() = "$server|$user"

    fun clearAccount() {
        p.edit().remove("server").remove("user").remove("pass").remove("ext").apply()
    }
}
