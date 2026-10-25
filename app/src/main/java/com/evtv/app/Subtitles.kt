package com.evtv.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** İnternetten altyazı aramak için film/bölüm bilgisi. */
data class SubMeta(
    val query: String,
    val year: String? = null,
    val tmdb: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class SubResult(
    val fileId: Long,
    val release: String,
    val lang: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val machine: Boolean,
)

/** Sağlayıcıların ad önekleri ("TR:", "|EN|", "4K" ...) ve yıl temizlenir. */
fun cleanTitle(raw: String): Pair<String, String?> {
    var t = raw
    val year = Regex("""[(\[]((?:19|20)\d{2})[)\]]""").find(t)?.groupValues?.get(1)
        ?: Regex("""\b((?:19|20)\d{2})\s*$""").find(t)?.groupValues?.get(1)
    t = t.replace(Regex("""^\s*[|\[(]?\s*[A-Za-z]{2,4}\s*[|\])]\s*[-:]?\s*"""), "") // |TR| , [EN]
    t = t.replace(Regex("""^\s*[A-Za-z]{2,3}\s*[:\-]\s+"""), "")                 // TR: , EN -
    t = t.replace(Regex("""[(\[]\s*(?:19|20)\d{2}\s*[)\]]"""), "")
    t = t.replace(Regex("""\b((?:19|20)\d{2})\s*$"""), "")
    t = t.replace(Regex("""\b(4K|UHD|FHD|HD|SD|HEVC|H265|x265|1080p|720p|2160p|MULTI|DUAL|AltyazılI|Altyazılı|Dublaj)\b""", RegexOption.IGNORE_CASE), "")
    t = t.replace(Regex("""[|\[\]]"""), " ").replace(Regex("""\s{2,}"""), " ").trim(' ', '-', ':', '.')
    return (t.ifBlank { raw.trim() }) to year
}

/**
 * OpenSubtitles.com ile konuşan küçük istemci.
 * Ücretsiz hesap + API anahtarı gerekir (Ayarlar'dan girilir).
 */
class OpenSubtitles(private val ctx: Context, private val prefs: Prefs) {
    private val base = "https://api.opensubtitles.com/api/v1"
    private val agent = "EvTV v1.0"

    private fun conn(url: String, method: String, token: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Api-Key", prefs.osKey)
        c.setRequestProperty("User-Agent", agent)
        c.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun send(c: HttpURLConnection, body: String?): Pair<Int, String> {
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return code to text
    }

    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        return when (code) {
            401, 403 -> "OpenSubtitles girişi başarısız (API anahtarı, kullanıcı adı veya şifre hatalı)."
            406 -> "Günlük altyazı indirme hakkı doldu. ${msg}".trim()
            429 -> "Çok sık istek gönderildi, biraz sonra tekrar dene."
            else -> "OpenSubtitles hatası ($code) $msg".trim()
        }
    }

    suspend fun token(forceNew: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!forceNew && prefs.osToken.isNotBlank() && prefs.osTokenAge < 12 * 3600_000L) return@withContext prefs.osToken
        val body = JSONObject().put("username", prefs.osUser).put("password", prefs.osPass).toString()
        val (code, text) = send(conn("$base/login", "POST", null), body)
        if (code !in 200..299) throw IOException(errorText(code, text))
        val t = JSONObject(text).optString("token")
        if (t.isBlank()) throw IOException("OpenSubtitles girişi başarısız.")
        prefs.osToken = t
        t
    }

    suspend fun search(meta: SubMeta, langs: List<String>): List<SubResult> = withContext(Dispatchers.IO) {
        // Parametreler alfabetik ve küçük harf olmalı (API öyle istiyor)
        val p = sortedMapOf<String, String>()
        p["languages"] = langs.joinToString(",")
        if (meta.season != null && meta.episode != null) {
            p["type"] = "episode"
            p["season_number"] = meta.season.toString()
            p["episode_number"] = meta.episode.toString()
            if (!meta.tmdb.isNullOrBlank()) p["parent_tmdb_id"] = meta.tmdb else p["query"] = meta.query.lowercase()
        } else {
            p["type"] = "movie"
            if (!meta.tmdb.isNullOrBlank()) p["tmdb_id"] = meta.tmdb
            else {
                p["query"] = meta.query.lowercase()
                meta.year?.let { p["year"] = it }
            }
        }
        val q = p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}" }
        val (code, text) = send(conn("$base/subtitles?$q", "GET", null), null)
        if (code !in 200..299) throw IOException(errorText(code, text))
        val arr = JSONObject(text).optJSONArray("data") ?: return@withContext emptyList()
        val out = ArrayList<SubResult>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            out += SubResult(
                fileId = f.optLong("file_id"),
                release = a.optString("release").ifBlank { f.optString("file_name") }.ifBlank { "Altyazı" },
                lang = a.optString("language"),
                downloads = a.optInt("download_count"),
                hearingImpaired = a.optBoolean("hearing_impaired"),
                machine = a.optBoolean("machine_translated") || a.optBoolean("ai_translated"),
            )
        }
        // Önce tercih edilen dil, sonra insan çevirisi, sonra en çok indirilen
        out.sortedWith(
            compareBy<SubResult> { langs.indexOf(it.lang).let { i -> if (i < 0) 99 else i } }
                .thenBy { it.machine }
                .thenBy { it.hearingImpaired }
                .thenByDescending { it.downloads }
        )
    }

    /** Altyazıyı indirip UTF-8 SRT olarak kaydeder. */
    suspend fun download(r: SubResult, dest: File): File = withContext(Dispatchers.IO) {
        val body = JSONObject().put("file_id", r.fileId).put("sub_format", "srt").toString()
        var (code, text) = send(conn("$base/download", "POST", token()), body)
        if (code == 401) {
            val again = send(conn("$base/download", "POST", token(forceNew = true)), body)
            code = again.first
            text = again.second
        }
        if (code !in 200..299) throw IOException(errorText(code, text))
        val link = JSONObject(text).optString("link")
        if (link.isBlank()) throw IOException("İndirme bağlantısı alınamadı.")
        val c = URL(link).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", agent)
        val bytes = c.inputStream.use { it.readBytes() }
        c.disconnect()
        dest.parentFile?.mkdirs()
        dest.writeText(decodeSubtitle(bytes), Charsets.UTF_8)
        dest
    }

    fun cacheFile(key: String, lang: String) = File(ctx.cacheDir, "subs/${key}_$lang.srt")
}

/** UTF-8 değilse Türkçe Windows kodlamasıyla (ş, ğ, ı bozulmasın) okur. */
private fun decodeSubtitle(bytes: ByteArray): String {
    val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        utf8.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        String(bytes, charset("windows-1254"))
    }
    return text.removePrefix("﻿")
}
