cmake_minimum_required(VERSION 3.22.1)
project("kodrix-native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

# Import prebuilt libgit2
add_library(git2 SHARED IMPORTED)
set_target_properties(git2 PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libgit2.so
)

# Build native bridge library
add_library(kodrix-native SHARED native-lib.cpp)

target_include_directories(kodrix-native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

# Link against Android logging and prebuilt libgit2
find_library(log-lib log)
target_link_libraries(kodrix-native ${log-lib} git2)