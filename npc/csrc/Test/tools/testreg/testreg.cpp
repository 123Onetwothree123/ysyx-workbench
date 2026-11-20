#include <cstdlib>
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>

import std;

namespace fs = std::filesystem;

namespace {

struct Json {
    using Array = std::vector<Json>;
    using Object = std::map<std::string, Json, std::less<>>;
    std::variant<std::nullptr_t, bool, double, std::string, Array, Object> v;

    [[nodiscard]] bool IsObject() const { return std::holds_alternative<Object>(v); }
    [[nodiscard]] bool IsArray() const { return std::holds_alternative<Array>(v); }
    [[nodiscard]] bool IsString() const { return std::holds_alternative<std::string>(v); }
    [[nodiscard]] const Object &Obj() const { return std::get<Object>(v); }
    [[nodiscard]] const Array &Arr() const { return std::get<Array>(v); }
    [[nodiscard]] const std::string &Str() const { return std::get<std::string>(v); }
};

class Parser {
public:
    explicit Parser(std::string_view s) : text(s) {}
    std::optional<Json> Parse() {
        auto v = Value();
        if (!v) return std::nullopt;
        Ws();
        if (pos != text.size()) return std::nullopt;
        return v;
    }
private:
    std::string_view text;
    std::size_t pos{0};

