// JNI bridge that loads amaze-core.wasm into WAMR (WebAssembly Micro Runtime)
// and exposes the C ABI exports declared in `core/src/lib.rs` to Kotlin via
// `CoreBridge.kt`.
//
// One CoreBridge -> one wasm_module_inst_t. The Kotlin side opens
// `assets/core.wasm` via AAssetManager, hands the bytes here, and the JNI
// functions below translate Kotlin arguments to WAMR's wasm_runtime_call_wasm
// calls. WASM pointers crossing the boundary are u32 offsets into the WASM
// linear memory; reads/writes go through wasm_runtime_addr_app_to_native to
// turn an offset into a host pointer the JNI code can memcpy from / to.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>

#include "wasm_export.h"

#define LOG_TAG "amaze-core"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Module {
    wasm_module_t module = nullptr;
    wasm_module_inst_t instance = nullptr;
    wasm_exec_env_t exec_env = nullptr;
    std::vector<uint8_t> bytes;
};

std::vector<uint8_t> readAsset(JNIEnv* env, jobject assetManagerObj, const char* name) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManagerObj);
    AAsset* a = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
    if (!a) {
        LOGE("asset %s not found", name);
        return {};
    }
    size_t len = AAsset_getLength(a);
    std::vector<uint8_t> out(len);
    AAsset_read(a, out.data(), len);
    AAsset_close(a);
    return out;
}

inline wasm_function_inst_t fn(Module* m, const char* name) {
    wasm_function_inst_t f = wasm_runtime_lookup_function(m->instance, name);
    if (!f) LOGE("missing WASM export: %s", name);
    return f;
}

inline void logEx(Module* m, const char* name) {
    LOGE("WASM call %s failed: %s", name, wasm_runtime_get_exception(m->instance));
}

// --- typed call wrappers --------------------------------------------------

int32_t call_i_i(Module* m, const char* name, int32_t a0) {
    wasm_function_inst_t f = fn(m, name);
    if (!f) return -1;
    wasm_val_t arg = {}; arg.kind = WASM_I32; arg.of.i32 = a0;
    wasm_val_t res = {}; res.kind = WASM_I32;
    if (!wasm_runtime_call_wasm_a(m->exec_env, f, 1, &res, 1, &arg)) {
        logEx(m, name);
        return -1;
    }
    return res.of.i32;
}

int32_t call_ii_i(Module* m, const char* name, int32_t a0, int32_t a1) {
    wasm_function_inst_t f = fn(m, name);
    if (!f) return -1;
    wasm_val_t args[2] = {};
    args[0].kind = WASM_I32; args[0].of.i32 = a0;
    args[1].kind = WASM_I32; args[1].of.i32 = a1;
    wasm_val_t res = {}; res.kind = WASM_I32;
    if (!wasm_runtime_call_wasm_a(m->exec_env, f, 1, &res, 2, args)) {
        logEx(m, name);
        return -1;
    }
    return res.of.i32;
}

float call_i_f(Module* m, const char* name, int32_t a0) {
    wasm_function_inst_t f = fn(m, name);
    if (!f) return 0.0f;
    wasm_val_t arg = {}; arg.kind = WASM_I32; arg.of.i32 = a0;
    wasm_val_t res = {}; res.kind = WASM_F32;
    if (!wasm_runtime_call_wasm_a(m->exec_env, f, 1, &res, 1, &arg)) {
        logEx(m, name);
        return 0.0f;
    }
    return res.of.f32;
}

void call_i_v(Module* m, const char* name, int32_t a0) {
    wasm_function_inst_t f = fn(m, name);
    if (!f) return;
    wasm_val_t arg = {}; arg.kind = WASM_I32; arg.of.i32 = a0;
    if (!wasm_runtime_call_wasm_a(m->exec_env, f, 0, nullptr, 1, &arg)) {
        logEx(m, name);
    }
}

void call_ii_v(Module* m, const char* name, int32_t a0, int32_t a1) {
    wasm_function_inst_t f = fn(m, name);
    if (!f) return;
    wasm_val_t args[2] = {};
    args[0].kind = WASM_I32; args[0].of.i32 = a0;
    args[1].kind = WASM_I32; args[1].of.i32 = a1;
    if (!wasm_runtime_call_wasm_a(m->exec_env, f, 0, nullptr, 2, args)) {
        logEx(m, name);
    }
}

inline Module* mod(jlong h) { return reinterpret_cast<Module*>(h); }
inline int32_t game32(jlong g) { return static_cast<int32_t>(static_cast<uint32_t>(g)); }

} // namespace

