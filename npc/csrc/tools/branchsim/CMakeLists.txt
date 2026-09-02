cmake_minimum_required(VERSION 3.21)

set(PRJ_DIR   ${CMAKE_CURRENT_SOURCE_DIR})
set(SRC_DIR   ${PRJ_DIR}/src)
set(BLD_DIR   ${CMAKE_CURRENT_BINARY_DIR})
set(NPC_BUILD ${PRJ_DIR}/../../../tools/build)
set(CONF      ${NPC_BUILD}/c_conf)
set(MCONF     ${NPC_BUILD}/c_mconf)
set(KCONFIG   ${PRJ_DIR}/Kconfig)
set(AUTO_CONF ${PRJ_DIR}/include/config/auto.conf)

# 初次配置时用 alldefconfig 生成 .config, 之后用 syncconfig 同步 include/
if(NOT EXISTS "${PRJ_DIR}/.config")
    execute_process(COMMAND ${CONF} -s --alldefconfig ${KCONFIG}
        WORKING_DIRECTORY ${PRJ_DIR})
endif()
execute_process(COMMAND ${CONF} -s --syncconfig ${KCONFIG}
    WORKING_DIRECTORY ${PRJ_DIR})

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

project(branchsim LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 编译器类型检测
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    set(IS_CLANG TRUE)
else()
    set(IS_CLANG FALSE)
endif()

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

# 分支预测器参数注入编译定义
list(APPEND CXX_FLAGS
    -DBTB_BITS=${CONFIG_BTB_BITS}
    -DBTB_WAYS=${CONFIG_BTB_WAYS}
    -DJAL_BTB_BITS=${CONFIG_JAL_BTB_BITS}
    -DJAL_BTB_WAYS=${CONFIG_JAL_BTB_WAYS}
    -DRAS_BITS=${CONFIG_RAS_BITS}
    -DRUN_MODE=${CONFIG_RUN_MODE}
    -DBRANCHSIM_DIR=\"${PRJ_DIR}\"
)

# GCC 需要 -fmodules 才能使用 import std
if(NOT IS_CLANG)
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
    set(STDXX_FLAGS -stdlib=libc++)

    add_custom_command(
        OUTPUT ${STD_PCM}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${PCM_DIR}
        COMMAND ${CMAKE_CXX_COMPILER} -std=c++26 ${STDXX_FLAGS}
            -x c++-module --precompile ${STD_MODULE_SRC} -o ${STD_PCM}
        DEPENDS ${STD_MODULE_SRC}
        COMMENT "预编译 std.pcm (Clang+libc++)"
    )
    add_custom_command(
        OUTPUT ${STD_MODULE_OBJ}
        COMMAND ${CMAKE_CXX_COMPILER} -std=c++26 ${STDXX_FLAGS}
            -c ${STD_PCM} -o ${STD_MODULE_OBJ}
        DEPENDS ${STD_PCM}
        COMMENT "编译 std_module.o (libc++)"
    )
else()
    # GCC 或 Clang+libstdc++: 统一用 libstdc++ 的 bits/std.cc
    file(GLOB STD_MODULE_SRC_LIST "/usr/include/c++/*/bits/std.cc")
    list(GET STD_MODULE_SRC_LIST 0 STD_MODULE_SRC)
    set(STDXX_FLAGS)

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
        add_custom_command(
            OUTPUT ${STD_MODULE_OBJ}
            COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                -x c++ -c ${STD_MODULE_SRC} -o ${STD_MODULE_OBJ}
            DEPENDS ${STD_MODULE_SRC}
            COMMENT "编译 std 模块 (GCC)"
        )
    endif()
endif()

# 用户模块 (.ixx)
file(GLOB IXX_SRCS ${SRC_DIR}/*.ixx)
set(MODULE_OBJS "")

if(IXX_SRCS)
    if(IS_CLANG)
        set(IXX_PCM_DIR ${BLD_DIR}/pcm_cache)
        file(MAKE_DIRECTORY ${IXX_PCM_DIR})
    endif()

    foreach(IXX_SRC ${IXX_SRCS})
        get_filename_component(IXX_NAME ${IXX_SRC} NAME_WE)
        set(MOD_OBJ ${BLD_DIR}/${IXX_NAME}.ixx.o)
        list(APPEND MODULE_OBJS ${MOD_OBJ})

        if(IS_CLANG)
            set(MOD_PCM ${IXX_PCM_DIR}/${IXX_NAME}.pcm)
            add_custom_command(
                OUTPUT ${MOD_PCM}
                COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                    -fprebuilt-module-path=${PCM_DIR} -Wno-reserved-module-identifier
                    -x c++-module --precompile ${IXX_SRC} -o ${MOD_PCM}
                DEPENDS ${IXX_SRC} ${STD_PCM}
                COMMENT "预编译模块 ${IXX_NAME}"
            )
            add_custom_command(
                OUTPUT ${MOD_OBJ}
                COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                    -fprebuilt-module-path=${IXX_PCM_DIR}
                    -c ${MOD_PCM} -o ${MOD_OBJ}
                DEPENDS ${MOD_PCM}
                COMMENT "编译模块 ${IXX_NAME}.o"
            )
        else()
            # GCC: 显式声明模块间依赖, 保证被 import 的模块先编译
            #   依赖链: BPConfig/BranchRecord → BTB → BPAlgorithmBase → 各算法 → BPAlgorithmsType
            #            BranchRecord → BTraceReader, RAS 无额外依赖
            set(IXX_DEPS ${IXX_SRC} ${STD_MODULE_OBJ})
            if(IXX_NAME STREQUAL "BTB")
                list(APPEND IXX_DEPS ${BLD_DIR}/BPConfig.ixx.o ${BLD_DIR}/BranchRecord.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "BPAlgorithmBase")
                list(APPEND IXX_DEPS ${BLD_DIR}/BTB.ixx.o ${BLD_DIR}/BPConfig.ixx.o ${BLD_DIR}/BranchRecord.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "AlwaysJump" OR IXX_NAME STREQUAL "BTFN")
                list(APPEND IXX_DEPS ${BLD_DIR}/BPAlgorithmBase.ixx.o ${BLD_DIR}/BranchRecord.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "BTFNSharedJal" OR IXX_NAME STREQUAL "BTFNSplitJal")
                list(APPEND IXX_DEPS ${BLD_DIR}/BPAlgorithmBase.ixx.o ${BLD_DIR}/BranchRecord.ixx.o ${BLD_DIR}/BTB.ixx.o ${BLD_DIR}/BPConfig.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "BTFNSplitJalRAS")
                list(APPEND IXX_DEPS ${BLD_DIR}/BPAlgorithmBase.ixx.o ${BLD_DIR}/BranchRecord.ixx.o ${BLD_DIR}/BTB.ixx.o ${BLD_DIR}/BPConfig.ixx.o ${BLD_DIR}/RAS.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "BPAlgorithmsType")
                list(APPEND IXX_DEPS ${BLD_DIR}/BPAlgorithmBase.ixx.o ${BLD_DIR}/AlwaysJump.ixx.o ${BLD_DIR}/BTFN.ixx.o ${BLD_DIR}/BTFNSharedJal.ixx.o ${BLD_DIR}/BTFNSplitJal.ixx.o ${BLD_DIR}/BTFNSplitJalRAS.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "BTraceReader")
                list(APPEND IXX_DEPS ${BLD_DIR}/BranchRecord.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "Run")
                list(APPEND IXX_DEPS ${BLD_DIR}/BPAlgorithmsType.ixx.o ${BLD_DIR}/BPAlgorithmBase.ixx.o ${BLD_DIR}/BranchRecord.ixx.o ${BLD_DIR}/stats.ixx.o)
            endif()
            if(IXX_NAME STREQUAL "dse")
                list(APPEND IXX_DEPS ${BLD_DIR}/BPConfig.ixx.o ${BLD_DIR}/BPAlgorithmsType.ixx.o ${BLD_DIR}/BPAlgorithmBase.ixx.o ${BLD_DIR}/BranchRecord.ixx.o ${BLD_DIR}/Run.ixx.o ${BLD_DIR}/stats.ixx.o)
            endif()
            add_custom_command(
                OUTPUT ${MOD_OBJ}
                COMMAND ${CMAKE_CXX_COMPILER} ${CXX_FLAGS}
                    -x c++ -c ${IXX_SRC} -o ${MOD_OBJ}
                DEPENDS ${IXX_DEPS}
                WORKING_DIRECTORY ${BLD_DIR}
                COMMENT "编译模块 ${IXX_NAME}"
            )
        endif()
    endforeach()
endif()

# 应用源文件
file(GLOB CPP_SRCS ${SRC_DIR}/*.cpp)

add_executable(branchsim ${CPP_SRCS} ${MODULE_OBJS} ${STD_MODULE_OBJ})
target_compile_options(branchsim PRIVATE ${CXX_FLAGS})

# 让依赖模块的 .cpp 在模块对象之后编译
foreach(CPP_FILE ${CPP_SRCS})
    set_source_files_properties(${CPP_FILE} PROPERTIES
        OBJECT_DEPENDS "${MODULE_OBJS};${STD_MODULE_OBJ}"
    )
endforeach()

if(IS_CLANG)
    target_compile_options(branchsim PRIVATE -fprebuilt-module-path=${IXX_PCM_DIR};${PCM_DIR})
endif()
if(LD_FLAGS)
    target_link_options(branchsim PRIVATE ${LD_FLAGS})
endif()
if(IS_CLANG)
    if(CONFIG_STDLIB_LIBCXX STREQUAL "y")
        target_link_libraries(branchsim PRIVATE c++ c++abi)
    endif()
endif()

# menuconfig 目标
add_custom_target(menuconfig
    COMMAND TERM=xterm LC_ALL=C.utf8 ${MCONF} ${KCONFIG}
    COMMAND ${CONF} -s --syncconfig ${KCONFIG}
    COMMAND ${CMAKE_COMMAND} -E remove -f ${BLD_DIR}/CMakeCache.txt
    COMMAND ${CMAKE_COMMAND} ${PRJ_DIR} -B ${BLD_DIR}
    WORKING_DIRECTORY ${PRJ_DIR}
    USES_TERMINAL
    COMMENT "运行 menuconfig..."
)