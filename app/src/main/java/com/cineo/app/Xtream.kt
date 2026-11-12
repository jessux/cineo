package com.cineo.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** m3u = true : `server` contient l'URL d'une playlist M3U (user/pass vides). */
data class Creds(val server: String, val user: String, val pass: String, val m3u: Boolean = false) {
    fun encode() = listOf(server, user, pass, if (m3u) "1" else "0").joinToString("\t")
    val label get() = if (m3u) server.substringAfter("://").substringBefore("/") + " (M3U)" else "${server.substringAfter("://")} · $user"
    companion object {
        fun parse(l: String) = l.split("\t").takeIf { it.size == 4 }?.let { Creds(it[0], it[1], it[2], it[3] == "1") }
    }
}
data class Category(val id: String, val name: String)
enum class Kind { MOVIE, SERIES, LIVE }
data class Item(
    val kind: Kind, val id: String, val name: String, val image: String?,
    val categoryId: String, val ext: String, val rating: String, val added: Long,
    val adult: Boolean = false, val archive: Boolean = false, val url: String? = null,
) { val key get() = "${kind.name}:$id" }
data class Episode(val id: String, val title: String, val ext: String, val season: Int, val num: Int, val image: String?, val plot: String?)
data class Info(
    val plot: String?, val backdrop: String?, val meta: String, val episodes: Map<Int, List<Episode>> = emptyMap(),
    val cast: String = "", val director: String = "", val trailer: String = "", val rating: String = "",
)
/** Programme TV (guide EPG). startStr = heure serveur "AAAA-MM-JJ HH:MM:SS", utilisée pour le replay. */
data class Prog(val title: String, val desc: String, val start: Long, val end: Long, val startStr: String, val archive: Boolean) {
    val isNow get() = System.currentTimeMillis() / 1000 in start until end
    val progress get() = ((System.currentTimeMillis() / 1000 - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

private fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k)
private fun <T> JSONArray.objs(f: (JSONObject) -> T) = (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }
private suspend fun <T> io(b: () -> T) = withContext(Dispatchers.IO) { b() }
private fun b64(s: String) = runCatching { String(Base64.decode(s, Base64.DEFAULT)) }.getOrDefault(s)

class Xtream(val c: Creds, private val cacheDir: File? = null) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetch(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 120000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }.trim()
        } finally { conn.disconnect() }
    }

    /** Catalogue gardé sur le téléphone : démarrage instantané, puis rafraîchi en arrière-plan. */
    private fun cacheFile(name: String) = cacheDir?.let { File(it, "cat_${(c.server + c.user).hashCode()}_$name") }

    private fun get(action: String? = null, extra: String = "", fromCache: Boolean = false): String {
        val f = if (action != null && extra.isEmpty()) cacheFile("$action.json") else null
        if (fromCache) return f?.takeIf { it.exists() }?.readText() ?: throw Exception("pas de cache")
        val txt = fetch("${c.server}/player_api.php?username=${enc(c.user)}&password=${enc(c.pass)}" +
            (if (action != null) "&action=$action" else "") + extra)
        f?.let { runCatching { it.writeText(txt) } }
        return txt
    }
    private fun arr(action: String, fromCache: Boolean) = get(action, fromCache = fromCache).let { if (it.startsWith("[")) JSONArray(it) else JSONArray() }
    private fun obj(action: String?, extra: String = "") = get(action, extra).let { if (it.startsWith("{")) JSONObject(it) else JSONObject() }

    suspend fun login(): Boolean = io {
        if (c.m3u) fetch(c.server).let { it.contains("#EXTINF") || it.startsWith("#EXTM3U") }
        else obj(null).optJSONObject("user_info")?.optInt("auth") == 1
    }

    // ---------- Playlist M3U ----------
    private var m3uItems: List<Item>? = null
    @Synchronized private fun m3u(fromCache: Boolean): List<Item> = m3uItems ?: run {
        val f = cacheFile("playlist.m3u")
        val txt = if (fromCache) f?.takeIf { it.exists() }?.readText() ?: throw Exception("pas de cache")
        else fetch(c.server).also { t -> f?.let { runCatching { it.writeText(t) } } }
        val out = ArrayList<Item>(); var inf = ""
        fun attr(k: String) = Regex("$k=\"([^\"]*)\"").find(inf)?.groupValues?.get(1).orEmpty()
        txt.lineSequence().forEach { raw ->
            val l = raw.trim()
            if (l.startsWith("#EXTINF")) inf = l
            else if (l.isNotEmpty() && !l.startsWith("#") && inf.isNotEmpty()) {
                val ext = l.substringAfterLast('.', "").substringBefore('?').lowercase()
                val vod = "/movie/" in l || "/series/" in l || ext in setOf("mp4", "mkv", "avi", "mov", "webm")
                val group = attr("group-title").ifBlank { "Autres" }
                out += Item(
                    if (vod) Kind.MOVIE else Kind.LIVE, l.hashCode().toString(), inf.substringAfterLast(",").trim(),
                    attr("tvg-logo").ifBlank { null }, group, ext.ifBlank { "ts" }, "", out.size.toLong(),
                    url = l,
                )
                inf = ""
            }
        }
        out.also { m3uItems = it }
    }

    suspend fun categories(kind: Kind, fromCache: Boolean = false) = io {
        if (c.m3u) return@io m3u(fromCache).filter { it.kind == kind }.map { it.categoryId }.distinct().map { Category(it, it) }
        arr(when (kind) { Kind.MOVIE -> "get_vod_categories"; Kind.SERIES -> "get_series_categories"; Kind.LIVE -> "get_live_categories" }, fromCache)
            .objs { Category(it.s("category_id"), it.s("category_name")) }
    }

    suspend fun streams(kind: Kind, fromCache: Boolean = false) = io {
        if (c.m3u) return@io m3u(fromCache).filter { it.kind == kind }
        val series = kind == Kind.SERIES
        arr(when (kind) { Kind.MOVIE -> "get_vod_streams"; Kind.SERIES -> "get_series"; Kind.LIVE -> "get_live_streams" }, fromCache).objs { o ->
            Item(
                kind, o.s(if (series) "series_id" else "stream_id"), o.s("name"),
                o.s(if (series) "cover" else "stream_icon").ifBlank { null }, o.s("category_id"),
                o.s("container_extension").ifBlank { "mp4" }, o.s("rating"),
                o.s(if (series) "last_modified" else "added").toLongOrNull() ?: 0,
                adult = o.s("is_adult") == "1", archive = o.s("tv_archive") == "1",
            )
        }
    }

    private fun backdrop(o: JSONObject): String? =
        (when (val b = o.opt("backdrop_path")) { is JSONArray -> b.optString(0); is String -> b; else -> null }?.ifBlank { null })
            ?: o.s("backdrop").split(Regex("\\s+")).firstOrNull { it.length > 12 }

    private fun extras(i: Info, o: JSONObject) =
        i.copy(cast = o.s("cast"), director = o.s("director"), trailer = o.s("youtube_trailer"), rating = o.s("rating"))

    suspend fun vodInfo(id: String) = io {
        if (c.m3u) return@io Info(null, null, "")
        val i = obj("get_vod_info", "&vod_id=$id").optJSONObject("info") ?: JSONObject()
        extras(Info(
            i.s("plot").ifBlank { i.s("description") }.ifBlank { null }, backdrop(i),
            listOf(i.s("releasedate").take(4), i.s("genre"), i.s("duration")).filter { it.isNotBlank() }.joinToString(" · "),
        ), i)
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
        extras(Info(
            i.s("plot").ifBlank { null }, backdrop(i),
            listOf(i.s("releaseDate").take(4), i.s("genre")).filter { it.isNotBlank() }.joinToString(" · "),
            eps.sortedBy { it.num }.groupBy { it.season }.toSortedMap(),
        ), i)
    }

    /** Guide TV : `full` = programme de la journée (avec replay), sinon en cours + suivant. */
    suspend fun epg(item: Item, full: Boolean): List<Prog> = io {
        if (c.m3u) return@io emptyList()
        val o = obj(if (full) "get_simple_data_table" else "get_short_epg", "&stream_id=${item.id}" + if (full) "" else "&limit=3")
        (o.optJSONArray("epg_listings") ?: JSONArray()).objs { e ->
            Prog(b64(e.s("title")), b64(e.s("description")), e.s("start_timestamp").toLongOrNull() ?: 0,
                e.s("stop_timestamp").toLongOrNull() ?: 0, e.s("start"), e.s("has_archive") == "1")
        }.sortedBy { it.start }
    }

    fun replayUrl(item: Item, p: Prog): String {
        val d = p.startStr.take(10); val t = p.startStr.drop(11).take(5).replace(':', '-')
        return "${c.server}/timeshift/${c.user}/${c.pass}/${(p.end - p.start) / 60}/$d:$t/${item.id}.ts"
    }

    /** Nombre de connexions en cours / maximum autorisé sur l'abonnement. */
    suspend fun connections(): Pair<Int, Int> = io {
        if (c.m3u) return@io 0 to 0
        val u = obj(null).optJSONObject("user_info") ?: JSONObject()
        (u.s("active_cons").toIntOrNull() ?: 0) to (u.s("max_connections").toIntOrNull() ?: 0)
    }

    fun url(item: Item) = item.url ?: when (item.kind) {
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
