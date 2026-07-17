cmake_minimum_required(VERSION 3.18)
project(process_test LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_executable(process_test process_test.c)

if(NOT DEFINED MILKY_CMAKE_OUTPUT_DIR)
    message(FATAL_ERROR "MILKY_CMAKE_OUTPUT_DIR must be provided by the Gradle native CMake task")
endif()
file(MAKE_DIRECTORY "${MILKY_CMAKE_OUTPUT_DIR}")

# Keep the path stable for the Gradle test task on both single- and multi-config generators.
set_target_properties(process_test PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${MILKY_CMAKE_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${MILKY_CMAKE_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${MILKY_CMAKE_OUTPUT_DIR}"
)
