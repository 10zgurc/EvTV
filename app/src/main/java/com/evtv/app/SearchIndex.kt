package com.evtv.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.Normalizer
import kotlin.coroutines.coroutineContext

/** Türkçe karakter, büyük/küçük harf ve işaretlerden bağımsız karşılaştırma için. */
fun normalizeForSearch(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
        when (ch) {
            'ı', 'I', 'İ', 'i' -> sb.append('i')
            'ş', 'Ş' -> sb.append('s')
            'ğ', 'Ğ' -> sb.append('g')
            'ü', 'Ü' -> sb.append('u')
            'ö', 'Ö' -> sb.append('o')
            'ç', 'Ç' -> sb.append('c')
            else -> sb.append(ch.lowercaseChar())
        }
    }
    val noMarks = MARKS.replace(Normalizer.normalize(sb, Normalizer.Form.NFD), "")
    return NON_ALNUM.replace(noMarks, " ").trim()
}

private val MARKS = Regex("\\p{Mn}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

/**
 * Bir hesabın tüm filmlerini (veya dizilerini) cihaza kaydeder ve içinde arama yapar.
 * Liste dosyada durur; bellekte sadece sadeleştirilmiş adlar ve dosya konumları tutulur
 * (200 bin film ≈ 20 MB bellek).
 */
class SearchIndex(ctx: Context, private val type: String) {
    private val file = File(ctx.filesDir, "search_$type.tsv")
    private val meta = ctx.getSharedPreferences("evtv_search", Context.MODE_PRIVATE)

    private var norms: Array<String>? = null
    private var offsets: LongArray? = null

    val exists get() = file.exists() && file.length() > 0
    val ageMs get() = System.currentTimeMillis() - meta.getLong("time_$type", 0)
    val size get() = norms?.size ?: meta.getInt("count_$type", 0)

    /** Sunucudan tüm listeyi indirip dosyaya yazar. onProgress: şimdiye kadar okunan öğe sayısı. */
    suspend fun build(api: XtreamApi, onProgress: (Int) -> Unit) {
        val tmp = File(file.path + ".tmp")
        var count = 0
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                val series = type == "s"
                api.streamEach(if (series) "&action=get_series" else "&action=get_vod_streams") { m, _ ->
                    val id = (if (series) m["series_id"] else m["stream_id"])?.toDoubleOrNull()?.toInt() ?: return@streamEach
                    val name = clean(m["name"]).ifBlank { return@streamEach }
                    val poster = clean((if (series) m["cover"] else m["stream_icon"]).httpOrNull())
                    val ext = if (series) "" else clean(m["container_extension"]).ifBlank { "mp4" }
                    val rating = ratingText(m["rating"]).orEmpty()
                    w.write("$id\t$ext\t$rating\t$poster\t${normalizeForSearch(name)}\t$name\n")
                    count++
                    if (count % 2000 == 0) onProgress(count)
                }
            }
            if (count == 0) throw IllegalStateException("Sunucu boş liste döndü")
            tmp.renameTo(file)
            meta.edit().putLong("time_$type", System.currentTimeMillis()).putInt("count_$type", count).apply()
            norms = null
            offsets = null
        } finally {
            tmp.delete()
        }
    }

    private fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /** Dosyadaki adları belleğe alır (ilk aramada bir kez). */
    private suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (norms != null) return@withContext
        val ns = ArrayList<String>(meta.getInt("count_$type", 1000) + 16)
        val offs = ArrayList<Long>(ns.size)
        BufferedInputStream(file.inputStream(), 256 * 1024).use { input ->
            val line = java.io.ByteArrayOutputStream(256)
            var pos = 0L
            var start = 0L
            while (true) {
                val b = input.read()
                if (b < 0) break
                pos++
                if (b == '\n'.code) {
                    // 5. sütun: arama için sadeleştirilmiş ad
                    val cols = line.toString("UTF-8").split('\t', limit = 6)
                    ns += cols.getOrElse(4) { "" }
                    offs += start
                    line.reset()
                    start = pos
                } else {
                    line.write(b)
                }
            }
        }
        norms = ns.toTypedArray()
        offsets = offs.toLongArray()
    }

    suspend fun search(query: String, limit: Int = 180): List<VodItem> = withContext(Dispatchers.Default) {
        if (!exists) return@withContext emptyList()
        ensureLoaded()
        val ns = norms ?: return@withContext emptyList()
        val offs = offsets ?: return@withContext emptyList()
        val q = normalizeForSearch(query)
        val words = q.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return@withContext emptyList()

        // Puan: tam başlangıç 0, kelime başı 1, içinde geçiyor 2
        val hits = ArrayList<Long>()
        for (i in ns.indices) {
            if (i % 20000 == 0) coroutineContext.ensureActive()
            val n = ns[i]
            if (words.all { n.contains(it) }) {
                val score = when {
                    n.startsWith(q) -> 0
                    n.startsWith(words[0]) || n.contains(" " + words[0]) -> 1
                    else -> 2
                }
                hits += (score.toLong() shl 32) or i.toLong()
            }
        }
        hits.sort()
        val pick = hits.take(limit).map { (it and 0xFFFFFFFFL).toInt() }
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { raf -> pick.mapNotNull { readItem(raf, offs[it]) } }
        }
    }

    private fun readItem(raf: RandomAccessFile, offset: Long): VodItem? {
        raf.seek(offset)
        val buf = java.io.ByteArrayOutputStream(256)
        while (true) {
            val b = raf.read()
            if (b < 0 || b == '\n'.code) break
            buf.write(b)
        }
        val p = buf.toString("UTF-8").split('\t')
        if (p.size < 6) return null
        return VodItem(
            id = p[0].toIntOrNull() ?: return null,
            name = p.subList(5, p.size).joinToString(" "),
            poster = p[3].ifBlank { null },
            rating = p[2].ifBlank { null },
            ext = p[1],
            isSeries = type == "s",
        )
    }

    fun clear() {
        file.delete()
        meta.edit().remove("time_$type").remove("count_$type").apply()
        norms = null
        offsets = null
    }
}
