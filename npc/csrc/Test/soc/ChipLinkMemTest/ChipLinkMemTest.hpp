#ifndef CHIPLINKMEMTEST_HPP
#define CHIPLINKMEMTEST_HPP
#include <expected>
#include <cstdint>
extern "C" std::expected<void, std::uintptr_t> ChipLink_mem_test(std::uintptr_t begin, std::size_t length);
#endif