#include "NPC_SDB.h"
#include "memory.h"
#include <iostream>
// 全局状态，true就是表示NPC停止，是EBREAK_DPI-C来设置
extern bool npc_halted;
uint32_t CPP_NPCGetGPR(int reg_num)
{
    if (reg_num < 0 || reg_num >= 32)
    {
        return 0;
    }
    return static_cast<uint32_t>(NPCGetGPR(reg_num));
}
void PrintGPR()
{
    for (int i = 0; i < 32; i++)
    {
        uint32_t val = CPP_NPCGetGPR(i);
        std::println("  x{:02} = 0x{:08x}", i, val);
    }
}
uint32_t NPCMemoryRead(uint32_t addr, size_t len)
{
    if (len != 1 && len != 2 && len != 4)
    {
        std::println(std::cerr, "NPCMemoryRead：不支持的长度 {}", len);
        return 0;
    }
    if (!check_pmem_range(addr, len))
    {
        std::println(std::cerr, "NPCMemoryRead:：地址越界 0x{:08x}, len={}", addr, len);
        return 0;
    }
    uint32_t HostAddr = guest_to_host(addr);
    uint32_t data = 0;
    for (size_t i = 0; i < len; i++)
    {
        data |= static_cast<uint32_t>(pmem[HostAddr + i]) << (i * 8);
    }
    return data;
}
void NPCMemoryScan(uint32_t addr, size_t count)
{
    addr &= ~0x3u; // 4字节对齐
    for (size_t i = 0; i < count; i++)
    {
        uint32_t current = addr + i * 4;
        if (!check_pmem_range(current, 4))
        {
            std::println(std::cerr, "NPCMemoryScan：地址越界 0x{:08x}", current);
            break;
        }
        uint32_t value = NPCMemoryRead(current, 4);
        if (i % 4 == 0) // 每4个一组
        {
            std::print("0x{:08x}:", current);
        }
        std::print(" 0x{:08x}", value);
        if (i % 4 == 3 || i == count - 1) // 一组满了四个或者到了最后一个就直接就换行
        {
            std::println("");
        }
    }
}
uint32_t CPP_NpcGetPC()
{
    return static_cast<uint32_t>(NPCGetPC());
}
static void step_cycle(VRV32E32Reg &top)
{
    top.clk = 0;
    top.eval();
    top.clk = 1;
    top.eval();
}
void sdb_main_loop(std::unique_ptr<VRV32E32Reg> &top, size_t &cycles)
{
    std::string cmd;
    while (std::getline(std::cin, cmd))
    {
        if (cmd.starts_with("si"))
        {
            size_t n{1};                                                                                // 学NEMU的，模拟1步
            std::string_view StringView(cmd);                                                           // 用cmd初始化，最开始用的是StringView=cmd，后面发现不行，没法直接操作
            StringView.remove_prefix(2);                                                                // 去掉si
            StringView.remove_prefix(std::min(StringView.find_first_not_of(" \t"), StringView.size())); // 跳过空白
            if (!StringView.empty())
            {
                std::ptrdiff_t TmpN{0}; // 临时存储解析的结果的
                auto FromCharResult{std::from_chars(StringView.data(), StringView.data() + StringView.size(), TmpN, 10)};
                if (FromCharResult.ec == std::errc() && TmpN > 0) // 先标记一下这句话的意思是转换成功且为正数
                {
                    n = TmpN; // 更新步数了
                }
            }
        }
        else if (cmd == "c")
        {
            // 懒得换了，直接把main.cpp的抄过来，然后改下函数名了，懒得做按检测和换代码了
            while (!Verilated::gotFinish() && !npc_halted)
            {
                step_cycle(*top);
                ++cycles;
            }
        }
        else if (cmd == "info r")
        {
            PrintGPR();
        }
        else if (cmd.starts_with("x "))
        {
            size_t n{0};       // 要扫描的字数，不是字节数，riscv的1字4字节
            size_t address{0}; // 起始地址
            std::string_view StringView(cmd);
            StringView.remove_prefix(2);
            StringView.remove_prefix(std::min(StringView.find_first_not_of(" \t"), StringView.size()));
            auto FromCharResult{std::from_chars(StringView.data(), StringView.data() + StringView.size(), n, 10)};
            if (FromCharResult.ec == std::errc() && n > 0)
            {
                StringView.remove_prefix(FromCharResult.ptr - StringView.data());
                StringView.remove_prefix(std::min(StringView.find_first_not_of(" \t"), StringView.size()));
                if (!StringView.empty())
                {
                    if (StringView.size() >= 2 && (StringView[0] == '0' && (StringView[1] == 'x' || StringView[1] == 'X')))
                    {
                        StringView.remove_prefix(2);
                        auto FromCharResult2{std::from_chars(StringView.data(), StringView.data() + StringView.size(), address, 10)};
                        if (FromCharResult2.ec != std::errc())
                        {
                            std::println("FromCharResult2错误码不是0了，解析失败了，地址清零");
                            address = 0; // 发现个好办法，可以拿来跳下面的if判断，能少写特殊处理代码了
                        }
                        if (address != 0)
                        {
                            NPCMemoryScan(address, static_cast<size_t>(n));
                        }
                    }
                }
            }
        }
        else if (cmd == "q")
        {
            break;
        }
        else if (!cmd.empty())
        {
            std::println(std::cerr, "鬼知道输入的是什么指令，可能没有实现吧: {}", cmd);
        }
    }
}