#include <cctype>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace
{
bool IsHexDigitString(const std::string &s)
{
    if (s.empty())
    {
        return false;
    }
    for (char c : s)
    {
        if (!std::isxdigit(static_cast<unsigned char>(c)))
        {
            return false;
        }
    }
    return true;
}

bool ConvertHexToBinary(const std::filesystem::path &hexPath, const std::filesystem::path &binPath)
{
    if (!std::filesystem::exists(hexPath) || !std::filesystem::is_regular_file(hexPath))
    {
        std::cerr << "Error: hex file does not exist or is not a regular file: " << hexPath << std::endl;
        return false;
    }

    std::ifstream in(hexPath);
    if (!in.is_open())
    {
        std::cerr << "Error: could not open hex file: " << hexPath << std::endl;
        return false;
    }

    // Skip the first ASCII header line (e.g. "v3.0 hex words addressed").
    std::string line;
    if (!std::getline(in, line))
    {
        std::cerr << "Error: hex file is empty: " << hexPath << std::endl;
        return false;
    }

    std::vector<uint8_t> binary;
    size_t currentWordAddr = 0;

    while (std::getline(in, line))
    {
        if (line.empty())
        {
            continue;
        }

        std::istringstream iss(line);
        std::string token;
        while (iss >> token)
        {
            if (!token.empty() && token.back() == ':')
            {
                token.pop_back();
                if (!IsHexDigitString(token))
                {
                    std::cerr << "Error: invalid address token: " << token << std::endl;
                    return false;
                }
                currentWordAddr = static_cast<size_t>(std::stoull(token, nullptr, 16));
                continue;
            }

            if (!IsHexDigitString(token))
            {
                std::cerr << "Error: invalid data token: " << token << std::endl;
                return false;
            }

            uint32_t word = static_cast<uint32_t>(std::stoul(token, nullptr, 16));
            size_t byteOffset = currentWordAddr * 4;
            if (binary.size() < byteOffset + 4)
            {
                binary.resize(byteOffset + 4, 0);
            }

            // Little-endian output to match emulator byte-loading logic.
            binary[byteOffset + 0] = static_cast<uint8_t>(word & 0xFF);
            binary[byteOffset + 1] = static_cast<uint8_t>((word >> 8) & 0xFF);
            binary[byteOffset + 2] = static_cast<uint8_t>((word >> 16) & 0xFF);
            binary[byteOffset + 3] = static_cast<uint8_t>((word >> 24) & 0xFF);
            ++currentWordAddr;
        }
    }

    std::ofstream out(binPath, std::ios::binary | std::ios::trunc);
    if (!out.is_open())
    {
        std::cerr << "Error: could not create output file: " << binPath << std::endl;
        return false;
    }

    if (!binary.empty())
    {
        out.write(reinterpret_cast<const char *>(binary.data()), static_cast<std::streamsize>(binary.size()));
        if (!out.good())
        {
            std::cerr << "Error: failed to write binary file: " << binPath << std::endl;
            return false;
        }
    }

    std::cout << "Converted: " << hexPath << " -> " << binPath << std::endl;
    std::cout << "Output bytes: " << binary.size() << std::endl;
    return true;
}
} // namespace

int main(int argc, char const *argv[])
{
    if (argc < 2 || argc > 3)
    {
        std::cout << "Usage: " << argv[0] << " <input.hex> [output.bin]" << std::endl;
        return 1;
    }

    std::filesystem::path hexPath = argv[1];
    std::filesystem::path binPath =
        (argc == 3) ? std::filesystem::path(argv[2]) : (hexPath.parent_path() / (hexPath.stem().string() + ".bin"));

    if (!ConvertHexToBinary(hexPath, binPath))
    {
        return 1;
    }
    return 0;
}

