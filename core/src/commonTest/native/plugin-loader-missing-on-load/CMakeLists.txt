cmake_minimum_required(VERSION 3.18)
project(plugin_loader_missing_on_load LANGUAGES C)
include("${CMAKE_CURRENT_LIST_DIR}/../plugin-loader-fixture.cmake")
milky_add_fixture(plugin_loader_missing_on_load plugin_loader_missing_on_load.c)
