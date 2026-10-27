module npc.configgen;
import std;
namespace npc::configgen {
std::string configDigest(const std::map<std::string, std::string> &values)
{
    std::uint64_t hash = 1469598103934665603ULL;
    for (const auto &[key, value] : values) {
        for (const char character : key) {
            hash ^= static_cast<unsigned char>(character);
            hash *= 1099511628211ULL;
        }
        hash ^= '=';
        hash *= 1099511628211ULL;
        for (const char character : value) {
            hash ^= static_cast<unsigned char>(character);
            hash *= 1099511628211ULL;
        }
        hash ^= '\n';
        hash *= 1099511628211ULL;
    }
    static constexpr char hex[] = "0123456789abcdef";
    std::string result(16, '0');
    for (int index = 15; index >= 0; --index) {
        result[static_cast<std::size_t>(index)] = hex[hash & 0xfU];
        hash >>= 4U;
    }
    return result;
}
}
