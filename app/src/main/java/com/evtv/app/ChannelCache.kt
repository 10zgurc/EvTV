package com.evtv.app

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File

/**
 * Kanal listesini cihazda saklar; uygulama açılınca liste anında görünür,
 * güncel liste arka planda indirilir.
 */
class ChannelCache(ctx: Context) {
    private val file = File(ctx.filesDir, "channels.json")

    fun save(account: String, cats: List<Category>, chans: List<Channel>) {
        val tmp = File(file.path + ".tmp")
        try {
            JsonWriter(tmp.bufferedWriter()).use { w ->
                w.beginObject()
                w.name("account").value(account)
                w.name("cats").beginArray()
                for (c in cats) w.beginArray().value(c.id).value(c.name).endArray()
                w.endArray()
                w.name("chans").beginArray()
                for (c in chans) {
                    w.beginArray().value(c.streamId.toLong()).value(c.num.toLong()).value(c.name)
                        .value(c.logo).value(c.categoryId).endArray()
                }
                w.endArray()
                w.endObject()
            }
            tmp.renameTo(file)
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    /** Kayıtlı hesap aynıysa listeyi döner, yoksa ya da dosya bozuksa null. */
    fun load(account: String): Pair<List<Category>, List<Channel>>? {
        if (!file.exists()) return null
        return try {
            JsonReader(file.bufferedReader()).use { r ->
                var acc: String? = null
                val cats = ArrayList<Category>()
                val chans = ArrayList<Channel>()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "account" -> acc = r.nextString()
                        "cats" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                cats += Category(r.nextString(), r.nextString())
                                r.endArray()
                            }
                            r.endArray()
                        }
                        "chans" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                chans += Channel(r.nextInt(), r.nextInt(), r.nextString(), r.optString(), r.optString())
                                r.endArray()
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (acc == account && chans.isNotEmpty()) cats to chans else null
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    fun clear() {
        file.delete()
    }

    private fun JsonReader.optString(): String? =
        if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
}
