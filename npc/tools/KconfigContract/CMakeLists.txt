cmake_minimum_required(VERSION 3.28)
project(npc_kconfig_contract LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS ON)
set(CMAKE_CXX_SCAN_FOR_MODULES ON)
if(NOT CMAKE_GENERATOR MATCHES "Ninja")
    message(FATAL_ERROR "npc_kconfig_contract requires the Ninja generator")
endif()
include(${CMAKE_CURRENT_SOURCE_DIR}/../cmake/NPCStdModule.cmake)
add_executable(kconfig_contract main.cpp config.cpp validation.cpp self_test.cpp)
target_sources(kconfig_contract PRIVATE
    FILE_SET CXX_MODULES
    BASE_DIRS ${CMAKE_CURRENT_SOURCE_DIR}
    FILES npc.kconfig_contract.ixx
)
npc_add_std_module(kconfig_contract)
target_compile_options(kconfig_contract PRIVATE -Wall -Wextra)
set_target_properties(kconfig_contract PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
