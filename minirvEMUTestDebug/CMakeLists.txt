cmake_minimum_required(VERSION 3.28)
project(minirvEMU LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS ON)

set(BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/build)
set(OUT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/build)

# === Compiler detection ===
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    set(IS_CLANG TRUE)
else()
    set(IS_GCC TRUE)
endif()

# === std module ===
if(IS_CLANG)
    set(STD_SRC /usr/share/libc++/v1/std.cppm)
    set(STD_PCM ${BUILD_DIR}/std.pcm)
    add_custom_command(
        OUTPUT ${STD_PCM}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
        COMMAND ${CMAKE_CXX_COMPILER} -std=c++23 -stdlib=libc++
                -x c++-module --precompile ${STD_SRC} -o ${STD_PCM}
        COMMENT "Precompiling std.pcm"
    )
    add_custom_target(std_module DEPENDS ${STD_PCM})
    set(STD_FLAGS -fmodule-file=std=${STD_PCM})
    set(STD_DEP ${STD_PCM})
else()
    file(GLOB GCC_STD_SRC /usr/include/c++/*/bits/std.cc)
    list(GET GCC_STD_SRC -1 STD_SRC)
    set(STD_OBJ ${BUILD_DIR}/std_module.o)
    add_custom_command(
        OUTPUT ${STD_OBJ}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
        COMMAND ${CMAKE_CXX_COMPILER} -std=gnu++23 -fmodules -x c++ -c ${STD_SRC} -o ${STD_OBJ}
        COMMENT "Compiling std module"
    )
    add_custom_target(std_module DEPENDS ${STD_OBJ})
endif()

# === Module compilation helper ===
function(add_ixx MODULE_NAME IXX_FILE)
    set(obj ${BUILD_DIR}/${MODULE_NAME}.ixx.o)
    if(IS_CLANG)
        add_custom_command(
            OUTPUT ${obj}
            COMMAND ${CMAKE_CXX_COMPILER} -std=c++23 -stdlib=libc++
                    -fprebuilt-module-path=${BUILD_DIR} ${STD_FLAGS}
                    -x c++-module --precompile ${CMAKE_CURRENT_SOURCE_DIR}/${IXX_FILE}
                    -o ${BUILD_DIR}/${MODULE_NAME}.pcm
            COMMAND ${CMAKE_CXX_COMPILER} -c -fprebuilt-module-path=${BUILD_DIR} ${STD_FLAGS}
                    ${BUILD_DIR}/${MODULE_NAME}.pcm -o ${obj}
            DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${IXX_FILE} std_module ${ARGN}
            COMMENT "CXX MODULE ${IXX_FILE}"
        )
    else()
        add_custom_command(
            OUTPUT ${obj}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
            COMMAND ${CMAKE_CXX_COMPILER} -std=gnu++23 -fmodules
                    -I${CMAKE_CURRENT_SOURCE_DIR}
                    -x c++ -c ${CMAKE_CURRENT_SOURCE_DIR}/${IXX_FILE} -o ${obj}
            DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${IXX_FILE} std_module ${ARGN}
            COMMENT "CXX MODULE ${IXX_FILE}"
        )
    endif()
    add_custom_target(mod_${MODULE_NAME} DEPENDS ${obj})
    set(MOD_OBJS ${MOD_OBJS} ${obj} PARENT_SCOPE)
endfunction()

add_ixx(minirvemu.Decoder Decoder.ixx)
add_ixx(minirvemu.ImmGen ImmGen.ixx mod_minirvemu.Decoder)
add_ixx(minirvemu.ProgramLoader ProgramLoader.ixx)
add_ixx(minirvemu.emu minirvEMU.ixx mod_minirvemu.Decoder mod_minirvemu.ImmGen)

# === C++ implementation files ===
set(CPP_SRCS
    Decoder.cpp
    ImmGen.cpp
    ProgramLoader.cpp
    minirvEMU.cpp
    main.cpp
)

set(CPP_OBJS)
foreach(cpp ${CPP_SRCS})
    set(obj ${BUILD_DIR}/${cpp}.o)
    list(APPEND CPP_OBJS ${obj})
    if(IS_CLANG)
        add_custom_command(
            OUTPUT ${obj}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
            COMMAND ${CMAKE_CXX_COMPILER} -std=c++23 -stdlib=libc++
                    -fprebuilt-module-path=${BUILD_DIR} ${STD_FLAGS}
                    -I${CMAKE_CURRENT_SOURCE_DIR}
                    -c ${CMAKE_CURRENT_SOURCE_DIR}/${cpp} -o ${obj}
            DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${cpp} mod_minirvemu.Decoder mod_minirvemu.ImmGen mod_minirvemu.ProgramLoader mod_minirvemu.emu std_module
            COMMENT "CXX ${cpp}"
        )
    else()
        add_custom_command(
            OUTPUT ${obj}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
            COMMAND ${CMAKE_CXX_COMPILER} -std=gnu++23 -fmodules
                    -I${CMAKE_CURRENT_SOURCE_DIR}
                    -c ${CMAKE_CURRENT_SOURCE_DIR}/${cpp} -o ${obj}
            DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${cpp} mod_minirvemu.Decoder mod_minirvemu.ImmGen mod_minirvemu.ProgramLoader mod_minirvemu.emu std_module
            COMMENT "CXX ${cpp}"
        )
    endif()
endforeach()

add_custom_target(npc_cpp DEPENDS ${CPP_OBJS})

# === hex2bin (standalone) ===
set(HEX2BIN_OBJ ${BUILD_DIR}/hex2bin.cpp.o)
if(IS_CLANG)
    add_custom_command(
        OUTPUT ${HEX2BIN_OBJ}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
        COMMAND ${CMAKE_CXX_COMPILER} -std=c++23 -stdlib=libc++
                -fprebuilt-module-path=${BUILD_DIR} ${STD_FLAGS}
                -c ${CMAKE_CURRENT_SOURCE_DIR}/hex2bin.cpp -o ${HEX2BIN_OBJ}
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/hex2bin.cpp std_module
        COMMENT "CXX hex2bin.cpp"
    )
else()
    add_custom_command(
        OUTPUT ${HEX2BIN_OBJ}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${BUILD_DIR}
        COMMAND ${CMAKE_CXX_COMPILER} -std=gnu++23 -fmodules
                -c ${CMAKE_CURRENT_SOURCE_DIR}/hex2bin.cpp -o ${HEX2BIN_OBJ}
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/hex2bin.cpp std_module
        COMMENT "CXX hex2bin.cpp"
    )
endif()

# === Link ===
set(BINARY ${OUT_DIR}/minirvEMU)
if(IS_CLANG)
    set(EXTRA_LINK_FLAGS -stdlib=libc++)
    set(EXTRA_OBJS)
else()
    set(EXTRA_LINK_FLAGS)
    set(EXTRA_OBJS ${STD_OBJ})
endif()
add_custom_command(
    OUTPUT ${BINARY}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${OUT_DIR}
    COMMAND ${CMAKE_CXX_COMPILER} -o ${BINARY} ${MOD_OBJS} ${CPP_OBJS} ${EXTRA_OBJS} ${EXTRA_LINK_FLAGS}
    DEPENDS npc_cpp mod_minirvemu.Decoder mod_minirvemu.ImmGen mod_minirvemu.ProgramLoader mod_minirvemu.emu std_module
    COMMENT "Linking minirvEMU"
)

set(HEX2BIN_BIN ${OUT_DIR}/hex2bin)
if(IS_CLANG)
    set(HEX_LINK_FLAGS -stdlib=libc++)
else()
    set(HEX_LINK_FLAGS)
endif()
add_custom_command(
    OUTPUT ${HEX2BIN_BIN}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${OUT_DIR}
    COMMAND ${CMAKE_CXX_COMPILER} -o ${HEX2BIN_BIN} ${HEX2BIN_OBJ} ${HEX_LINK_FLAGS}
    DEPENDS ${HEX2BIN_OBJ}
    COMMENT "Linking hex2bin"
)

add_custom_target(minirvEMU_all ALL DEPENDS ${BINARY} ${HEX2BIN_BIN})