    void Ws() {
        while (pos < text.size() && std::isspace(static_cast<unsigned char>(text[pos]))) ++pos;
    }
    bool Eat(char c) {
        Ws();
        if (pos < text.size() && text[pos] == c) { ++pos; return true; }
        return false;
    }
    std::optional<std::string> String() {
        if (!Eat('"')) return std::nullopt;
        std::string out;
        while (pos < text.size()) {
            char c = text[pos++];
            if (c == '"') return out;
            if (c != '\\') { out.push_back(c); continue; }
            if (pos >= text.size()) return std::nullopt;
            char e = text[pos++];
            switch (e) {
                case '"': out.push_back('"'); break;
                case '\\': out.push_back('\\'); break;
                case '/': out.push_back('/'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                default: return std::nullopt;
            }
        }
        return std::nullopt;
    }
    std::optional<Json> Value() {
        Ws();
        if (pos >= text.size()) return std::nullopt;
        char c = text[pos];
        if (c == '{') return ObjectValue();
        if (c == '[') return ArrayValue();
        if (c == '"') {
            auto s = String();
            if (!s) return std::nullopt;
            return Json{*s};
        }
        if (c == 't' && text.substr(pos, 4) == "true") { pos += 4; return Json{true}; }
        if (c == 'f' && text.substr(pos, 5) == "false") { pos += 5; return Json{false}; }
        if (c == 'n' && text.substr(pos, 4) == "null") { pos += 4; return Json{nullptr}; }
        return Number();
    }
    std::optional<Json> Number() {
        std::size_t start = pos;
        if (pos < text.size() && (text[pos] == '-' || text[pos] == '+')) ++pos;
        while (pos < text.size() && (std::isdigit(static_cast<unsigned char>(text[pos])) || text[pos] == '.')) ++pos;
        if (start == pos) return std::nullopt;
        try { return Json{std::stod(std::string(text.substr(start, pos - start)))}; }
        catch (...) { return std::nullopt; }
    }
    std::optional<Json> ObjectValue() {
        if (!Eat('{')) return std::nullopt;
        Json::Object obj;
        if (Eat('}')) return Json{std::move(obj)};
        while (true) {
            auto key = String();
            if (!key || !Eat(':')) return std::nullopt;
            auto val = Value();
            if (!val) return std::nullopt;
            obj.emplace(std::move(*key), std::move(*val));
            if (Eat('}')) return Json{std::move(obj)};
            if (!Eat(',')) return std::nullopt;
        }
    }
    std::optional<Json> ArrayValue() {
        if (!Eat('[')) return std::nullopt;
        Json::Array arr;
        if (Eat(']')) return Json{std::move(arr)};
        while (true) {
            auto val = Value();
            if (!val) return std::nullopt;
            arr.push_back(std::move(*val));
            if (Eat(']')) return Json{std::move(arr)};
            if (!Eat(',')) return std::nullopt;
        }
    }
};

struct TestCase {
    std::string name;
    std::string tier;
    fs::path cwd;
    std::vector<std::string> command;
    std::vector<std::string> archs;
    std::vector<std::string> tags;
    int timeout{300};
    int retry{0};
    bool enabled{true};
};

std::string ReplaceArch(std::string s, const std::string &arch) {
    const std::string key = "${ARCH}";
    std::size_t p = 0;
    while ((p = s.find(key, p)) != std::string::npos) {
        s.replace(p, key.size(), arch);
        p += arch.size();
    }
    return s;
}

bool ReadFile(const fs::path &path, std::string &out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

std::optional<TestCase> MakeTest(const Json &j, const fs::path &base) {
    TestCase t;
    if (!j.IsObject()) return std::nullopt;
    const auto &o = j.Obj();
    auto it = o.find("name");
    if (it == o.end() || !it->second.IsString()) return std::nullopt;
    t.name = it->second.Str();
    it = o.find("tier");
    if (it == o.end() || !it->second.IsString()) return std::nullopt;
    t.tier = it->second.Str();
    if (t.tier != "functional" && t.tier != "performance") return std::nullopt;
    it = o.find("command");
    if (it == o.end() || !it->second.IsArray()) return std::nullopt;
    for (const auto &a : it->second.Arr()) {
        if (!a.IsString()) return std::nullopt;
        t.command.push_back(a.Str());
    }
    if (t.command.empty()) return std::nullopt;
    it = o.find("cwd");
    t.cwd = (it != o.end() && it->second.IsString()) ? base / it->second.Str() : base;
    it = o.find("timeout");
    if (it != o.end() && std::holds_alternative<double>(it->second.v)) {
        t.timeout = static_cast<int>(std::get<double>(it->second.v));
    }
    it = o.find("retry");
    if (it != o.end() && std::holds_alternative<double>(it->second.v)) {
        t.retry = std::max(0, static_cast<int>(std::get<double>(it->second.v)));
    }
    it = o.find("enabled");
    if (it != o.end() && std::holds_alternative<bool>(it->second.v)) t.enabled = std::get<bool>(it->second.v);
    auto readList = [&](std::string_view key, std::vector<std::string> &out) {
        auto f = o.find(key);
        if (f == o.end() || !f->second.IsArray()) return;
        for (const auto &a : f->second.Arr()) if (a.IsString()) out.push_back(a.Str());
    };
    readList("archs", t.archs);
    readList("tags", t.tags);
    return t;
}

struct Selection {
    std::string tier;
    std::string tag;
    std::string name;
    std::string arch;
    int timeoutScale{1};
    int retry{0};
};

std::string TrimQuotes(std::string s) {
    if (s.size() >= 2 && s.front() == '"' && s.back() == '"') return s.substr(1, s.size() - 2);
    return s;
}

Selection LoadSelection(const fs::path &path) {
    Selection sel;
    std::ifstream in(path);
    if (!in) return sel;
    std::string line;
    while (std::getline(in, line)) {
        if (line.rfind("CONFIG_TEST_", 0) != 0) continue;
        auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string key = line.substr(0, eq);
        std::string val = TrimQuotes(line.substr(eq + 1));
        if (key == "CONFIG_TEST_TIER_FUNCTIONAL" && val == "y") sel.tier = "functional";
        else if (key == "CONFIG_TEST_TIER_PERFORMANCE" && val == "y") sel.tier = "performance";
        else if (key == "CONFIG_TEST_TIER_ALL" && val == "y") sel.tier = "all";
        else if (key == "CONFIG_TEST_TAG") sel.tag = val;
        else if (key == "CONFIG_TEST_NAME") sel.name = val;
        else if (key == "CONFIG_TEST_ARCH") sel.arch = val;
        else if (key == "CONFIG_TEST_TIMEOUT_SCALE") sel.timeoutScale = std::max(1, std::atoi(val.c_str()));
        else if (key == "CONFIG_TEST_RETRY") sel.retry = std::max(0, std::atoi(val.c_str()));
    }
    return sel;
}

std::vector<TestCase> Scan(const fs::path &root) {
    std::vector<TestCase> tests;
    std::error_code ec;
    for (auto it = fs::recursive_directory_iterator(root, fs::directory_options::skip_permission_denied, ec);
         it != fs::recursive_directory_iterator(); it.increment(ec)) {
        if (ec) break;
        const auto &p = it->path();
        if (it->is_directory()) {
            const auto n = p.filename().string();
            if (n == "build" || n == "out" || n == ".git" || n == "target") it.disable_recursion_pending();
            continue;
        }
        if (p.filename() != "test.json") continue;
        std::string text;
        if (!ReadFile(p, text)) continue;
        auto parsed = Parser(text).Parse();
        if (!parsed) { std::println(std::cerr, "JSON 解析失败: {}", p.string()); continue; }
        const auto base = p.parent_path();
        if (parsed->IsObject()) {
            const auto &o = parsed->Obj();
            auto list = o.find("tests");
            if (list != o.end() && list->second.IsArray()) {
                for (const auto &e : list->second.Arr()) {
                    if (auto t = MakeTest(e, base)) tests.push_back(std::move(*t));
                }
            } else if (auto t = MakeTest(*parsed, base)) {
                tests.push_back(std::move(*t));
            }
        }
    }
    std::sort(tests.begin(), tests.end(), [](const auto &a, const auto &b) { return a.name < b.name; });
    for (std::size_t i = 1; i < tests.size(); ++i) {
        if (tests[i - 1].name == tests[i].name) {
            std::println(std::cerr, "重复的测试名: {}", tests[i].name);
            tests.clear();
            break;
        }
    }
    return tests;
}

bool Match(const TestCase &t, const std::string &tier, const std::string &tag,
           const std::string &nameFilter, const std::string &arch, bool allTiers = false) {
    if (!t.enabled) return false;
    if (!allTiers && !tier.empty() && t.tier != tier) return false;
    if (!tag.empty() && std::find(t.tags.begin(), t.tags.end(), tag) == t.tags.end()) return false;
    if (!nameFilter.empty() && t.name.find(nameFilter) == std::string::npos) return false;
    if (!arch.empty() && !t.archs.empty() &&
        std::find(t.archs.begin(), t.archs.end(), arch) == t.archs.end()) return false;
    return true;
}

std::string Sanitize(std::string s) {
    for (char &c : s) if (c == '/' || c == ' ' || c == ':') c = '_';
    return s;
}

enum class Result { Pass, Fail, Hang, Skip };

Result RunOnce(const TestCase &t, const std::string &arch, const fs::path &logDir);

Result Run(const TestCase &t, const std::string &arch, const fs::path &logDir) {
    Result last = Result::Fail;
    for (int attempt = 0; attempt <= t.retry; ++attempt) {
        last = RunOnce(t, arch, logDir);
        if (last == Result::Pass || last == Result::Skip) return last;
        if (attempt < t.retry) {
            std::println(std::cerr, "重试 {}/{}: {}", attempt + 1, t.retry, t.name);
        }
    }
    return last;
}

Result RunOnce(const TestCase &t, const std::string &arch, const fs::path &logDir) {
    if (!arch.empty() && !t.archs.empty() &&
        std::find(t.archs.begin(), t.archs.end(), arch) == t.archs.end()) {
        return Result::Skip;
    }
    fs::create_directories(logDir);
    const auto logPath = logDir / (Sanitize(t.name) + ".log");
    std::vector<std::string> argvStr = t.command;
    for (auto &a : argvStr) a = ReplaceArch(a, arch);
    if (const char *ma = std::getenv("TESTREG_MAINARGS"); ma && *ma && argvStr[0] == "make") {
        argvStr.insert(argvStr.begin() + 1, std::string("mainargs=") + ma);
    }
    std::vector<char *> argv;
    argv.reserve(argvStr.size() + 1);
    for (auto &a : argvStr) argv.push_back(a.data());
    argv.push_back(nullptr);

    pid_t pid = fork();
    if (pid < 0) return Result::Fail;
    if (pid == 0) {
        std::error_code ec;
        fs::current_path(t.cwd, ec);
        int fd = ::open(logPath.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0644);
        if (fd >= 0) { ::dup2(fd, STDOUT_FILENO); ::dup2(fd, STDERR_FILENO); ::close(fd); }
        ::execvp(argv[0], argv.data());
        _exit(127);
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(t.timeout);
    int status = 0;
    while (true) {
        pid_t r = ::waitpid(pid, &status, WNOHANG);
        if (r == pid) break;
        if (r < 0) return Result::Fail;
        if (std::chrono::steady_clock::now() >= deadline) {
            ::kill(pid, SIGKILL);
            ::waitpid(pid, &status, 0);
            return Result::Hang;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) return Result::Pass;
    return Result::Fail;
}

std::string Usage() {
    return "用法:\n"
           "  testreg list [--tier functional|performance] [--tag T] [--name S] [--json]\n"
           "  testreg run  [--tier functional|performance|all] [--tag T] [--name S] [--arch A] [--retry N]\n"
           "  testreg menu\n"
           "  testreg clean\n"
           "环境变量 ARCH 可作为 --arch 的默认值。\n";
}

} // namespace

int main(int argc, char **argv) {
    if (argc < 2) {
        std::print("{}", Usage());
        return 2;
    }
    const std::string cmd = argv[1];
#ifndef TESTREG_TEST_DIR
#define TESTREG_TEST_DIR "."
#endif
    const fs::path testDir = fs::weakly_canonical(fs::path{TESTREG_TEST_DIR});
    const Selection sel = LoadSelection(testDir / ".config");
    std::string tier{sel.tier.empty() ? "functional" : sel.tier};
    std::string tag = sel.tag;
    std::string nameFilter = sel.name;
    std::string arch = !sel.arch.empty() ? sel.arch
                      : (std::getenv("ARCH") ? std::getenv("ARCH") : "");
    bool allTiers = (tier == "all");
    bool jsonOut = false;
    int jobs = 1;
    int retryOverride = sel.retry;
    int timeoutScale = sel.timeoutScale;
    for (int i = 2; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { return (i + 1 < argc) ? argv[++i] : ""; };
        if (a == "--tier") { tier = next(); allTiers = (tier == "all"); }
        else if (a == "--all-tiers") allTiers = true;
        else if (a == "--tag") tag = next();
        else if (a == "--name") nameFilter = next();
        else if (a == "--arch") arch = next();
        else if (a == "--jobs") jobs = std::max(1, std::atoi(next().c_str()));
        else if (a == "--json") jsonOut = true;
        else if (a == "--retry") retryOverride = std::max(0, std::atoi(next().c_str()));
        else if (a == "-h" || a == "--help") { std::print("{}", Usage()); return 0; }
        else { std::println(std::cerr, "未知参数: {}", a); return 2; }
    }
    const auto tests = Scan(testDir);
    if (jobs != 1) std::println(std::cerr, "警告：当前只实现串行执行，忽略 --jobs {}", jobs);

    if (cmd == "list") {
        if (jsonOut) {
            std::println("[");
            bool first = true;
            for (const auto &t : tests) {
                if (!Match(t, tier, tag, nameFilter, arch, allTiers)) continue;
                if (!first) std::println(",");
                first = false;
                std::print("  {{\"name\":\"{}\",\"tier\":\"{}\"}}", t.name, t.tier);
            }
            std::println("\n]");
            return 0;
        }
        for (const auto &t : tests) {
            if (!Match(t, tier, tag, nameFilter, arch, allTiers)) continue;
            std::print("{:<48} {:<12}", t.name, t.tier);
            for (const auto &x : t.tags) std::print(" {}", x);
            std::println("");
        }
        return 0;
    }
    if (cmd == "menu") {
        const fs::path kconfig = testDir / "Kconfig";
        const fs::path cfg = testDir / ".config";
        const fs::path npcRoot = testDir / ".." / "..";
        const fs::path mconf = npcRoot / "tools" / "build" / "c_mconf";
        if (!fs::exists(mconf)) {
            std::println(std::cerr, "找不到 {}，请先在 npc 下构建 Kconfig 工具", mconf.string());
            return 2;
        }
        ::setenv("KCONFIG_CONFIG", cfg.c_str(), 1);
        ::setenv("TERM", "xterm", 1);
        ::setenv("LC_ALL", "C.utf8", 1);
        std::vector<std::string> args{mconf.string(), kconfig.string()};
        std::vector<char *> argv2;
        for (auto &a : args) argv2.push_back(a.data());
        argv2.push_back(nullptr);
        ::execv(argv2[0], argv2.data());
        std::println(std::cerr, "menuconfig 启动失败");
        return 2;
    }
    if (cmd == "clean") {
        const fs::path logDir = testDir / "build" / "test-logs";
        std::error_code ec;
        fs::remove_all(logDir, ec);
        std::println("已清理 {}", logDir.string());
        return 0;
    }
    if (cmd != "run") {
        std::print("{}", Usage());
        return 2;
    }

    if (arch.empty()) {
        for (const auto &t : tests) {
            if (!Match(t, tier, tag, nameFilter, arch, allTiers)) continue;
            for (const auto &a : t.command) {
                if (a.find("${ARCH}") != std::string::npos) {
                    std::println(std::cerr, "测试 {} 需要 --arch 或 ARCH 环境变量", t.name);
                    return 2;
                }
            }
        }
    }
    const fs::path logDir = testDir / "build" / "test-logs";
    int pass = 0, fail = 0, hang = 0, skip = 0;
    for (const auto &t : tests) {
        if (!Match(t, tier, tag, nameFilter, arch, allTiers)) continue;
        if (!arch.empty() && !t.archs.empty() &&
            std::find(t.archs.begin(), t.archs.end(), arch) == t.archs.end()) {
            std::println("SKIP {} (ARCH={} 不支持)", t.name, arch);
            ++skip;
            continue;
        }
        TestCase tc = t;
        if (retryOverride >= 0) tc.retry = retryOverride;
        if (timeoutScale > 1) tc.timeout *= timeoutScale;
        auto r = Run(tc, arch, logDir);
        switch (r) {
            case Result::Pass: std::println("PASS {}", t.name); ++pass; break;
            case Result::Fail: std::println("FAIL {}", t.name); ++fail; break;
            case Result::Hang: std::println("HANG {} (>{}s)", t.name, t.timeout); ++hang; break;
            case Result::Skip: std::println("SKIP {}", t.name); ++skip; break;
        }
        std::fflush(nullptr);
    }
    std::println("----------------------------------------");
    std::println("total={} pass={} fail={} hang={} skip={}", pass + fail + hang + skip, pass, fail, hang, skip);
    return (fail + hang) == 0 ? 0 : 1;
}
