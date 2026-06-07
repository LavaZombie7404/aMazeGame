cmake_minimum_required(VERSION 3.22.1)
# Both C and CXX — WAMR's iwasm runtime is mostly C, our bridge is C++.
project(amaze_native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# WAMR's CMake guards `WAMR_BUILD_PLATFORM` with `if(NOT DEFINED …)`, so any
# value we set BEFORE FetchContent_MakeAvailable below is what its build will
# see. Default-fallback uses CMAKE_HOST_SYSTEM_NAME, which would be "Linux"
# here (we're cross-compiling) and would pick the wrong platform port.
set(WAMR_BUILD_PLATFORM "android" CACHE STRING "WAMR target OS" FORCE)

# Derive WAMR's target arch from the ABI Gradle is building for. Same
# concern as WAMR_BUILD_PLATFORM — set it first, FetchContent runs WAMR's
# CMakeLists which honour our values.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(WAMR_BUILD_TARGET "AARCH64" CACHE STRING "WAMR target arch" FORCE)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(WAMR_BUILD_TARGET "ARMV7" CACHE STRING "WAMR target arch" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(WAMR_BUILD_TARGET "X86_64" CACHE STRING "WAMR target arch" FORCE)
elseif(ANDROID_ABI STREQUAL "x86")
    set(WAMR_BUILD_TARGET "X86_32" CACHE STRING "WAMR target arch" FORCE)
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
endif()

# Pin the interpreter-only WAMR build (no JIT/AOT) for portability + small
# binary. Fast-interp is JIT-like in speed without the JIT compiler.
set(WAMR_BUILD_INTERP        1 CACHE STRING "" FORCE)
set(WAMR_BUILD_FAST_INTERP   1 CACHE STRING "" FORCE)
set(WAMR_BUILD_AOT           0 CACHE STRING "" FORCE)
set(WAMR_BUILD_JIT           0 CACHE STRING "" FORCE)
set(WAMR_BUILD_LIBC_BUILTIN  1 CACHE STRING "" FORCE)
set(WAMR_BUILD_LIBC_WASI     0 CACHE STRING "" FORCE)
# Don't build the iwasm_shared (.so) target — we only need the static lib
# baked into amaze_native, and iwasm_shared exposes no symbols anyway due
# to -fvisibility=hidden.
set(WAMR_BUILD_SHARED        0 CACHE STRING "" FORCE)

# WAMR — WebAssembly Micro Runtime. Pinned to a tested release tag.
include(FetchContent)
FetchContent_Declare(
    wamr
    GIT_REPOSITORY https://github.com/bytecodealliance/wasm-micro-runtime.git
    GIT_TAG WAMR-2.1.2
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(wamr)

# Our JNI bridge linking against WAMR's shared library (iwasm_shared) target,
# which FetchContent has now defined.
add_library(amaze_native SHARED
    core_bridge.cc
)

target_include_directories(amaze_native PRIVATE
    ${wamr_SOURCE_DIR}/core/iwasm/include
    ${wamr_SOURCE_DIR}/core/shared/utils
)

find_library(log-lib log)
find_library(android-lib android)

# WAMR forces -fvisibility=hidden on iwasm sources and only annotates its
# public API with dllexport on Windows. On Android, both iwasm_static and
# iwasm_shared have hidden symbols — but with iwasm_static we bake the
# objects directly into libamaze_native.so, so visibility doesn't matter
# at the static-link step. Then we re-export the WAMR symbols we use.
target_link_libraries(amaze_native
    iwasm_static
    ${log-lib}
    ${android-lib}
)
