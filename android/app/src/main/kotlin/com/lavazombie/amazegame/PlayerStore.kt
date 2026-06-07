package com.lavazombie.amazegame

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight wrapper around SharedPreferences for the scaffold. The web
 * app uses sql.js + IndexedDB for richer queries; on Android we'll switch
 * to Room (or sql.js compiled via WAMR, identical to the web) when stats
 * grow beyond name + counter.
 */
class PlayerStore(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("amazegame", Context.MODE_PRIVATE)

    var name: String?
        get() = sp.getString(KEY_NAME, null)
        set(value) {
            sp.edit().putString(KEY_NAME, value).apply()
        }

    var mazesCompleted: Int
        get() = sp.getInt(KEY_SCORE, 0)
        set(value) {
            sp.edit().putInt(KEY_SCORE, value).apply()
        }

    fun incrementMazesCompleted(): Int {
        val next = mazesCompleted + 1
        mazesCompleted = next
        return next
    }

    companion object {
        private const val KEY_NAME = "player_name"
        private const val KEY_SCORE = "mazes_completed"
    }
}
