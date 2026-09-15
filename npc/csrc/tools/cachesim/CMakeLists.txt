cmake_minimum_required(VERSION 3.21)

set(PRJ_DIR   ${CMAKE_CURRENT_SOURCE_DIR})
set(SRC_DIR   ${PRJ_DIR}/src)
set(BLD_DIR   ${CMAKE_CURRENT_BINARY_DIR})
set(NPC_BUILD ${PRJ_DIR}/../../../tools/build)
set(CONF      ${NPC_BUILD}/c_conf)
set(MCONF     ${NPC_BUILD}/c_mconf)
set(KCONFIG   ${PRJ_DIR}/Kconfig)
set(AUTO_CONF ${PRJ_DIR}/include/config/auto.conf)

# 同步 kconfig → .config & auto.conf
if(EXISTS "${PRJ_DIR}/.config")
    execute_process(COMMAND ${CONF} -s --syncconfig ${KCONFIG}
        WORKING_DIRECTORY ${PRJ_DIR})
else()
    execute_process(COMMAND ${CONF} -s --olddefconfig ${KCONFIG}
        WORKING_DIRECTORY ${PRJ_DIR})
endif()

# 解析 auto.conf
if(EXISTS "${AUTO_CONF}")
    include(${PRJ_DIR}/scripts/read_config.cmake)
endif()

# 编译器一致性校验 (切换编译器需 rm -rf build)
if(DEFINED CMAKE_CXX_COMPILER AND DEFINED CONFIG_CC AND NOT CONFIG_CC STREQUAL "")
    get_filename_component(CXX_SHORT "${CMAKE_CXX_COMPILER}" NAME)
    if(NOT CXX_SHORT STREQUAL "${CONFIG_CC}")
        message(WARNING "CMAKE_CXX_COMPILER=${CMAKE_CXX_COMPILER} 与 Kconfig 中 CONFIG_CC=${CONFIG_CC} 不一致")
        message(WARNING "切换编译器需执行: rm -rf build && cmake -B build")
    endif()
endif()

if(DEFINED CONFIG_CC AND NOT CONFIG_CC STREQUAL "")
    if(NOT DEFINED CMAKE_CXX_COMPILER OR CMAKE_CXX_COMPILER STREQUAL "")
        set(CMAKE_CXX_COMPILER "${CONFIG_CC}")
    endif()
endif()

