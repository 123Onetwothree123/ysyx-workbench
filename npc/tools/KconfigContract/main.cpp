import npc.kconfig_contract;
import std;
using namespace npc::kconfig_contract;
void print_usage(std::ostream& out, const char* program) {
  out << "Kconfig 配置契约校验（C++）\n\n"
      << "用法:\n"
      << "  " << program << " --config PATH [--typed-json PATH] [--soc-rtl]\n"
      << "  " << program << " --defconfigs [--defconfigs-dir DIR] [--soc-rtl]\n"
      << "  " << program << " --self-test\n"
      << "  " << program << " --help\n\n"
      << "选项:\n"
      << "  --config PATH          校验一个 .config/defconfig\n"
      << "  --typed-json PATH      同时校验 npc_config_gen 生成的 typed JSON：\n"
      << "                         从顶层 flat 读取生效值，先跑完整契约，再\n"
      << "                         逐项比较 .config 请求值（需配合 --config）\n"
      << "  --set CONFIG_X=VALUE   把命令行覆盖视为请求值（可重复，供构建链转发）\n"
      << "  --defconfigs           校验 DIR 下所有 *_defconfig（默认 npc/configs）\n"
      << "  --defconfigs-dir DIR   配合 --defconfigs 指定目录\n"
      << "  --soc-rtl              同时检查 ysyxSoC 地址契约与 Kconfig PMA 是否一致\n"
      << "  --soc-rtl-file PATH    指定 ysyxSoC 地址契约文件（隐含 --soc-rtl）\n"
      << "  --self-test           运行 C++ 内置回归自检（不需要 Python）\n"
      << "  --quiet                成功时不打印逐文件信息\n"
      << "  -h, --help             显示本帮助\n"
      << "\n"
      << "环境变量 SIM_TOP=soc 或 NPC_SOC_CONTRACT=1 时自动启用 --soc-rtl\n";
}
int run_defconfigs(const fs::path& dir, bool quiet,
                   const fs::path& socRtlFile) {
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
      errors = check_file(path, socRtlFile);
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
  bool soc_rtl = false;
  std::optional<fs::path> config_path;
  std::optional<fs::path> typed_json_path;
  std::optional<fs::path> defconfigs_dir;
  std::optional<fs::path> soc_rtl_file;
  std::vector<std::string> overrides;
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
    if (arg == "--typed-json") {
      if (i + 1 >= argc) {
        std::cerr << "错误: --typed-json 需要一个路径\n";
        return 2;
      }
      typed_json_path = fs::path(argv[++i]);
      continue;
    }
    if (arg == "--set") {
      if (i + 1 >= argc) {
        std::cerr << "错误: --set 需要 CONFIG_X=VALUE\n";
        return 2;
      }
      overrides.push_back(argv[++i]);
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
    if (arg == "--soc-rtl") {
      soc_rtl = true;
      continue;
    }
    if (arg == "--soc-rtl-file") {
      if (i + 1 >= argc) {
        std::cerr << "错误: --soc-rtl-file 需要一个路径\n";
        return 2;
      }
      soc_rtl = true;
      soc_rtl_file = fs::path(argv[++i]);
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
  if (!soc_rtl) {
    const char* sim_top = std::getenv("SIM_TOP");
    if (sim_top != nullptr && lower_ascii(strip(sim_top)) == "soc") {
      soc_rtl = true;
    }
    const char* soc_env = std::getenv("NPC_SOC_CONTRACT");
    if (soc_env != nullptr) {
      const std::string value = lower_ascii(strip(soc_env));
      if (value == "1" || value == "y" || value == "yes" ||
          value == "true" || value == "on") {
        soc_rtl = true;
      }
    }
  }
  std::optional<fs::path> soc_map_path;
  if (soc_rtl && (config_mode || defconfigs_mode)) {
    if (soc_rtl_file) {
      soc_map_path = *soc_rtl_file;
    } else {
      soc_map_path = default_soc_address_map(argv[0]);
      if (soc_map_path->empty()) {
        std::cerr << "错误: 找不到 ysyxSoC 地址契约文件 "
                     "ysyxSoC/src/Top.scala；请用 --soc-rtl-file "
                     "指定\n";
        return 2;
      }
    }
    std::error_code ec;
    if (!fs::is_regular_file(*soc_map_path, ec)) {
      std::cerr << "错误: ysyxSoC 地址契约文件不存在: "
                << soc_map_path->string() << '\n';
      return 2;
    }
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
  if (typed_json_path && !config_mode) {
    std::cerr << "错误: --typed-json 只能与 --config 一起使用\n";
    return 2;
  }
  if (!overrides.empty() && !config_mode) {
    std::cerr << "错误: --set 只能与 --config 一起使用\n";
    return 2;
  }
  try {
    if (config_mode) {
      std::error_code ec;
      if (!fs::is_regular_file(*config_path, ec)) {
        std::cerr << "错误: 配置文件不存在: " << config_path->string() << '\n';
        return 2;
      }
      Config requested = parse_config(*config_path);
      apply_config_overrides(requested, overrides);
      const std::string source = config_path->filename().string();
      std::vector<std::string> errors = validate_config(requested, source);
      if (soc_map_path) {
        const auto socErrors =
            check_soc_rtl_file(requested, *soc_map_path, source);
        errors.insert(errors.end(), socErrors.begin(), socErrors.end());
      }
      if (typed_json_path) {
        if (!fs::is_regular_file(*typed_json_path, ec)) {
          std::cerr << "错误: typed JSON 不存在: " << typed_json_path->string()
                    << '\n';
          return 2;
        }
        const Config effective = parse_typed_json_flat(*typed_json_path);
        const std::string effectiveSource =
            typed_json_path->filename().string() + " flat";
        const auto effectiveErrors =
            check_effective_config(requested, effective, effectiveSource);
        errors.insert(errors.end(), effectiveErrors.begin(),
                      effectiveErrors.end());
        if (soc_map_path) {
          const auto socErrors =
              check_soc_rtl_file(effective, *soc_map_path, effectiveSource);
          errors.insert(errors.end(), socErrors.begin(), socErrors.end());
        }
      }
      if (!errors.empty()) {
        for (const auto& message : errors) {
          std::cerr << "[FAIL] " << message << '\n';
        }
        return 1;
      }
      if (!quiet) {
        std::cout << "[OK] Kconfig 配置契约校验通过: " << config_path->string();
        if (typed_json_path) {
          std::cout << " + " << typed_json_path->filename().string()
                    << " flat";
        }
        std::cout << '\n';
      }
      return 0;
    }
    if (self_test)
      return run_self_test();
    return run_defconfigs(defconfigs_dir ? *defconfigs_dir
                                         : default_configs_dir(argv[0]),
                          quiet,
                          soc_map_path ? *soc_map_path : fs::path{});
  } catch (const std::exception& ex) {
    std::cerr << "错误: " << ex.what() << '\n';
    return 1;
  }
}
