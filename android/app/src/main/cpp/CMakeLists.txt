cmake_minimum_required(VERSION 3.22.1)
project(amaze_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# WAMR — WebAssembly Micro Runtime. Pulled at configure time so contributors
# don't need to clone a submodule manually. Pin to a tested release tag.
include(FetchContent)
FetchContent_Declare(
    wamr
    GIT_REPOSITORY https://github.com/bytecodealliance/wasm-micro-runtime.git
    GIT_TAG WAMR-2.1.2
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(wamr)

# WAMR's CMake wants these set before its product-mini glue is included.
set(WAMR_BUILD_PLATFORM "android")
set(WAMR_BUILD_TARGET   "AARCH64" CACHE STRING "WAMR build target")
set(WAMR_BUILD_INTERP   1)
set(WAMR_BUILD_FAST_INTERP 1)
set(WAMR_BUILD_AOT      0)
set(WAMR_BUILD_JIT      0)
set(WAMR_BUILD_LIBC_BUILTIN 1)
set(WAMR_BUILD_LIBC_WASI 0)

# Bring in the WAMR sources directly. This compiles the iwasm core as part
# of our amaze_native library, no separate .so.
set(WAMR_ROOT_DIR ${wamr_SOURCE_DIR})
include(${WAMR_ROOT_DIR}/build-scripts/runtime_lib.cmake)

add_library(amaze_native SHARED
    core_bridge.cc
    ${WAMR_RUNTIME_LIB_SOURCE}
)

target_include_directories(amaze_native PRIVATE
    ${WAMR_ROOT_DIR}/core/iwasm/include
    ${WAMR_ROOT_DIR}/core/shared/utils
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(amaze_native
    ${log-lib}
    ${android-lib}
)
