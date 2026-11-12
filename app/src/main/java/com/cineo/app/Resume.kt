package com.cineo.app

import android.content.Context

/** Positions de lecture par profil : clé "profil|MOVIE:id", "profil|SERIES:id" (dernier épisode) ou "profil|EP:id". */
object Resume {
    data class Pos(val pos: Long, val dur: Long, val ep: String) { val progress get() = pos.toFloat() / dur }

    private fun prefs(c: Context) = c.getSharedPreferences("resume", 0)

    fun get(c: Context, k: String): Pos? = prefs(c).getString(k, null)?.split("|")?.takeIf { it.size == 3 }
        ?.let { Pos(it[0].toLongOrNull() ?: 0, it[1].toLongOrNull() ?: 1, it[2]) }

    fun put(c: Context, keys: Array<String>, pos: Long, dur: Long, ep: String) {
        if (dur <= 0) return
        val e = prefs(c).edit()
        val done = pos > dur * 0.95 || pos < 15_000 // terminé ou à peine commencé
        keys.forEach { if (done) e.remove(it) else e.putString(it, "$pos|$dur|$ep") }
        e.apply()
    }
}
