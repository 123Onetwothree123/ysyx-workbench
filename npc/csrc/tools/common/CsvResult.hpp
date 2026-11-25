#pragma once
#include <chrono>
#include <ctime>
#include <filesystem>
#include <format>
#include <fstream>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <system_error>
#include <vector>
namespace common
{
// RFC4180: 含逗号/引号/换行才加引号
inline auto EscapeCsvCell(std::string_view cell) -> std::string
{
    if (cell.find_first_of(",\"\r\n") == std::string_view::npos)
        return std::string{cell};
    std::string escaped{"\""};
    for (char c : cell)
    {
        if (c == '"')
            escaped += "\"\"";
        else
            escaped += c;
    }
    escaped += '"';
    return escaped;
}
// base/YYYYMMDD-HHMMSS[-tag]/，目录不存在则创建
inline auto CreateTimestampDir(const std::filesystem::path& base, std::string_view tag = {})
    -> std::filesystem::path
{
    const auto now{std::chrono::system_clock::now()};
    const auto time_t{std::chrono::system_clock::to_time_t(now)};
    const auto tm{*std::localtime(&time_t)};
    auto dirname{std::format("{:04}{:02}{:02}-{:02}{:02}{:02}", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec)};
    if (!tag.empty())
        dirname += std::format("-{}", tag);
    const auto dir{base / dirname};
    std::filesystem::create_directories(dir);
    return dir;
}
class CsvWriter
{
public:
    CsvWriter(const std::filesystem::path& path, bool bom)
        : path_{path}, temp_{path.string() + ".tmp"}, file_{temp_, std::ios::binary}
    {
        if (!file_)
            throw std::runtime_error(std::format("无法写入 CSV 文件: {}", temp_.string()));
        if (bom)
            file_ << "\xEF\xBB\xBF";
    }
    ~CsvWriter()
    {
        if (!committed_)
        {
            file_.close();
            std::error_code ec{};
            std::filesystem::remove(temp_, ec);
        }
    }
    CsvWriter(const CsvWriter&) = delete;
    CsvWriter& operator=(const CsvWriter&) = delete;
    void Row(std::span<const std::string> cells)
    {
        for (std::size_t i{0}; i < cells.size(); ++i)
        {
            if (i != 0)
                file_ << ',';
            file_ << EscapeCsvCell(cells[i]);
        }
        file_ << '\n';
    }
    void Raw(std::string_view line)
    {
        file_ << line;
        if (!line.ends_with('\n'))
            file_ << '\n';
    }
    auto Finish() -> const std::filesystem::path&
    {
        file_.flush();
        if (!file_)
            throw std::runtime_error(std::format("无法写入 CSV 文件: {}", temp_.string()));
        file_.close();
        if (!file_)
            throw std::runtime_error(std::format("无法写入 CSV 文件: {}", temp_.string()));
        std::error_code ec{};
        std::filesystem::rename(temp_, path_, ec);
        if (ec)
            throw std::runtime_error(std::format("无法保存 CSV 文件: {}: {}", path_.string(), ec.message()));
        committed_ = true;
        return path_;
    }

private:
    std::filesystem::path path_;
    std::filesystem::path temp_;
    std::ofstream file_;
    bool committed_{};
};
template <typename Row, typename ToCells>
void WriteCsvFile(const std::filesystem::path& path, std::string_view header,
                  std::span<const Row> rows, ToCells to_cells, bool bom = false)
{
    if (path.has_parent_path())
        std::filesystem::create_directories(path.parent_path());
    CsvWriter writer{path, bom};
    writer.Raw(header);
    for (const auto& row : rows)
        writer.Row(to_cells(row));
    writer.Finish();
}
// 写 base/时间戳(-tag)/result.csv
template <typename Row, typename ToCells>
auto WriteDseCsv(std::span<const Row> rows, const std::filesystem::path& base,
                 std::string_view tag, std::string_view header, ToCells to_cells,
                 bool bom = true) -> std::filesystem::path
{
    const auto path{CreateTimestampDir(base, tag) / "result.csv"};
    WriteCsvFile(path, header, rows, to_cells, bom);
    return path;
}
}
