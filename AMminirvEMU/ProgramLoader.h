#ifndef PROGRAM_LOADER_H
#define PROGRAM_LOADER_H

#include <stdint.h>
#include <stddef.h>

// 定义一个简单的结构体返回数据
struct BinaryBuffer {
    const uint8_t* data;
    size_t size;
};

class ProgramLoader {
public:
    // 不再需要路径，直接返回编译时嵌入的代码
    static BinaryBuffer GetInternalBinary();
};

#endif