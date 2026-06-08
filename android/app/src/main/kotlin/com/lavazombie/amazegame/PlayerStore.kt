package com.lavazombie.amazegame

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed persistence for the player record. Mirrors
 * `web/src/storage.ts`'s `player` table fields (name, mazes_completed,
 * skin_id, character_shape, start_shape, goal_shape).
 *
 * Will switch to Room (or the same sql.js-via-WAMR path the web uses) when
 * stats grow beyond this; the API surface is intentionally tiny.
 */
class PlayerStore(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("amazegame", Context.MODE_PRIVATE)

    var name: String?
        get() = sp.getString(KEY_NAME, null)
        set(value) { sp.edit().putString(KEY_NAME, value).apply() }

    var mazesCompleted: Int
        get() = sp.getInt(KEY_SCORE, 0)
        set(value) { sp.edit().putInt(KEY_SCORE, value).apply() }

    var skinId: String
        get() = sp.getString(KEY_SKIN, null) ?: "math-textbook"
        set(value) { sp.edit().putString(KEY_SKIN, value).apply() }

    var legacyMovement: Boolean
        get() = sp.getBoolean(KEY_LEGACY_MOVEMENT, false)
        set(value) { sp.edit().putBoolean(KEY_LEGACY_MOVEMENT, value).apply() }

    var speedMultiplier: Float
        get() = sp.getFloat(KEY_SPEED_MULTIPLIER, 1.0f)
        set(value) { sp.edit().putFloat(KEY_SPEED_MULTIPLIER, value).apply() }

    var autoMode: Boolean
        get() = sp.getBoolean(KEY_AUTO_MODE, false)
        set(value) { sp.edit().putBoolean(KEY_AUTO_MODE, value).apply() }

    fun shapeOverride(slot: ShapeSlot): String? = sp.getString(slot.key, null)
    fun setShapeOverride(slot: ShapeSlot, name: String?) {
        sp.edit().apply {
            if (name == null) remove(slot.key) else putString(slot.key, name)
        }.apply()
    }

    fun colorOverride(slot: ShapeSlot): String? = sp.getString(slot.colorKey, null)
    fun setColorOverride(slot: ShapeSlot, hex: String?) {
        sp.edit().apply {
            if (hex == null) remove(slot.colorKey) else putString(slot.colorKey, hex)
        }.apply()
    }

    fun incrementMazesCompleted(): Int {
        val next = mazesCompleted + 1
        mazesCompleted = next
        return next
    }

    enum class ShapeSlot(val key: String, val colorKey: String) {
        Character("shape_character", "color_character"),
        Start("shape_start", "color_start"),
        Goal("shape_goal", "color_goal"),
    }

    companion object {
        private const val KEY_NAME = "player_name"
        private const val KEY_SCORE = "mazes_completed"
        private const val KEY_SKIN = "skin_id"
        private const val KEY_LEGACY_MOVEMENT = "legacy_movement"
        private const val KEY_SPEED_MULTIPLIER = "speed_multiplier"
        private const val KEY_AUTO_MODE = "auto_mode"
    }
}
