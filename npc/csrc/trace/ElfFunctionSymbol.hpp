#ifndef ELF_FUNCTION_SYMBOL_HPP
#define ELF_FUNCTION_SYMBOL_HPP
#include <cstdint>
#include <cstddef>
#include <string_view>
class ElfFunctionSymbol
{
private:
    std::string_view name; // 函数名，指向内部字符串表（非拥有）
    std::size_t start;     // 函数起始地址
    std::size_t end;       // 函数结束地址，目前的设想是打算用[start, end)
    std::size_t size;      // 函数大小
public:
    ElfFunctionSymbol();
    ~ElfFunctionSymbol();
    ElfFunctionSymbol(std::string_view InputName, std::size_t InputStart, std::size_t InputEnd);
    std::string_view GetName();
    std::size_t GetStart();
    std::size_t GetEnd();
    std::size_t GetSize();
};
#endif
