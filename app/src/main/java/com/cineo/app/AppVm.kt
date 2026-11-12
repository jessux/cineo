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

    val favs = mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favs", emptySet())!!) }

    fun toggleFav(i: Item) {
        if (!favs.remove(i.key)) favs.add(0, i.key)
        prefs.edit().putStringSet("favs", favs.toSet()).apply()
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
        creds = null; movies = emptyList(); series = emptyList(); live = emptyList(); detail = null
    }

    fun loadAll() {
        val x = api ?: return
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
