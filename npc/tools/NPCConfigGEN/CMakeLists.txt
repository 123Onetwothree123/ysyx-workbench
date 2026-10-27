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

set(STD_MODULE_FLAGS)
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
    file(GLOB STD_MODULE_CANDIDATES "/usr/include/c++/*/bits/std.cc")
    if(NOT STD_MODULE_CANDIDATES)
        message(FATAL_ERROR "找不到 GCC 的 bits/std.cc")
    endif()
    list(SORT STD_MODULE_CANDIDATES)
    list(GET STD_MODULE_CANDIDATES -1 STD_MODULE_SOURCE)
    file(COPY ${STD_MODULE_SOURCE} DESTINATION ${CMAKE_CURRENT_BINARY_DIR})
    set(STD_MODULE_FILE ${CMAKE_CURRENT_BINARY_DIR}/std.cc)
elseif(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    set(STD_MODULE_SOURCE /usr/share/libc++/v1/std.cppm)
    if(NOT EXISTS ${STD_MODULE_SOURCE})
        message(FATAL_ERROR "找不到 libc++ 的 std.cppm")
    endif()
    file(COPY ${STD_MODULE_SOURCE} DESTINATION ${CMAKE_CURRENT_BINARY_DIR})
    set(STD_MODULE_FILE ${CMAKE_CURRENT_BINARY_DIR}/std.cppm)
    set(STD_MODULE_FLAGS -stdlib=libc++)
else()
    message(FATAL_ERROR "npc_config_gen 仅支持 GCC 或 Clang 的 std 模块")
endif()

add_executable(npc_config_gen ${NPC_CONFIG_GEN_SOURCES})
target_sources(npc_config_gen PRIVATE
    FILE_SET CXX_MODULES
    BASE_DIRS ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}
    FILES npc.configgen.ixx ${STD_MODULE_FILE}
)
target_compile_options(npc_config_gen PRIVATE -Wall -Wextra ${STD_MODULE_FLAGS})
target_link_options(npc_config_gen PRIVATE ${STD_MODULE_FLAGS})
set_target_properties(npc_config_gen PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}
)
