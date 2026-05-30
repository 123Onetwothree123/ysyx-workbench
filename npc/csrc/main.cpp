#include <verilated.h>
#include <VRV32I.h>
#include <print>
#include <cstddef>
#include <iostream>
#include "CliOptions.hpp"
int main(int argc, char const *argv[])
{
    auto options{CliOptions::Parse(argc, argv)};
    if (!options)
    {
        std::println(std::cerr, "{0}", options.error());
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    const auto image_size{ImageLoader::LoadFromCli(*options)};
    if (!image_size)
    {
        std::println(std::cerr, "{0}", image_size.error());
        return 1;
    }
    return 0;
}
