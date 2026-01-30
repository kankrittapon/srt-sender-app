cmake_minimum_required(VERSION 3.22.1)

project("srtsender")

# Include FetchContent to download libsrt
include(FetchContent)

FetchContent_Declare(
    srt
    GIT_REPOSITORY https://github.com/Haivision/srt.git
    GIT_TAG        v1.5.3 # Use a stable version
)

# We only need the library, not the apps/docs
set(ENABLE_APPS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_ENCRYPTION OFF CACHE BOOL "" FORCE) # Disable encryption to avoid OpenSSL dependency
set(USE_OPENSSL_PC OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(srt)

# Define our native library
add_library(native-lib SHARED
    native-lib.cpp
    MpegTsMuxer.cpp
    SrtTransport.cpp
)

target_include_directories(native-lib PRIVATE 
    ${srt_SOURCE_DIR}/srtcore
    ${srt_BINARY_DIR} 
    ${srt_BINARY_DIR}/srtcore
)

# Link against Android libs and libsrt
find_library(log-lib log)

target_link_libraries(native-lib
    ${log-lib}
    srt_static
)
