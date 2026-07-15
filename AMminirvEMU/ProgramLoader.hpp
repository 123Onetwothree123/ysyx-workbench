#include <cstdint>
#ifndef PROGRAM_LOADER_H
#define PROGRAM_LOADER_H

#include <stdint.hpp>
#include <stddef.hpp>

// 定义一个简单的结构体返回数据
struct BinaryBuffer {
    const std::uint8_t* data;
    std::size_t size;
};

class ProgramLoader {
public:
    // 不再需要路径，直接返回编译时嵌入的代码
    static BinaryBuffer GetInternalBinary();
};

#endif