cmake_minimum_required(VERSION 3.28)
project(testreg LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 26)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_file(STD_MODULE_SRC
  NAMES std.cc
  PATHS /usr/include/c++/16/bits /usr/include/c++/*/bits
  NO_DEFAULT_PATH)
if(NOT STD_MODULE_SRC)
  message(FATAL_ERROR "找不到 libstdc++ 的 bits/std.cc")
endif()
set(STD_MODULE_OBJ ${CMAKE_BINARY_DIR}/std_module.o)
add_custom_command(
  OUTPUT ${STD_MODULE_OBJ}
  COMMAND ${CMAKE_CXX_COMPILER} -std=c++26 -fmodules-ts -c ${STD_MODULE_SRC} -o ${STD_MODULE_OBJ}
  WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
  DEPENDS ${STD_MODULE_SRC}
  COMMENT "Building std module")
add_custom_target(std_module DEPENDS ${STD_MODULE_OBJ})
set_source_files_properties(${STD_MODULE_OBJ} PROPERTIES EXTERNAL_OBJECT TRUE GENERATED TRUE)

add_executable(testreg testreg.cpp ${STD_MODULE_OBJ})
add_dependencies(testreg std_module)
target_compile_options(testreg PRIVATE -fmodules-ts -Wall -Wextra -Wpedantic)
target_compile_definitions(testreg PRIVATE
  TESTREG_TEST_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../..")
