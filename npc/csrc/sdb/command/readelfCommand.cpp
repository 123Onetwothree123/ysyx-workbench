#include "readelfCommand.hpp"
#include <print>
std::string_view readelfCommand::name() const noexcept
{
    return "readelf";
}
SDBCommandUsageList readelfCommand::usage() const noexcept
{
    static const SDBCommandUsage entries[]{
        {"-h", "看看ELF文件头"},
        {"-S", "看看ELF里有哪些节区"},
        {"-s", "看看ELF里的符号表"},
        {"-a", "把ELF文件头和节区还有符号表都打出来"}};
    return entries;
}
SDBCommandResult readelfCommand::execute(SDBCommandContext &context, std::string_view args)
{
    static_cast<void>(context);
    //没写完
}