project(cachesim LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 收集编译和链接标志
set(CXX_FLAGS -std=c++26)
set(LD_FLAGS)

macro(flag_list_out lst_out)
    set(${lst_out})
endmacro()

macro(add_cxx flag)
    list(APPEND CXX_FLAGS ${flag})
endmacro()
macro(add_ld flag)
    list(APPEND LD_FLAGS ${flag})
endmacro()
macro(add_both flag)
    list(APPEND CXX_FLAGS ${flag})
    list(APPEND LD_FLAGS ${flag})
endmacro()

macro(if_y var flag)
    if(${var} STREQUAL "y")
        list(APPEND CXX_FLAGS ${flag})
    endif()
endmacro()
macro(if_y_both var flag)
    if(${var} STREQUAL "y")
        list(APPEND CXX_FLAGS ${flag})
        list(APPEND LD_FLAGS ${flag})
    endif()
endmacro()

# 标准库 (仅 Clang 需要 -stdlib= 标志, GCC 默认 libstdc++)
if(IS_CLANG AND DEFINED CONFIG_STDLIB_FLAG AND NOT CONFIG_STDLIB_FLAG STREQUAL "")
    add_cxx("${CONFIG_STDLIB_FLAG}")
endif()
if(DEFINED CONFIG_CC_OPT AND NOT CONFIG_CC_OPT STREQUAL "")
    add_cxx("${CONFIG_CC_OPT}")
endif()
if(DEFINED CONFIG_LD_FLAG AND NOT CONFIG_LD_FLAG STREQUAL "")
    add_ld("${CONFIG_LD_FLAG}")
endif()

if_y_both(CONFIG_CC_LTO "-flto")

if(CONFIG_MARCH_NATIVE STREQUAL "y")
    add_cxx("-march=native")
elseif(CONFIG_MARCH_CUSTOM STREQUAL "y" AND DEFINED CONFIG_MARCH_CUSTOM_VALUE)
    add_cxx("-march=${CONFIG_MARCH_CUSTOM_VALUE}")
endif()

if_y(CONFIG_CC_WERROR "-Werror")
if_y(CONFIG_CC_WALL "-Wall")
if_y(CONFIG_CC_WALL "-Wextra")
if_y(CONFIG_CC_WCONVERSION "-Wconversion")
if_y(CONFIG_CC_PEDANTIC "-Wpedantic")
if_y(CONFIG_CC_DEBUG "-g")
if_y_both(CONFIG_CC_ASAN "-fsanitize=address")
if_y_both(CONFIG_CC_UBSAN "-fsanitize=undefined")

# 标准库硬化 (P3471): libstdc++ 单级开关, libc++ 按挡位
if(CONFIG_LIB_HARDENING STREQUAL "y")
    if(CONFIG_STDLIB_LIBCXX STREQUAL "y")
        add_cxx("-D_LIBCPP_HARDENING_MODE=${CONFIG_LIB_HARDENING_MODE}")
    else()
        add_cxx("-D_GLIBCXX_ASSERTIONS")
    endif()
endif()

# cache 参数注入编译定义
list(APPEND CXX_FLAGS
    -DCACHE_BLOCK_SIZE=${CACHE_BLOCK_SIZE}
    -DCACHE_NUM_BLOCKS=${CACHE_NUM_BLOCKS}
    -DCACHE_WAYS=${CACHE_WAYS}
    -DCACHE_REPL_POLICY=${REPL_POLICY}
    -DRUN_MODE=${CONFIG_RUN_MODE}
    -DCACHESIM_DIR=\"${PRJ_DIR}\"
)

# 编译器类型检测
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    set(IS_CLANG TRUE)
else()
    set(IS_CLANG FALSE)
    list(APPEND CXX_FLAGS -fmodules)
endif()

# 标准库模块源: 按编译器+标准库选择
#   GCC                        → libstdc++ 的 bits/std.cc
#   Clang + libstdc++          → libstdc++ 的 bits/std.cc
#   Clang + libc++ (LLVM)      → libc++ 自带 std.cppm (/usr/share/libc++/v1/)
set(STD_MODULE_OBJ ${BLD_DIR}/std_module.o)

if(IS_CLANG AND CONFIG_STDLIB_LIBCXX STREQUAL "y")
    # 使用 libc++ 的 std.cppm
    set(STD_MODULE_SRC /usr/share/libc++/v1/std.cppm)
    set(PCM_DIR ${BLD_DIR}/pcm_cache)
    set(STD_PCM ${PCM_DIR}/std.pcm)

    add_custom_command(
        OUTPUT ${STD_PCM}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${PCM_DIR}
        COMMAND ${CMAKE_CXX_COMPILER} -std=c++26 -stdlib=libc++
            -x c++-module --precompile ${STD_MODULE_SRC} -o ${STD_PCM}
        DEPENDS ${STD_MODULE_SRC}
        COMMENT "预编译 std.pcm (Clang+libc++)"
    )
    add_custom_command(
        OUTPUT ${STD_MODULE_OBJ}
        COMMAND ${CMAKE_CXX_COMPILER} -std=c++26 -stdlib=libc++
            -c ${STD_PCM} -o ${STD_MODULE_OBJ}
        DEPENDS ${STD_PCM}
        COMMENT "编译 std_module.o (libc++)"
    )
else()
    file(GLOB STD_MODULE_SRC_LIST "/usr/include/c++/*/bits/std.cc")
    list(GET STD_MODULE_SRC_LIST 0 STD_MODULE_SRC)

    if(IS_CLANG)
        set(PCM_DIR ${BLD_DIR}/pcm_cache)
        set(STD_PCM ${PCM_DIR}/std.pcm)
        add_custom_command(
            OUTPUT ${STD_PCM}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${PCM_DIR}
            COMMAND ${CMAKE_CXX_COMPILER} -std=c++26
                -x c++-module --precompile ${STD_MODULE_SRC} -o ${STD_PCM}
            DEPENDS ${STD_MODULE_SRC}
            COMMENT "预编译 std.pcm (Clang+libstdc++)"
        )
        add_custom_command(
            OUTPUT ${STD_MODULE_OBJ}
            COMMAND ${CMAKE_CXX_COMPILER} -std=c++26
                -c ${STD_PCM} -o ${STD_MODULE_OBJ}
            DEPENDS ${STD_PCM}
            COMMENT "编译 std_module.o (libstdc++)"
        )
    else()
        set(PCM_DIR)
        add_custom_command(
            OUTPUT ${STD_MODULE_OBJ}
            COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                -x c++ -c ${STD_MODULE_SRC} -o ${STD_MODULE_OBJ}
            DEPENDS ${STD_MODULE_SRC}
            COMMENT "编译 std 模块 (GCC)"
        )
    endif()
endif()

# 用户模块
set(IXX_SRCS
    ${SRC_DIR}/types.ixx
    ${SRC_DIR}/cache.ixx
    ${SRC_DIR}/trace.ixx
    ${SRC_DIR}/stats.ixx
    ${SRC_DIR}/dse.ixx
)

set(CPP_SRCS
    ${SRC_DIR}/main.cpp
    ${SRC_DIR}/cache.cpp
    ${SRC_DIR}/trace.cpp
    ${SRC_DIR}/stats.cpp
    ${SRC_DIR}/dse.cpp
)

# 提取模块导出名: "export module foo;" → "foo"
function(get_module_name ixx_file out_var)
    file(READ ${ixx_file} content)
    string(REGEX MATCH "export module ([a-zA-Z0-9_]+)" _ ${content})
    if(CMAKE_MATCH_1)
        set(${out_var} "${CMAKE_MATCH_1}" PARENT_SCOPE)
    else()
        set(${out_var} "" PARENT_SCOPE)
    endif()
endfunction()

set(MODULE_OBJS "")

foreach(IXX_SRC ${IXX_SRCS})
    get_filename_component(IXX_NAME ${IXX_SRC} NAME_WE)
    get_module_name(${IXX_SRC} MOD_NAME)
    set(MODULE_OBJ ${BLD_DIR}/${MOD_NAME}.ixx.o)
    list(APPEND MODULE_OBJS ${MODULE_OBJ})

    if(IS_CLANG)
        set(MOD_PCM ${PCM_DIR}/${MOD_NAME}.pcm)
        list(APPEND ALL_PCMS ${MOD_PCM})

        # 依赖：types.pcm 必须先于 cache.pcm / stats.pcm
        set(PCM_DEPS ${STD_PCM})
        if(MOD_NAME STREQUAL "cache" OR MOD_NAME STREQUAL "stats" OR MOD_NAME STREQUAL "dse")
            list(APPEND PCM_DEPS ${PCM_DIR}/types.pcm)
        endif()
        if(MOD_NAME STREQUAL "dse")
            list(APPEND PCM_DEPS ${PCM_DIR}/cache.pcm)
        endif()

        add_custom_command(
            OUTPUT ${MOD_PCM}
            COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                -fprebuilt-module-path=${PCM_DIR} -Wno-reserved-module-identifier
                -x c++-module --precompile ${IXX_SRC} -o ${MOD_PCM}
            DEPENDS ${IXX_SRC} ${PCM_DEPS}
            COMMENT "预编译模块 ${MOD_NAME}"
        )
        add_custom_command(
            OUTPUT ${MODULE_OBJ}
            COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                -fprebuilt-module-path=${PCM_DIR}
                -c ${MOD_PCM} -o ${MODULE_OBJ}
            DEPENDS ${MOD_PCM}
            COMMENT "编译模块 ${MOD_NAME}.o"
        )
    else()
        # GCC: 依赖顺序
        set(IXX_DEPS ${IXX_SRC} ${STD_MODULE_OBJ})
        if(MOD_NAME STREQUAL "cache" OR MOD_NAME STREQUAL "stats" OR MOD_NAME STREQUAL "dse")
            list(APPEND IXX_DEPS ${BLD_DIR}/types.ixx.o)
        endif()
        if(MOD_NAME STREQUAL "dse")
            list(APPEND IXX_DEPS ${BLD_DIR}/cache.ixx.o)
        endif()

        add_custom_command(
            OUTPUT ${MODULE_OBJ}
            COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                -x c++ -c ${IXX_SRC} -o ${MODULE_OBJ}
            DEPENDS ${IXX_DEPS}
            WORKING_DIRECTORY ${BLD_DIR}
            COMMENT "编译模块 ${IXX_NAME}"
        )
    endif()
endforeach()

# 可执行文件（.cpp 由 CMake 编译, .ixx 模块对象由自定义命令提供）
add_executable(cachesim ${CPP_SRCS} ${MODULE_OBJS} ${STD_MODULE_OBJ})

target_compile_options(cachesim PRIVATE ${CXX_FLAGS})

foreach(CPP_FILE ${CPP_SRCS})
    set_source_files_properties(${CPP_FILE} PROPERTIES
        OBJECT_DEPENDS "${MODULE_OBJS};${STD_MODULE_OBJ}"
    )
endforeach()

if(IS_CLANG)
    target_compile_options(cachesim PRIVATE -fprebuilt-module-path=${PCM_DIR})
endif()
if(LD_FLAGS)
    target_link_options(cachesim PRIVATE ${LD_FLAGS})
endif()
if(IS_CLANG)
    if(CONFIG_STDLIB_LIBCXX STREQUAL "y")
        # libc++: 显式链接 libc++ 与 libc++abi
        target_link_libraries(cachesim PRIVATE c++ c++abi)
    endif()
endif()

# menuconfig 目标
add_custom_target(menuconfig
    COMMAND TERM=xterm LC_ALL=C.utf8 ${MCONF} ${KCONFIG}
    COMMAND ${CONF} -s --syncconfig ${KCONFIG}
    COMMAND ${CMAKE_COMMAND} -E remove -f ${BLD_DIR}/CMakeCache.txt
    COMMAND ${CMAKE_COMMAND} ${SRC_DIR} -B ${BLD_DIR}
    WORKING_DIRECTORY ${SRC_DIR}
    USES_TERMINAL
    COMMENT "运行 menuconfig..."
)
