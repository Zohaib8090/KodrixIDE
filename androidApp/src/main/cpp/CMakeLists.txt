cmake_minimum_required(VERSION 3.22.1)
project("kodrix-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libgit2.so is prebuilt and ships per-ABI in jniLibs/ (built outside this
# project); we only compile native-lib.cpp here and link against it.
add_library(git2 SHARED IMPORTED)
set_target_properties(git2 PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libgit2.so"
)

# Loaded at runtime via System.load() as "libnative-lib.so" (see
# NativeLibLoader.kt's loadOrder), so the target name must stay "native-lib".
add_library(native-lib SHARED native-lib.cpp)

target_include_directories(native-lib PRIVATE include)

find_library(log-lib log)

target_link_libraries(native-lib
    git2
    ${log-lib}
)

# libkodrix_exec.so — the LD_PRELOAD exec/path-redirect bridge (Language Packs Phase 0
# spike, see documents/LANGUAGE_PACKS.md). Plain C, no dependency on native-lib.cpp.
# NOT wired into any production exec path yet — only loaded when the user turns on the
# "Native exec bridge" toggle in Settings, which sources it into terminal sessions via
# init.sh. Untested off-device; do not use for real language-pack installs until the
# Phase 0 device matrix passes.
add_library(kodrix_exec SHARED kodrix_exec.c)
target_link_libraries(kodrix_exec ${log-lib})
