#include <verilated.h>
#include <Vminirvcpu.h>
#include <print>
#include <iostream>
#include <cstdint>
#include <memory>
#include <filesystem>
#include <fstream>
#include <array>
constexpr uint32_t PMEM_SIZE = 128 * 1024 * 1024; // 抄AM的
constexpr uint32_t CONFIG_MBASE = 0x80000000;     // 内存基地址
std::array<uint8_t, PMEM_SIZE> pmem{};            // 抄am拿数组当内存
static bool npc_halted = false;
static uint32_t halt_pc{0};  // 记录停止的时候的PC
static uint32_t halt_ret{0}; // 返回码，0是good，1是bad
bool check_pmem_safe_address(uint32_t address, size_t len = 1);
size_t load_file(const std::filesystem::path &FilePath);
void clock_change(std::unique_ptr<Vminirvcpu> &top); // 妈的直接把拉clk封装然后缩到函数里面
void reset_dut(std::unique_ptr<Vminirvcpu> &top);    // 复位Device Under Test被测设计的
// 检查地址是否在pmem范围内
inline bool check_pmem_range(uint32_t addr, size_t len = 1)
{
    return addr >= CONFIG_MBASE && (addr - CONFIG_MBASE + len) <= PMEM_SIZE;
}
// 将guest物理地址转换为host地址（pmem数组索引）
inline uint32_t guest_to_host(uint32_t gaddr)
{
    return gaddr - CONFIG_MBASE;
}
extern "C" int pmem_read(int raddr)
{
    // 总是读取地址为`raddr & ~0x3u`的4字节返回
    auto addr{static_cast<uint32_t>(raddr)};
    addr &= ~0x3u; // 4字节对齐
    // 检查地址是否在有效范围内
    if (!check_pmem_range(addr, 4))
    {
        std::println(std::cerr, "pmem读取越界，guest_addr = 0x{:x}", addr);
        std::abort();
    }
    // 地址转换：将guest地址转换为pmem数组索引
    auto host_addr = guest_to_host(addr);
    uint32_t data{0};
    data |= static_cast<uint32_t>(pmem[host_addr + 0]) << 0;
    data |= static_cast<uint32_t>(pmem[host_addr + 1]) << 8;
    data |= static_cast<uint32_t>(pmem[host_addr + 2]) << 16;
    data |= static_cast<uint32_t>(pmem[host_addr + 3]) << 24;
    return static_cast<int>(data);
}
extern "C" void pmem_write(int waddr, int wdata, char wmask)
{
    // 总是往地址为`waddr & ~0x3u`的4字节按写掩码`wmask`写入`wdata`
    // `wmask`中每比特表示`wdata`中1个字节的掩码,
    // 如`wmask = 0x3`代表只写入最低2个字节, 内存中的其它字节保持不变
    auto guest_addr{static_cast<uint32_t>(waddr & ~0x3u)};
    auto data{static_cast<uint32_t>(wdata)};
    auto mask{static_cast<uint8_t>(wmask)};
    // 检查地址是否在有效范围内
    if (!check_pmem_range(guest_addr, 4))
    {
        std::println(std::cerr, "pmem写入越界，guest_addr = 0x{:x}", guest_addr);
        std::abort();
    }
    // 地址转换：将guest地址转换为pmem数组索引
    auto host_addr = guest_to_host(guest_addr);
    // 妈的实在是背不下来掩码了，直接注释标记了
    //  检查掩码的第0位，决定是否写入最低字节
    if (mask & 0x01)
    {
        pmem[host_addr + 0] = static_cast<uint8_t>(data & 0xFF);
    }
    // 检查掩码的第1位，决定是否写入第1字节
    if (mask & 0x02)
    {
        pmem[host_addr + 1] = static_cast<uint8_t>((data >> 8) & 0xFF);
    }
    // 检查掩码的第2位，决定是否写入第2字节
    if (mask & 0x04)
    {
        pmem[host_addr + 2] = static_cast<uint8_t>((data >> 16) & 0xFF);
    }
    // 检查掩码的第3位，决定是否写入第3字节
    if (mask & 0x08)
    {
        pmem[host_addr + 3] = static_cast<uint8_t>((data >> 24) & 0xFF);
    }
}
extern "C" void npc_ebreak(int pc, int code)
{
    npc_halted = true;
    halt_pc = static_cast<uint32_t>(pc);
    halt_ret = static_cast<uint32_t>(code);
}
bool check_pmem_safe_address(uint32_t address, size_t len)
{
    auto result{(address + len) <= PMEM_SIZE};
    return result;
}
size_t load_file(const std::filesystem::path &FilePath)
{
    if (!std::filesystem::exists(FilePath))
    {
        std::println(std::cerr, "他妈的文件路径没找到文件{}", FilePath.string());
        std::abort();
    }
    if (!std::filesystem::is_regular_file(FilePath))
    {
        std::println(std::cerr, "fuck，不是普通文件{}", FilePath.string());
        std::abort();
    }
    auto FileSize{std::filesystem::file_size(FilePath)};
    if (FileSize > PMEM_SIZE)
    {
        std::println(std::cerr, "文件太大了{}", FilePath.string());
        std::abort();
    }
    std::ifstream ifs(FilePath, std::ios::binary);
    if (!ifs)
    {
        std::println(std::cerr, "文件打不开{}", FilePath.string());
        std::abort();
    }
    ifs.read(reinterpret_cast<char *>(pmem.data()), static_cast<std::streamsize>(FileSize));
    if (!ifs)
    {
        std::println(std::cerr, "读文件失败{}", FilePath.string());
        std::abort();
    }
    return static_cast<std::size_t>(FileSize);
}
void clock_change(std::unique_ptr<Vminirvcpu> &top)
{
    top->clk = 0;
    top->eval();
    top->clk = 1;
    top->eval();
}
void reset_dut(std::unique_ptr<Vminirvcpu> &top)
{
    top->clk = 0;
    top->rst = 1;
    top->eval();
    top->clk = 1;
    top->eval();
    top->clk = 0;
    top->rst = 0;
    top->eval();
}
int main(int argc, char const *argv[])
{
    if (argc < 2)
    {
        std::println(std::cerr, "缺少测试文件参数");
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::filesystem::path FilePath{argv[1]};
    const auto FileSize{load_file(FilePath)};
    std::println("文件加载了: {}, size = {} bytes", FilePath.string(), FileSize);
    auto top{std::make_unique<Vminirvcpu>()}; // 管不了了复制修改以前代码，直接创建顶层的对象然后实例
    uint64_t cycles{0};                       // 统计总周期数的
    reset_dut(top);
    while (!Verilated::gotFinish() && !npc_halted)
    {
        clock_change(top);
        ++cycles;
    }
    top->final();
    if (npc_halted)
    {
        if (halt_ret == 0)
        {
            std::println("HIT GOOD TRAP at pc = 0x{:08x}, cycles = {}", halt_pc, cycles);
            return 0;
        }
        std::println(std::cerr, "HIT BAD TRAP at pc = 0x{:08x}, code = {}, cycles = {}", halt_pc, halt_ret, cycles);
        return 1;
    }
    return 0;
}
