cmake_minimum_required(VERSION 3.21)
project(kconfig LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS ON)
# 扫描 import/module 声明（.cpp 里也有 import，不只是 .ixx）
set(CMAKE_CXX_SCAN_FOR_MODULES ON)

set(BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/build)

# === C++20 模块：只有部分生成器支持 ===
if(NOT CMAKE_GENERATOR MATCHES "Ninja|Visual Studio")
    message(FATAL_ERROR
        "kconfig 的核心接口已改为 C++20 模块（kconfig.ixx），\n"
        "当前生成器（${CMAKE_GENERATOR}）不支持模块扫描。\n"
        "请用 Ninja 配置：cmake -B build -S . -G Ninja")
endif()

# === Compiler detection and std setup ===
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -stdlib=libc++")
    set(STD_SRC /usr/share/libc++/v1/std.cppm)
    set(STD_PCM ${BUILD_DIR}/std.pcm)

    if(EXISTS ${STD_SRC})
        add_custom_command(
            OUTPUT ${STD_PCM}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
            COMMAND ${CMAKE_CXX_COMPILER} -std=gnu++23 -stdlib=libc++
                    -x c++-module --precompile ${STD_SRC} -o ${STD_PCM}
            COMMENT "Precompiling std.pcm (Clang)"
        )
        add_custom_target(std_module DEPENDS ${STD_PCM})
        set(STD_MOD_FLAGS -fmodule-file=std=${STD_PCM})
        message(STATUS "Clang: precompiling std.pcm")
    endif()
else()
    # GCC: use traditional includes via -include to avoid module conflicts
    file(WRITE ${BUILD_DIR}/std_compat.h
        "#ifndef _STD_COMPAT_H_\n"
        "#define _STD_COMPAT_H_\n"
        "#include <bits/stdc++.h>\n"
        "#endif\n"
    )
    set(STD_COMPAT_FLAGS -include ${BUILD_DIR}/std_compat.h)
    message(STATUS "GCC: using -include std_compat.h")
endif()

# === Flex and Bison ===
find_package(FLEX REQUIRED)
find_package(BISON REQUIRED)

# Bison: parser.y -> parser.tab.c + parser.tab.h
add_custom_command(
    OUTPUT ${BUILD_DIR}/parser.tab.c ${BUILD_DIR}/parser.tab.h
    COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
    COMMAND ${BISON_EXECUTABLE} -v ${CMAKE_CURRENT_SOURCE_DIR}/parser.y
            --defines=${BUILD_DIR}/parser.tab.h -o ${BUILD_DIR}/parser.tab.c
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/parser.y
    COMMENT "Bison: parser.y"
)

# Post-process parser.tab.c -> parser.tab.cpp
add_custom_command(
    OUTPUT ${BUILD_DIR}/parser.tab.cpp
    COMMAND ${CMAKE_CURRENT_SOURCE_DIR}/kconfig_postprocess.sh ${BUILD_DIR}/parser.tab.c ${BUILD_DIR}/parser.tab.cpp
    DEPENDS ${BUILD_DIR}/parser.tab.c ${CMAKE_CURRENT_SOURCE_DIR}/kconfig_postprocess.sh
    COMMENT "Post-process parser.tab.c -> parser.tab.cpp"
)

# Flex: lexer.l -> lexer.lex.c
add_custom_command(
    OUTPUT ${BUILD_DIR}/lexer.lex.c
    COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
    COMMAND ${FLEX_EXECUTABLE} -o ${BUILD_DIR}/lexer.lex.c ${CMAKE_CURRENT_SOURCE_DIR}/lexer.l
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/lexer.l ${BUILD_DIR}/parser.tab.h
    COMMENT "Flex: lexer.l"
)

# Post-process lexer.lex.c -> lexer.lex.cpp
add_custom_command(
    OUTPUT ${BUILD_DIR}/lexer.lex.cpp
    COMMAND ${CMAKE_CURRENT_SOURCE_DIR}/kconfig_lexer_postprocess.sh ${BUILD_DIR}/lexer.lex.c ${BUILD_DIR}/lexer.lex.cpp
    DEPENDS ${BUILD_DIR}/lexer.lex.c ${CMAKE_CURRENT_SOURCE_DIR}/kconfig_lexer_postprocess.sh
    COMMENT "Post-process lexer.lex.c -> lexer.lex.cpp"
)

# === Generated sources library (compiled once, shared by both targets) ===
add_library(kconfig_generated OBJECT
    ${BUILD_DIR}/parser.tab.cpp
    ${BUILD_DIR}/lexer.lex.cpp
)
target_include_directories(kconfig_generated PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(kconfig_generated PRIVATE ${STD_COMPAT_FLAGS} -Wno-sign-compare -Wno-unused-function)
# 生成文件是模块导入方（非模块单元）：链接核心库以获得 BMI 与对象
target_link_libraries(kconfig_generated PRIVATE kconfig_core)
if(TARGET std_module)
    add_dependencies(kconfig_generated std_module)
    target_compile_options(kconfig_generated PRIVATE ${STD_MOD_FLAGS})
endif()

# === Core library (shared between conf and mconf) ===
set(CORE_SRCS
    confdata.cpp
    expr.cpp
    kconfig.cpp
    preprocess.cpp
    symbol.cpp
    util.cpp
    menu.cpp
)

add_library(kconfig_core OBJECT ${CORE_SRCS})
# 核心接口模块（实现单元：上面这些 .cpp）
target_sources(kconfig_core PUBLIC FILE_SET CXX_MODULES FILES kconfig.ixx)
target_include_directories(kconfig_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/lxdialog)
# 模块单元不能带 -include（module; 必须是 TU 的第一条声明），std 由模块 GMF 提供
if(TARGET std_module)
    add_dependencies(kconfig_core std_module)
    target_compile_options(kconfig_core PRIVATE ${STD_MOD_FLAGS})
endif()

# === conf executable ===
add_executable(c_conf conf.cpp)
target_include_directories(c_conf PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(c_conf PRIVATE ${STD_COMPAT_FLAGS})
target_link_libraries(c_conf PRIVATE kconfig_core kconfig_generated)
if(TARGET std_module)
    add_dependencies(c_conf std_module)
    target_compile_options(c_conf PRIVATE ${STD_MOD_FLAGS})
endif()
set_target_properties(c_conf PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../build)

# === mconf executable (with ncurses) ===
find_package(Curses REQUIRED)

set(LXDIALOG_SRCS
    lxdialog/checklist.cpp
    lxdialog/inputbox.cpp
    lxdialog/menubox.cpp
    lxdialog/textbox.cpp
    lxdialog/util.cpp
    lxdialog/yesno.cpp
)

add_executable(c_mconf mconf.cpp ${LXDIALOG_SRCS})
target_include_directories(c_mconf PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/lxdialog ${CURSES_INCLUDE_DIRS})
target_compile_options(c_mconf PRIVATE ${STD_COMPAT_FLAGS})
target_link_libraries(c_mconf PRIVATE kconfig_core kconfig_generated)
if(TARGET std_module)
    add_dependencies(c_mconf std_module)
    target_compile_options(c_mconf PRIVATE ${STD_MOD_FLAGS})
endif()
target_link_libraries(c_mconf PRIVATE ${CURSES_LIBRARIES} tinfo)
set_target_properties(c_mconf PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../build)
