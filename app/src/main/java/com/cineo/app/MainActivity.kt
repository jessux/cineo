package com.cineo.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.viewModels
import coil.compose.AsyncImage
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.draw.drawBehind

val Bg = Color(0xFF0E0D13)
val Card = Color(0xFF1C1A24)
val Accent = Color(0xFF8B6CFF)
val Gold = Color(0xFFFFB547)

class MainActivity : AppCompatActivity() {
    private val vm: AppVm by viewModels()
    override fun onResume() { super.onResume(); vm.resumeTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Cast.context(this) // démarre la découverte des Chromecast
        setContent {
            var splash by rememberSaveable { mutableStateOf(true) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg, secondary = Gold)) {
                App(vm)
                if (splash) SplashScreen { splash = false }
            }
        }
    }
}

@Composable
fun App(vm: AppVm) {
    if (vm.creds == null) { LoginScreen(vm); return }
    if (vm.profile == null) { ProfilesScreen(vm); return }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf<Pair<String, ImageVector>>(
        "Accueil" to Icons.Filled.Home, "Films" to Icons.Filled.Movie, "Séries" to Icons.Filled.Tv,
        "Direct" to Icons.Filled.LiveTv, "Recherche" to Icons.Filled.Search,
    )
    BackHandler(vm.detail == null && tab != 0) { tab = 0 }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF15131C)) {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, null) }, label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    vm.busy && vm.movies.isEmpty() && vm.live.isEmpty() ->
                        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    vm.movies.isEmpty() && vm.series.isEmpty() && vm.live.isEmpty() ->
                        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                            Text(vm.error ?: "Aucun contenu chargé", textAlign = TextAlign.Center)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { vm.loadAll() }) { Text("Réessayer") }
                            TextButton(onClick = { vm.logout() }) { Text("Changer de compte") }
                        }
                    else -> when (tab) {
                        0 -> HomeScreen(vm)
                        1 -> CatalogScreen(vm, "Films", vm.movieCats, vm.moviesByCat)
                        2 -> CatalogScreen(vm, "Séries", vm.seriesCats, vm.seriesByCat)
                        3 -> LiveScreen(vm)
                        else -> SearchScreen(vm)
                    }
                }
            }
        }
        vm.detail?.let { d ->
            BackHandler { vm.detail = null }
            DetailScreen(vm, d)
        }
        vm.pendingPlay?.let { act ->
            AlertDialog(
                onDismissRequest = { vm.pendingPlay = null }, containerColor = Card,
                icon = { Icon(Icons.Filled.Warning, null, tint = Gold) },
                title = { Text("Quelqu'un regarde déjà") },
                text = { Text("Connexions en cours : ${vm.conns.first} sur ${vm.conns.second} autorisée(s). " +
                    "Lancer la lecture peut couper l'autre écran ou être refusé par le serveur.") },
                confirmButton = { TextButton(onClick = { vm.pendingPlay = null; act() }) { Text("Regarder quand même") } },
                dismissButton = { TextButton(onClick = { vm.pendingPlay = null }) { Text("Annuler") } },
            )
        }
    }
}