// ===== Lifecycle =========================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jobject assetManagerObj) {

    static bool wamrUp = false;
    if (!wamrUp) {
        RuntimeInitArgs args = {};
        args.mem_alloc_type = Alloc_With_System_Allocator;
        if (!wasm_runtime_full_init(&args)) {
            LOGE("wasm_runtime_full_init failed");
            return 0;
        }
        wamrUp = true;
    }

    auto bytes = readAsset(env, assetManagerObj, "core.wasm");
    if (bytes.empty()) return 0;

    auto* m = new Module();
    m->bytes = std::move(bytes);

    char errBuf[256] = {0};
    m->module = wasm_runtime_load(
        m->bytes.data(), m->bytes.size(), errBuf, sizeof(errBuf));
    if (!m->module) {
        LOGE("wasm_runtime_load: %s", errBuf);
        delete m;
        return 0;
    }
    // 256 KiB stack, 1 MiB heap. Sufficient for the maze core.
    m->instance = wasm_runtime_instantiate(
        m->module, 256 * 1024, 1024 * 1024, errBuf, sizeof(errBuf));
    if (!m->instance) {
        LOGE("wasm_runtime_instantiate: %s", errBuf);
        wasm_runtime_unload(m->module);
        delete m;
        return 0;
    }
    m->exec_env = wasm_runtime_create_exec_env(m->instance, 256 * 1024);
    if (!m->exec_env) {
        LOGE("wasm_runtime_create_exec_env failed");
        wasm_runtime_deinstantiate(m->instance);
        wasm_runtime_unload(m->module);
        delete m;
        return 0;
    }
    LOGI("core.wasm loaded; %zu bytes; module @ %p", m->bytes.size(), m->module);
    return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* m = mod(handle);
    if (!m) return;
    if (m->exec_env) wasm_runtime_destroy_exec_env(m->exec_env);
    if (m->instance) wasm_runtime_deinstantiate(m->instance);
    if (m->module)   wasm_runtime_unload(m->module);
    delete m;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeAbiVersion(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* m = mod(handle);
    wasm_function_inst_t f = fn(m, "core_abi_version");
    if (!f) return -1;
    wasm_val_t res = {}; res.kind = WASM_I32;
    if (!wasm_runtime_call_wasm_a(m->exec_env, f, 1, &res, 0, nullptr)) {
        logEx(m, "core_abi_version");
        return -1;
    }
    return res.of.i32;
}

// ===== Game lifecycle ====================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeGameNew(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint size, jint seed) {
    auto* m = mod(handle);
    int32_t game = call_ii_i(m, "core_game_new", size, seed);
    // Re-pack as 32-bit unsigned in a 64-bit jlong so the Kotlin side gets a
    // non-sign-extended value (some WASM pointers are > 0x80000000).
    return static_cast<jlong>(static_cast<uint32_t>(game));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeGameNewExt(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
    jint size, jint seed, jint weave) {
    auto* m = mod(handle);
    wasm_function_inst_t f = fn(m, "core_game_new_ext");
    if (!f) return 0;
    wasm_val_t args[3] = {};
    args[0].kind = WASM_I32; args[0].of.i32 = size;
    args[1].kind = WASM_I32; args[1].of.i32 = seed;
    args[2].kind = WASM_I32; args[2].of.i32 = weave;
    wasm_val_t res = {}; res.kind = WASM_I32;
    if (!wasm_runtime_call_wasm_a(m->exec_env, f, 1, &res, 3, args)) {
        logEx(m, "core_game_new_ext");
        return 0;
    }
    return static_cast<jlong>(static_cast<uint32_t>(res.of.i32));
}

extern "C" JNIEXPORT void JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeGameDrop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong game) {
    call_i_v(mod(handle), "core_game_drop", game32(game));
}

