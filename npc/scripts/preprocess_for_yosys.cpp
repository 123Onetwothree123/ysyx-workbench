import std;

int main(int argc, char *argv[])
{
    if (argc > 2)
    {
        std::println(std::cerr, "Usage: {} [input.sv]", argv[0]);
        return 1;
    }
    std::string text;
    {
        std::istream *in{&std::cin};
        std::ifstream f;
        if (argc == 2)
        {
            f.open(argv[1]);
            if (!f) { std::println(std::cerr, "Cannot open {}", argv[1]); return 1; }
            in = &f;
        }
        std::stringstream ss;
        ss << in->rdbuf();
        text = ss.str();
    }

    std::vector<std::string> lines;
    {
        std::stringstream ss{text};
        std::string l;
        while (std::getline(ss, l))
            lines.push_back(l);
    }

    std::regex mod_start{R"(^\s*module\s)"};
    std::regex mod_end{R"(^\s*endmodule\b)"};
    std::regex always_start{R"(^\s*(always|initial)\b)"};
    std::regex dpi_import{R"(^\s*import\s+"DPI-C")"};
    std::regex typedef_line{R"(^\s*typedef\b)"};

    std::vector<std::string> out;
    std::size_t i{0}, n{lines.size()};

    while (i < n)
    {
        if (std::regex_search(lines[i], dpi_import))
        {
            i++;
            continue;
        }
        if (std::regex_search(lines[i], typedef_line))
        {
            i++;
            continue;
        }
        if (!std::regex_search(lines[i], mod_start))
        {
            out.push_back(lines[i]);
            i++;
            continue;
        }

        std::vector<std::string> ml;
        ml.push_back(lines[i++]);
        while (i < n)
        {
            ml.push_back(lines[i]);
            if (std::regex_search(lines[i], mod_end)) { i++; break; }
            i++;
        }

        std::vector<std::string> wires;
        std::vector<std::string> cleaned;

        std::regex dim_re{R"(\[(\d+):(\d+)\])"};
        auto add_assign = [&](const std::string &indent, const std::string &width,
                               const std::string &name, const std::string &expr) {
            wires.push_back(std::format("{}wire {} {};", indent, width, name));
            if (!width.empty() && width.find("][") != std::string::npos
                && expr.starts_with("'{"))
            {
                auto inner{expr.substr(2)};
                if (!inner.empty() && inner.back() == '}') inner.pop_back();
                std::smatch dm;
                auto first_dim{width.substr(0, width.find("][") + 1)};
                if (std::regex_search(first_dim, dm, dim_re))
                {
                    int hi{std::stoi(dm[1])}, lo{std::stoi(dm[2])};
                    int step{hi > lo ? -1 : 1};
                    std::stringstream pss{inner};
                    std::string part;
                    int idx{hi};
                    while (std::getline(pss, part, ','))
                    {
                        while (!part.empty() && part.front() == ' ') part.erase(0, 1);
                        while (!part.empty() && part.back() == ' ') part.pop_back();
                        wires.push_back(
                            std::format("{}assign {}[{}] = {};", indent, name, idx, part));
                        idx += step;
                    }
                }
            }
            else
            {
                wires.push_back(std::format("{}assign {} = {};", indent, name, expr));
            }
        };

        std::size_t j{0};
        while (j < ml.size())
        {
            auto &ri{ml[j]};

            std::smatch m1;
            static std::regex p1{R"(^\s*automatic\s+logic\s+((?:\s*\[\S+\]\s*)*)(\w+)\s*=\s*$)"};
            if (std::regex_match(ri, m1, p1))
            {
                auto indent{m1.prefix().str()};
                auto width{m1[1].str()};
                auto name{m1[2].str()};
                std::string expr;
                std::size_t k{j + 1};
                while (k < ml.size())
                {
                    auto el{ml[k]};
                    auto semi{el.find(';')};
                    if (semi != std::string::npos)
                    {
                        expr += el.substr(0, semi + 1);
                        break;
                    }
                    expr += el;
                    k++;
                }
                {
                    auto eq{expr.find('=')};
                    if (eq != std::string::npos) expr = expr.substr(eq + 1);
                    auto s{expr.find(';')};
                    if (s != std::string::npos) expr = expr.substr(0, s);
                    while (!expr.empty() && (expr.front()==' '||expr.front()=='\t')) expr.erase(0,1);
                    while (!expr.empty() && (expr.back()==' '||expr.back()=='\n')) expr.pop_back();
                }
                add_assign(indent, width, name, expr);
                j = k + 1;
                continue;
            }

            std::smatch m2;
            static std::regex p2{R"(^\s*automatic\s+logic\s+((?:\s*\[\S+\]\s*)*)(\w+)\s*=\s*(.+);\s*(//.*)?$)"};
            if (std::regex_match(ri, m2, p2))
            {
                auto indent{m2.prefix().str()};
                auto width{m2[1].str()};
                auto name{m2[2].str()};
                auto expr{m2[3].str()};
                while (!expr.empty() && expr.back()==' ') expr.pop_back();
                add_assign(indent, width, name, expr);
                j++;
                continue;
            }

            std::smatch m3;
            static std::regex p3{R"(^\s*automatic\s+logic\s+((?:\s*\[\S+\]\s*)*)(\w+)\s*;\s*(//.*)?$)"};
            if (std::regex_match(ri, m3, p3))
            {
                auto indent{m3.prefix().str()};
                auto width{m3[1].str()};
                auto name{m3[2].str()};
                wires.push_back(std::format("{}wire {} {};", indent, width, name));
                j++;
                continue;
            }
            if (std::regex_search(ri, dpi_import))
            {
                j++;
                continue;
            }
            if (std::regex_search(ri, typedef_line))
            {
                j++;
                continue;
            }

            static std::regex brace_bit{R"(\{([^{}]+)\}\[(\d+):(\d+)\])"};
            std::smatch bbm;
            if (std::regex_search(ri, bbm, brace_bit))
            {
                static int tmpcnt{0};
                int hi{std::stoi(bbm[2])}, lo{std::stoi(bbm[3])};
                auto tn{std::format("_sv2v_t{:04d}", tmpcnt++)};
                auto expr{bbm[1].str()};
                while (!expr.empty() && expr.front() == ' ') expr.erase(0, 1);
                while (!expr.empty() && expr.back() == ' ') expr.pop_back();
                wires.push_back(std::format("wire [{}:0] {} = {};", hi - lo, tn, expr));
                auto repl{std::format("{}[{}:{}]", tn, hi, lo)};
                cleaned.push_back(std::regex_replace(ri, brace_bit, repl));
                j++;
                continue;
            }

            cleaned.push_back(ml[j]);
            j++;
        }

        if (!wires.empty())
        {
            std::size_t ins{cleaned.size() - 1};
            for (std::size_t k{0}; k < cleaned.size(); ++k)
            {
                if (std::regex_search(cleaned[k], always_start))
                {
                    ins = k;
                    break;
                }
            }
            for (auto it{wires.rbegin()}; it != wires.rend(); ++it)
                cleaned.insert(cleaned.begin() + ins, *it);
        }

        for (auto &cl : cleaned)
            out.push_back(cl);
    }

    for (auto &l : out)
        std::cout << l << '\n';
}
