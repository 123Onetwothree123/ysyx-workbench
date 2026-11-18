#ifndef SDRAMMEMTEST_HPP
#define SDRAMMEMTEST_HPP
#include <cstdint>
#include <expected>
#include <cstddef>
extern "C" std::expected<void, std::uintptr_t> sdram_mem_test(std::uintptr_t begin, std::size_t length);
#endif