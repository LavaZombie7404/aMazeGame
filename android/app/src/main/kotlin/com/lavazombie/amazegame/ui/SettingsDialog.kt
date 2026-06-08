package com.lavazombie.amazegame.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f)

private val COLOR_PALETTE = listOf(
    "#c83b3b", // red (math-textbook character default)
    "#2f6fb8", // blue (math-textbook goal default)
    "#2a3450", // ink soft (math-textbook start default)
    "#2e8b57", // green
    "#ffd700", // gold
    "#ff8c00", // orange
    "#8e4fb0", // purple
    "#e91e63", // pink
    "#00bcd4", // cyan
    "#1d2433", // ink (near-black)
)

private fun speedLabel(v: Float): String = when (v) {
    1.0f -> "1x (default)"
    else -> {
        // Trim trailing ".0" so 2.0 shows as "2x".
        val s = if (v % 1f == 0f) v.toInt().toString() else v.toString()
        "${s}x"
    }
}

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
    characterColor: String?,
    startColor: String?,
    goalColor: String?,
    legacyMovement: Boolean,
    speedMultiplier: Float,
    onSkinChange: (String) -> Unit,
    onShapeChange: (PlayerStore.ShapeSlot, String?) -> Unit,
    onColorChange: (PlayerStore.ShapeSlot, String?) -> Unit,
    onLegacyMovementChange: (Boolean) -> Unit,
    onSpeedMultiplierChange: (Float) -> Unit,
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                ColorPicker(
                    label = "Character color",
                    current = characterColor,
                    onChange = { onColorChange(PlayerStore.ShapeSlot.Character, it) },
                )
                ShapePicker(
                    label = "Start marker",
                    current = startOverride,
                    onChange = { onShapeChange(PlayerStore.ShapeSlot.Start, it) },
                )
                ColorPicker(
                    label = "Start color",
                    current = startColor,
                    onChange = { onColorChange(PlayerStore.ShapeSlot.Start, it) },
                )
                ShapePicker(
                    label = "Goal marker",
                    current = goalOverride,
                    onChange = { onShapeChange(PlayerStore.ShapeSlot.Goal, it) },
                )
                ColorPicker(
                    label = "Goal color",
                    current = goalColor,
                    onChange = { onColorChange(PlayerStore.ShapeSlot.Goal, it) },
                )
                LegacyMovementToggle(
                    enabled = legacyMovement,
                    onChange = onLegacyMovementChange,
                )
                SpeedPicker(
                    current = speedMultiplier,
                    onChange = onSpeedMultiplierChange,
                )
            }
        },
    )
}

@Composable
private fun ColorPicker(label: String, current: String?, onChange: (String?) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Use default" swatch — neutral grey with a slash, clears the override.
            ColorSwatch(
                color = Color(0xFFE0E0E0),
                selected = current == null,
                showSlash = true,
                onClick = { onChange(null) },
            )
            COLOR_PALETTE.forEach { hex ->
                ColorSwatch(
                    color = Color(android.graphics.Color.parseColor(hex)),
                    selected = current?.equals(hex, ignoreCase = true) == true,
                    onClick = { onChange(hex) },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    showSlash: Boolean = false,
    onClick: () -> Unit,
) {
    val borderWidth = if (selected) 2.5.dp else 1.dp
    val borderColor = if (selected) Color(0xFF1D2433) else Color(0xFF888888)
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(BorderStroke(borderWidth, borderColor), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (showSlash) {
            Text("/", color = Color(0xFF555555), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SpeedPicker(current: Float, onChange: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = SPEED_OPTIONS.minByOrNull { kotlin.math.abs(it - current) }
        ?: 1.0f
    Column {
        Text("Speed", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Box(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    speedLabel(active),
                    fontSize = 16.sp,
                    color = Color(0xFF1D2433),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                SPEED_OPTIONS.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(speedLabel(v)) },
                        onClick = {
                            open = false
                            onChange(v)
                        },
                    )
                }
            }
        }
    }
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
