cmake_minimum_required(VERSION 3.28)
project(npc_linux_boot_test LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_CXX_SCAN_FOR_MODULES ON)
if(NOT CMAKE_GENERATOR MATCHES "Ninja")
    message(FATAL_ERROR "npc_linux_boot_test requires the Ninja generator")
endif()
include(${CMAKE_CURRENT_SOURCE_DIR}/../cmake/NPCStdModule.cmake)
add_executable(linux_boot_test main.cpp common.cpp fixtures.cpp harness.cpp dtb_tests.cpp pack_tests.cpp)
target_sources(linux_boot_test PRIVATE
    FILE_SET CXX_MODULES
    BASE_DIRS ${CMAKE_CURRENT_SOURCE_DIR}
    FILES npc.linux_boot_test.ixx
)
npc_add_std_module(linux_boot_test)
target_compile_options(linux_boot_test PRIVATE -Wall -Wextra)
set_target_properties(linux_boot_test PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
