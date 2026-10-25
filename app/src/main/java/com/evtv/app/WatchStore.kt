package com.evtv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** "İzlemeye devam et" listesindeki bir film veya dizi. */
data class RecentItem(val item: VodItem, val time: Long)

/**
 * Kaldığın yer ve izleme geçmişi.
 * Anahtarlar: film = "m<id>", bölüm = "e<id>".
 */
class WatchStore(ctx: Context) {
    private val pos = ctx.getSharedPreferences("evtv_resume", Context.MODE_PRIVATE)
    private val recent = ctx.getSharedPreferences("evtv_recent", Context.MODE_PRIVATE)

    /** Kaydedilmiş konum (ms). İzlenip bitmişse veya yoksa 0. */
    fun position(key: String): Long {
        val v = pos.getString(key, null) ?: return 0L
        return v.substringBefore(',').toLongOrNull()?.takeIf { it > 0 } ?: 0L
    }

    /** 0..1 arası ilerleme; bitmişse 1, hiç başlanmamışsa 0. */
    fun progress(key: String): Float {
        val v = pos.getString(key, null) ?: return 0f
        if (v == "done") return 1f
        val p = v.substringBefore(',').toLongOrNull() ?: return 0f
        val d = v.substringAfter(',', "").toLongOrNull() ?: return 0f
        return if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    fun isDone(key: String) = pos.getString(key, null) == "done"

    fun save(key: String, positionMs: Long, durationMs: Long) {
        val e = pos.edit()
        when {
            durationMs > 0 && positionMs > durationMs * 0.93 -> e.putString(key, "done")
            positionMs < 30_000 -> e.remove(key) // ilk 30 saniye kayda değmez
            else -> e.putString(key, "$positionMs,$durationMs")
        }
        e.apply()
    }

    fun markDone(key: String) {
        pos.edit().putString(key, "done").apply()
    }

    // ---- İzlemeye devam et ----

    fun recents(series: Boolean): List<VodItem> = loadRecents().filter { it.item.isSeries == series }.map { it.item }

    fun touch(item: VodItem) {
        val list = loadRecents().filterNot { it.item.id == item.id && it.item.isSeries == item.isSeries }.toMutableList()
        list.add(0, RecentItem(item, System.currentTimeMillis()))
        saveRecents(list.take(60))
    }

    fun forget(item: VodItem) {
        saveRecents(loadRecents().filterNot { it.item.id == item.id && it.item.isSeries == item.isSeries })
    }

    // ---- Listem (film/dizi favorileri) ----

    fun myList(series: Boolean): List<VodItem> = loadList("mylist").filter { it.item.isSeries == series }.map { it.item }

    fun allMyList(): List<VodItem> = loadList("mylist").map { it.item }

    fun inMyList(item: VodItem) = loadList("mylist").any { it.item.id == item.id && it.item.isSeries == item.isSeries }

    /** Ekler ya da çıkarır; sonuç: listede mi */
    fun toggleMyList(item: VodItem): Boolean {
        val list = loadList("mylist").toMutableList()
        val i = list.indexOfFirst { it.item.id == item.id && it.item.isSeries == item.isSeries }
        val added = if (i >= 0) { list.removeAt(i); false } else { list.add(0, RecentItem(item, System.currentTimeMillis())); true }
        saveList("mylist", list.take(300))
        return added
    }

    /** Film ve diziler karışık, en son izlenen önce. */
    fun allRecents(): List<VodItem> = loadRecents().map { it.item }

    /** Dizide en son izlenen bölümün kimliği. */
    fun lastEpisode(seriesId: Int): String? = recent.getString("last_ep_$seriesId", null)

    fun setLastEpisode(seriesId: Int, episodeId: String) {
        recent.edit().putString("last_ep_$seriesId", episodeId).apply()
    }

    private fun loadRecents(): List<RecentItem> = loadList("list")

    private fun loadList(key: String): List<RecentItem> = try {
        val arr = JSONArray(recent.getString(key, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            RecentItem(
                VodItem(
                    id = o.getInt("id"),
                    name = o.optString("name"),
                    poster = o.optString("poster").ifBlank { null },
                    rating = o.optString("rating").ifBlank { null },
                    ext = o.optString("ext"),
                    isSeries = o.optBoolean("series"),
                ),
                o.optLong("t"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveRecents(list: List<RecentItem>) = saveList("list", list)

    private fun saveList(key: String, list: List<RecentItem>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("id", r.item.id)
                    .put("name", r.item.name)
                    .put("poster", r.item.poster ?: "")
                    .put("rating", r.item.rating ?: "")
                    .put("ext", r.item.ext)
                    .put("series", r.item.isSeries)
                    .put("t", r.time)
            )
        }
        recent.edit().putString(key, arr.toString()).apply()
    }

    fun clear() {
        pos.edit().clear().apply()
        recent.edit().clear().apply()
    }
}
