cmake_minimum_required(VERSION 3.18)
project(llama_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(STATUS "Building with llama.cpp from ${LLAMA_CPP_DIR}")

    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_BINARY_DIR}/llama.cpp)

    add_library(llama_jni SHARED llama_jni.cpp)

    target_include_directories(llama_jni PRIVATE
        ${LLAMA_CPP_DIR}/include
    )

    target_link_libraries(llama_jni
        llama
        ggml
        ggml-base
        android
        log
    )
else()
    message(WARNING
        "llama.cpp not found at ${LLAMA_CPP_DIR}. "
        "Clone it: git clone --depth 1 https://github.com/ggerganov/llama.cpp.git"
    )

    add_library(llama_jni SHARED llama_jni_stub.cpp)
    target_link_libraries(llama_jni android log)
endif()
