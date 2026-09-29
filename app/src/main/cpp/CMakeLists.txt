cmake_minimum_required(VERSION 3.22.1)
project(itantra_speech LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_INTONATIONS OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/espeak-ng" espeak EXCLUDE_FROM_ALL)
add_library(itantra_espeak SHARED espeak_jni.cpp)
target_link_libraries(itantra_espeak PRIVATE espeak-ng log)
target_link_options(itantra_espeak PRIVATE "-Wl,-z,max-page-size=16384")

# Neural TTS bridge. Resolves the ONNX Runtime already shipped inside the
# sherpa-onnx AAR through dlopen/OrtGetApiBase, so no second runtime is packaged
# and sherpa's and Moonshine's isolated libraries are left untouched.
add_library(itantra_tts SHARED onnx_tts_jni.cpp)
target_include_directories(itantra_tts PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/onnxruntime/include")
target_link_libraries(itantra_tts PRIVATE log dl)
target_link_options(itantra_tts PRIVATE "-Wl,-z,max-page-size=16384")
