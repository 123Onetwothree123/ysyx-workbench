#include "ProgramLoader.h"
#include "program_data.h" // xxd 生成的文件

BinaryBuffer ProgramLoader::GetInternalBinary() {
    BinaryBuffer buf;
    // xxd -i vga.bin 会生成 vga_bin 和 vga_bin_len
    buf.data = (const uint8_t*)vga_bin; 
    buf.size = (size_t)vga_bin_len;
    return buf;
}