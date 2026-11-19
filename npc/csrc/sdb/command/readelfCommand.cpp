module npc.sdb.command.readelfCommand;
import npc.Output;
import npc.trace.ftrace;
import npc.SimulationContext;
import npc.trace.readelf;

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
        {"-a", "把ELF文件头和节区还有符号表都打出来"},
    };
    return entries;
}
SDBCommandResult readelfCommand::execute(SDBCommandContext &context, std::string_view args)
{
    auto &out{context.GetSimulation().Out()};
    const Readelf *Reader{context.GetSimulation().Trace().ELFReader()};
    if (Reader == nullptr)
    {
        out.Error("readelf: 还没有加载ELF文件，启动NPC时传 --elf FILE");
        return SDBCommandResult::Continue;
    }
    if (args == "-h")
    {
        Reader->print_file_header(std::cout);
        return SDBCommandResult::Continue;
    }
    if (args == "-S")
    {
        Reader->print_section_headers(std::cout);
        return SDBCommandResult::Continue;
    }
    if (args == "-s")
    {
        Reader->print_symbols(std::cout);
        return SDBCommandResult::Continue;
    }
    if (args == "-a")
    {
        Reader->print_file_header(std::cout);
        Reader->print_section_headers(std::cout);
        Reader->print_symbols(std::cout);
        return SDBCommandResult::Continue;
    }
    out.Info("用法：readelf -h | -S | -s | -a");
    return SDBCommandResult::Continue;
}
