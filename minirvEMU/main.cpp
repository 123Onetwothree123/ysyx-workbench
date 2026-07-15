#include <iostream>
#include <cstdint>
#include <string>
#include <cstring>
#include <fstream>
#include "ImmGen.h"
#include "Decoder.h"
#include "minirvEMU.h"
#include "ProgramLoader.h"
#include <am.h>
#include <klib.h>
int main(int argc, char const *argv[])
{
    if (argc < 2)
    {
        std::cout << "Usage: " << argv[0] << " <filename.bin>" << std::endl;
        return -1;
    }
    ioe_init();
    minirvEMU emu;
    std::filesystem::path binPath = argv[1];
    auto result = ProgramLoader::LoadBinary(binPath);
    if (!result.has_value())
    {
        std::cout << "load binary fail" << std::endl;
        return -1;
    }
    const std::vector<uint8_t> &code = result.value(); // 拿来解包数据的
    minirvEMU emu;
    for (size_t i = 0; i < code.size(); ++i)
    {
        emu.write_byte(static_cast<uint32_t>(i), code[i]);
    }
    std::cout << "Loaded " << code.size() << " bytes from " << binPath.filename() << std::endl;
    while (!emu.IsHalted())
    {
        emu.step();
    }
    emu.UpdateVGA();
    printf("Simulation halted. Press ESC or Ctrl+C to exit (depending on environment).\n");
    emu.PrintState();
    return 0;
}