package com.cineo.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsDialog(vm: AppVm, onDismiss: () -> Unit) {
    var pinMode by remember { mutableStateOf<String?>(null) } // "unlock" | "set"
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card,
        title = { Text("Réglages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Compte", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(vm.creds?.label.orEmpty(), color = Color.LightGray, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.loadAll(); onDismiss() }) { Text("Actualiser") }
                    OutlinedButton(onClick = { onDismiss(); vm.logout() }) { Text("Changer de compte") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Contrôle parental", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Afficher le contenu adulte", Modifier.weight(1f))
                    Switch(checked = vm.showAdult, onCheckedChange = { on ->
                        if (!on) vm.lockAdult() else pinMode = if (vm.pin == null) "set" else "unlock"
                    })
                }
                Text(if (vm.pin == null) "Aucun code PIN défini" else "Protégé par code PIN", fontSize = 12.sp, color = Color.Gray)
                TextButton(onClick = { pinMode = "set" }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (vm.pin == null) "Définir un code PIN" else "Changer le code PIN")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
    pinMode?.let { mode ->
        PinDialog(
            title = when {
                mode == "unlock" -> "Code PIN"
                mode == "set" && vm.pin != null && !vm.showAdult -> "Code PIN actuel"
                else -> "Nouveau code PIN (4 chiffres)"
            },
            // Changer un PIN existant exige l'ancien : on vérifie d'abord, puis on demande le nouveau
            onDone = { code ->
                when {
                    mode == "unlock" -> vm.unlockAdult(code).also { if (it) pinMode = null }
                    mode == "set" && vm.pin != null && !vm.showAdult -> (code == vm.pin).also { if (it) pinMode = "new" }
                    else -> { vm.changePin(code); vm.unlockAdult(code); pinMode = null; true }
                }
            },
            onDismiss = { pinMode = null },
        )
    }
}

/** onDone renvoie false si le code est faux. */
@Composable
fun PinDialog(title: String, onDone: (String) -> Boolean, onDismiss: () -> Unit) {
    var code by remember(title) { mutableStateOf("") }
    var wrong by remember(title) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card,
        title = { Text(title) },
        text = {
            OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(4); wrong = false },
                singleLine = true, isError = wrong, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                supportingText = { if (wrong) Text("Code incorrect") })
        },
        confirmButton = { TextButton(onClick = { wrong = !onDone(code); if (!wrong) code = "" }, enabled = code.length == 4) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
