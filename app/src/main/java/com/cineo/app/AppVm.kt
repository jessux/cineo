package com.cineo.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class AppVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cineo", 0)

    var creds by mutableStateOf(
        prefs.getString("server", null)?.let { Creds(it, prefs.getString("user", "")!!, prefs.getString("pass", "")!!, prefs.getBoolean("m3u", false)) }
    )
        private set
    private var x: Xtream? = null
    val api: Xtream? get() = creds?.let { c -> x?.takeIf { it.c == c } ?: Xtream(c, app.cacheDir).also { x = it } }
    private val app get() = getApplication<Application>()

    // ---- Comptes enregistrés ----
    val accounts = mutableStateListOf<Creds>().apply {
        addAll(prefs.getString("accounts", "")!!.split("\n").mapNotNull(Creds::parse))
    }
    private fun saveAccounts() = prefs.edit().putString("accounts", accounts.joinToString("\n") { it.encode() }).apply()
    fun forgetAccount(c: Creds) { accounts.remove(c); saveAccounts() }

    // ---- Contrôle parental ----
    var pin by mutableStateOf(prefs.getString("pin", null)); private set
    var showAdult by mutableStateOf(false); private set
    fun changePin(p: String) { pin = p; prefs.edit().putString("pin", p).apply() }
    fun unlockAdult(p: String): Boolean { if (p != pin) return false; showAdult = true; publish(); return true }
    fun lockAdult() { showAdult = false; publish() }

    // ---- Guide TV : programme en cours par chaîne, chargé à la demande ----
    val nowEpg = mutableStateMapOf<String, List<Prog>>()
    private val epgGate = kotlinx.coroutines.sync.Semaphore(4)
    fun loadNow(i: Item) {
        if (nowEpg.containsKey(i.id) || creds?.m3u == true) return
        nowEpg[i.id] = emptyList()
        viewModelScope.launch { epgGate.acquire(); try { nowEpg[i.id] = runCatching { api!!.epg(i, false) }.getOrDefault(emptyList()) } finally { epgGate.release() } }
    }

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

    val pid get() = profile?.id ?: "p0"
    // ---- Alerte : abonnement déjà utilisé ailleurs ----
    var pendingPlay by mutableStateOf<(() -> Unit)?>(null)
    var conns by mutableStateOf(0 to 0); private set
    fun guard(action: () -> Unit) {
        val x = api ?: return
        viewModelScope.launch {
            val c = runCatching { x.connections() }.getOrDefault(0 to 0)
            if (c.second > 0 && c.first >= c.second) { conns = c; pendingPlay = action } else action()
        }
    }

    /** Rafraîchit le compteur de connexions ; garde la valeur précédente en cas d'erreur réseau. */
    suspend fun refreshConns() {
        val xt = api ?: return
        conns = runCatching { xt.connections() }.getOrDefault(conns)
    }

    fun checkUpdate() { viewModelScope.launch { Updater.checkAndInstall(app) } }

    var resumeTick by mutableStateOf(0) // relu au retour du lecteur
    fun resumeOf(c: android.content.Context, i: Item) = Resume.get(c, "$pid|${i.key}")

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

    fun login(server: String, user: String, pass: String, m3u: Boolean = false) {
        var u = user.trim(); var p = pass.trim()
        // Un lien M3U Xtream (get.php?username=X&password=Y) est converti en connexion Xtream, plus complète
        val xtreamLink = "username=" in server && "password=" in server
        Regex("username=([^&]+)").find(server)?.let { u = it.groupValues[1] }
        Regex("password=([^&]+)").find(server)?.let { p = it.groupValues[1] }
        val c = if (m3u && !xtreamLink) Creds(server.trim(), "", "", true) else Creds(Xtream.normalize(server), u, p)
        viewModelScope.launch {
            busy = true; error = null
            try {
                if (Xtream(c).login()) {
                    prefs.edit().putString("server", c.server).putString("user", c.user).putString("pass", c.pass).putBoolean("m3u", c.m3u).apply()
                    accounts.remove(c); accounts.add(0, c); saveAccounts()
                    raw = null; publish()
                    creds = c
                    busy = false
                    loadAll()
                } else error = if (c.m3u) "Playlist M3U invalide" else "Identifiants refusés par le serveur"
            } catch (e: Exception) { error = "Connexion impossible : ${e.message}" }
            busy = false
        }
    }

    fun logout() {
        prefs.edit().remove("server").remove("user").remove("pass").apply()
        selectProfile(null)
        creds = null; raw = null; publish(); detail = null; nowEpg.clear()
    }


    private class Raw(
        val mc: List<Category>, val m: List<Item>, val sc: List<Category>, val s: List<Item>, val lc: List<Category>, val l: List<Item>,
    )
    private var raw: Raw? = null

    private suspend fun fetchAll(x: Xtream, cache: Boolean) = kotlinx.coroutines.coroutineScope {
        val mc = async { x.categories(Kind.MOVIE, cache) }; val m = async { x.streams(Kind.MOVIE, cache) }
        val sc = async { x.categories(Kind.SERIES, cache) }; val s = async { x.streams(Kind.SERIES, cache) }
        val lc = async { x.categories(Kind.LIVE, cache) }; val l = async { x.streams(Kind.LIVE, cache) }
        Raw(mc.await(), m.await(), sc.await(), s.await(), lc.await(), l.await())
    }

    /** Applique le filtre parental puis expose les listes à l'écran. */
    private fun publish() {
        val r = raw
        val adultName = Regex("(?i)adult|xxx|\\+ ?18|18 ?\\+|porn")
        fun ok(cats: List<Category>) = cats.filter { showAdult || !adultName.containsMatchIn(it.name) }.map { it.id }.toSet()
        val okM = ok(r?.mc.orEmpty()); val okS = ok(r?.sc.orEmpty()); val okL = ok(r?.lc.orEmpty())
        fun List<Item>.f(okC: Set<String>, cats: List<Category>) =
            filter { showAdult || (!it.adult && (it.categoryId in okC || cats.none { c -> c.id == it.categoryId })) }
        movies = r?.m.orEmpty().f(okM, r?.mc.orEmpty()); series = r?.s.orEmpty().f(okS, r?.sc.orEmpty()); live = r?.l.orEmpty().f(okL, r?.lc.orEmpty())
        moviesByCat = movies.groupBy { it.categoryId }
        seriesByCat = series.groupBy { it.categoryId }
        liveByCat = live.groupBy { it.categoryId }
        movieCats = r?.mc.orEmpty().filter { moviesByCat.containsKey(it.id) }
        seriesCats = r?.sc.orEmpty().filter { seriesByCat.containsKey(it.id) }
        liveCats = r?.lc.orEmpty().filter { liveByCat.containsKey(it.id) }
    }

    fun loadAll() {
        val x = api ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true; error = null
            if (raw == null) runCatching { raw = fetchAll(x, true); publish() } // instantané depuis le cache
            try { raw = fetchAll(x, false); publish() }
            catch (e: Exception) { if (raw == null) error = "Chargement impossible : ${e.message}" }
            busy = false
        }
    }
    init { if (creds != null) loadAll() } // en dernier : toutes les propriétés sont initialisées
}

data class Profile(val id: String, val name: String, val color: Int) {
    fun encode() = "$id|${name.replace("|", " ").replace("\n", " ")}|$color"
    companion object {
        fun parse(l: String) = l.split("|").takeIf { it.size == 3 }?.let { Profile(it[0], it[1], it[2].toIntOrNull() ?: 0) }
    }
}
