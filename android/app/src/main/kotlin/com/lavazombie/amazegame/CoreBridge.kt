package com.lavazombie.amazegame

import android.content.Context

/**
 * Kotlin-side façade for the WAMR-hosted Rust core. Mirrors the C ABI in
 * `core/src/lib.rs`. One `CoreBridge` owns one WAMR `wasm_module_inst_t`
 * pointer (the `nativeHandle`); every Game instance is itself a pointer
 * inside that WASM linear memory.
 *
 * The actual WASM bytes are packaged as `assets/core.wasm`. The JNI side
 * (`cpp/core_bridge.cc`) reads them via Android's asset manager on first
 * use, so we don't materialise the file on disk.
 */
class CoreBridge(private val context: Context) {

    // Native handle for the WAMR module instance — populated by init().
    private var nativeHandle: Long = 0L

    init {
        System.loadLibrary("amaze_native")
    }

    fun init() {
        require(nativeHandle == 0L) { "CoreBridge already initialised" }
        nativeHandle = nativeInit(context.assets)
        check(nativeHandle != 0L) { "WAMR init failed" }
        val abi = nativeAbiVersion(nativeHandle)
        check(abi == EXPECTED_ABI_VERSION) {
            "core.wasm ABI $abi does not match expected $EXPECTED_ABI_VERSION"
        }
    }

    fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    // ---- Game lifecycle ----

    fun gameNew(size: Int, seed: Int): Long =
        nativeGameNew(nativeHandle, size, seed)

    /**
     * Like [gameNew] but `weave = true` opts the freshly-generated maze
     * into the weave/bridges post-process. Mirrors `core_game_new_ext`.
     */
    fun gameNewExt(size: Int, seed: Int, weave: Boolean): Long =
        nativeGameNewExt(nativeHandle, size, seed, if (weave) 1 else 0)

    fun gameDrop(game: Long) {
        nativeGameDrop(nativeHandle, game)
    }

    // ---- Maze ----

    fun mazeSize(game: Long): Int = nativeMazeSize(nativeHandle, game)
    fun mazeStart(game: Long): IntArray = nativeMazeStart(nativeHandle, game)
    fun mazeGoal(game: Long): IntArray = nativeMazeGoal(nativeHandle, game)
    /** Returns a copy of the wall mask (length = size * size). */
    fun mazeWalls(game: Long): ByteArray = nativeMazeWalls(nativeHandle, game)
    fun mazeHash(game: Long): ByteArray = nativeMazeHash(nativeHandle, game)

    // ---- Step + control ----

    /** Returns flags; bit 0 = reached goal. */
    fun step(game: Long, dtMs: Int): Int =
        nativeStep(nativeHandle, game, dtMs)

    fun queueDirection(game: Long, dir: Int) {
        nativeQueueDirection(nativeHandle, game, dir)
    }

    fun setLegacyMovement(game: Long, value: Boolean) {
        nativeSetLegacyMovement(nativeHandle, game, if (value) 1 else 0)
    }

    /** Reset the dot to the maze's start cell without regenerating walls. */
    fun resetPlayer(game: Long) {
        nativeResetPlayer(nativeHandle, game)
    }

    /**
     * Install an additional wall overlay for collision decisions only.
     * Renderers never see it — used by auto mode to seal everything but the
     * solution path. Pass null (or an empty array) to clear.
     */
    fun setExtraWalls(game: Long, walls: ByteArray?) {
        nativeSetExtraWalls(nativeHandle, game, walls)
    }

    fun playerRender(game: Long): FloatArray =
        nativePlayerRender(nativeHandle, game)

    fun playerCell(game: Long): IntArray =
        nativePlayerCell(nativeHandle, game)

    /** -1 if the dot is idle, 0..3 (N/E/S/W) otherwise. */
    fun playerDir(game: Long): Int = nativePlayerDir(nativeHandle, game)

    fun visited(game: Long): IntArray = nativeVisited(nativeHandle, game)

    // ---- JNI -----

    private external fun nativeInit(assets: android.content.res.AssetManager): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeAbiVersion(handle: Long): Int

    private external fun nativeGameNew(handle: Long, size: Int, seed: Int): Long
    private external fun nativeGameNewExt(handle: Long, size: Int, seed: Int, weave: Int): Long
    private external fun nativeGameDrop(handle: Long, game: Long)

    private external fun nativeMazeSize(handle: Long, game: Long): Int
    private external fun nativeMazeStart(handle: Long, game: Long): IntArray
    private external fun nativeMazeGoal(handle: Long, game: Long): IntArray
    private external fun nativeMazeWalls(handle: Long, game: Long): ByteArray
    private external fun nativeMazeHash(handle: Long, game: Long): ByteArray

    private external fun nativeStep(handle: Long, game: Long, dtMs: Int): Int
    private external fun nativeQueueDirection(handle: Long, game: Long, dir: Int)
    private external fun nativeSetLegacyMovement(handle: Long, game: Long, value: Int)
    private external fun nativeSetExtraWalls(handle: Long, game: Long, walls: ByteArray?)
    private external fun nativeResetPlayer(handle: Long, game: Long)
    private external fun nativePlayerRender(handle: Long, game: Long): FloatArray
    private external fun nativePlayerCell(handle: Long, game: Long): IntArray
    private external fun nativePlayerDir(handle: Long, game: Long): Int
    private external fun nativeVisited(handle: Long, game: Long): IntArray

    companion object {
        const val EXPECTED_ABI_VERSION = 1
    }
}
