cmake_minimum_required(VERSION 3.28)
project(npc_config_gen LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS ON)
set(CMAKE_CXX_SCAN_FOR_MODULES ON)

if(NOT CMAKE_GENERATOR MATCHES "Ninja")
    message(FATAL_ERROR "npc_config_gen requires the Ninja generator")
endif()

set(NPC_CONFIG_GEN_SOURCES
    main.cpp
    io.cpp
    perf.cpp
    JsonConfig/format.cpp
    JsonConfig/parser.cpp
    DeriveConfig/derive.cpp
    DeriveConfig/helpers.cpp
    ModuleConfig/digest.cpp
    ModuleConfig/render.cpp
    TypedConfig/cpu.cpp
    TypedConfig/debug.cpp
    TypedConfig/mmu.cpp
    TypedConfig/platform.cpp
    TypedConfig/render.cpp
)

include(${CMAKE_CURRENT_SOURCE_DIR}/../cmake/NPCStdModule.cmake)

add_executable(npc_config_gen ${NPC_CONFIG_GEN_SOURCES})
target_sources(npc_config_gen PRIVATE
    FILE_SET CXX_MODULES
    BASE_DIRS ${CMAKE_CURRENT_SOURCE_DIR}
    FILES npc.configgen.ixx
)
npc_add_std_module(npc_config_gen)
target_compile_options(npc_config_gen PRIVATE -Wall -Wextra)
set_target_properties(npc_config_gen PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}
)
