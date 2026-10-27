module npc.configgen;
import std;
namespace npc::configgen {
std::string renderTypedJson(const Config &values, const std::string &digest,
                            const std::string &pdkOverride, long long socXlen)
{
    const long long xlen = flatInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    if (socXlen != 32 && socXlen != 64)
        throw std::runtime_error("SOC_XLEN 只允许 32/64");
    const long long addressWidth =
        flatInt(values, "ADDRESS_WIDTH", xlen == 64 ? 64 : 32);
    TypedFields root;
    addField(root, "schema", TypedJson::integer(1));
    addField(root, "config_hash", TypedJson::string(digest));
    addIsa(root, values, xlen, socXlen);
    addFpu(root, values);
    addMdu(root, values);
    addCache(root, values);
    addFrontend(root, values);
    addMmu(root, values, xlen);
    addCsr(root, values);
    addInterrupt(root, values);
    addDebug(root, values);
    addPlatform(root, values, addressWidth, socXlen);
    addSoc(root, values, xlen, socXlen);
    addBoot(root, values);
    addBuild(root, values, pdkOverride);
    addFlat(root, values);
    std::ostringstream out;
    TypedJson::object(std::move(root)).write(out, 0);
    out << '\n';
    return out.str();
}
}
