#include "Memory.hpp"
#include <print>
Memory::Memory() : memory(MemorySize) {}
std::expected<void, std::string> Memory::CheckMemoryRange(std::size_t address, std::size_t bytes) const noexcept
{
    if (address + bytes > MemorySize)
    {
        return std::unexpected("牛逼，地址超内存总量了");
    }
    return {};
}
std::expected<void, std::string> Memory::StoreByte(std::size_t address, std::uint8_t value) noexcept
{
    // 这个没办法，没法手动抽象出来一个检查函数，算了，反正写一次，没必要专门分离做一个函数
    /*
    if (address >= MemorySize)
    {
        return std::unexpected("牛逼，地址超内存总量了");
    }
        */
    // 等等，好像可以修改一下检查函数，然后改成加字节后有没有超范围，好像可以代码复用
    auto check{CheckMemoryRange(address, 1)};
    if (!check)
    { // 不对呀，怎么感觉代码总量还增加了？
        return std::unexpected(check.error());
    }
    memory[address] = static_cast<std::byte>(value);
    return {};
}
std::expected<void, std::string> Memory::StoreWord(std::size_t address, std::uint32_t value)
{
    auto check{CheckMemoryRange(address, 4)}; // ？额？怎么感觉这又变成了和复制粘贴代码差不多？
    if (!check)
    {
        return std::unexpected(check.error());
    }
    memory[address + 0] = static_cast<std::byte>(value >> 0);
    memory[address + 1] = static_cast<std::byte>(value >> 8);
    memory[address + 2] = static_cast<std::byte>(value >> 16);
    memory[address + 3] = static_cast<std::byte>(value >> 24);
    return {};
}
std::expected<std::uint32_t, std::string> Memory::LoadWord(std::size_t address) const
{
    auto check{CheckMemoryRange(address, 4)}; // 坏了这下真的就复制粘贴了
    if (!check)
    {
        return std::unexpected(check.error());
    }
    std::uint32_t result{0};                                      // 为什么默认是int，搞得我现在都不能写auto了
    auto byte0 = static_cast<std::uint32_t>(memory[address + 0]); // 最低字节
    auto byte1 = static_cast<std::uint32_t>(memory[address + 1]);
    auto byte2 = static_cast<std::uint32_t>(memory[address + 2]);
    auto byte3 = static_cast<std::uint32_t>(memory[address + 3]); // 最高字节
    result = (byte3 << 24) | (byte2 << 16) | (byte1 << 8) | byte0;
}
