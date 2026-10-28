export module npc.kconfig_contract;
import std;
export namespace npc::kconfig_contract {
namespace fs = std::filesystem;
struct Value {
  enum class Kind { Boolean, Integer, String };
  Kind kind = Kind::String;
  bool boolean = false;
  long long integer = 0;
  std::string string;
  static Value make_bool(bool value) {
    Value v;
    v.kind = Kind::Boolean;
    v.boolean = value;
    return v;
  }
  static Value make_int(long long value) {
    Value v;
    v.kind = Kind::Integer;
    v.integer = value;
    return v;
  }
  static Value make_string(std::string value) {
    Value v;
    v.kind = Kind::String;
    v.string = std::move(value);
    return v;
  }
};
using Config = std::map<std::string, Value>;

std::string strip(std::string_view text);
std::string lower_ascii(std::string text);
std::string value_to_string(const Value &value);
bool is_power_of_two(long long value);
long long log2_int(long long value);
std::optional<long long> parse_integer(std::string_view input);
long long as_int(const Config &values, const std::string &key, long long fallback);
bool as_bool(const Config &values, const std::string &key, bool fallback = false);
std::string as_str(const Config &values, const std::string &key, const std::string &fallback);
using ChoiceList = std::initializer_list<std::pair<std::string_view, std::string_view>>;
std::string derived_choice_string(const Config &values, const std::string &key,
                                  ChoiceList choices, const std::string &fallback);
bool standard_pte_layout(const Config &values, long long pte_bits);
std::string linux_mmu_type(const Config &values, long long xlen);
std::string hex_no_prefix(long long value);
std::string hex0x(long long value);
std::vector<std::string> validate_config(const Config &values,
                                         const std::string &source = ".config");
Config parse_config(std::istream &input);
Config parse_config(const fs::path &path);
std::vector<std::string> check_file(const fs::path &path);
fs::path executable_path(const char *argv0);
fs::path default_configs_dir(const char *argv0);
int run_self_test();
}
