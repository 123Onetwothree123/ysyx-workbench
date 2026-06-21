// 牛逼，好不容易写完了，结果发现AM居然是C++17，但是这个起码可以makefile直接传2023过去，但是好像AM不支持模块，一编译全是报错
#ifndef MEM_TEST_HPP
#define MEM_TEST_HPP
#include <cstdint>
#include <expected>
#include <cstddef>
// #include <string>我苦思冥想，为什么这玩意编译失败
// extern "C" std::expected<std::size_t, std::string> mem_test(std::size_t addr, std::size_t len);
extern "C" std::expected<void,std::uintptr_t> mem_test(std::uintptr_t begin,std::size_t length);
#endif
