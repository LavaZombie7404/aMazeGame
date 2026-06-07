package com.lavazombie.amazegame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lavazombie.amazegame.ui.MainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var game: GameRuntime
    private lateinit var puppet: PuppetServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        game = GameRuntime(applicationContext)
        game.boot()

        puppet = PuppetServer(game, port = 8088)
        puppet.start(ioScope)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(game)
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
