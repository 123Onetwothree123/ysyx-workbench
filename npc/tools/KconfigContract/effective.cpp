module npc.kconfig_contract;
import std;
namespace npc::kconfig_contract {
namespace {
// 只解析 typed JSON 顶层的 "flat" 对象。flat 是 npc_config_gen 写出的
// 扁平生效值投影；其余嵌套结构不是配置契约的输入，直接跳过。
class TypedJsonReader {
public:
  explicit TypedJsonReader(std::string_view text) : text_(text) {}
  Config parse() {
    skip_whitespace();
    expect('{');
    skip_whitespace();
    if (peek() == '}') {
      ++position_;
      throw std::runtime_error("typed JSON 缺少 flat 对象");
    }
    Config flat;
    bool found = false;
    for (;;) {
      std::string key = parse_string();
      skip_whitespace();
      expect(':');
      if (key == "flat") {
        flat = parse_flat_object();
        found = true;
      } else {
        skip_value();
      }
      skip_whitespace();
      if (peek() == ',') {
        ++position_;
        skip_whitespace();
        continue;
      }
      expect('}');
      break;
    }
    if (!found) {
      throw std::runtime_error("typed JSON 缺少 flat 对象");
    }
    return flat;
  }
private:
  char peek() const {
    return position_ < text_.size() ? text_[position_] : '\0';
  }
  void expect(char character) {
    if (peek() != character) {
      throw std::runtime_error(std::string("typed JSON 解析失败：期望 '") +
                               character + "'，位置 " +
                               std::to_string(position_));
    }
    ++position_;
  }
  void skip_whitespace() {
    while (position_ < text_.size() &&
           std::isspace(static_cast<unsigned char>(text_[position_]))) {
      ++position_;
    }
  }
  static int hex_value(char character) {
    if (character >= '0' && character <= '9') {
      return character - '0';
    }
    if (character >= 'a' && character <= 'f') {
      return character - 'a' + 10;
    }
    if (character >= 'A' && character <= 'F') {
      return character - 'A' + 10;
    }
    return -1;
  }
  static void append_utf8(std::string& out, unsigned codepoint) {
    if (codepoint <= 0x7f) {
      out.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
      out.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
      out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
      out.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
      out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
      out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
  }
  std::string parse_string() {
    skip_whitespace();
    expect('"');
    std::string result;
    while (position_ < text_.size()) {
      const char character = text_[position_++];
      if (character == '"') {
        return result;
      }
      if (character != '\\') {
        result.push_back(character);
        continue;
      }
      if (position_ >= text_.size()) {
        break;
      }
      const char escaped = text_[position_++];
      switch (escaped) {
      case '"': result.push_back('"'); break;
      case '\\': result.push_back('\\'); break;
      case '/': result.push_back('/'); break;
      case 'b': result.push_back('\b'); break;
      case 'f': result.push_back('\f'); break;
      case 'n': result.push_back('\n'); break;
      case 'r': result.push_back('\r'); break;
      case 't': result.push_back('\t'); break;
      case 'u': {
        unsigned codepoint = 0;
        for (int index = 0; index < 4; ++index) {
          if (position_ >= text_.size()) {
            throw std::runtime_error("typed JSON Unicode 转义不完整");
          }
          const int digit = hex_value(text_[position_++]);
          if (digit < 0) {
            throw std::runtime_error(
                "typed JSON Unicode 转义包含非法十六进制字符");
          }
          codepoint = (codepoint << 4) | static_cast<unsigned>(digit);
        }
        append_utf8(result, codepoint);
        break;
      }
      default:
        throw std::runtime_error("typed JSON 包含未知转义字符");
      }
    }
    throw std::runtime_error("typed JSON 字符串未闭合");
  }
  void skip_value() {
    skip_whitespace();
    const char character = peek();
    if (character == '"') {
      (void)parse_string();
      return;
    }
    if (character == '{') {
      ++position_;
      skip_whitespace();
      if (peek() == '}') {
        ++position_;
        return;
      }
      for (;;) {
        (void)parse_string();
        skip_whitespace();
        expect(':');
        skip_value();
        skip_whitespace();
        if (peek() == ',') {
          ++position_;
          continue;
        }
        expect('}');
        return;
      }
    }
    if (character == '[') {
      ++position_;
      skip_whitespace();
      if (peek() == ']') {
        ++position_;
        return;
      }
      for (;;) {
        skip_value();
        skip_whitespace();
        if (peek() == ',') {
          ++position_;
          continue;
        }
        expect(']');
        return;
      }
    }
    for (const std::string_view literal : {"true", "false", "null"}) {
      if (text_.substr(position_, literal.size()) == literal) {
        position_ += literal.size();
        return;
      }
    }
    const std::size_t begin = position_;
    while (position_ < text_.size()) {
      const char current = text_[position_];
      if (std::isspace(static_cast<unsigned char>(current)) ||
          current == ',' || current == ']' || current == '}') {
        break;
      }
      ++position_;
    }
    if (position_ == begin) {
      throw std::runtime_error("typed JSON 包含无法解析的值");
    }
  }
  Config parse_flat_object() {
    skip_whitespace();
    expect('{');
    Config values;
    skip_whitespace();
    if (peek() == '}') {
      ++position_;
      return values;
    }
    for (;;) {
      std::string key = parse_string();
      skip_whitespace();
      expect(':');
      skip_whitespace();
      if (peek() != '"') {
        throw std::runtime_error("typed JSON 的 flat 字段 " + key +
                                 " 不是字符串");
      }
      std::string value = parse_string();
      values[std::move(key)] = Value::make_string(std::move(value));
      skip_whitespace();
      if (peek() == ',') {
        ++position_;
        skip_whitespace();
        continue;
      }
      expect('}');
      break;
    }
    return values;
  }
  std::string_view text_;
  std::size_t position_{0};
};
std::string display_value(const Value& value) {
  if (value.kind == Value::Kind::Boolean) {
    return value.boolean ? "y" : "n";
  }
  return value_to_string(value);
}
std::optional<bool> bool_value(const Value& value) {
  if (value.kind == Value::Kind::Boolean) {
    return value.boolean;
  }
  const std::string text = lower_ascii(strip(value_to_string(value)));
  if (text == "y" || text == "1" || text == "true") {
    return true;
  }
  if (text == "n" || text == "0" || text == "false") {
    return false;
  }
  return std::nullopt;
}
std::optional<long long> integer_value(const Value& value) {
  if (value.kind == Value::Kind::Integer) {
    return value.integer;
  }
  if (value.kind == Value::Kind::String) {
    return parse_integer(value.string);
  }
  return std::nullopt;
}
// bool 的 y/n/1/0 以及整数的十进制/0x 表示只是文本形式不同，语义相同；
// 其余字符串必须逐字相等。这样既允许 Kconfig 的十六进制格式规范化，
// 又不会把真正的数值改写当成合法差异。
bool values_equivalent(const Value& requested, const Value& effective) {
  const auto requested_bool = bool_value(requested);
  const auto effective_bool = bool_value(effective);
  if (requested_bool && effective_bool) {
    return *requested_bool == *effective_bool;
  }
  const auto requested_int = integer_value(requested);
  const auto effective_int = integer_value(effective);
  if (requested_int && effective_int) {
    return *requested_int == *effective_int;
  }
  return display_value(requested) == display_value(effective);
}
// 白名单：这些符号由 npc_config_gen 按其它配置项归一化/派生，允许
// .config 与 typed JSON flat 的文本不同。其它字段一旦不同就报错。
//   ITRACE：ITRACE_WRITE_FILE=y 时生成器强制打开 ITRACE。
//   ICACHE/DCACHE：CACHE_ENABLE=n 时生成器统一写成 "0"。
std::optional<std::string> expected_derived_value(const Config& requested,
                                                  const std::string& key) {
  if (key == "ITRACE") {
    const bool enabled = as_bool(requested, "ITRACE", false) ||
                         as_bool(requested, "ITRACE_WRITE_FILE", false);
    return enabled ? "y" : "n";
  }
  if (key == "ICACHE" || key == "DCACHE") {
    if (!as_bool(requested, "CACHE_ENABLE", true)) {
      return "0";
    }
  }
  return std::nullopt;
}
} // namespace
Config parse_typed_json_flat_text(std::string_view text) {
  return TypedJsonReader{text}.parse();
}
Config parse_typed_json_flat(const fs::path& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    throw std::runtime_error("无法打开 typed JSON: " + path.string());
  }
  std::ostringstream buffer;
  buffer << input.rdbuf();
  try {
    return parse_typed_json_flat_text(buffer.str());
  } catch (const std::exception& ex) {
    throw std::runtime_error(path.string() + ": " + ex.what());
  }
}
void apply_config_overrides(Config& values,
                            const std::vector<std::string>& overrides) {
  for (const auto& assignment : overrides) {
    const std::size_t separator = assignment.find('=');
    if (separator == std::string::npos) {
      throw std::runtime_error("--set 必须是 CONFIG_X=VALUE，收到 '" +
                               assignment + "'");
    }
    std::string key = strip(std::string_view(assignment).substr(0, separator));
    if (key.starts_with("CONFIG_")) {
      key.erase(0, 7);
    }
    if (key.empty()) {
      throw std::runtime_error("--set 符号名为空: '" + assignment + "'");
    }
    std::string text = strip(std::string_view(assignment).substr(separator + 1));
    if (text.size() >= 2 && text.front() == '"' && text.back() == '"') {
      text = text.substr(1, text.size() - 2);
    }
    if (text == "y") {
      values[key] = Value::make_bool(true);
    } else if (text == "n") {
      values[key] = Value::make_bool(false);
    } else if (const auto integer = parse_integer(text)) {
      values[key] = Value::make_int(*integer);
    } else {
      values[key] = Value::make_string(text);
    }
  }
}
std::vector<std::string> check_effective_config(const Config& requested,
                                                const Config& effective,
                                                const std::string& source) {
  std::vector<std::string> errors = validate_config(effective, source);
  const auto err = [&errors, &source](const std::string& message) {
    errors.push_back(source + ": " + message);
  };
  for (const auto& [key, requested_value] : requested) {
    const auto effective_it = effective.find(key);
    if (effective_it == effective.end()) {
      err(key + ": 请求值='" + display_value(requested_value) +
          "' 在 typed JSON 的 flat 中缺失");
      continue;
    }
    if (const auto expected = expected_derived_value(requested, key)) {
      if (!values_equivalent(Value::make_string(*expected),
                             effective_it->second)) {
        err(key + ": 请求值='" + display_value(requested_value) +
            "'，按派生规则应为='" + *expected + "'，实际生效值='" +
            display_value(effective_it->second) + "'");
      }
      continue;
    }
    if (!values_equivalent(requested_value, effective_it->second)) {
      err(key + ": 请求值='" + display_value(requested_value) +
          "' 与生效值='" + display_value(effective_it->second) +
          "' 不一致：该符号不是生成器派生字段，显式值不应被改写");
    }
  }
  return errors;
}
} // namespace npc::kconfig_contract
