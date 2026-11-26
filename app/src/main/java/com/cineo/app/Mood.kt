package com.cineo.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Genres reconnus dans les noms de catégories et le champ « genre » du serveur (texte passé par [norm], FR/EN).
 * Un mot-clé correspond au début d'un mot (« comed » : comédie, comedy…) ; terminé par une espace, au mot entier.
 */
enum class Genre(vararg val words: String) {
    COMEDY("comed", "comiq", "humo", "rire", "sitcom", "parodi", "burlesq", "stand up", "standup", "feel good", "feelgood"),
    ROMANCE("romanc", "romanti", "amour", "love", "sentimental", "rom com", "romcom", "telenovela", "soap "),
    ANIMATION("animation", "anime", "dessin", "cartoon", "manga"),
    FAMILY("famil", "enfant", "kid", "jeunesse", "junior", "child", "pixar", "dreamworks", "noel", "christmas"),
    MUSIC("music", "musiq", "concert", "danse", "dance"),
    ADVENTURE("aventur", "adventur"),
    ACTION("action", "martia", "super hero", "superhero", "kung fu", "combat", "marvel", "western", "catastrophe"),
    SCIFI("science fiction", "sci fi", "scifi", "sf ", "fantas", "star wars"),
    HORROR("horreur", "horror", "epouvante", "gore", "zombie", "slasher"),
    THRILLER("thriller", "suspens", "myster", "crim", "polic", "polar", "enquete", "detective", "espion", "spy ", "mafia", "gangster"),
    DRAMA("dram"),
    DOCUMENTARY("docu", "doc ", "docs ", "reportage", "nature", "animaux", "animal", "voyage", "travel", "decouverte", "cuisine", "cooking"),
    HISTORY("histo", "biopic", "biograph"),
    WAR("guerre", "war ", "militaire", "military"),
}

/** Humeurs proposées sous le carrousel. Un titre est retenu quand la somme des poids de ses genres atteint [MoodPicker.MIN_SCORE]. */
enum class Mood(val label: String, val emoji: String, val title: String, val color: Color, private val weights: Map<Genre, Int>) {
    JOYEUX("Joyeux", "😄", "Pour une humeur joyeuse", Color(0xFFFFB547), mapOf(
        Genre.COMEDY to 3, Genre.ANIMATION to 3, Genre.FAMILY to 3, Genre.MUSIC to 2, Genre.ADVENTURE to 1,
        Genre.HORROR to -5, Genre.WAR to -3, Genre.THRILLER to -2,
    )),
    CALME("Calme", "😌", "Pour se détendre", Color(0xFF3DD6B0), mapOf(
        Genre.DOCUMENTARY to 4, Genre.DRAMA to 3, Genre.MUSIC to 2, Genre.HISTORY to 1, Genre.ROMANCE to 1, Genre.ANIMATION to 1, Genre.FAMILY to 1,
        Genre.HORROR to -5, Genre.THRILLER to -3, Genre.ACTION to -3, Genre.WAR to -3,
    )),
    ROMANTIQUE("Romantique", "❤️", "Pour une soirée romantique", Color(0xFFFF6B8B), mapOf(
        Genre.ROMANCE to 4, Genre.COMEDY to 1, Genre.DRAMA to 1, Genre.MUSIC to 1,
        Genre.HORROR to -5, Genre.WAR to -3, Genre.THRILLER to -2, Genre.ACTION to -1,
    )),
    FRISSONS("Frissons", "😱", "Pour avoir des frissons", Color(0xFF8B6CFF), mapOf(
        Genre.HORROR to 4, Genre.THRILLER to 3, Genre.SCIFI to 1,
        Genre.FAMILY to -5, Genre.ANIMATION to -3, Genre.COMEDY to -1, Genre.ROMANCE to -1,
    )),
    ADRENALINE("Adrénaline", "💥", "Pour une dose d'adrénaline", Color(0xFFFF7A3D), mapOf(
        Genre.ACTION to 4, Genre.SCIFI to 3, Genre.ADVENTURE to 2, Genre.WAR to 2, Genre.THRILLER to 1,
        Genre.DOCUMENTARY to -3, Genre.FAMILY to -2, Genre.ROMANCE to -1,
    ));

    /** genres : masque de bits (1 shl Genre.ordinal). */
    fun score(genres: Int) = weights.entries.sumOf { (g, w) -> if ((genres and (1 shl g.ordinal)) != 0) w else 0 }
}

/**
 * Sélection pour une humeur. Classement : goûts du profil d'abord (mots des catégories et genres des titres aimés :
 * langue, plateforme, genres…) et type préféré (films / séries), puis pertinence pour l'humeur, note, nouveauté,
 * et un peu de hasard qui change chaque jour.
 */
object MoodPicker {
    const val MIN_SCORE = 3
    private val STOP = setOf("de", "du", "des", "la", "le", "les", "et", "the", "and", "of", "film", "films", "serie", "series", "vod", "tv")
    private val genreCache = ConcurrentHashMap<String, Int>()
    private val tokenCache = ConcurrentHashMap<String, List<String>>()

    fun genres(text: String): Int = genreCache.getOrPut(text) {
        val s = " ${norm(text)} "
        Genre.entries.fold(0) { m, g -> if (g.words.any { s.contains(" $it") }) m or (1 shl g.ordinal) else m }
    }

    private fun tokens(text: String): List<String> = tokenCache.getOrPut(text) {
        norm(text).split(' ').filter { it.length > 1 && it !in STOP }.distinct()
    }

    private fun tokens(i: Item, cat: String) = (tokens(cat) + tokens(i.genre)).distinct()

