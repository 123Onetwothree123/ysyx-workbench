#include <cstdio>
import std;

namespace
{
bool IsHexDigitString(const std::string &s)
{
    if (s.empty())
        return false;
    for (char c : s)
        if (!std::isxdigit(static_cast<unsigned char>(c)))
            return false;
    return true;
}

bool ConvertHexToBinary(const std::filesystem::path &hexPath, const std::filesystem::path &binPath)
{
    if (!std::filesystem::exists(hexPath) || !std::filesystem::is_regular_file(hexPath))
    {
        std::println(stderr, "Error: hex file does not exist or is not a regular file: {}", hexPath.string());
        return false;
    }

    std::ifstream in{hexPath};
    if (!in.is_open())
    {
        std::println(stderr, "Error: could not open hex file: {}", hexPath.string());
        return false;
    }

    std::string line;
    if (!std::getline(in, line))
    {
        std::println(stderr, "Error: hex file is empty: {}", hexPath.string());
        return false;
    }

    std::vector<std::uint8_t> binary;
    std::size_t currentWordAddr{0};

    while (std::getline(in, line))
    {
        if (line.empty())
            continue;

        std::istringstream iss{line};
        std::string token;
        while (iss >> token)
        {
            if (!token.empty() && token.back() == ':')
            {
                token.pop_back();
                if (!IsHexDigitString(token))
                {
                    std::println(stderr, "Error: invalid address token: {}", token);
                    return false;
                }
                currentWordAddr = static_cast<std::size_t>(std::stoull(token, nullptr, 16));
                continue;
            }

            if (!IsHexDigitString(token))
            {
                std::println(stderr, "Error: invalid data token: {}", token);
                return false;
            }

            std::uint32_t word{static_cast<std::uint32_t>(std::stoul(token, nullptr, 16))};
            std::size_t byteOffset{currentWordAddr * 4};
            if (binary.size() < byteOffset + 4)
                binary.resize(byteOffset + 4, 0);

            binary[byteOffset + 0] = static_cast<std::uint8_t>(word & 0xFF);
            binary[byteOffset + 1] = static_cast<std::uint8_t>((word >> 8) & 0xFF);
            binary[byteOffset + 2] = static_cast<std::uint8_t>((word >> 16) & 0xFF);
            binary[byteOffset + 3] = static_cast<std::uint8_t>((word >> 24) & 0xFF);
            ++currentWordAddr;
        }
    }

    std::ofstream out{binPath, std::ios::binary | std::ios::trunc};
    if (!out.is_open())
    {
        std::println(stderr, "Error: could not create output file: {}", binPath.string());
        return false;
    }

    if (!binary.empty())
    {
        out.write(reinterpret_cast<const char *>(binary.data()), static_cast<std::streamsize>(binary.size()));
        if (!out.good())
        {
            std::println(stderr, "Error: failed to write binary file: {}", binPath.string());
            return false;
        }
    }

    std::println("Converted: {} -> {}", hexPath.string(), binPath.string());
    std::println("Output bytes: {}", binary.size());
    return true;
}
} // namespace

int main(int argc, char const *argv[])
{
    if (argc < 2 || argc > 3)
    {
        std::println("Usage: {} <input.hex> [output.bin]", argv[0]);
        return 1;
    }

    std::filesystem::path hexPath{argv[1]};
    std::filesystem::path binPath{(argc == 3) ? std::filesystem::path{argv[2]} : (hexPath.parent_path() / (hexPath.stem().string() + ".bin"))};

    if (!ConvertHexToBinary(hexPath, binPath))
        return 1;
    return 0;
}
