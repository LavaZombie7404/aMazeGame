package com.lavazombie.amazegame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lavazombie.amazegame.GameRuntime

private val PAPER = Color(0xFFF7F3E8)
private val PAPER_HUD = Color(0xD9F7F3E8)
private val INK = Color(0xFF1D2433)
private val INK_SOFT = Color(0xFF2A3450)

/**
 * Full Android screen: HUD + math-notebook canvas + bottom hint.
 * Mirrors `web/index.html` + `web/src/styles.css` HUD layout.
 */
@Composable
fun MainScreen(game: GameRuntime) {
    val player by game.player.collectAsStateWithLifecycle()
    val needsName = player.name.isNullOrBlank()

    if (needsName) {
        NameDialog(onSubmit = { game.setPlayerName(it) })
    }

    Box(Modifier.fillMaxSize().background(PAPER)) {
        Column(Modifier.fillMaxSize()) {
            Hud(
                playerName = player.name ?: "—",
                mazesSolved = player.mazesCompleted,
                onReset = { game.reset() },
                onSettings = { /* future: skin/shape picker dialog */ },
            )
            // The canvas occupies the rest of the screen.
            Box(Modifier.weight(1f)) {
                MazeCanvas(game = game, modifier = Modifier.fillMaxSize())
            }
            HintBar()
        }
    }
}

@Composable
private fun Hud(
    playerName: String,
    mazesSolved: Int,
    onReset: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PAPER_HUD)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "PLAYER",
                color = INK_SOFT.copy(alpha = 0.65f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                playerName,
                color = INK,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudIconButton(symbol = "↻", contentDescription = "New maze", onClick = onReset)
            Spacer(Modifier.width(8.dp))
            HudIconButton(symbol = "⚙", contentDescription = "Settings", onClick = onSettings)
        }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                "MAZES SOLVED",
                color = INK_SOFT.copy(alpha = 0.65f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                mazesSolved.toString(),
                color = INK,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HudIconButton(symbol: String, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .border(BorderStroke(1.5.dp, INK_SOFT), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = INK, fontSize = 20.sp)
        // contentDescription consumed via semantics in clickable's onClickLabel;
        // for the scaffold we accept the warning rather than wire Modifier.semantics.
        @Suppress("UNUSED_PARAMETER") val cd = contentDescription
    }
}

@Composable
private fun HintBar() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PAPER_HUD)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "swipe ↑ ↓ ← → to steer",
            color = INK.copy(alpha = 0.6f),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun NameDialog(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { /* required; must take a name */ },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(text.ifBlank { "Player" }.take(24)) },
            ) { Text("Start") }
        },
        title = { Text("Who's playing?") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(24) },
                singleLine = true,
                placeholder = { Text("your name") },
                textStyle = TextStyle(color = INK),
            )
        },
    )
}
