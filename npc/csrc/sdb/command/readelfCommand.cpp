#include "command/readelfCommand.hpp"
#include "ftrace.hpp"
#include <iostream>
#include <print>
std::string_view readelfCommand::Name() const noexcept
{
    return "readelf";
}
SDBCommandUsageList readelfCommand::Usage() const noexcept
{
    static constexpr SDBCommandUsage Entries[]{
        {"-h", "看看ELF文件头"},
        {"-S", "看看ELF里有哪些节区"},
        {"-s", "看看ELF里的符号表"},
        {"-a", "把ELF文件头和节区还有符号表都打出来"},
    };
    return Entries;
}
SDBCommandResult readelfCommand::Execute(SDBCommandContext &Context, std::string_view Args)
{
    static_cast<void>(Context);
    const Readelf *Reader{GlobalFtrace.ElfReader()};// 获取这个ELF的读取器
    if (Reader == nullptr)
    {
        std::println("readelf: 还没有加载ELF文件，启动NPC时传 --elf FILE");
        return SDBCommandResult::Continue;
    }
    if (Args == "-h")
    {
        Reader->print_file_header(std::cout);
        return SDBCommandResult::Continue;
    }
    if (Args == "-S")
    {
        Reader->print_section_headers(std::cout);
        return SDBCommandResult::Continue;
    }
    if (Args == "-s")
    {
        Reader->print_symbols(std::cout);
        return SDBCommandResult::Continue;
    }
    if (Args == "-a")
    {
        Reader->print_file_header(std::cout);
        Reader->print_section_headers(std::cout);
        Reader->print_symbols(std::cout);
        return SDBCommandResult::Continue;
    }
    std::println("用法：readelf -h | -S | -s | -a");
    return SDBCommandResult::Continue;
}
