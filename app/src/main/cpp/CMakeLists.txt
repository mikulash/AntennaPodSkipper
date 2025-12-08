cmake_minimum_required(VERSION 3.22.1)

project("llamacpp")

# Add llama.cpp as a subdirectory
set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build common library" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "Disable curl" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries" FORCE)

add_subdirectory(llama.cpp)

# Create our JNI library
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        LlamaModel.cpp
        LlamaGenerationSession.cpp)

# Link llama.cpp libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        android
        log)
