package com.evtv.app

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val USER_AGENT = "EvTV/1.0 (Linux; Android TV)"

data class Category(val id: String, val name: String)

data class Channel(
    val streamId: Int,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String?,
)

data class EpgItem(val title: String, val start: Long, val end: Long)

class LoginResult(val ok: Boolean, val message: String?, val preferredExt: String)

/** Kullanıcının yapıştırdığı M3U linkinden (get.php?username=..&password=..) bilgileri ayıklar. */
data class ParsedLink(val server: String, val user: String?, val pass: String?)

fun parseServerInput(raw: String): ParsedLink {
    val s = raw.trim()
    return try {
        val u = Uri.parse(if (s.startsWith("http")) s else "http://$s")
        val user = u.getQueryParameter("username")
        val pass = u.getQueryParameter("password")
        if (user != null) {
            val port = if (u.port > 0) ":${u.port}" else ""
            ParsedLink("${u.scheme}://${u.host}$port", user, pass)
        } else ParsedLink(s, null, null)
    } catch (e: Exception) {
        ParsedLink(s, null, null)
    }
}

/** Xtream Codes sunucusuyla konuşan istemci. */
class XtreamApi(serverRaw: String, private val user: String, private val pass: String) {

    val base: String = normalize(serverRaw)

    companion object {
        fun normalize(s: String): String {
            var x = s.trim().trimEnd('/')
            x = x.removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
            if (!x.startsWith("http://", true) && !x.startsWith("https://", true)) x = "http://$x"
            return x
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    internal fun open(query: String): HttpURLConnection {
        val url = URL("$base/player_api.php?username=${enc(user)}&password=${enc(pass)}$query")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 90_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", USER_AGENT)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException(
                when (code) {
                    401, 403 -> "Erişim reddedildi (HTTP $code). Bilgileri veya hesap durumunu kontrol et."
                    404 -> "Sunucu adresi yanlış görünüyor (HTTP 404)."
                    else -> "Sunucu hata verdi (HTTP $code)"
                }
            )
        }
        return c
    }

    internal suspend fun fetchText(query: String): String = withContext(Dispatchers.IO) {
        val c = open(query)
        try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Büyük yanıtları belleğe tamamen almadan, parça parça okur. */
    internal suspend fun <T> streamArray(query: String, parse: (Map<String, String?>, Int) -> T?): List<T> {
        val out = ArrayList<T>()
        streamEach(query) { m, i -> parse(m, i)?.let(out::add) }
        return out
    }

    /** Her öğe için onItem çağrılır; hiçbir şey biriktirilmez (200 bin+ öğe için). */
    internal suspend fun streamEach(query: String, onItem: (Map<String, String?>, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val c = open(query)
            try {
                JsonReader(InputStreamReader(c.inputStream.buffered(64 * 1024), Charsets.UTF_8)).use { r ->
                    r.isLenient = true
                    if (r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray()
                        var i = 0
                        while (r.hasNext()) {
                            if (r.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlatObject(r), i) else r.skipValue()
                            i++
                        }
                        r.endArray()
                    } else {
                        // Bazı sunucular boş listeyi {} ya da null olarak döner
                        r.skipValue()
                    }
                }
            } finally {
                c.disconnect()
            }
        }

    internal fun readFlatObject(r: JsonReader): Map<String, String?> {
        val m = HashMap<String, String?>(16)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            m[k] = when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
                JsonToken.BOOLEAN -> r.nextBoolean().toString()
                JsonToken.NULL -> { r.nextNull(); null }
                else -> { r.skipValue(); null }
            }
        }
        r.endObject()
        return m
    }

    suspend fun login(): LoginResult {
        val text = fetchText("")
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return LoginResult(false, "Sunucu beklenmeyen bir yanıt verdi. Adres doğru mu?", "ts")
        }
        val info = o.optJSONObject("user_info")
            ?: return LoginResult(false, "Giriş başarısız: kullanıcı adı veya şifre hatalı.", "ts")
        if (info.optString("auth") != "1") {
            return LoginResult(false, "Giriş başarısız: kullanıcı adı veya şifre hatalı.", "ts")
        }
        val status = info.optString("status")
        if (status.isNotBlank() && !status.equals("Active", true)) {
            val msg = when (status.lowercase()) {
                "expired" -> "Hesabının süresi dolmuş."
                "banned" -> "Hesap sağlayıcı tarafından engellenmiş."
                "disabled" -> "Hesap devre dışı."
                else -> "Hesap durumu: $status"
            }
            return LoginResult(false, msg, "ts")
        }
        val formats = info.optJSONArray("allowed_output_formats")
        val list = (0 until (formats?.length() ?: 0)).map { formats!!.optString(it) }
        val ext = if (list.isEmpty() || "ts" in list) "ts" else "m3u8"
        return LoginResult(true, null, ext)
    }

    suspend fun liveCategories(): List<Category> =
        streamArray("&action=get_live_categories") { m, _ ->
            val id = m["category_id"]?.takeIf { it.isNotBlank() } ?: return@streamArray null
            Category(id, m["category_name"]?.trim().orEmpty().ifBlank { "Kategori" })
        }.distinctBy { it.id }

    suspend fun liveStreams(): List<Channel> {
        val list = streamArray("&action=get_live_streams") { m, i ->
            val id = m["stream_id"]?.toDoubleOrNull()?.toInt() ?: return@streamArray null
            Channel(
                streamId = id,
                num = m["num"]?.toDoubleOrNull()?.toInt() ?: (i + 1),
                name = m["name"]?.trim().orEmpty().ifBlank { "Kanal $id" },
                logo = m["stream_icon"]?.trim()?.takeIf { it.startsWith("http", true) },
                categoryId = m["category_id"]?.takeIf { it.isNotBlank() },
            )
        }
        return withContext(Dispatchers.Default) { list.distinctBy { it.streamId }.sortedBy { it.num } }
    }

    /** Kanalın şu anki ve sıradaki programları. */
    suspend fun shortEpg(streamId: Int): List<EpgItem> {
        val o = try {
            JSONObject(fetchText("&action=get_short_epg&stream_id=$streamId&limit=12"))
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = e.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val end = e.optString("stop_timestamp").toLongOrNull() ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            EpgItem(decode(e.optString("title")), start * 1000, end * 1000)
        }.filter { it.end > now }.sortedBy { it.start }
    }

    internal fun decode(s: String): String {
        if (s.isBlank()) return "Program"
        return try {
            val d = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
            // Base64 değilse anlamsız karakterler çıkar; o zaman orijinali kullan
            if (d.isEmpty() || d.any { it == '�' || (it.isISOControl() && it != '\n') }) s else d
        } catch (e: Exception) {
            s
        }
    }

    fun streamUrl(streamId: Int, ext: String): String =
        "$base/live/${Uri.encode(user)}/${Uri.encode(pass)}/$streamId.$ext"

    fun movieUrl(id: Int, ext: String): String =
        "$base/movie/${Uri.encode(user)}/${Uri.encode(pass)}/$id.$ext"

    fun episodeUrl(id: String, ext: String): String =
        "$base/series/${Uri.encode(user)}/${Uri.encode(pass)}/$id.$ext"
}