// ===== Maze accessors ====================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeMazeSize(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong game) {
    return call_i_i(mod(handle), "core_maze_size", game32(game));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeMazeStart(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    jint v[2] = {
        call_i_i(m, "core_maze_start_x", g),
        call_i_i(m, "core_maze_start_y", g),
    };
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeMazeGoal(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    jint v[2] = {
        call_i_i(m, "core_maze_goal_x", g),
        call_i_i(m, "core_maze_goal_y", g),
    };
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeMazeWalls(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    int32_t offset = call_i_i(m, "core_maze_walls_ptr", g);
    int32_t len    = call_i_i(m, "core_maze_walls_len", g);
    if (offset <= 0 || len <= 0) return env->NewByteArray(0);
    void* host = wasm_runtime_addr_app_to_native(m->instance, offset);
    if (!host) return env->NewByteArray(0);
    jbyteArray arr = env->NewByteArray(len);
    env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(host));
    return arr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeMazeHash(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    int32_t out = call_i_i(m, "core_alloc", 32);
    if (out <= 0) return env->NewByteArray(0);
    call_ii_v(m, "core_maze_hash", g, out);
    void* host = wasm_runtime_addr_app_to_native(m->instance, out);
    jbyteArray arr = env->NewByteArray(32);
    if (host) env->SetByteArrayRegion(arr, 0, 32, reinterpret_cast<const jbyte*>(host));
    call_ii_v(m, "core_free", out, 32);
    return arr;
}

// ===== Step + control ====================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeStep(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong game, jint dtMs) {
    return call_ii_i(mod(handle), "core_step", game32(game), dtMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeQueueDirection(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong game, jint dir) {
    call_ii_v(mod(handle), "core_queue_direction", game32(game), dir);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeSetLegacyMovement(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong game, jint value) {
    call_ii_v(mod(handle), "core_set_legacy_movement", game32(game), value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeSetExtraWalls(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game, jbyteArray walls) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    if (walls == nullptr) {
        // Null array clears the overlay.
        wasm_function_inst_t f = fn(m, "core_set_extra_walls");
        if (!f) return;
        wasm_val_t args[3] = {};
        args[0].kind = WASM_I32; args[0].of.i32 = g;
        args[1].kind = WASM_I32; args[1].of.i32 = 0;
        args[2].kind = WASM_I32; args[2].of.i32 = 0;
        if (!wasm_runtime_call_wasm_a(m->exec_env, f, 0, nullptr, 3, args)) {
            logEx(m, "core_set_extra_walls");
        }
        return;
    }
    jsize len = env->GetArrayLength(walls);
    if (len <= 0) return;
    // Allocate a buffer inside WASM linear memory and copy the bytes in.
    int32_t out = call_i_i(m, "core_alloc", len);
    if (out <= 0) return;
    void* host = wasm_runtime_addr_app_to_native(m->instance, out);
    if (host) {
        env->GetByteArrayRegion(walls, 0, len, reinterpret_cast<jbyte*>(host));
    }
    // Call core_set_extra_walls(g, ptr, len). The Rust side copies into its
    // own Vec, so we can free the WASM allocation right after.
    wasm_function_inst_t f = fn(m, "core_set_extra_walls");
    if (f) {
        wasm_val_t args[3] = {};
        args[0].kind = WASM_I32; args[0].of.i32 = g;
        args[1].kind = WASM_I32; args[1].of.i32 = out;
        args[2].kind = WASM_I32; args[2].of.i32 = len;
        if (!wasm_runtime_call_wasm_a(m->exec_env, f, 0, nullptr, 3, args)) {
            logEx(m, "core_set_extra_walls");
        }
    }
    call_ii_v(m, "core_free", out, len);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativePlayerRender(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    jfloat v[2] = {
        call_i_f(m, "core_player_render_x", g),
        call_i_f(m, "core_player_render_y", g),
    };
    jfloatArray arr = env->NewFloatArray(2);
    env->SetFloatArrayRegion(arr, 0, 2, v);
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativePlayerCell(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    jint v[2] = {
        call_i_i(m, "core_player_cell_x", g),
        call_i_i(m, "core_player_cell_y", g),
    };
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeVisited(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong game) {
    auto* m = mod(handle);
    int32_t g = game32(game);
    int32_t len = call_i_i(m, "core_visited_len", g);
    if (len <= 0) return env->NewIntArray(0);

    int32_t out = call_i_i(m, "core_alloc", len * 4);
    if (out <= 0) return env->NewIntArray(0);
    call_ii_v(m, "core_visited_copy", g, out);

    void* host = wasm_runtime_addr_app_to_native(m->instance, out);
    jintArray arr = env->NewIntArray(len);
    // Visited cells are written by Rust as little-endian u32s. arm64 is also
    // LE, so we can SetIntArrayRegion directly. If we ever target a BE host
    // this needs a byte-swap loop.
    if (host) env->SetIntArrayRegion(arr, 0, len, reinterpret_cast<const jint*>(host));
    call_ii_v(m, "core_free", out, len * 4);
    return arr;
}
