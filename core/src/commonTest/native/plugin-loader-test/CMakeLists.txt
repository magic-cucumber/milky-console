cmake_minimum_required(VERSION 3.18)
project(plugin_loader_test LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PLUGIN_DIRECTORY "${CMAKE_BINARY_DIR}/output/plugin-loader-test")
set(PLUGIN_PLATFORM_DIRECTORY "${PLUGIN_DIRECTORY}/platform")

if(WIN32)
    set(PLUGIN_LIBRARY_NAME "WINDOWS-X64")
elseif(APPLE)
    set(PLUGIN_LIBRARY_NAME "MACOSX-ARM64")
elseif(UNIX)
    set(PLUGIN_LIBRARY_NAME "LINUX-X64")
else()
    message(FATAL_ERROR "Unsupported plugin fixture platform")
endif()

add_library(plugin_loader_test SHARED plugin_loader_test.c)
target_compile_definitions(plugin_loader_test PRIVATE MILKY_CONSOLE_BUILDING_LIBRARY)
target_include_directories(plugin_loader_test PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../../../plugin/api/include")

set_target_properties(plugin_loader_test PROPERTIES
    PREFIX ""
    OUTPUT_NAME "${PLUGIN_LIBRARY_NAME}"
    RUNTIME_OUTPUT_DIRECTORY "${PLUGIN_PLATFORM_DIRECTORY}"
    LIBRARY_OUTPUT_DIRECTORY "${PLUGIN_PLATFORM_DIRECTORY}"
    ARCHIVE_OUTPUT_DIRECTORY "${PLUGIN_PLATFORM_DIRECTORY}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${PLUGIN_PLATFORM_DIRECTORY}"
    LIBRARY_OUTPUT_DIRECTORY_DEBUG "${PLUGIN_PLATFORM_DIRECTORY}"
    ARCHIVE_OUTPUT_DIRECTORY_DEBUG "${PLUGIN_PLATFORM_DIRECTORY}"
)

file(MAKE_DIRECTORY "${PLUGIN_DIRECTORY}")
configure_file(manifest.json "${PLUGIN_DIRECTORY}/manifest.json" COPYONLY)
configure_file(default-config.json "${PLUGIN_DIRECTORY}/default-config.json" COPYONLY)
