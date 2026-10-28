export module npc.configgen;
import std;
export namespace npc::configgen {
using Config = std::map<std::string, std::string>;
Config parseFlatJson(std::string_view text);
bool asBool(const Config &values, const std::string &key,
            bool fallback = false);
long long asInt(const Config &values, const std::string &key,
                long long fallback);
std::string asString(const Config &values, const std::string &key,
                     const std::string &fallback = "");
std::string cppBool(bool value);
std::string cppString(const std::string &value);
std::string jsonEscape(const std::string &value);
std::string renderEffectiveJson(const Config &values);
struct TypedJson {
    enum class Kind { Null, Boolean, Integer, String, Object, Array };
    Kind kind{Kind::Null};
    bool booleanValue{false};
    long long integerValue{0};
    std::string stringValue;
    std::vector<std::pair<std::string, TypedJson>> objectValue;
    std::vector<TypedJson> arrayValue;
    static TypedJson null()
    {
        return TypedJson{};
    }
    static TypedJson boolean(bool value)
    {
        TypedJson result;
        result.kind = Kind::Boolean;
        result.booleanValue = value;
        return result;
    }
    static TypedJson integer(long long value)
    {
        TypedJson result;
        result.kind = Kind::Integer;
        result.integerValue = value;
        return result;
    }
    static TypedJson string(std::string value)
    {
        TypedJson result;
        result.kind = Kind::String;
        result.stringValue = std::move(value);
        return result;
    }
    static TypedJson object(
        std::vector<std::pair<std::string, TypedJson>> fields)
    {
        TypedJson result;
        result.kind = Kind::Object;
        result.objectValue = std::move(fields);
        return result;
    }
    static TypedJson array(std::vector<TypedJson> items)
    {
        TypedJson result;
        result.kind = Kind::Array;
        result.arrayValue = std::move(items);
        return result;
    }
    void write(std::ostream &out, int indent) const
    {
        switch (kind) {
        case Kind::Null:
            out << "null";
            break;
        case Kind::Boolean:
            out << (booleanValue ? "true" : "false");
            break;
        case Kind::Integer:
            out << integerValue;
            break;
        case Kind::String:
            out << '"' << jsonEscape(stringValue) << '"';
            break;
        case Kind::Object:
            if (objectValue.empty()) {
                out << "{}";
                break;
            }
            out << "{\n";
            for (std::size_t index = 0; index < objectValue.size(); ++index) {
                const auto &[key, value] = objectValue[index];
                out << std::string(static_cast<std::size_t>(indent + 2), ' ')
                    << '"' << jsonEscape(key) << "\": ";
                value.write(out, indent + 2);
                if (index + 1 != objectValue.size())
                    out << ',';
                out << '\n';
            }
            out << std::string(static_cast<std::size_t>(indent), ' ') << '}';
            break;
        case Kind::Array:
            if (arrayValue.empty()) {
                out << "[]";
                break;
            }
            out << "[\n";
            for (std::size_t index = 0; index < arrayValue.size(); ++index) {
                out << std::string(static_cast<std::size_t>(indent + 2), ' ');
                arrayValue[index].write(out, indent + 2);
                if (index + 1 != arrayValue.size())
                    out << ',';
                out << '\n';
            }
            out << std::string(static_cast<std::size_t>(indent), ' ') << ']';
            break;
        }
    }
};
using TypedFields = std::vector<std::pair<std::string, TypedJson>>;
inline void addField(TypedFields &fields, std::string key, TypedJson value)
{
    fields.emplace_back(std::move(key), std::move(value));
}
inline bool flatBool(const std::map<std::string, std::string> &values,
              const std::string &key, bool fallback = false)
{
    return asBool(values, key, fallback);
}
inline long long flatInt(const std::map<std::string, std::string> &values,
                  const std::string &key, long long fallback)
{
    return asInt(values, key, fallback);
}
inline std::string flatString(const std::map<std::string, std::string> &values,
                       const std::string &key,
                       const std::string &fallback = "")
{
    return asString(values, key, fallback);
}
inline std::vector<long long> parseIntList(const std::string &key,
                                    const std::string &text)
{
    std::vector<long long> result;
    std::size_t start = 0;
    while (start <= text.size()) {
        const auto separator = text.find(',', start);
        std::string item = text.substr(
            start, separator == std::string::npos ? std::string::npos
                                                  : separator - start);
        const auto first =
            item.find_first_not_of(" \t\r\n");
        const auto last =
            item.find_last_not_of(" \t\r\n");
        if (first == std::string::npos) {
            item.clear();
        } else {
            item = item.substr(first, last - first + 1);
        }
        if (!item.empty()) {
            try {
                std::size_t consumed = 0;
                const long long parsed = std::stoll(item, &consumed, 0);
                if (consumed != item.size())
                    throw std::invalid_argument{"trailing characters"};
                result.push_back(parsed);
            } catch (const std::exception &) {
                throw std::runtime_error("配置项 " + key +
                                         " 包含非法整数: " + item);
            }
        }
        if (separator == std::string::npos)
            break;
        start = separator + 1;
    }
    return result;
}
struct PerfGroup {
    const char *name;
    std::vector<const char *> counters;
};
const std::vector<PerfGroup> &perfGroups();
bool hasConfigValue(const Config &values, const std::string &key);
void setConfigDefault(Config &values, std::string key, std::string value);
void copyConfigDefault(Config &values, const std::string &target,
                       const std::string &source);
std::string deriveCsrEnabled(const Config &values);
void deriveEffectiveConfig(Config &values);
std::string configDigest(const Config &values);
std::string renderModule(const Config &values, long long socXlen,
                         const std::string &digest,
                         const std::string &perfBuildMetadata,
                         const std::string &perfSynth, bool savable,
                         const std::string &pdkOverride,
                         const std::vector<std::string> &overrides);
std::string renderTypedJson(const Config &values, const std::string &digest,
                             const std::string &pdkOverride,
                             long long socXlen);
std::string readFile(const std::string &path);
void writeIfChanged(const std::string &path, const std::string &content);
void addIsa(TypedFields &root, const Config &values, long long xlen,
            long long socXlen);
void addFpu(TypedFields &root, const Config &values);
void addAlu(TypedFields &root, const Config &values);
void addMdu(TypedFields &root, const Config &values);
void addCache(TypedFields &root, const Config &values);
void addFrontend(TypedFields &root, const Config &values);
void addMmu(TypedFields &root, const Config &values, long long xlen);
void addCsr(TypedFields &root, const Config &values);
void addInterrupt(TypedFields &root, const Config &values);
void addDebug(TypedFields &root, const Config &values);
void addPlatform(TypedFields &root, const Config &values,
                 long long addressWidth, long long socXlen);
void addSoc(TypedFields &root, const Config &values, long long xlen,
            long long socXlen);
void addBoot(TypedFields &root, const Config &values);
void addBuild(TypedFields &root, const Config &values,
              const std::string &pdkOverride);
void addFlat(TypedFields &root, const Config &values);
}
