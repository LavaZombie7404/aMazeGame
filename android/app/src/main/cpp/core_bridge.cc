// JNI bridge that loads amaze-core.wasm into WAMR (WebAssembly Micro Runtime)
// and exposes the C ABI exports declared in `core/src/lib.rs` to Kotlin via
// `CoreBridge.kt`.
//
// One CoreBridge -> one wasm_module_inst_t. The Kotlin side opens
// `assets/core.wasm` via AAssetManager, hands the bytes here, and the JNI
// functions below translate Kotlin arguments to WAMR's wasm_runtime_call_wasm
// calls.
//
// This file is the integration point. The Compose UI never calls these
// directly — it talks to CoreBridge.kt which talks to us.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>

// WAMR public headers.
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

// Invoke a 0-arg WASM export returning i32 (used for ABI version, etc.).
int32_t callI32(Module* m, const char* name) {
    wasm_function_inst_t fn = wasm_runtime_lookup_function(m->instance, name);
    if (!fn) return -1;
    wasm_val_t result{};
    if (!wasm_runtime_call_wasm_a(m->exec_env, fn, 1, &result, 0, nullptr)) {
        LOGE("WASM call %s failed: %s", name, wasm_runtime_get_exception(m->instance));
        return -1;
    }
    return result.of.i32;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jobject assetManagerObj) {

    static bool wamrUp = false;
    if (!wamrUp) {
        RuntimeInitArgs args{};
        args.mem_alloc_type = Alloc_With_System_Allocator;
        if (!wasm_runtime_full_init(&args)) {
            LOGE("wasm_runtime_full_init failed");
            return 0;
        }
        wamrUp = true;
    }

    auto bytes = readAsset(env, assetManagerObj, "core.wasm");
    if (bytes.empty()) return 0;

    auto* m = new Module{};
    m->bytes = std::move(bytes);

    char errBuf[256] = {0};
    m->module = wasm_runtime_load(
        m->bytes.data(), m->bytes.size(), errBuf, sizeof(errBuf));
    if (!m->module) {
        LOGE("wasm_runtime_load: %s", errBuf);
        delete m;
        return 0;
    }
    // 256 KiB stack, 1 MiB heap is plenty for the maze core.
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
    return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* m = reinterpret_cast<Module*>(handle);
    if (!m) return;
    if (m->exec_env) wasm_runtime_destroy_exec_env(m->exec_env);
    if (m->instance) wasm_runtime_deinstantiate(m->instance);
    if (m->module)   wasm_runtime_unload(m->module);
    delete m;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lavazombie_amazegame_CoreBridge_nativeAbiVersion(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return callI32(reinterpret_cast<Module*>(handle), "core_abi_version");
}

// The remaining JNI functions (game_new, step, queue_direction, …) follow
// the same pattern: lookup the function, package args as wasm_val_t, call,
// translate result back. Implemented in a follow-up commit alongside the
// Compose renderer using them.
//
// For the scaffold landing here, the runtime initialises and the ABI
// handshake works end-to-end — proof the WAMR + Rust core pipeline closes
// on Android. The richer surface lands next.
