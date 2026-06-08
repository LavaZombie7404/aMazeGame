package com.lavazombie.amazegame.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lavazombie.amazegame.PlayerStore
import com.lavazombie.amazegame.ui.shapes.listShapeNames
import com.lavazombie.amazegame.ui.skins.Skin
import com.lavazombie.amazegame.ui.skins.listSkins

private const val SHAPE_DEFAULT_LABEL = "(skin default)"

/**
 * Skin + per-shape override picker. Mirrors `web/src/main.ts::populateSettings`
 * + the matching dialog in `web/index.html`. Mirrors the web rule that the
 * skin row hides itself when there's only one skin available.
 */
@Composable
fun SettingsDialog(
    currentSkinId: String,
    characterOverride: String?,
    startOverride: String?,
    goalOverride: String?,
    legacyMovement: Boolean,
    onSkinChange: (String) -> Unit,
    onShapeChange: (PlayerStore.ShapeSlot, String?) -> Unit,
    onLegacyMovementChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val skins = listSkins()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (skins.size > 1) {
                    SkinPicker(
                        skins = skins,
                        current = currentSkinId,
                        onChange = onSkinChange,
                    )
                }
                ShapePicker(
                    label = "Character shape",
                    current = characterOverride,
                    onChange = { onShapeChange(PlayerStore.ShapeSlot.Character, it) },
                )
                ShapePicker(
                    label = "Start marker",
                    current = startOverride,
                    onChange = { onShapeChange(PlayerStore.ShapeSlot.Start, it) },
                )
                ShapePicker(
                    label = "Goal marker",
                    current = goalOverride,
                    onChange = { onShapeChange(PlayerStore.ShapeSlot.Goal, it) },
                )
                LegacyMovementToggle(
                    enabled = legacyMovement,
                    onChange = onLegacyMovementChange,
                )
            }
        },
    )
}

@Composable
private fun LegacyMovementToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Stop at every corner",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Old movement: confirm every turn",
                fontSize = 11.sp,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkinPicker(skins: List<Skin>, current: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = skins.firstOrNull { it.id == current } ?: skins.first()
    Column {
        Text("Skin", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        ExposedDropdownMenuBox(
            expanded = open,
            onExpandedChange = { open = it },
        ) {
            OutlinedTextField(
                value = active.name,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                skins.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name) },
                        onClick = {
                            open = false
                            onChange(s.id)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShapePicker(label: String, current: String?, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val displayed = current ?: SHAPE_DEFAULT_LABEL
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = displayed,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                DropdownMenuItem(
                    text = { Text(SHAPE_DEFAULT_LABEL) },
                    onClick = {
                        open = false
                        onChange(null)
                    },
                )
                listShapeNames().forEach { n ->
                    DropdownMenuItem(
                        text = { Text(n) },
                        onClick = {
                            open = false
                            onChange(n)
                        },
                    )
                }
            }
        }
    }
}
