cmake_minimum_required(VERSION 3.18)
project(plugin_loader_config_rejected LANGUAGES C)
include("${CMAKE_CURRENT_LIST_DIR}/../plugin-loader-fixture.cmake")
milky_add_fixture(plugin_loader_config_rejected plugin_loader_config_rejected.c)
