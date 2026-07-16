cmake_minimum_required(VERSION 3.18)
project(plugin_loader_missing_entry LANGUAGES C)
include("${CMAKE_CURRENT_LIST_DIR}/../plugin-loader-fixture.cmake")
milky_add_fixture(plugin_loader_missing_entry plugin_loader_missing_entry.c)
