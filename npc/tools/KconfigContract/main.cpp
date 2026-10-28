import npc.kconfig_contract;
import std;
using namespace npc::kconfig_contract;
void print_usage(std::ostream& out, const char* program) {
  out << "Kconfig 配置契约校验（C++）\n\n"
      << "用法:\n"
      << "  " << program << " --config PATH\n"
      << "  " << program << " --defconfigs [--defconfigs-dir DIR]\n"
      << "  " << program << " --self-test\n"
      << "  " << program << " --help\n\n"
      << "选项:\n"
      << "  --config PATH          校验一个 .config/defconfig\n"
      << "  --defconfigs           校验 DIR 下所有 *_defconfig（默认 npc/configs）\n"
      << "  --defconfigs-dir DIR   配合 --defconfigs 指定目录\n"
      << "  --self-test           运行 C++ 内置回归自检（不需要 Python）\n"
      << "  --quiet                成功时不打印逐文件信息\n"
      << "  -h, --help             显示本帮助\n";
}
int run_defconfigs(const fs::path& dir, bool quiet) {
  std::error_code ec;
  if (!fs::is_directory(dir, ec)) {
    std::cerr << "错误: 没有找到 " << dir.string() << "/*_defconfig\n";
    return 2;
  }
  std::vector<fs::path> files;
  for (const auto& entry : fs::directory_iterator(dir, ec)) {
    if (ec) {
      break;
    }
    if (!entry.is_regular_file()) {
      continue;
    }
    const std::string name = entry.path().filename().string();
    if (name.size() >= std::string("_defconfig").size() &&
        name.compare(name.size() - std::string("_defconfig").size(),
                     std::string("_defconfig").size(), "_defconfig") == 0) {
      files.push_back(entry.path());
    }
  }
  if (ec) {
    std::cerr << "错误: 无法读取目录 " << dir.string() << ": " << ec.message()
              << '\n';
    return 2;
  }
  std::sort(files.begin(), files.end(),
            [](const fs::path& lhs, const fs::path& rhs) {
              return lhs.filename().string() < rhs.filename().string();
            });
  if (files.empty()) {
    std::cerr << "错误: 没有找到 " << dir.string() << "/*_defconfig\n";
    return 2;
  }
  int failed = 0;
  for (const auto& path : files) {
    std::vector<std::string> errors;
    try {
      errors = check_file(path);
    } catch (const std::exception& ex) {
      std::cerr << "[FAIL] " << path.filename().string() << ": "
                << ex.what() << '\n';
      ++failed;
      continue;
    }
    if (!errors.empty()) {
      ++failed;
      for (const auto& message : errors) {
        std::cerr << "[FAIL] " << message << '\n';
      }
    } else if (!quiet) {
      std::cout << "[OK] " << path.filename().string() << '\n';
    }
  }
  if (failed != 0) {
    std::cerr << "[FAIL] " << failed << "/" << files.size()
              << " 个 defconfig 违反配置契约\n";
    return 1;
  }
  if (!quiet) {
    std::cout << "[OK] " << files.size() << " 个 defconfig 全部通过配置契约校验\n";
  }
  return 0;
}
int main(int argc, char** argv) {
  bool config_mode = false;
  bool defconfigs_mode = false;
  bool self_test = false;
  bool quiet = false;
  std::optional<fs::path> config_path;
  std::optional<fs::path> defconfigs_dir;
  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    if (arg == "--help" || arg == "-h") {
      print_usage(std::cout, argv[0]);
      return 0;
    }
    if (arg == "--config") {
      if (i + 1 >= argc) {
        std::cerr << "错误: --config 需要一个路径\n";
        return 2;
      }
      config_mode = true;
      config_path = fs::path(argv[++i]);
      continue;
    }
    if (arg == "--defconfigs") {
      defconfigs_mode = true;
      continue;
    }
    if (arg == "--defconfigs-dir") {
      if (i + 1 >= argc) {
        std::cerr << "错误: --defconfigs-dir 需要一个目录\n";
        return 2;
      }
      defconfigs_dir = fs::path(argv[++i]);
      continue;
    }
    if (arg == "--self-test") {
      self_test = true;
      continue;
    }
    if (arg == "--quiet") {
      quiet = true;
      continue;
    }
    std::cerr << "错误: 未知参数 " << arg << '\n';
    print_usage(std::cerr, argv[0]);
    return 2;
  }
  const int mode_count = static_cast<int>(config_mode) +
                         static_cast<int>(defconfigs_mode) +
                         static_cast<int>(self_test);
  if (mode_count != 1) {
    std::cerr << "错误: 必须且只能指定 --config、--defconfigs 或 --self-test 之一\n";
    print_usage(std::cerr, argv[0]);
    return 2;
  }
  if (defconfigs_dir && !defconfigs_mode) {
    std::cerr << "错误: --defconfigs-dir 只能与 --defconfigs 一起使用\n";
    return 2;
  }
  try {
    if (config_mode) {
      std::error_code ec;
      if (!fs::is_regular_file(*config_path, ec)) {
        std::cerr << "错误: 配置文件不存在: " << config_path->string() << '\n';
        return 2;
      }
      const std::vector<std::string> errors = check_file(*config_path);
      if (!errors.empty()) {
        for (const auto& message : errors) {
          std::cerr << "[FAIL] " << message << '\n';
        }
        return 1;
      }
      if (!quiet) {
        std::cout << "[OK] Kconfig 配置契约校验通过: " << config_path->string()
                  << '\n';
      }
      return 0;
    }
    if (self_test)
      return run_self_test();
    return run_defconfigs(defconfigs_dir ? *defconfigs_dir
                                         : default_configs_dir(argv[0]),
                          quiet);
  } catch (const std::exception& ex) {
    std::cerr << "错误: " << ex.what() << '\n';
    return 1;
  }
}
