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
