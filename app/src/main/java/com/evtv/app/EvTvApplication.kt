package com.evtv.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama çökerse veya ekran 3 saniyeden uzun donarsa nedenini dosyaya yazar.
 * Bir sonraki açılışta ekranda gösterilir; fotoğrafını çekip gönderirsen sorunu bulabiliriz.
 */
class EvTvApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        startFreezeWatchdog()
        warmUpPlayer()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                writeReport(this, "crash", "ÇÖKME (${t.name})\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Ana iş parçacığı 3 sn'den uzun cevap vermezse o anda ne yaptığını kaydeder. */
    private fun startFreezeWatchdog() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var lastReport = 0L
            while (true) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                val sent = SystemClock.uptimeMillis()
                main.post { answered.set(true) }
                Thread.sleep(3_000)
                if (!answered.get()) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastReport > 60_000) {
                        lastReport = now
                        val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                        writeReport(this, "freeze", "DONMA (${now - sent} ms)\n$stack")
                    }
                    // Donma bitene kadar bekle
                    while (!answered.get()) Thread.sleep(200)
                }
            }
        }, "evtv-watchdog").apply { isDaemon = true }.start()
    }

    /** Ses çözücü kütüphanesini arka planda yükle; ilk kanal açılışı takılmasın. */
    private fun warmUpPlayer() {
        Thread({
            runCatching {
                Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
                    .getMethod("isAvailable").invoke(null)
            }
        }, "evtv-warmup").apply { isDaemon = true }.start()
    }

    companion object {
        fun reportFile(app: Application, kind: String) = File(app.filesDir, "report_$kind.txt")

        fun writeReport(app: Application, kind: String, body: String) {
            val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val header = "EvTV ${BuildConfig.VERSION_NAME} • ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} • $time"
            reportFile(app, kind).writeText(sanitize(app, "$header\n$body"))
        }

        /** Raporda şifre/kullanıcı adı görünmesin. */
        private fun sanitize(app: Application, text: String): String {
            val p = Prefs(app)
            var t = text
            listOf(p.pass, p.user, p.osPass, p.osKey).filter { it.length >= 3 }.forEach { t = t.replace(it, "***") }
            return t
        }

        /** Gösterilmemiş son rapor (varsa) ve onu "görüldü" işaretler. */
        fun takeUnseenReport(app: Application): String? {
            val prefs = app.getSharedPreferences("evtv_reports", MODE_PRIVATE)
            val seen = prefs.getLong("seen", 0)
            val f = listOf(reportFile(app, "crash"), reportFile(app, "freeze"))
                .filter { it.exists() && it.lastModified() > seen }
                .maxByOrNull { it.lastModified() } ?: return null
            prefs.edit().putLong("seen", f.lastModified()).apply()
            return f.readText()
        }

        fun allReports(app: Application): String? =
            listOf(reportFile(app, "crash"), reportFile(app, "freeze"))
                .filter { it.exists() }
                .sortedByDescending { it.lastModified() }
                .joinToString("\n\n──────────\n\n") { it.readText() }
                .ifBlank { null }

        /** Ekranda okunabilsin diye: hata satırı + uygulamaya ait satırlar öne. */
        fun shorten(report: String): String {
            val lines = report.lines()
            val head = lines.take(3)
            val ours = lines.filter { "com.evtv" in it }.take(12)
            val causes = lines.filter { it.startsWith("Caused by") }.take(3)
            val rest = lines.drop(3).take(10)
            return (head + causes + listOf("— uygulama satırları —") + ours + listOf("— ilk satırlar —") + rest)
                .distinct().joinToString("\n")
        }
    }
}
