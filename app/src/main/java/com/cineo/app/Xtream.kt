package com.cineo.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Creds(val server: String, val user: String, val pass: String)
data class Category(val id: String, val name: String)
enum class Kind { MOVIE, SERIES, LIVE }
data class Item(
    val kind: Kind, val id: String, val name: String, val image: String?,
    val categoryId: String, val ext: String, val rating: String, val added: Long,
) { val key get() = "${kind.name}:$id" }
data class Episode(val id: String, val title: String, val ext: String, val season: Int, val num: Int, val image: String?, val plot: String?)
data class Info(val plot: String?, val backdrop: String?, val meta: String, val episodes: Map<Int, List<Episode>> = emptyMap())

private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k)
private fun <T> JSONArray.objs(f: (JSONObject) -> T) = (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }
private suspend fun <T> io(b: () -> T) = withContext(Dispatchers.IO) { b() }

class Xtream(val c: Creds) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(action: String? = null, extra: String = ""): String {
        val u = "${c.server}/player_api.php?username=${enc(c.user)}&password=${enc(c.pass)}" +
            (if (action != null) "&action=$action" else "") + extra
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 120000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }.trim()
        } finally { conn.disconnect() }
    }
    private fun arr(action: String) = get(action).let { if (it.startsWith("[")) JSONArray(it) else JSONArray() }
    private fun obj(action: String?, extra: String = "") = get(action, extra).let { if (it.startsWith("{")) JSONObject(it) else JSONObject() }

    suspend fun login(): Boolean = io { obj(null).optJSONObject("user_info")?.optInt("auth") == 1 }

    suspend fun categories(kind: Kind) = io {
        arr(when (kind) { Kind.MOVIE -> "get_vod_categories"; Kind.SERIES -> "get_series_categories"; Kind.LIVE -> "get_live_categories" })
            .objs { Category(it.s("category_id"), it.s("category_name")) }
    }

    suspend fun streams(kind: Kind) = io {
        val series = kind == Kind.SERIES
        arr(when (kind) { Kind.MOVIE -> "get_vod_streams"; Kind.SERIES -> "get_series"; Kind.LIVE -> "get_live_streams" }).objs { o ->
            Item(
                kind, o.s(if (series) "series_id" else "stream_id"), o.s("name"),
                o.s(if (series) "cover" else "stream_icon").ifBlank { null }, o.s("category_id"),
                o.s("container_extension").ifBlank { "mp4" }, o.s("rating"),
                o.s(if (series) "last_modified" else "added").toLongOrNull() ?: 0,
            )
        }
    }

    private fun backdrop(o: JSONObject): String? =
        when (val b = o.opt("backdrop_path")) { is JSONArray -> b.optString(0); is String -> b; else -> null }?.ifBlank { null }

    suspend fun vodInfo(id: String) = io {
        val i = obj("get_vod_info", "&vod_id=$id").optJSONObject("info") ?: JSONObject()
        Info(
            i.s("plot").ifBlank { i.s("description") }.ifBlank { null }, backdrop(i),
            listOf(i.s("releasedate").take(4), i.s("genre"), i.s("duration")).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    suspend fun seriesInfo(id: String) = io {
        val o = obj("get_series_info", "&series_id=$id")
        val i = o.optJSONObject("info") ?: JSONObject()
        val eps = mutableListOf<Episode>()
        fun add(a: JSONArray) {
            eps += a.objs { e ->
                val ei = e.optJSONObject("info")
                Episode(
                    e.s("id"), e.s("title"), e.s("container_extension").ifBlank { "mp4" },
                    e.s("season").toIntOrNull() ?: 1, e.s("episode_num").toIntOrNull() ?: 0,
                    ei?.s("movie_image")?.ifBlank { null }, ei?.s("plot")?.ifBlank { null },
                )
            }
        }
        when (val e = o.opt("episodes")) {
            is JSONObject -> e.keys().forEach { k -> e.optJSONArray(k)?.let { add(it) } }
            is JSONArray -> for (n in 0 until e.length()) e.optJSONArray(n)?.let { add(it) }
        }
        Info(
            i.s("plot").ifBlank { null }, backdrop(i),
            listOf(i.s("releaseDate").take(4), i.s("genre")).filter { it.isNotBlank() }.joinToString(" · "),
            eps.sortedBy { it.num }.groupBy { it.season }.toSortedMap(),
        )
    }

    fun url(item: Item) = when (item.kind) {
        Kind.MOVIE -> "${c.server}/movie/${c.user}/${c.pass}/${item.id}.${item.ext}"
        Kind.LIVE -> "${c.server}/live/${c.user}/${c.pass}/${item.id}.ts"
        Kind.SERIES -> ""
    }
    fun url(e: Episode) = "${c.server}/series/${c.user}/${c.pass}/${e.id}.${e.ext}"

    companion object {
        const val UA = "VLC/3.0.20 LibVLC/3.0.20"
        fun normalize(s: String): String {
            var u = s.trim().substringBefore("/player_api.php").substringBefore("/get.php").trimEnd('/')
            if (!u.startsWith("http")) u = "http://$u"
            return u
        }
    }
}
