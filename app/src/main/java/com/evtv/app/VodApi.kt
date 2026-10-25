package com.evtv.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Film veya dizi (afiş ızgarasında gösterilen kart). */
data class VodItem(
    val id: Int,
    val name: String,
    val poster: String?,
    val rating: String?,
    /** Filmlerde dosya uzantısı (mkv, mp4...). Dizilerde boş. */
    val ext: String,
    val isSeries: Boolean,
)

data class MovieInfo(
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val year: String?,
    val duration: String?,
    val rating: String?,
    val backdrop: String?,
    val poster: String?,
    val ext: String?,
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val plot: String?,
    val duration: String?,
    val image: String?,
)

data class SeriesInfo(
    val name: String?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val year: String?,
    val rating: String?,
    val backdrop: String?,
    val poster: String?,
    val seasons: Map<Int, List<Episode>>,
)

private fun JSONObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = opt(k)
        val s = when (v) {
            null, JSONObject.NULL -> null
            is JSONArray -> if (v.length() > 0) v.optString(0) else null
            else -> v.toString()
        }?.trim()
        if (!s.isNullOrBlank() && s != "null") return s
    }
    return null
}

internal fun String?.httpOrNull(): String? = this?.trim()?.takeIf { it.startsWith("http", true) }

internal fun ratingText(r: String?): String? {
    val d = r?.replace(',', '.')?.toDoubleOrNull() ?: return null
    if (d <= 0) return null
    return String.format(java.util.Locale.US, "%.1f", if (d > 10) d / 10 else d)
}

suspend fun XtreamApi.vodCategories(): List<Category> =
    streamArray("&action=get_vod_categories") { m, _ ->
        val id = m["category_id"]?.takeIf { it.isNotBlank() } ?: return@streamArray null
        Category(id, m["category_name"]?.trim().orEmpty().ifBlank { "Kategori" })
    }.distinctBy { it.id }

suspend fun XtreamApi.seriesCategories(): List<Category> =
    streamArray("&action=get_series_categories") { m, _ ->
        val id = m["category_id"]?.takeIf { it.isNotBlank() } ?: return@streamArray null
        Category(id, m["category_name"]?.trim().orEmpty().ifBlank { "Kategori" })
    }.distinctBy { it.id }

/** Sadece bir kategorinin filmleri (200 bin filmi bir anda indirmemek için). */
suspend fun XtreamApi.vodStreams(categoryId: String): List<VodItem> {
    val list = streamArray("&action=get_vod_streams&category_id=${java.net.URLEncoder.encode(categoryId, "UTF-8")}") { m, _ ->
        val id = m["stream_id"]?.toDoubleOrNull()?.toInt() ?: return@streamArray null
        VodItem(
            id = id,
            name = m["name"]?.trim().orEmpty().ifBlank { "Film $id" },
            poster = m["stream_icon"].httpOrNull(),
            rating = ratingText(m["rating"]),
            ext = m["container_extension"]?.trim()?.ifBlank { null } ?: "mp4",
            isSeries = false,
        ) to (m["added"]?.toLongOrNull() ?: 0L)
    }
    // En yeni eklenen en üstte
    return withContext(Dispatchers.Default) {
        list.distinctBy { it.first.id }.sortedByDescending { it.second }.map { it.first }
    }
}

suspend fun XtreamApi.seriesList(categoryId: String): List<VodItem> {
    val list = streamArray("&action=get_series&category_id=${java.net.URLEncoder.encode(categoryId, "UTF-8")}") { m, _ ->
        val id = m["series_id"]?.toDoubleOrNull()?.toInt() ?: return@streamArray null
        VodItem(
            id = id,
            name = m["name"]?.trim().orEmpty().ifBlank { "Dizi $id" },
            poster = m["cover"].httpOrNull(),
            rating = ratingText(m["rating"]),
            ext = "",
            isSeries = true,
        ) to (m["last_modified"]?.toLongOrNull() ?: 0L)
    }
    return withContext(Dispatchers.Default) {
        list.distinctBy { it.first.id }.sortedByDescending { it.second }.map { it.first }
    }
}

