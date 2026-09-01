module npc.sdb.TablePrinter;
import npc.unicode;
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
