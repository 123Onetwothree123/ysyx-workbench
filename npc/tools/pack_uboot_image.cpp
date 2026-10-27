// Pack an NPC U-Boot firmware image from already-built artifacts.
//
// This is the C++ replacement for the small Python heredoc that used to live
// in npc/scripts/build_uboot_npc.sh.  Offsets are relative to the NPC RAM
// base (0x80000000), matching the shell script's layout calculations.

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Payload {
    std::string name;
    std::string path;
    std::uint64_t offset;
    std::vector<std::uint8_t> bytes;
};

std::uint64_t parseNumber(const std::string &text, const char *name)
{
    if (text.empty())
        throw std::runtime_error(std::string{"空参数: "} + name);
    std::size_t consumed = 0;
    unsigned long long value = 0;
    try {
        value = std::stoull(text, &consumed, 0);
    } catch (const std::exception &) {
        throw std::runtime_error(std::string{"非法数字 "} + name + ": " + text);
    }
    if (consumed != text.size())
        throw std::runtime_error(std::string{"非法数字 "} + name + ": " + text);
    return static_cast<std::uint64_t>(value);
}

std::vector<std::uint8_t> readFile(const std::string &path)
{
    std::ifstream input{path, std::ios::binary};
    if (!input)
        throw std::runtime_error("无法打开文件: " + path);
    input.seekg(0, std::ios::end);
    const auto end = input.tellg();
    if (end < 0)
        throw std::runtime_error("无法获取文件大小: " + path);
    input.seekg(0, std::ios::beg);
    std::vector<std::uint8_t> data(static_cast<std::size_t>(end));
    if (!data.empty())
        input.read(reinterpret_cast<char *>(data.data()), end);
    if (!input && !input.eof())
        throw std::runtime_error("读取文件失败: " + path);
    return data;
}

void usage(const char *program)
{
    std::cerr
        << "用法: " << program
        << " OUT FW UBOOT EFI_APP INITRAMFS KERNEL "
           "FW_OFF UBOOT_OFF EFI_APP_OFF INITRAMFS_OFF KERNEL_OFF "
           "DTB_OFF RAM_SIZE\n";
}

} // namespace

int main(int argc, char **argv)
{
    try {
        if (argc != 14) {
            usage(argv[0]);
            return 2;
        }
        const std::string output = argv[1];
        const std::vector<Payload> payloadSpecs{
            {"opensbi", argv[2], parseNumber(argv[7], "FW_OFF"), {}},
            {"u-boot", argv[3], parseNumber(argv[8], "UBOOT_OFF"), {}},
            {"efi_app", argv[4], parseNumber(argv[9], "EFI_APP_OFF"), {}},
            {"initrd", argv[5], parseNumber(argv[10], "INITRAMFS_OFF"), {}},
            {"kernel", argv[6], parseNumber(argv[11], "KERNEL_OFF"), {}},
        };
        const std::uint64_t dtbOffset = parseNumber(argv[12], "DTB_OFF");
        const std::uint64_t ramSize = parseNumber(argv[13], "RAM_SIZE");

        std::vector<Payload> payloads;
        for (const auto &spec : payloadSpecs) {
            if (spec.path.empty())
                continue;
            Payload payload = spec;
            payload.bytes = readFile(spec.path);
            payloads.push_back(std::move(payload));
        }
        if (payloads.empty())
            throw std::runtime_error("没有可打包的载荷");

        std::uint64_t imageEnd = 0;
        for (const auto &payload : payloads) {
            if (payload.offset >
                std::numeric_limits<std::uint64_t>::max() - payload.bytes.size())
                throw std::runtime_error("载荷地址+大小溢出: " + payload.name);
            imageEnd = std::max(imageEnd, payload.offset + payload.bytes.size());
        }
        if (imageEnd > dtbOffset)
            throw std::runtime_error(
                "镜像结束地址 0x" + [&] {
                    std::ostringstream text;
                    text << std::hex << imageEnd;
                    return text.str();
                }() + " 超过 DTB 区偏移 0x" + [&] {
                    std::ostringstream text;
                    text << std::hex << dtbOffset;
                    return text.str();
                }());
        if (imageEnd > ramSize)
            throw std::runtime_error(
                "镜像结束地址超过 RAM 大小: 0x" + [&] {
                    std::ostringstream text;
                    text << std::hex << imageEnd;
                    return text.str();
                }() + " > 0x" + [&] {
                    std::ostringstream text;
                    text << std::hex << ramSize;
                    return text.str();
                }());

        std::vector<Payload> ordered = payloads;
        std::sort(ordered.begin(), ordered.end(),
                  [](const Payload &left, const Payload &right) {
                      return left.offset < right.offset;
                  });
        for (std::size_t index = 1; index < ordered.size(); ++index) {
            const auto &previous = ordered[index - 1];
            const auto &current = ordered[index];
            if (previous.offset + previous.bytes.size() > current.offset)
                throw std::runtime_error("载荷在 RAM 中重叠: " +
                                         previous.name + " 与 " + current.name);
        }

        std::vector<std::uint8_t> image(static_cast<std::size_t>(imageEnd), 0);
        for (const auto &payload : payloads) {
            std::copy(payload.bytes.begin(), payload.bytes.end(),
                      image.begin() + static_cast<std::ptrdiff_t>(payload.offset));
        }

        std::ofstream out{output, std::ios::binary | std::ios::trunc};
        if (!out)
            throw std::runtime_error("无法创建输出文件: " + output);
        out.write(reinterpret_cast<const char *>(image.data()),
                  static_cast<std::streamsize>(image.size()));
        if (!out)
            throw std::runtime_error("写入输出文件失败: " + output);

        for (const auto &payload : payloads) {
            std::cout << payload.name << "_addr=0x" << std::hex
                      << (0x80000000ULL + payload.offset) << std::dec << '\n'
                      << payload.name << "_bytes=" << payload.bytes.size() << '\n';
        }
        std::cout << "image_bytes=" << image.size() << '\n';
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "错误：" << error.what() << '\n';
        return 1;
    }
}
