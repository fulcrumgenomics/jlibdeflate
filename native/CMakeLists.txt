cmake_minimum_required(VERSION 3.14)
project(jlibdeflate C)

# Build libdeflate as a static library (linked into our shared lib)
set(LIBDEFLATE_BUILD_STATIC_LIB ON CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_SHARED_LIB OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_GZIP OFF CACHE BOOL "" FORCE)
set(LIBDEFLATE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# Force PIC on all targets — required because the static library will be linked
# into a shared library.  On macOS PIC is the default; on Linux it is not.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(libdeflate)

# Find JNI headers.  CMake's FindJNI sometimes fails on Homebrew JDKs that lack
# AWT, so we fall back to using JAVA_HOME directly when needed.
find_package(JNI QUIET)
if(NOT JNI_FOUND)
    if(DEFINED ENV{JAVA_HOME})
        set(JAVA_HOME "$ENV{JAVA_HOME}")
    else()
        # Try to detect JAVA_HOME on macOS
        if(APPLE)
            execute_process(COMMAND /usr/libexec/java_home
                OUTPUT_VARIABLE JAVA_HOME OUTPUT_STRIP_TRAILING_WHITESPACE)
        endif()
    endif()
    if(NOT JAVA_HOME)
        message(FATAL_ERROR "Could not find JNI headers. Set JAVA_HOME.")
    endif()
    set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include")
    # Add platform-specific subdirectory
    if(APPLE)
        list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/darwin")
    elseif(UNIX)
        list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/linux")
    elseif(WIN32)
        list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/win32")
    endif()
    message(STATUS "Using JNI headers from JAVA_HOME: ${JAVA_HOME}")
endif()

# Build the JNI shared library
add_library(jlibdeflate SHARED jlibdeflate.c)
target_include_directories(jlibdeflate PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/libdeflate
)
target_link_libraries(jlibdeflate PRIVATE libdeflate_static)

# Hide all symbols except those explicitly exported via JNIEXPORT
set_target_properties(jlibdeflate PROPERTIES
    C_STANDARD 99
    C_VISIBILITY_PRESET hidden
    POSITION_INDEPENDENT_CODE ON
)

# On macOS, don't prefix with "lib" — wait, actually we DO want "lib" prefix
# because System.loadLibrary("jlibdeflate") looks for "libjlibdeflate.dylib".
# The default is to add the "lib" prefix, which is what we want.

# Strip on release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    if(APPLE)
        add_custom_command(TARGET jlibdeflate POST_BUILD
            COMMAND strip -x $<TARGET_FILE:jlibdeflate>)
    elseif(UNIX)
        add_custom_command(TARGET jlibdeflate POST_BUILD
            COMMAND strip $<TARGET_FILE:jlibdeflate>)
    endif()
endif()