    /**
     * @param liked titres regardés ou dans Ma liste, avec leur poids.
     * @param exclude clés à ne pas proposer (déjà vus, masqués) ; [seenNames] : titres déjà vus, normalisés (doublons du catalogue).
     */
    fun pick(
        mood: Mood, catalog: List<Item>, catName: (Item) -> String, liked: List<Pair<Item, Int>>,
        exclude: Set<String>, seenNames: Set<String>, seed: Int, limit: Int = 30,
    ): List<Item> {
        val taste = HashMap<String, Int>()
        liked.forEach { (i, w) -> tokens(i, catName(i)).forEach { taste.merge(it, w, Int::plus) } }
        val kinds = HashMap<Kind, Int>()
        liked.forEach { (i, w) -> kinds.merge(i.kind, w, Int::plus) }
        val totalKinds = kinds.values.sum().coerceAtLeast(1).toDouble()

        class Cand(val item: Item, val cat: String, val mood: Int, val taste: Int)
        // Mots de genre aimés (comédie, action…) : comptent seulement s'ils vont avec l'humeur ; langue, plateforme… toujours
        val fitsMood = HashMap<String, Boolean>()
        fun tasteOf(i: Item, cat: String) = tokens(i, cat).sumOf { t ->
            if (fitsMood.getOrPut(t) { genres(t).let { g -> g == 0 || mood.score(g) > 0 } }) taste[t] ?: 0 else 0
        }
        val memo = HashMap<Int, Int>()
        val cands = ArrayList<Cand>()
        for (i in catalog) {
            if (i.image == null) continue
            val cat = catName(i)
            val g = genres(cat) or genres(i.genre)
            if (g == 0) continue
            val m = memo.getOrPut(g) { mood.score(g) }
            if (m < MIN_SCORE || i.key in exclude) continue
            cands += Cand(i, cat, m, if (taste.isEmpty()) 0 else tasteOf(i, cat))
        }
        if (cands.isEmpty()) return emptyList()

        val maxMood = cands.maxOf { it.mood }.toDouble()
        val maxTaste = cands.maxOf { it.taste }.coerceAtLeast(1).toDouble()
        val minAdded = cands.minOf { it.item.added }
        val spanAdded = (cands.maxOf { it.item.added } - minAdded).coerceAtLeast(1).toDouble()
        fun score(c: Cand): Double {
            val i = c.item
            return 2.0 * c.taste / maxTaste + 1.0 * (kinds[i.kind] ?: 0) / totalKinds + 0.6 * c.mood / maxMood +
                0.3 * ((i.rating.toDoubleOrNull() ?: 0.0) / 10).coerceIn(0.0, 1.0) + 0.15 * (i.added - minAdded) / spanAdded +
                0.25 * Random(i.key.hashCode() * 31 + seed).nextDouble()
        }

        // Variété : chaque titre supplémentaire d'une même catégorie est un peu pénalisé ; pas de doublon ni de titre déjà vu
        // (une playlist M3U peut lister deux fois la même URL : même clé, que la rangée ne supporte pas)
        val inCat = HashMap<String, Int>()
        val names = HashSet(seenNames)
        val keys = HashSet<String>()
        return cands.map { it to score(it) }.sortedByDescending { it.second }.take(limit * 20)
            .map { (c, s) -> c to s - 0.15 * (inCat.merge(c.cat, 1, Int::plus)!! - 1) }
            .sortedByDescending { it.second }
            .asSequence().map { it.first.item }.filter { keys.add(it.key) && names.add(norm(it.name)) }.take(limit).toList()
    }
}

/** Sous le carrousel : choix de l'humeur, puis une rangée de films et séries adaptés aux goûts du profil. */
@Composable
fun MoodSection(vm: AppVm, onClick: (Item) -> Unit) {
    val mood = vm.mood
    // Calculé en arrière-plan (catalogues de dizaines de milliers de titres) ; la rangée précédente reste affichée en attendant
    val picks by produceState<Pair<Mood, List<Item>>?>(
        null, mood, vm.movies, vm.series, vm.history.size, vm.history.firstOrNull(), vm.favs.size, vm.hidden,
    ) {
        mood?.let { m -> value = m to background { vm.moodPicks(m) } }
    }
    Column(Modifier.padding(top = 14.dp)) {
        Text("Quelle est votre humeur ?", Modifier.padding(horizontal = 16.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            if (vm.history.isEmpty() && vm.favs.isEmpty()) "Des films et séries choisis selon votre humeur"
            else "Des films et séries choisis selon votre humeur et vos goûts",
            Modifier.padding(horizontal = 16.dp), color = Color.Gray, fontSize = 12.sp,
        )
        LazyRow(
            Modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Mood.entries) { m ->
                val sel = m == mood
                FilterChip(
                    selected = sel, onClick = { vm.pickMood(if (sel) null else m) },
                    label = { Text("${m.emoji}  ${m.label}", fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = m.color.copy(alpha = 0.25f), selectedLabelColor = Color.White),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = sel, borderColor = Color.DarkGray, selectedBorderColor = m.color),
                )
            }
        }
        val p = picks
        when {
            mood == null || p == null -> {}
            p.second.isNotEmpty() -> PosterRow(p.first.title, p.second.filter { it.key !in vm.hidden }, onClick = onClick) // masqué : retiré sans attendre
            p.first == mood -> Text(
                "Aucun titre de votre catalogue ne correspond à cette humeur pour le moment.",
                Modifier.padding(16.dp, 12.dp), color = Color.Gray, fontSize = 13.sp,
            )
        }
    }
}
