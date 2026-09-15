export module npc.unicode;
import std;

namespace
{
    std::uint32_t read_utf8_code_point(std::string_view text, std::size_t &index)
    {
        const auto first{static_cast<unsigned char>(text[index++])};
        if (first < 0x80u)
        {
            return first;
        }
        auto need{std::size_t{0}};
        auto code_point{std::uint32_t{0}};
        if ((first & 0xe0u) == 0xc0u)
        {
            need = 1;
            code_point = first & 0x1fu;
        }
        else if ((first & 0xf0u) == 0xe0u)
        {
            need = 2;
            code_point = first & 0x0fu;
        }
        else if ((first & 0xf8u) == 0xf0u)
        {
            need = 3;
            code_point = first & 0x07u;
        }
        else
        {
            return 0xfffdu;
        }
        if (index + need > text.size())
        {
            index = text.size();
            return 0xfffdu;
        }
        for (std::size_t i{0}; i < need; ++i)
        {
            const auto next{static_cast<unsigned char>(text[index])};
            if ((next & 0xc0u) != 0x80u)
            {
                return 0xfffdu;
            }
            ++index;
            code_point = (code_point << 6u) | (next & 0x3fu);
        }
        return code_point;
    }
    bool is_wide_code_point(std::uint32_t code_point)
    {
        return (code_point >= 0x1100u && code_point <= 0x115fu) ||
               (code_point >= 0x2329u && code_point <= 0x232au) ||
               (code_point >= 0x2e80u && code_point <= 0xa4cfu) ||
               (code_point >= 0xac00u && code_point <= 0xd7a3u) ||
               (code_point >= 0xf900u && code_point <= 0xfaffu) ||
               (code_point >= 0xfe10u && code_point <= 0xfe19u) ||
               (code_point >= 0xfe30u && code_point <= 0xfe6fu) ||
               (code_point >= 0xff00u && code_point <= 0xff60u) ||
               (code_point >= 0xffe0u && code_point <= 0xffe6u) ||
               (code_point >= 0x20000u && code_point <= 0x3fffdu);
    }
    std::size_t code_point_width(std::uint32_t code_point)
    {
        if (code_point == 0 || code_point < 0x20u ||
            (code_point >= 0x7fu && code_point < 0xa0u) ||
            (code_point >= 0x0300u && code_point <= 0x036fu))
        {
            return 0;
        }
        return is_wide_code_point(code_point) ? 2u : 1u;
    }
}

export int display_width(std::string_view text)
{
    auto width{std::size_t{0}};
    for (std::size_t index{0}; index < text.size();)
    {
        // 跳过 ANSI 转义序列 (CSI): ESC [ ... 最终字节(0x40-0x7e)
        if (text[index] == '\033' && index + 1 < text.size() && text[index + 1] == '[')
        {
            index += 2;
            while (index < text.size() && !(text[index] >= 0x40 && text[index] <= 0x7e))
            {
                ++index;
            }
            if (index < text.size())
            {
                ++index;
            }
            continue;
        }
        width += code_point_width(read_utf8_code_point(text, index));
    }
    return static_cast<int>(width);
}

export std::string clip_to_width(std::string_view text, std::size_t width)
{
    auto result{std::string{}};
    auto used{std::size_t{0}};
    for (std::size_t index{0}; index < text.size();)
    {
        const auto begin{index};
        const auto code_point{read_utf8_code_point(text, index)};
        const auto next_width{code_point_width(code_point)};
        if (used + next_width > width)
        {
            break;
        }
        result.append(text.substr(begin, index - begin));
        used += next_width;
    }
    return result;
}

export std::string pad_right(std::string_view text, std::size_t width)
{
    auto result{clip_to_width(text, width)};
    const auto used{static_cast<std::size_t>(display_width(result))};
    if (used < width)
    {
        result.append(width - used, ' ');
    }
    return result;
}

export std::string pad_left(std::string_view text, std::size_t width)
{
    auto clipped{clip_to_width(text, width)};
    const auto used{static_cast<std::size_t>(display_width(clipped))};
    if (used >= width)
    {
        return clipped;
    }
    return std::string(width - used, ' ') + clipped;
}