// ---------- Connexion ----------
@Composable
fun LoginScreen(vm: AppVm) {
    var m3u by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(40)
        Spacer(Modifier.height(24.dp))
        if (vm.accounts.isNotEmpty()) {
            Text("Comptes enregistrés", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            vm.accounts.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(Card).tvFocus(RoundedCornerShape(8.dp))
                    .clickable(enabled = !vm.busy) { vm.login(a.server, a.user, a.pass, a.m3u) }.padding(14.dp, 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (a.m3u) Icons.Filled.List else Icons.Filled.LiveTv, null, tint = Gold)
                    Text(a.label, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.forgetAccount(a) }) { Icon(Icons.Filled.Close, "Oublier", tint = Color.Gray) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        TabRow(if (m3u) 1 else 0, containerColor = Bg) {
            Tab(!m3u, { m3u = false }, text = { Text("Xtream Codes") })
            Tab(m3u, { m3u = true }, text = { Text("Lien M3U") })
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(server, { server = it }, label = { Text(if (m3u) "URL de la playlist M3U" else "Serveur (http://hote:port)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        if (!m3u) {
            OutlinedTextField(user, { user = it }, label = { Text("Nom d'utilisateur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Mot de passe") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { vm.login(server, user, pass, m3u) }, enabled = !vm.busy && server.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(8.dp)) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Se connecter", fontWeight = FontWeight.Bold)
        }
        vm.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Color(0xFFFF6B6B), textAlign = TextAlign.Center) }
    }
}

@Composable
fun Logo(size: Int) = Text(
    "CINÉO", color = Gold, fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp,
)

// ---------- Accueil ----------
/** season = épisodes de la saison : ceux qui suivent `ep` s'enchaînent automatiquement. */
fun AppVm.play(ctx: android.content.Context, item: Item, ep: Episode? = null, season: List<Episode> = emptyList()) =
    guard { startPlay(ctx, item, ep, season) }

private fun AppVm.startPlay(ctx: android.content.Context, item: Item, ep: Episode?, season: List<Episode>) {
    val x = api ?: return
    recordPlay(item)
    if (ep != null) {
        val next = season.dropWhile { it.id != ep.id }.ifEmpty { listOf(ep) }
        PlayerActivity.start(ctx, next.map {
            PlayItem(x.url(it), "${item.name} – ${it.title}", it.image ?: item.image, listOf("$pid|EP:${it.id}", "$pid|${item.key}"), it.id)
        })
    } else PlayerActivity.start(ctx, listOf(PlayItem(x.url(item), item.name, item.image,
        if (item.kind == Kind.LIVE) emptyList() else listOf("$pid|${item.key}"))), item.kind == Kind.LIVE)
}

fun AppVm.playReplay(ctx: android.content.Context, item: Item, p: Prog) = guard {
    val x = api ?: return@guard
    PlayerActivity.start(ctx, listOf(PlayItem(x.replayUrl(item, p), "${item.name} – ${p.title}", item.image)))
}

@Composable
fun HomeScreen(vm: AppVm) {
    val reco = remember(vm.movies, vm.series, vm.history.size, vm.favs.size) { vm.recommendations() }
    val because = remember(vm.movies, vm.series, vm.history.firstOrNull()) { vm.becauseYouWatched() }
    val ctx = LocalContext.current
    val tick = vm.resumeTick
    val watched = vm.watched().take(20)
    val cont = remember(watched, tick) { watched.mapNotNull { i -> vm.resumeOf(ctx, i)?.let { i to it.progress } } }
    val recentMovies = remember(vm.movies) { vm.movies.sortedByDescending { it.added }.take(30) }
    val recentSeries = remember(vm.series) { vm.series.sortedByDescending { it.added }.take(30) }
    val favs = vm.favItems()
    var settings by remember { mutableStateOf(false) }
    if (settings) SettingsDialog(vm) { settings = false }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(24); Spacer(Modifier.weight(1f))
                vm.profile?.let { Avatar(it, 30.dp, Modifier.padding(end = 6.dp).clickable { vm.selectProfile(null) }) }
                CastButton()
                IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, "Réglages") }
            }
        }
        if (reco.isNotEmpty()) item { HeroCarousel(vm, reco.take(8), if (vm.history.isEmpty()) "À la une" else "Recommandé pour vous") }
        if (cont.isNotEmpty()) item {
            val prog = cont.toMap()
            PosterRow("Continuer à regarder", cont.map { it.first }, { prog[it] }) { if (it.kind == Kind.MOVIE) vm.play(ctx, it) else vm.detail = it }
        }
        if (watched.isNotEmpty()) item { PosterRow("Récemment regardés", watched) { vm.detail = it } }
        if (favs.isNotEmpty()) item { PosterRow("Ma liste", favs) { vm.detail = it } }
        if (reco.size > 8) item { PosterRow("Sélectionnés pour vous", reco.drop(8)) { vm.detail = it } }
        because?.let { (src, list) -> item { PosterRow("Parce que vous avez regardé ${src.name}", list) { vm.detail = it } } }
        item { PosterRow("Films ajoutés récemment", recentMovies) { vm.detail = it } }
        item { PosterRow("Séries récentes", recentSeries) { vm.detail = it } }
        items(vm.movieCats.take(10), key = { "m" + it.id }) { c -> PosterRow(c.name, vm.moviesByCat[c.id].orEmpty()) { vm.detail = it } }
        items(vm.seriesCats.take(10), key = { "s" + it.id }) { c -> PosterRow(c.name, vm.seriesByCat[c.id].orEmpty()) { vm.detail = it } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun HeroCarousel(vm: AppVm, list: List<Item>, label: String) {
    val ctx = LocalContext.current
    val pager = rememberPagerState { list.size }
    LaunchedEffect(pager, list) {
        while (true) {
            delay(6000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % list.size, animationSpec = tween(700))
        }
    }
    Box(Modifier.fillMaxWidth().height(480.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val h = list[page]
            val off = (pager.currentPage - page) + pager.currentPageOffsetFraction
            Box(Modifier.fillMaxSize().clickable { vm.detail = h }) {
                AsyncImage(thumb(h.image, 780), null, Modifier.fillMaxSize().graphicsLayer {
                    translationX = off * size.width * 0.4f; val sc = 1.1f - 0.1f * (1 - kotlin.math.abs(off)).coerceIn(0f, 1f); scaleX = sc; scaleY = sc
                }, contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Bg.copy(alpha = 0.85f), Bg))))
                Column(Modifier.align(Alignment.BottomCenter).padding(16.dp, 0.dp, 16.dp, 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label.uppercase(), color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(h.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(if (h.kind == Kind.MOVIE) "Film" else "Série", h.rating.ifBlank { null }?.let { "★ $it" }).joinToString(" · "),
                        color = Color.LightGray, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { if (h.kind == Kind.MOVIE) vm.play(ctx, h) else vm.detail = h },
                            colors = ButtonDefaults.buttonColors(Color.White, Color.Black), shape = RoundedCornerShape(6.dp)) {
                            Icon(Icons.Filled.PlayArrow, null); Text("Lecture", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(onClick = { vm.detail = h }, shape = RoundedCornerShape(6.dp)) {
                            Icon(Icons.Filled.Info, null); Spacer(Modifier.width(4.dp)); Text("Infos")
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(list.size) { i ->
                val sel = i == pager.currentPage
                Box(Modifier.height(4.dp).width(if (sel) 18.dp else 6.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (sel) Gold else Color.Gray.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
fun PosterRow(title: String, items: List<Item>, progress: (Item) -> Float? = { null }, onClick: (Item) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { Poster(it, Modifier.width(112.dp), progress(it)) { onClick(it) } }
        }
    }
}

@Composable
fun Poster(item: Item, modifier: Modifier = Modifier, progress: Float? = null, onClick: () -> Unit) {
    var state by remember(item.key) { mutableIntStateOf(if (item.image == null) 2 else 0) } // 0 chargement, 1 ok, 2 erreur
    Box(modifier.tvFocus().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(Card).clickable(onClick = onClick)) {
        when (state) {
            0 -> LoadingLogo(Modifier.fillMaxSize())
            2 -> Text(item.name, Modifier.align(Alignment.Center).padding(8.dp), fontSize = 12.sp, textAlign = TextAlign.Center, color = Color.LightGray)
        }
        if (item.image != null) AsyncImage(
            thumb(item.image, 342), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { state = 1 }, onError = { state = 2 },
        )
        progress?.let {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(it.coerceIn(0f, 1f)).background(Accent))
            }
        }
    }
}

/** Navigation à la télécommande (Android TV) : contour + léger zoom sur l'élément sélectionné. */
fun Modifier.tvFocus(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)): Modifier = composed {
    var f by remember { mutableStateOf(false) }
    this.onFocusChanged { f = it.isFocused }.scale(if (f) 1.06f else 1f).border(if (f) 3.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)
}

fun openUrl(ctx: android.content.Context, url: String) = runCatching {
    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
}

/** Les affiches TMDB arrivent en taille originale (plusieurs Mo) : on demande une version réduite. */
fun thumb(url: String?, w: Int): String? = url?.replace("/t/p/original/", "/t/p/w$w/")

/** Placeholder animé : reflet qui balaie + monogramme Cinéo qui pulse. */
@Composable
fun LoadingLogo(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "load")
    val x by tr.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val a by tr.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(modifier.drawBehind {
        val w = size.width
        drawRect(Brush.linearGradient(
            listOf(Card, Color(0xFF2A2735), Card),
            start = androidx.compose.ui.geometry.Offset(w * x - w * 0.5f, 0f),
            end = androidx.compose.ui.geometry.Offset(w * x + w * 0.5f, size.height),
        ))
    }, contentAlignment = Alignment.Center) {
        Text("C", Modifier.graphicsLayer { alpha = a; scaleX = 0.9f + a * 0.15f; scaleY = 0.9f + a * 0.15f },
            color = Gold, fontSize = 34.sp, fontWeight = FontWeight.Black)
    }
}

// ---------- Films / Séries ----------
@Composable
fun CatalogScreen(vm: AppVm, title: String, cats: List<Category>, byCat: Map<String, List<Item>>) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(title, Modifier.statusBarsPadding().padding(16.dp, 12.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        items(cats, key = { it.id }) { c -> PosterRow(c.name, byCat[c.id].orEmpty()) { vm.detail = it } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ---------- Direct ----------
@Composable
fun LiveScreen(vm: AppVm) {
    var cat by rememberSaveable { mutableStateOf(vm.liveCats.firstOrNull()?.id) }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp, 12.dp, 8.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TV en direct", Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            CastButton()
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.liveCats, key = { it.id }) { c ->
                FilterChip(selected = c.id == cat, onClick = { cat = c.id }, label = { Text(c.name) })
            }
        }
        Text("Appui long sur une chaîne : guide TV et replay", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp), fontSize = 11.sp, color = Color.Gray)
        ChannelGrid(vm, vm.liveByCat[cat].orEmpty()) { ch -> vm.play(ctx, ch) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelGrid(vm: AppVm, list: List<Item>, onClick: (Item) -> Unit) {
    var sheet by remember { mutableStateOf<Item?>(null) }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.key }) { ch ->
            LaunchedEffect(ch.id) { vm.loadNow(ch) }
            val now = vm.nowEpg[ch.id]?.firstOrNull { it.isNow }
            Column(Modifier.tvFocus(RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(Card)
                .combinedClickable(onClick = { onClick(ch) }, onLongClick = { sheet = ch }).padding(8.dp)) {
                AsyncImage(ch.image, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Fit)
                Text(ch.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (now != null) {
                    Text(now.title, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator({ now.progress }, Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp))
                }
                if (ch.archive) Text("Replay", fontSize = 10.sp, color = Gold)
            }
        }
    }
    sheet?.let { ChannelSheet(vm, it) { sheet = null } }
}

/** Guide du jour d'une chaîne ; les programmes passés sont rejouables si la chaîne a le replay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSheet(vm: AppVm, ch: Item, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var progs by remember(ch.id) { mutableStateOf<List<Prog>?>(null) }
    LaunchedEffect(ch.id) { progs = runCatching { vm.api!!.epg(ch, true) }.getOrDefault(emptyList()) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE) }
    val nowS = System.currentTimeMillis() / 1000
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card) {
        Text(ch.name, Modifier.padding(16.dp, 0.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Button(onClick = { onDismiss(); vm.play(ctx, ch) }, Modifier.padding(16.dp).fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, null); Text("Regarder en direct")
        }
        val list = progs
        when {
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            list.isEmpty() -> Text("Pas de guide TV pour cette chaîne", Modifier.padding(16.dp), color = Color.Gray)
            else -> {
                val state = rememberLazyListState(list.indexOfFirst { it.end > nowS }.coerceAtLeast(0))
                LazyColumn(state = state, modifier = Modifier.heightIn(max = 480.dp)) {
                    items(list) { p ->
                        val past = p.end <= nowS
                        val canReplay = past && ch.archive
                        Row(Modifier.fillMaxWidth().then(if (canReplay) Modifier.clickable { onDismiss(); vm.playReplay(ctx, ch, p) } else Modifier)
                            .background(if (p.isNow) Accent.copy(alpha = 0.15f) else Color.Transparent).padding(16.dp, 10.dp)) {
                            Text(fmt.format(java.util.Date(p.start * 1000)), Modifier.width(52.dp), color = if (p.isNow) Gold else Color.Gray, fontSize = 13.sp)
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontWeight = if (p.isNow) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp,
                                    color = if (past && !canReplay) Color.Gray else Color.White)
                                if (p.desc.isNotBlank()) Text(p.desc, fontSize = 12.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (canReplay) Icon(Icons.Filled.Replay, "Revoir", tint = Gold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

// ---------- Recherche ----------
@Composable
fun SearchScreen(vm: AppVm) {
    var q by rememberSaveable { mutableStateOf("") }
    val ctx = LocalContext.current
    val res = remember(q, vm.movies, vm.series) {
        if (q.length < 2) emptyList() else (vm.movies + vm.series).filter { it.name.contains(q, true) }.take(300)
    }
    val resLive = remember(q, vm.live) { if (q.length < 2) emptyList() else vm.live.filter { it.name.contains(q, true) }.take(100) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp), singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Films, séries, chaînes…") })
        LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(res, key = { it.key }) { Poster(it) { vm.detail = it } }
            items(resLive, key = { it.key }) { ch ->
                Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Card)
                    .clickable { vm.play(ctx, ch) }.padding(6.dp)) {
                    AsyncImage(ch.image, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Fit)
                    Text(ch.name, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- Fiche ----------
@Composable
fun DetailScreen(vm: AppVm, item: Item) {
    val ctx = LocalContext.current
    var info by remember(item.key) { mutableStateOf<Info?>(null) }
    var season by remember(item.key) { mutableStateOf<Int?>(null) }
    LaunchedEffect(item.key) {
        info = runCatching { if (item.kind == Kind.SERIES) vm.api!!.seriesInfo(item.id) else vm.api!!.vodInfo(item.id) }
            .getOrElse { Info(null, null, "") }
        season = info?.episodes?.keys?.firstOrNull()
    }
    val fav = item.key in vm.favs
    LazyColumn(Modifier.fillMaxSize().background(Bg)) {
        item {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                AsyncImage(thumb(info?.backdrop ?: item.image, 780), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent, Bg))))
                IconButton(onClick = { vm.detail = null }, Modifier.statusBarsPadding().padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                }
                CastButton(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(item.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val meta = listOfNotNull(info?.meta?.ifBlank { null }, item.rating.ifBlank { null }?.let { "★ $it" }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                val res = remember(item.key, vm.resumeTick) { vm.resumeOf(ctx, item) }
                val resEp = res?.let { r -> info?.episodes?.values?.flatten()?.firstOrNull { it.id == r.ep } }
                if (item.kind == Kind.MOVIE || resEp != null) {
                    Button(onClick = { vm.play(ctx, item, resEp) },
                        Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(Color.White, Color.Black)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(when {
                            resEp != null -> "Reprendre S${resEp.season} E${resEp.num}"
                            res != null -> "Reprendre"
                            else -> "Lecture"
                        }, fontWeight = FontWeight.Bold)
                    }
                    res?.let { LinearProgressIndicator({ it.progress }, Modifier.fillMaxWidth().padding(top = 6.dp)) }
                    Spacer(Modifier.height(8.dp))
                }
                FilledTonalButton(onClick = { vm.toggleFav(item) }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp)) {
                    Icon(if (fav) Icons.Filled.Check else Icons.Filled.Add, null); Spacer(Modifier.width(6.dp))
                    Text(if (fav) "Dans ma liste" else "Ma liste")
                }
                info?.plot?.let { Text(it, Modifier.padding(top = 14.dp), fontSize = 14.sp, color = Color(0xFFDDDDDD)) }
                info?.let { inf ->
                    if (inf.director.isNotBlank()) Text("Réalisation : ${inf.director}", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Color.Gray)
                    if (inf.cast.isNotBlank()) Text("Avec : ${inf.cast}", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.Gray, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (inf.trailer.isNotBlank()) OutlinedButton(onClick = { openUrl(ctx, "https://www.youtube.com/watch?v=${inf.trailer}") }) {
                            Icon(Icons.Filled.Movie, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Bande-annonce")
                        }
                        OutlinedButton(onClick = {
                            openUrl(ctx, "https://www.imdb.com/find/?q=" + java.net.URLEncoder.encode(item.name, "UTF-8"))
                        }) { Text("IMDb", color = Gold, fontWeight = FontWeight.Black) }
                    }
                }
                if (info == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 14.dp))
            }
        }
        val eps = info?.episodes.orEmpty()
        if (eps.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(eps.keys.toList()) { s -> FilterChip(selected = s == season, onClick = { season = s }, label = { Text("Saison $s") }) }
                }
            }
            items(eps[season].orEmpty(), key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(4.dp)).clickable { vm.play(ctx, item, e, eps[season].orEmpty()) }.padding(16.dp, 8.dp)) {
                    Box(Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Card), Alignment.Center) {
                        AsyncImage(e.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Icon(Icons.Filled.PlayCircleOutline, null, tint = Color.White)
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("${e.num}. ${e.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        e.plot?.let { Text(it, fontSize = 12.sp, color = Color.Gray, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
}
