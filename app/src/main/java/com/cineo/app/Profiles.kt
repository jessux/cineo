package com.cineo.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val AvatarColors = listOf(
    Color(0xFF8B6CFF) to Color(0xFF5B3FD9), Color(0xFFFFB547) to Color(0xFFE07A1F), Color(0xFF3DD6B0) to Color(0xFF1F8F8A),
    Color(0xFFFF6B8B) to Color(0xFFC2335A), Color(0xFF4FA3FF) to Color(0xFF2155C9), Color(0xFF9BE15D) to Color(0xFF3E9B2F),
)

@Composable
fun Avatar(p: Profile, size: Dp, modifier: Modifier = Modifier) {
    val (a, b) = AvatarColors[p.color.mod(AvatarColors.size)]
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 6)).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Text(p.name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun ProfilesScreen(vm: AppVm) {
    var edit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Logo(26)
        Spacer(Modifier.height(32.dp))
        Text(if (edit) "Gérer les profils" else "Qui regarde ?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(120.dp), Modifier.widthIn(max = 560.dp).weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(vm.profiles, key = { it.id }) { p ->
                var pressed by remember { mutableStateOf(false) }
                val sc by animateFloatAsState(if (pressed) 1.08f else 1f, label = "s")
                Column(
                    Modifier.clickable {
                        if (edit) editing = p else { pressed = true; vm.selectProfile(p) }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Avatar(p, 104.dp, Modifier.scale(sc))
                        if (edit) Box(Modifier.size(104.dp).clip(RoundedCornerShape(17.dp)).background(Color(0x99000000)), Alignment.Center) {
                            Icon(Icons.Filled.Edit, "Modifier", tint = Color.White)
                        }
                    }
                    Text(p.name, Modifier.padding(top = 8.dp), color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (vm.profiles.size < 6) item {
                Column(Modifier.clickable { editing = Profile("p" + System.currentTimeMillis(), "", vm.profiles.size) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(104.dp).clip(RoundedCornerShape(17.dp)).border(2.dp, Color.DarkGray, RoundedCornerShape(17.dp)), Alignment.Center) {
                        Icon(Icons.Filled.Add, null, Modifier.size(48.dp), tint = Color.Gray)
                    }
                    Text("Ajouter", Modifier.padding(top = 8.dp), color = Color.Gray)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { edit = !edit }, shape = RoundedCornerShape(4.dp)) {
            Text(if (edit) "Terminé" else "Gérer les profils", color = Color.LightGray)
        }
    }
    editing?.let { p ->
        ProfileDialog(p, isNew = vm.profiles.none { it.id == p.id }, canDelete = vm.profiles.size > 1,
            onSave = { vm.saveProfile(it); editing = null },
            onDelete = { vm.deleteProfile(p); editing = null },
            onDismiss = { editing = null })
    }
}

@Composable
fun ProfileDialog(p: Profile, isNew: Boolean, canDelete: Boolean, onSave: (Profile) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var color by remember { mutableIntStateOf(p.color.mod(AvatarColors.size)) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card,
        title = { Text(if (isNew) "Nouveau profil" else "Modifier le profil") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(p.copy(name = name.ifBlank { "?" }, color = color), 80.dp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AvatarColors.forEachIndexed { i, (c, _) ->
                        Box(Modifier.size(30.dp).clip(CircleShape).background(c)
                            .border(if (i == color) 3.dp else 0.dp, Color.White, CircleShape).clickable { color = i })
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("Nom") }, singleLine = true)
                if (!isNew && canDelete) TextButton(onClick = onDelete) { Text("Supprimer ce profil", color = Color(0xFFFF6B6B)) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(p.copy(name = name.trim(), color = color)) }, enabled = name.isNotBlank()) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

