module npc.kconfig_contract;
import std;
namespace npc::kconfig_contract {
std::string strip(std::string_view text) {
  std::size_t begin = 0;
  std::size_t end = text.size();
  while (begin < end && std::isspace(static_cast<unsigned char>(text[begin]))) {
    ++begin;
  }
  while (end > begin && std::isspace(static_cast<unsigned char>(text[end - 1]))) {
    --end;
  }
  return std::string(text.substr(begin, end - begin));
}
std::string lower_ascii(std::string text) {
  for (char& ch : text) {
    ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
  }
  return text;
}
std::string value_to_string(const Value& value) {
  switch (value.kind) {
    case Value::Kind::Boolean:
      return value.boolean ? "True" : "False";
    case Value::Kind::Integer:
      return std::to_string(value.integer);
    case Value::Kind::String:
      return value.string;
  }
  return {};
}
bool is_power_of_two(long long value) {
  if (value <= 0) {
    return false;
  }
  const auto unsigned_value = static_cast<unsigned long long>(value);
  return (unsigned_value & (unsigned_value - 1)) == 0;
}
long long log2_int(long long value) {
  long long result = 0;
  auto unsigned_value = static_cast<unsigned long long>(value);
  while (unsigned_value > 1) {
    unsigned_value >>= 1;
    ++result;
  }
  return result;
}
std::optional<long long> parse_integer(std::string_view input) {
  std::string text = strip(input);
  if (text.empty()) {
    return std::nullopt;
  }
  std::size_t index = 0;
  bool negative = false;
  if (text[index] == '+' || text[index] == '-') {
    negative = text[index] == '-';
    ++index;
  }
  if (index >= text.size()) {
    return std::nullopt;
  }
  int base = 10;
  if (text[index] == '0' && index + 1 < text.size()) {
    const char prefix = static_cast<char>(std::tolower(
        static_cast<unsigned char>(text[index + 1])));
    if (prefix == 'x') {
      base = 16;
      index += 2;
    } else if (prefix == 'o') {
      base = 8;
      index += 2;
    } else if (prefix == 'b') {
      base = 2;
      index += 2;
    }
  }
  if (index >= text.size()) {
    return std::nullopt;
  }
  std::string digits;
  digits.reserve(text.size() - index);
  bool previous_underscore = false;
  for (std::size_t i = index; i < text.size(); ++i) {
    const char ch = text[i];
    if (ch == '_') {
      if (digits.empty() || previous_underscore) {
        return std::nullopt;
      }
      previous_underscore = true;
      continue;
    }
    const auto uch = static_cast<unsigned char>(ch);
    int digit = -1;
    if (std::isdigit(uch)) {
      digit = ch - '0';
    } else if (std::isalpha(uch)) {
      digit = std::tolower(uch) - 'a' + 10;
    }
    if (digit < 0 || digit >= base) {
      return std::nullopt;
    }
    digits.push_back(ch);
    previous_underscore = false;
  }
  if (digits.empty() || previous_underscore) {
    return std::nullopt;
  }
  if (base == 10 && digits.size() > 1 && digits.front() == '0' &&
      std::any_of(digits.begin(), digits.end(), [](char ch) { return ch != '0'; })) {
    return std::nullopt;
  }
  unsigned long long magnitude = 0;
  constexpr unsigned long long kLimit = 1ULL << 63;
  for (char ch : digits) {
    int digit = 0;
    if (ch >= '0' && ch <= '9') {
      digit = ch - '0';
    } else {
      digit = std::tolower(static_cast<unsigned char>(ch)) - 'a' + 10;
    }
    if (magnitude > (kLimit - static_cast<unsigned long long>(digit)) /
                          static_cast<unsigned long long>(base)) {
      return std::nullopt;
    }
    magnitude = magnitude * static_cast<unsigned long long>(base) +
                static_cast<unsigned long long>(digit);
  }
  if (negative) {
    if (magnitude == kLimit) {
      return std::numeric_limits<long long>::min();
    }
    if (magnitude > static_cast<unsigned long long>(std::numeric_limits<long long>::max())) {
      return std::nullopt;
    }
    return -static_cast<long long>(magnitude);
  }
  if (magnitude > static_cast<unsigned long long>(std::numeric_limits<long long>::max())) {
    return std::nullopt;
  }
  return static_cast<long long>(magnitude);
}
[[noreturn]] void throw_not_integer(const std::string& key, const Value& value) {
  std::string rendered = value_to_string(value);
  if (value.kind == Value::Kind::String) {
    rendered = "'" + rendered + "'";
  }
  throw std::runtime_error(key + "=" + rendered + " 不是整数");
}
long long as_int(const Config& values, const std::string& key, long long fallback) {
  const auto it = values.find(key);
  if (it == values.end()) {
    return fallback;
  }
  const Value& value = it->second;
  if (value.kind == Value::Kind::Boolean) {
    return value.boolean ? 1 : 0;
  }
  if (value.kind == Value::Kind::Integer) {
    return value.integer;
  }
  const auto parsed = parse_integer(value.string);
  if (!parsed) {
    throw_not_integer(key, value);
  }
  return *parsed;
}
bool as_bool(const Config& values, const std::string& key, bool fallback) {
  const auto it = values.find(key);
  if (it == values.end()) {
    return fallback;
  }
  if (it->second.kind == Value::Kind::Boolean) {
    return it->second.boolean;
  }
  const std::string text = lower_ascii(strip(value_to_string(it->second)));
  return text == "y" || text == "1" || text == "true";
}
std::string as_str(const Config& values, const std::string& key,
                   const std::string& fallback) {
  const auto it = values.find(key);
  if (it == values.end() || it->second.kind == Value::Kind::String) {
    return it == values.end() ? fallback : it->second.string;
  }
  return value_to_string(it->second);
}
std::string derived_choice_string(const Config& values, const std::string& key,
                                  ChoiceList choices,
                                  const std::string& fallback) {
  if (values.find(key) != values.end()) {
    return as_str(values, key, fallback);
  }
  for (const auto& [symbol, value] : choices) {
    if (as_bool(values, std::string(symbol), false)) {
      return std::string(value);
    }
  }
  return fallback;
}
Config parse_config(std::istream& input) {
  Config values;
  std::string raw;
  while (std::getline(input, raw)) {
    const std::string line = strip(raw);
    if (line.empty() || line.front() == '#') {
      continue;
    }
    if (line.rfind("CONFIG_", 0) != 0) {
      continue;
    }
    std::size_t index = 7;
    while (index < line.size()) {
      const auto ch = static_cast<unsigned char>(line[index]);
      if (!(std::isalnum(ch) || ch == '_')) {
        break;
      }
      ++index;
    }
    if (index == 7 || index >= line.size() || line[index] != '=') {
      continue;
    }
    const std::string key = line.substr(7, index - 7);
    const std::string text = strip(std::string_view(line).substr(index + 1));
    if (text.size() >= 2 && text.front() == '"' && text.back() == '"') {
      values[key] = Value::make_string(text.substr(1, text.size() - 2));
    } else if (text == "y") {
      values[key] = Value::make_bool(true);
    } else if (text == "n") {
      values[key] = Value::make_bool(false);
    } else if (const auto integer = parse_integer(text)) {
      values[key] = Value::make_int(*integer);
    } else {
      values[key] = Value::make_string(text);
    }
  }
  return values;
}
Config parse_config(const fs::path& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    throw std::runtime_error("无法打开配置文件: " + path.string());
  }
  return parse_config(input);
}
std::vector<std::string> check_file(const fs::path& path) {
  return validate_config(parse_config(path), path.filename().string());
}
fs::path executable_path(const char* argv0) {
  std::error_code ec;
  const fs::path absolute = fs::absolute(fs::path(argv0), ec);
  if (ec) {
    return fs::path(argv0);
  }
  const fs::path canonical = fs::weakly_canonical(absolute, ec);
  return ec ? absolute : canonical;
}
fs::path default_configs_dir(const char* argv0) {
  std::error_code ec;
  const fs::path exe = executable_path(argv0);
  const fs::path from_exe =
      exe.parent_path().parent_path().parent_path() / "configs";
  if (fs::is_directory(from_exe, ec)) {
    return from_exe;
  }
  const fs::path from_cwd = fs::current_path(ec) / "configs";
  if (fs::is_directory(from_cwd, ec)) {
    return from_cwd;
  }
  return from_exe;
}

}
