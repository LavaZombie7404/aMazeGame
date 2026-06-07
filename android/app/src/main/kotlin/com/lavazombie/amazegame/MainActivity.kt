package com.lavazombie.amazegame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var game: GameRuntime
    private lateinit var puppet: PuppetServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Load the shared Rust core via WAMR (JNI bridge in cpp/).
        game = GameRuntime(applicationContext)
        game.boot()

        // 2. Stand up the puppet HTTP server so Claude Code can drive the
        // game over adb forward. The same kind of CDP-style automation we
        // already use against Chrome on the phone.
        puppet = PuppetServer(game, port = 8088)
        puppet.start(ioScope)

        // 3. Native Compose UI — game canvas + HUD.
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MazeScreen(game)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        puppet.stop()
        game.shutdown()
    }
}

@Composable
private fun MazeScreen(game: GameRuntime) {
    // Placeholder shell; the proper Compose Canvas renderer lives in
    // MazeRenderer.kt and is wired in the next iteration.
    com.lavazombie.amazegame.ui.MazeCanvas(game = game)
}
