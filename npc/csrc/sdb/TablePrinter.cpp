module npc.sdb.TablePrinter;
int display_width(std::string_view s)
{
    auto w{0};
    for (std::size_t i{0}; i < s.size();)
    {
        if (s[i] == '\033' && i + 1 < s.size() && s[i + 1] == '[')
        {
            i += 2;
            while (i < s.size() && !(s[i] >= 0x40 && s[i] <= 0x7e))
            {
                i++;
            }
            if (i < s.size())
            {
                i++;
            }
            continue;
        }
        const auto c{static_cast<unsigned char>(s[i])};
        if (c < 0x80)
        {
            w += 1;
            i += 1;
        }
        else if ((c & 0xE0) == 0xC0)
        {
            w += 2;
            i += 2;
        }
        else if ((c & 0xF0) == 0xE0)
        {
            w += 2;
            i += 3;
        }
        else if ((c & 0xF8) == 0xF0)
        {
            w += 2;
            i += 4;
        }
        else
        {
            i += 1;
        }
    }
    return w;
}
void print_border(const std::vector<int> &widths)
{
    std::print("{0}+{1}", ANSI::FG_BLUE, ANSI::NONE);
    for (int w : widths)
    {
        for (int i{0}; i < w + 2; i++)
        {
            std::print("{0}-{1}", ANSI::FG_BLUE, ANSI::NONE);
        }
        std::print("{0}+{1}", ANSI::FG_BLUE, ANSI::NONE);
    }
    std::print("\n");
}
void print_cell_colored(std::string_view raw_content, int width, bool center)
{
    auto content_width{display_width(raw_content)};
    auto pad{width - content_width};
    if (pad < 0)
    {
        pad = 0;
    }
    auto left_pad{center ? pad / 2 : 0};
    auto right_pad{pad - left_pad};
    std::print(" ");
    for (int i{0}; i < left_pad; i++)
    {
        std::print(" ");
    }
    std::print("{0}", raw_content);
    for (int i{0}; i < right_pad; i++)
    {
        std::print(" ");
    }
    std::print(" {0}|{1}", ANSI::FG_BLUE, ANSI::NONE);
}
