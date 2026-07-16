cmake_minimum_required(VERSION 3.18)
project(plugin_loader_on_load_crash LANGUAGES C)

include("${CMAKE_CURRENT_LIST_DIR}/../plugin-loader-fixture.cmake")
milky_add_fixture(plugin_loader_on_load_crash plugin_loader_on_load_crash.c)
