#ifndef PSRAMMEMTEST_HPP
#define PSRAMMEMTEST_HPP
#include <cstdint>
#include <expected>
#include <cstddef>
extern "C" std::expected<void,std::uintptr_t> psram_mem_test(std::uintptr_t begin,std::size_t length);
#endif