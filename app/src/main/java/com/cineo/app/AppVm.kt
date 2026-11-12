package com.cineo.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class AppVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cineo", 0)

    var creds by mutableStateOf(
        prefs.getString("server", null)?.let { Creds(it, prefs.getString("user", "")!!, prefs.getString("pass", "")!!) }
    )
        private set
    val api get() = creds?.let { Xtream(it) }

    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var detail by mutableStateOf<Item?>(null)

    var movieCats by mutableStateOf(emptyList<Category>()); private set
    var seriesCats by mutableStateOf(emptyList<Category>()); private set
    var liveCats by mutableStateOf(emptyList<Category>()); private set
    var movies by mutableStateOf(emptyList<Item>()); private set
    var series by mutableStateOf(emptyList<Item>()); private set
    var live by mutableStateOf(emptyList<Item>()); private set
    var moviesByCat by mutableStateOf(emptyMap<String, List<Item>>()); private set
    var seriesByCat by mutableStateOf(emptyMap<String, List<Item>>()); private set
    var liveByCat by mutableStateOf(emptyMap<String, List<Item>>()); private set

    // ---- Profils ("Qui regarde ?") : Ma liste + historique propres à chacun ----
    val profiles = mutableStateListOf<Profile>().apply {
        addAll(prefs.getString("profiles", "")!!.split("\n").mapNotNull(Profile::parse))
        if (isEmpty()) { // 1er lancement : profil par défaut qui reprend les anciennes données
            add(Profile("p0", "Moi", 0))
            prefs.edit().putString("profiles", "p0|Moi|0")
                .putStringSet("favs_p0", prefs.getStringSet("favs", emptySet()))
                .putString("history_p0", prefs.getString("history", "")).apply()
        }
    }
    var profile by mutableStateOf<Profile?>(null); private set

    fun selectProfile(p: Profile?) {
        profile = p; detail = null
        favs.clear(); history.clear()
        if (p == null) return
        favs.addAll(prefs.getStringSet("favs_${p.id}", emptySet())!!)
        history.addAll(prefs.getString("history_${p.id}", "")!!.split("\n").filter { it.isNotBlank() })
    }

    fun saveProfile(p: Profile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i >= 0) profiles[i] = p else profiles.add(p)
        if (profile?.id == p.id) profile = p
        prefs.edit().putString("profiles", profiles.joinToString("\n") { it.encode() }).apply()
    }

    fun deleteProfile(p: Profile) {
        if (profiles.size <= 1) return
        profiles.removeAll { it.id == p.id }
        prefs.edit().putString("profiles", profiles.joinToString("\n") { it.encode() })
            .remove("favs_${p.id}").remove("history_${p.id}").apply()
    }

    private val pid get() = profile?.id ?: "p0"

    val favs = mutableStateListOf<String>()

    fun toggleFav(i: Item) {
        if (!favs.remove(i.key)) favs.add(0, i.key)
        prefs.edit().putStringSet("favs_$pid", favs.toSet()).apply()
    }
    // ---- Profil de goût : historique de lecture + Ma liste ----
    val history = mutableStateListOf<String>()

    fun recordPlay(i: Item) {
        if (i.kind == Kind.LIVE) return
        history.remove(i.key); history.add(0, i.key)
        while (history.size > 100) history.removeAt(history.lastIndex)
        prefs.edit().putString("history_$pid", history.joinToString("\n")).apply()
    }

    private fun index() = (movies + series).associateBy { it.key }
    fun watched(): List<Item> = index().let { all -> history.mapNotNull { all[it] } }

    /** Score par catégorie : lectures récentes pèsent plus, Ma liste compte aussi. */
    fun recommendations(limit: Int = 30): List<Item> {
        val score = HashMap<String, Int>()
        watched().forEachIndexed { n, it -> score.merge("${it.kind}:${it.categoryId}", if (n < 5) 3 else 1, Int::plus) }
        favItems().forEach { score.merge("${it.kind}:${it.categoryId}", 2, Int::plus) }
        val seen = history.toSet()
        val pool = (movies + series).filter { it.key !in seen && it.image != null }
        if (score.isEmpty()) // pas encore de profil : récents les mieux notés
            return pool.sortedByDescending { it.added }.take(300).sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }.take(limit)
        return pool.filter { score.containsKey("${it.kind}:${it.categoryId}") }
            .sortedWith(compareByDescending<Item> { score["${it.kind}:${it.categoryId}"] }
                .thenByDescending { it.rating.toDoubleOrNull() ?: 0.0 }.thenByDescending { it.added })
            .take(limit * 3).shuffled().take(limit)
    }

    /** "Parce que vous avez regardé X" */
    fun becauseYouWatched(): Pair<Item, List<Item>>? {
        val last = watched().firstOrNull() ?: return null
        val seen = history.toSet()
        val list = (if (last.kind == Kind.MOVIE) moviesByCat else seriesByCat)[last.categoryId].orEmpty()
            .filter { it.key !in seen }.shuffled().take(20)
        return if (list.isEmpty()) null else last to list
    }

    fun favItems(): List<Item> {
        val all = (movies + series).associateBy { it.key }
        return favs.mapNotNull { all[it] }
    }

    fun login(server: String, user: String, pass: String) {
        var s = server; var u = user.trim(); var p = pass.trim()
        // Accepte aussi un lien M3U complet : http://hote:port/get.php?username=X&password=Y
        Regex("username=([^&]+)").find(server)?.let { u = it.groupValues[1] }
        Regex("password=([^&]+)").find(server)?.let { p = it.groupValues[1] }
        s = Xtream.normalize(s)
        val c = Creds(s, u, p)
        viewModelScope.launch {
            busy = true; error = null
            try {
                if (Xtream(c).login()) {
                    prefs.edit().putString("server", s).putString("user", u).putString("pass", p).apply()
                    creds = c
                    loadAll()
                } else error = "Identifiants refusés par le serveur"
            } catch (e: Exception) { error = "Connexion impossible : ${e.message}" }
            busy = false
        }
    }

    fun logout() {
        prefs.edit().remove("server").remove("user").remove("pass").apply()
        selectProfile(null)
        creds = null; movies = emptyList(); series = emptyList(); live = emptyList(); detail = null
    }

    init { if (creds != null) loadAll() }

    fun loadAll() {
        val x = api ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true; error = null
            try {
                val mc = async { x.categories(Kind.MOVIE) }; val m = async { x.streams(Kind.MOVIE) }
                val sc = async { x.categories(Kind.SERIES) }; val s = async { x.streams(Kind.SERIES) }
                val lc = async { x.categories(Kind.LIVE) }; val l = async { x.streams(Kind.LIVE) }
                movies = m.await(); series = s.await(); live = l.await()
                moviesByCat = movies.groupBy { it.categoryId }
                seriesByCat = series.groupBy { it.categoryId }
                liveByCat = live.groupBy { it.categoryId }
                movieCats = mc.await().filter { moviesByCat.containsKey(it.id) }
                seriesCats = sc.await().filter { seriesByCat.containsKey(it.id) }
                liveCats = lc.await().filter { liveByCat.containsKey(it.id) }
            } catch (e: Exception) { error = "Chargement impossible : ${e.message}" }
            busy = false
        }
    }
}

data class Profile(val id: String, val name: String, val color: Int) {
    fun encode() = "$id|${name.replace("|", " ").replace("\n", " ")}|$color"
    companion object {
        fun parse(l: String) = l.split("|").takeIf { it.size == 3 }?.let { Profile(it[0], it[1], it[2].toIntOrNull() ?: 0) }
    }
}
