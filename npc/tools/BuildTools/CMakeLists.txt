cmake_minimum_required(VERSION 3.28)
project(npc_build_tools LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS ON)
set(CMAKE_CXX_SCAN_FOR_MODULES ON)

if(NOT CMAKE_GENERATOR MATCHES "Ninja")
    message(FATAL_ERROR "npc_build_tools requires the Ninja generator")
endif()

include(${CMAKE_CURRENT_SOURCE_DIR}/../cmake/NPCStdModule.cmake)

set(NPC_BUILD_TOOLS
    build_dtb
    pack_linux_image
    audit_rootfs_elf
    merge_config
)

foreach(tool IN LISTS NPC_BUILD_TOOLS)
    add_executable(${tool} ${tool}.cpp)
    target_sources(${tool} PRIVATE
        FILE_SET CXX_MODULES
        BASE_DIRS ${CMAKE_CURRENT_SOURCE_DIR}
        FILES npc.buildtools.ixx
    )
    npc_add_std_module(${tool})
    target_compile_options(${tool} PRIVATE -Wall -Wextra)
    set_target_properties(${tool} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}
    )
endforeach()
