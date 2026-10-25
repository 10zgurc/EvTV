package com.evtv.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val url: String, val size: Long)

/** GitHub'daki en yeni sürümü kontrol eder, indirir ve kurulum ekranını açar. */
object Updater {
    private const val API = "https://api.github.com/repos/10zgurc/EvTV/releases/tags/latest"

    /** "1.10" > "1.9" gibi sürüm karşılaştırma */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Yeni sürüm varsa bilgisini, yoksa null döner. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "OzzyTV")
        val text = try {
            if (c.responseCode != 200) throw IOException("GitHub yanıtı ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val assets = JSONObject(text).optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val m = Regex("""^OzzyTV-([\d.]+)\.apk$""").find(a.optString("name")) ?: continue
            val v = m.groupValues[1]
            return@withContext if (isNewer(v, BuildConfig.VERSION_NAME))
                UpdateInfo(v, a.optString("browser_download_url"), a.optLong("size")) else null
        }
        null
    }

    /** APK'yı indirir; onProgress 0..1 */
    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "OzzyTV-${info.version}.apk")
        var url = URL(info.url)
        var c: HttpURLConnection
        // GitHub başka bir adrese yönlendirir; elle takip et
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "OzzyTV")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                hops++
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("İndirme hatası (HTTP $code)")
            }
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
        try {
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        out
    }

    /** Android 8+ için "bilinmeyen uygulamaları yükleme" izni var mı */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** İzin ekranını açar (kullanıcı OzzyTV'ye izin verip geri döner). */
    fun openInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            // Bazı TV'lerde bu ekran yok: genel güvenlik ayarlarını aç
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Android'in kurulum ekranını açar. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
