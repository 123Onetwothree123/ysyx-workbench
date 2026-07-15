#include <am.hpp>
#include <klib.hpp>
#include "minirvEMU.hpp"
#include "ProgramLoader.hpp"

// 解决 AM 宏定义与 C++ static_assert 关键字的冲突
#ifdef static_assert
#undef static_assert
#endif

int main(const char *args) {
    if (!ioe_init()) return -1;
    minirvEMU emu;
    emu.init_vga();

    BinaryBuffer code = ProgramLoader::GetInternalBinary();
    for (std::size_t i {0}; i < code.size; ++i) {
        emu.write_byte(i, code.data[i]);
    }

    printf("Program Loaded: %u bytes. Start PC: 0x%08x\n", (unsigned int)code.size, (unsigned int)emu.GetPC());

    std::uint32_t inst_count {0};
    while (!emu.IsHalted()) {
        emu.step();
        
        // 关键：每 10 万条指令打印一次 PC 状态
        if (++inst_count % 100000 == 0) {
            printf("Running... PC: 0x%08x | Instructions: %u\n", (unsigned int)emu.GetPC(), (unsigned int)inst_count);
            emu.update_vga();
        }
    }
    emu.update_vga();
    while (1);
    return 0;
}