module npc.configgen;
import std;
namespace npc::configgen {
std::string renderTypedJson(const Config &values, const std::string &digest,
                            const std::string &pdkOverride, long long SOCXlen)
{
    const long long xlen = flatInt(values, "XLEN", 32);
    if (xlen != 32 && xlen != 64)
        throw std::runtime_error("XLEN 只允许 32/64");
    if (SOCXlen != 32 && SOCXlen != 64)
        throw std::runtime_error("SOC_XLEN 只允许 32/64");
    const long long addressWidth =
        flatInt(values, "ADDRESS_WIDTH", xlen == 64 ? 64 : 32);
    TypedFields root;
    addField(root, "schema", TypedJson::integer(1));
    addField(root, "config_hash", TypedJson::string(digest));
    addIsa(root, values, xlen, SOCXlen);
    addFPU(root, values);
    addALU(root, values);
    addIDU(root, values);
    addMDU(root, values);
    addCache(root, values);
    addFrontend(root, values);
    addMMU(root, values, xlen);
    addCSR(root, values);
    addInterrupt(root, values);
    addDebug(root, values);
    addPlatform(root, values, addressWidth, SOCXlen);
    addPma(root, values);
    addSoc(root, values, xlen, SOCXlen);
    addBoot(root, values);
    addFpga(root, values);
    addBuild(root, values, pdkOverride);
    addFlat(root, values);
    std::ostringstream out;
    TypedJson::object(std::move(root)).write(out, 0);
    out << '\n';
    return out.str();
}
}