suspend fun XtreamApi.movieInfo(id: Int): MovieInfo = withContext(Dispatchers.Default) {
    val o = JSONObject(fetchText("&action=get_vod_info&vod_id=$id"))
    val info = o.optJSONObject("info") ?: JSONObject()
    val data = o.optJSONObject("movie_data") ?: JSONObject()
    val date = info.str("releasedate", "release_date", "year")
    MovieInfo(
        plot = info.str("plot", "description"),
        genre = info.str("genre"),
        cast = info.str("cast", "actors"),
        director = info.str("director"),
        year = date?.take(4),
        duration = info.str("duration")?.let(::shortDuration),
        rating = ratingText(info.str("rating")),
        backdrop = info.str("backdrop_path", "backdrop").httpOrNull(),
        poster = info.str("movie_image", "cover_big").httpOrNull(),
        ext = data.str("container_extension"),
    )
}

suspend fun XtreamApi.seriesInfo(id: Int): SeriesInfo = withContext(Dispatchers.Default) {
    val o = JSONObject(fetchText("&action=get_series_info&series_id=$id"))
    val info = o.optJSONObject("info") ?: JSONObject()
    val eps = ArrayList<Episode>()

    fun addEpisode(e: JSONObject, seasonHint: Int) {
        val epId = e.str("id") ?: return
        val ei = e.optJSONObject("info") ?: JSONObject()
        val season = e.str("season")?.toIntOrNull() ?: seasonHint
        val num = e.str("episode_num")?.toIntOrNull() ?: 0
        eps += Episode(
            id = epId,
            season = season,
            num = num,
            title = e.str("title")?.let { cleanEpisodeTitle(it) } ?: "Bölüm $num",
            ext = e.str("container_extension") ?: "mp4",
            plot = ei.str("plot"),
            duration = ei.str("duration")?.let(::shortDuration),
            image = ei.str("movie_image").httpOrNull(),
        )
    }

    // "episodes" bazı sunucularda {"1":[...]} nesnesi, bazılarında [[...],[...]] dizisi
    when (val raw = o.opt("episodes")) {
        is JSONObject -> raw.keys().forEach { k ->
            val arr = raw.optJSONArray(k) ?: return@forEach
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, k.toIntOrNull() ?: 1) }
        }
        is JSONArray -> for (s in 0 until raw.length()) {
            val arr = raw.optJSONArray(s) ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, s + 1) }
        }
    }

    SeriesInfo(
        name = info.str("name"),
        plot = info.str("plot"),
        genre = info.str("genre"),
        cast = info.str("cast"),
        year = info.str("releaseDate", "releasedate", "year")?.take(4),
        rating = ratingText(info.str("rating")),
        backdrop = info.str("backdrop_path").httpOrNull(),
        poster = info.str("cover").httpOrNull(),
        seasons = eps.distinctBy { it.id }
            .groupBy { it.season }
            .mapValues { (_, v) -> v.sortedBy { it.num } }
            .toSortedMap(),
    )
}

/** "01:45:00" -> "1 sa 45 dk" */
private fun shortDuration(s: String): String {
    val p = s.split(":").mapNotNull { it.trim().toIntOrNull() }
    if (p.size < 2) return s
    val (h, m) = if (p.size == 3) p[0] to p[1] else 0 to p[0]
    return when {
        h > 0 && m > 0 -> "$h sa $m dk"
        h > 0 -> "$h sa"
        m > 0 -> "$m dk"
        else -> s
    }
}

/** "Dizi Adı - S01E02 - Bölüm adı" gibi uzun başlıkları kısaltır. */
private fun cleanEpisodeTitle(t: String): String {
    val m = Regex("""S\d+\s*E\d+\s*[-–:]?\s*(.*)$""", RegexOption.IGNORE_CASE).find(t)
    val rest = m?.groupValues?.get(1)?.trim()
    return if (!rest.isNullOrBlank()) rest else t
}
