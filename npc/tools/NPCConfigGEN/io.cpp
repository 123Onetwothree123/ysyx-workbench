module npc.configgen;
import std;
namespace npc::configgen {
std::string readFile(const std::string &path)
{
    std::ifstream input{path, std::ios::binary};
    if (!input)
        throw std::runtime_error("无法打开 " + path);
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}
void ensureParentDirectory(const std::string &path)
{
    const std::filesystem::path parent = std::filesystem::path{path}.parent_path();
    if (parent.empty())
        return;
    std::error_code error;
    std::filesystem::create_directories(parent, error);
    if (error)
        throw std::runtime_error("无法创建目录 " + parent.string() + ": " +
                                 error.message());
}
void writeIfChanged(const std::string &path, const std::string &content)
{
    std::ifstream existing{path, std::ios::binary};
    if (existing) {
        std::ostringstream buffer;
        buffer << existing.rdbuf();
        if (buffer.str() == content)
            return;
    }
    ensureParentDirectory(path);
    const auto temporary = path + ".tmp";
    {
        std::ofstream output{temporary, std::ios::binary | std::ios::trunc};
        if (!output)
            throw std::runtime_error("无法写入 " + temporary);
        output << content;
        output.flush();
        if (!output)
            throw std::runtime_error("写入 " + temporary + " 失败");
    }
    if (std::rename(temporary.c_str(), path.c_str()) != 0) {
        std::remove(temporary.c_str());
        throw std::runtime_error("无法替换 " + path);
    }
}
}
