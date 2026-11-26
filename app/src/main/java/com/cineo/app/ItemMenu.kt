package com.cineo.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Ouvre le menu d'actions d'un titre (fourni par l'activité) ; null : pas d'appui long. */
val LocalItemMenu = staticCompositionLocalOf<((Item) -> Unit)?> { null }

/**
 * Clic, et appui long qui ouvre le menu d'actions du titre : au doigt, ou bouton OK maintenu sur une télécommande
 * (cette version de Compose ne gère l'appui long qu'au toucher).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.itemClick(item: Item, onClick: () -> Unit): Modifier = composed {
    val menu = LocalItemMenu.current
    var held by remember { mutableStateOf(false) } // OK relâché après un appui long : ne pas ouvrir la fiche
    val remote = if (menu == null) Modifier else Modifier.onPreviewKeyEvent { e ->
        if ((e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyDown) {
            if (e.nativeKeyEvent.repeatCount == 0) held = false
            else if (e.nativeKeyEvent.isLongPress) { held = true; menu(item) }
        }
        false
    }
    remote.combinedClickable(onLongClickLabel = "Plus d'actions", onLongClick = menu?.let { { it(item) } }) {
        if (held) held = false else onClick()
    }
}

/** Menu d'appui long : lecture, fiche, Ma liste, retrait de « Continuer à regarder », de l'historique ou des suggestions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemMenu(vm: AppVm, item: Item, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val res = remember(item.key, vm.resumeTick) { vm.resumeOf(ctx, item) }
    val movie = item.kind == Kind.MOVIE
    val fav = item.key in vm.favs
    val seen = item.key in vm.history
    val hidden = item.key in vm.hidden
    // L'action s'applique tout de suite, puis le menu se referme en glissant
    fun run(action: () -> Unit) { action(); scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Card) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                thumb(item.image, 342), null, // même taille que les affiches : déjà en cache
                Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).background(Bg), contentScale = ContentScale.Crop,
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text(item.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (movie) "Film" else "Série", item.rating.ifBlank { null }?.let { "★ $it" }).joinToString(" · "),
                    color = Color.Gray, fontSize = 13.sp)
                res?.let { LinearProgressIndicator({ it.progress }, Modifier.width(140.dp).padding(top = 8.dp)) }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        // La lecture quitte l'écran : menu fermé sans attendre l'animation
        if (movie) MenuAction(Icons.Filled.PlayArrow, if (res != null) "Reprendre" else "Lecture") { onDismiss(); vm.play(ctx, item) }
        MenuAction(Icons.Filled.Info, if (movie) "Plus d'infos" else "Épisodes et infos") { run { vm.detail = item } }
        MenuAction(if (fav) Icons.Filled.Check else Icons.Filled.Add, if (fav) "Retirer de ma liste" else "Ajouter à ma liste") {
            run { vm.toggleFavUndoable(item) }
        }
        if (res != null) MenuAction(Icons.Filled.RemoveCircleOutline, "Retirer de « Continuer à regarder »", "Oublie la progression de lecture") {
            run { vm.forgetResume(item) }
        }
        when {
            seen -> MenuAction(Icons.Filled.History, "Retirer de l'historique", "Ne comptera plus dans vos recommandations") { run { vm.forgetPlay(item) } }
            hidden -> MenuAction(Icons.Filled.Visibility, "Me le proposer à nouveau") { run { vm.toggleHidden(item) } }
            else -> MenuAction(Icons.Filled.VisibilityOff, "Ne plus me le proposer", "Masqué des suggestions de l'accueil") { run { vm.toggleHidden(item) } }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: String, hint: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.LightGray)
        Column(Modifier.padding(start = 18.dp)) {
            Text(label, fontSize = 15.sp)
            hint?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
        }
    }
}
