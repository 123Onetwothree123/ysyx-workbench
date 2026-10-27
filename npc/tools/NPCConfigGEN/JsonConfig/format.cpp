module npc.configgen;
import std;
namespace npc::configgen {
bool asBool(const std::map<std::string, std::string> &values,
            const std::string &key, bool fallback)
{
    const auto found = values.find(key);
    if (found == values.end())
        return fallback;
    const auto &value = found->second;
    return value == "y" || value == "1" || value == "true";
}
long long asInt(const std::map<std::string, std::string> &values,
                const std::string &key, long long fallback)
{
    const auto found = values.find(key);
    if (found == values.end() || found->second.empty())
        return fallback;
    try {
        std::size_t consumed = 0;
        const long long parsed = std::stoll(found->second, &consumed, 0);
        if (consumed != found->second.size())
            throw std::invalid_argument{"trailing characters"};
        return parsed;
    } catch (const std::exception &) {
        throw std::runtime_error("配置项 " + key + " 不是整数: " +
                                 found->second);
    }
}
std::string asString(const std::map<std::string, std::string> &values,
                     const std::string &key, const std::string &fallback)
{
    const auto found = values.find(key);
    return found == values.end() ? fallback : found->second;
}
std::string cppBool(bool value)
{
    return value ? "true" : "false";
}
std::string cppString(const std::string &value)
{
    std::string result{"\""};
    for (const char character : value) {
        switch (character) {
        case '\\': result += "\\\\"; break;
        case '"': result += "\\\""; break;
        case '\n': result += "\\n"; break;
        case '\r': result += "\\r"; break;
        case '\t': result += "\\t"; break;
        default: result.push_back(character); break;
        }
    }
    result.push_back('"');
    return result;
}
std::string jsonEscape(const std::string &value)
{
    std::string result;
    result.reserve(value.size());
    for (const unsigned char character : value) {
        switch (character) {
        case '"': result += "\\\""; break;
        case '\\': result += "\\\\"; break;
        case '\b': result += "\\b"; break;
        case '\f': result += "\\f"; break;
        case '\n': result += "\\n"; break;
        case '\r': result += "\\r"; break;
        case '\t': result += "\\t"; break;
        default:
            if (character < 0x20) {
                char buffer[7];
                std::snprintf(buffer, sizeof(buffer), "\\u%04x", character);
                result += buffer;
            } else {
                result.push_back(static_cast<char>(character));
            }
            break;
        }
    }
    return result;
}
std::string renderEffectiveJson(const std::map<std::string, std::string> &values)
{
    std::ostringstream out;
    out << "{\n";
    bool first = true;
    for (const auto &[key, value] : values) {
        if (!first)
            out << ",\n";
        first = false;
        out << "  \"" << jsonEscape(key) << "\": \"" << jsonEscape(value)
            << "\"";
    }
    out << "\n}\n";
    return out.str();
}
}
