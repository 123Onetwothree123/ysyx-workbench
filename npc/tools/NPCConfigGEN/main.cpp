import npc.configgen;
import std;
using namespace npc::configgen;
int main(int argc, char **argv)
{
    try {
        std::string configJson;
        std::string output;
        std::string effectiveJson;
        std::string typedJson;
        std::string getKey;
        std::string perfBuildMetadata;
        std::string perfSynth;
        std::string pdk;
        std::string savable{"n"};
        long long socXlen = -1;
        std::vector<std::string> overrides;
        for (int index = 1; index < argc; ++index) {
            const std::string argument{argv[index]};
            const auto next = [&](const char *name) -> std::string {
                if (index + 1 >= argc)
                    throw std::runtime_error(std::string{"缺少参数 "} + name);
                return argv[++index];
            };
            if (argument == "--config-json")
                configJson = next("--config-json");
            else if (argument == "--output")
                output = next("--output");
            else if (argument == "--effective-json")
                effectiveJson = next("--effective-json");
            else if (argument == "--typed-json")
                typedJson = next("--typed-json");
            else if (argument == "--get")
                getKey = next("--get");
            else if (argument == "--soc-xlen")
                socXlen = std::stoll(next("--soc-xlen"));
            else if (argument == "--perf-build-metadata")
                perfBuildMetadata = next("--perf-build-metadata");
            else if (argument == "--perf-synth")
                perfSynth = next("--perf-synth");
            else if (argument == "--savable")
                savable = next("--savable");
            else if (argument == "--pdk")
                pdk = next("--pdk");
            else if (argument == "--set")
                overrides.push_back(next("--set"));
            else
                throw std::runtime_error("未知参数: " + argument);
        }
        if (configJson.empty())
            throw std::runtime_error("必须指定 --config-json");
        if (output.empty() && effectiveJson.empty() && typedJson.empty() &&
            getKey.empty())
            throw std::runtime_error(
                "必须指定 --output、--effective-json、--typed-json 或 --get "
                "之一");
        auto values = parseFlatJson(readFile(configJson));
        for (const auto &assignment : overrides) {
            const auto separator = assignment.find('=');
            if (separator == std::string::npos)
                throw std::runtime_error("--set 必须是 CONFIG_X=VALUE");
            std::string key = assignment.substr(0, separator);
            const std::string value = assignment.substr(separator + 1);
            if (key.starts_with("CONFIG_"))
                key.erase(0, 7);
            values[key] = value;
        }
        deriveEffectiveConfig(values);
        if (socXlen < 0) {
            socXlen = asInt(values, "SOC_XLEN_EFFECTIVE", 32);
        } else {
            if (socXlen != 32 && socXlen != 64)
                throw std::runtime_error("SOC_XLEN 只允许 32/64");
            values["SOC_XLEN_EFFECTIVE"] = std::to_string(socXlen);
        }
        if (socXlen != 32 && socXlen != 64)
            throw std::runtime_error("SOC_XLEN 只允许 32/64");
        const bool getMode = !getKey.empty();
        const auto digest = configDigest(values);
        if (!effectiveJson.empty()) {
            writeIfChanged(effectiveJson, renderEffectiveJson(values));
            if (!getMode) {
                std::cout << "[npc-config] wrote effective config "
                          << effectiveJson << "\n";
            }
        }
        if (!typedJson.empty()) {
            writeIfChanged(
                typedJson, renderTypedJson(values, digest, pdk, socXlen));
            if (!getMode) {
                std::cout << "[npc-config] wrote typed config " << typedJson
                          << "\n";
            }
        }
        if (getMode) {
            std::string key = getKey;
            if (key.starts_with("CONFIG_"))
                key.erase(0, 7);
            const auto found = values.find(key);
            std::cout << (found == values.end() ? "" : found->second) << "\n";
            return 0;
        }
        if (output.empty())
            return 0;
        const auto content = renderModule(
            values, socXlen, digest, perfBuildMetadata, perfSynth,
            savable == "y", pdk, overrides);
        writeIfChanged(output, content);
        std::cout << "[npc-config] generated " << output << " (xlen="
                  << asInt(values, "XLEN", 32) << ", soc_xlen=" << socXlen
                  << ")\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << "错误：" << error.what() << "\n";
        return 2;
    }
}
