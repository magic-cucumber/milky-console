function(milky_add_fixture target source)
    if(NOT DEFINED MILKY_CMAKE_OUTPUT_DIR)
        message(FATAL_ERROR "MILKY_CMAKE_OUTPUT_DIR must be provided by the Gradle native CMake task")
    endif()
    set(plugin_directory "${MILKY_CMAKE_OUTPUT_DIR}")
    set(plugin_platform_directory "${plugin_directory}/platform")
    if(WIN32)
        set(plugin_library_name "WINDOWS-X64")
    elseif(APPLE)
        set(plugin_library_name "MACOSX-ARM64")
    elseif(UNIX)
        set(plugin_library_name "LINUX-X64")
    else()
        message(FATAL_ERROR "Unsupported plugin fixture platform")
    endif()

    add_library(${target} SHARED ${source})
    set_target_properties(${target} PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
    target_compile_definitions(${target} PRIVATE MILKY_CONSOLE_BUILDING_LIBRARY)
    target_include_directories(${target} PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../../../../plugin/api/include")
    set_target_properties(${target} PROPERTIES PREFIX "" OUTPUT_NAME "${plugin_library_name}"
        RUNTIME_OUTPUT_DIRECTORY "${plugin_platform_directory}"
        LIBRARY_OUTPUT_DIRECTORY "${plugin_platform_directory}"
        ARCHIVE_OUTPUT_DIRECTORY "${plugin_platform_directory}"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${plugin_platform_directory}"
        LIBRARY_OUTPUT_DIRECTORY_DEBUG "${plugin_platform_directory}"
        ARCHIVE_OUTPUT_DIRECTORY_DEBUG "${plugin_platform_directory}")
    file(MAKE_DIRECTORY "${plugin_directory}")
    configure_file("${CMAKE_CURRENT_LIST_DIR}/manifest.json" "${plugin_directory}/manifest.json" COPYONLY)
    if(EXISTS "${CMAKE_CURRENT_LIST_DIR}/default-config.json")
        configure_file("${CMAKE_CURRENT_LIST_DIR}/default-config.json" "${plugin_directory}/default-config.json" COPYONLY)
    endif()
endfunction()
