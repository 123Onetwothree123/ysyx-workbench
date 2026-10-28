module npc.configgen;
import std;
namespace npc::configgen {
void addIsa(TypedFields &root, const Config &values, long long xlen,
            long long socXlen)
{
    const bool hasS = flatBool(values, "HAS_S", true);
    const bool hasU = flatBool(values, "HAS_U", hasS);
    TypedFields isa;
    addField(isa, "xlen", TypedJson::integer(xlen));
    addField(isa, "soc_xlen", TypedJson::integer(socXlen));
    addField(isa, "soc_xlen_follow_cpu",
             TypedJson::boolean(flatBool(values, "SOC_XLEN_FOLLOW_CPU")));
    addField(isa, "m", TypedJson::boolean(flatBool(values, "RISCV_M")));
    addField(isa, "a", TypedJson::boolean(flatBool(values, "RISCV_A")));
    addField(isa, "c", TypedJson::boolean(flatBool(values, "RISCV_C")));
    addField(isa, "f", TypedJson::boolean(flatBool(values, "RISCV_F")));
    addField(isa, "d", TypedJson::boolean(flatBool(values, "RISCV_D")));
    addField(isa, "s", TypedJson::boolean(hasS));
    addField(isa, "u", TypedJson::boolean(hasU));
    addField(root, "isa", TypedJson::object(std::move(isa)));
}
void addFpu(TypedFields &root, const Config &values)
{
    TypedFields fpu;
    addField(fpu, "impl",
             TypedJson::string(flatString(values, "FPU_IMPL", "none")));
    addField(fpu, "flen", TypedJson::integer(flatInt(values, "FPU_FLEN", 64)));
    addField(fpu, "pipeline",
             TypedJson::integer(flatInt(values, "FPU_PIPELINE", 0)));
    addField(fpu, "fma", TypedJson::boolean(flatBool(values, "FPU_FMA", true)));
    addField(fpu, "divsqrt",
             TypedJson::boolean(flatBool(values, "FPU_DIVSQRT", true)));
    addField(fpu, "rounding",
             TypedJson::boolean(flatBool(values, "FPU_ROUNDING", true)));
    addField(fpu, "flags",
             TypedJson::boolean(flatBool(values, "FPU_FLAGS", true)));
    TypedFields hardfloat;
    addField(hardfloat, "recode",
             TypedJson::boolean(
                 flatBool(values, "FPU_HARDFLOAT_RECODE", true)));
    addField(hardfloat, "tininess_before_rounding",
             TypedJson::boolean(flatBool(
                 values, "FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING", false)));
    addField(fpu, "hardfloat", TypedJson::object(std::move(hardfloat)));
    TypedFields fudian;
    addField(fudian, "shared_divsqrt",
             TypedJson::boolean(
                 flatBool(values, "FPU_FUDIAN_SHARED_DIVSQRT", true)));
    addField(fpu, "fudian", TypedJson::object(std::move(fudian)));
    addField(root, "fpu", TypedJson::object(std::move(fpu)));
}
void addMdu(TypedFields &root, const Config &values)
{
    TypedFields mdu;
    addField(mdu, "impl",
             TypedJson::string(flatString(values, "MDU_IMPL", "standard")));
    addField(mdu, "mul_impl",
             TypedJson::string(
                 flatString(values, "MUL_IMPL", "shift_add")));
    addField(mdu, "mul_encoding",
             TypedJson::string(flatString(values, "MUL_ENCODING", "plain")));
    addField(mdu, "div_impl",
             TypedJson::string(flatString(values, "DIV_IMPL", "restoring")));
    addField(mdu, "mul_radix",
             TypedJson::integer(flatInt(values, "MUL_RADIX", 4)));
    addField(mdu, "mul_width",
             TypedJson::integer(flatInt(values, "MUL_WIDTH", 32)));

    TypedFields compressor;
    addField(compressor, "inputs",
             TypedJson::integer(
                 flatInt(values, "MUL_COMPRESSOR_INPUTS", 3)));
    addField(compressor, "outputs",
             TypedJson::integer(
                 flatInt(values, "MUL_COMPRESSOR_OUTPUTS", 2)));
    {
        std::vector<TypedJson> offsets;
        for (const long long offset : parseIntList(
                 "MUL_COMPRESSOR_OUTPUT_OFFSETS",
                 flatString(values, "MUL_COMPRESSOR_OUTPUT_OFFSETS", "0,1")))
            offsets.push_back(TypedJson::integer(offset));
        addField(compressor, "output_offsets",
                 TypedJson::array(std::move(offsets)));
    }
    addField(compressor, "has_cin",
             TypedJson::boolean(
                 flatBool(values, "MUL_COMPRESSOR_HAS_CIN")));
    addField(compressor, "has_cout",
             TypedJson::boolean(
                 flatBool(values, "MUL_COMPRESSOR_HAS_COUT")));
    addField(compressor, "cout_offset",
             TypedJson::integer(
                 flatInt(values, "MUL_COMPRESSOR_COUT_OFFSET", 1)));
    addField(compressor, "allow_redundant",
             TypedJson::boolean(
                 flatBool(values, "MUL_COMPRESSOR_ALLOW_REDUNDANT")));

    TypedFields shiftAdd;
    addField(shiftAdd, "iter_bits",
             TypedJson::integer(flatInt(values, "MUL_ITER_BITS", 1)));
    addField(shiftAdd, "split",
             TypedJson::integer(flatInt(values, "MUL_SPLIT", 1)));
    addField(shiftAdd, "early_out",
             TypedJson::boolean(flatBool(values, "MUL_EARLY_OUT")));

    TypedFields compression;
    addField(compression, "pipeline",
             TypedJson::integer(flatInt(values, "MUL_PIPELINE", 0)));
    addField(compression, "compressor", TypedJson::object(std::move(compressor)));

    TypedFields mul;
    addField(mul, "width", TypedJson::integer(flatInt(values, "MUL_WIDTH", 32)));
    addField(mul, "encoding",
             TypedJson::string(flatString(values, "MUL_ENCODING", "plain")));
    addField(mul, "radix",
             TypedJson::integer(flatInt(values, "MUL_RADIX", 4)));
    addField(mul, "shift_add", TypedJson::object(std::move(shiftAdd)));
    addField(mul, "compression", TypedJson::object(std::move(compression)));
    addField(mdu, "mul", TypedJson::object(std::move(mul)));

    addField(mdu, "div_radix",
             TypedJson::integer(flatInt(values, "DIV_RADIX", 2)));
    addField(mdu, "div_iter_bits",
             TypedJson::integer(flatInt(values, "DIV_ITER_BITS", 1)));
    addField(mdu, "div_early_out",
             TypedJson::boolean(flatBool(values, "DIV_EARLY_OUT")));
    addField(mdu, "mul_iter_bits",
             TypedJson::integer(flatInt(values, "MUL_ITER_BITS", 1)));
    addField(mdu, "mul_pipeline",
             TypedJson::integer(flatInt(values, "MUL_PIPELINE", 0)));
    addField(mdu, "mul_split",
             TypedJson::integer(flatInt(values, "MUL_SPLIT", 1)));
    addField(mdu, "mul_early_out",
             TypedJson::boolean(flatBool(values, "MUL_EARLY_OUT")));

    TypedFields div;
    addField(div, "impl",
             TypedJson::string(flatString(values, "DIV_IMPL", "restoring")));
    auto addDivParams = [&](TypedFields &target, const char *prefix) {
        addField(target, "radix",
                 TypedJson::integer(flatInt(
                     values, std::string{prefix} + "_RADIX",
                     flatInt(values, "DIV_RADIX", 2))));
        addField(target, "iter_bits",
                 TypedJson::integer(flatInt(
                     values, std::string{prefix} + "_ITER_BITS",
                     flatInt(values, "DIV_ITER_BITS", 1))));
        addField(target, "early_out",
                 TypedJson::boolean(flatBool(
                     values, std::string{prefix} + "_EARLY_OUT")));
    };
    TypedFields restoring;
    addDivParams(restoring, "DIV_RESTORING");
    TypedFields nonrestoring;
    addDivParams(nonrestoring, "DIV_NONRESTORING");
    TypedFields srt;
    addDivParams(srt, "DIV_SRT");
    addField(div, "restoring", TypedJson::object(std::move(restoring)));
    addField(div, "nonrestoring", TypedJson::object(std::move(nonrestoring)));
    addField(div, "srt", TypedJson::object(std::move(srt)));
    addField(mdu, "div", TypedJson::object(std::move(div)));

    addField(root, "mdu", TypedJson::object(std::move(mdu)));
}

void addCache(TypedFields &root, const Config &values)
{
    const long long blockSizeLog2 =
        flatInt(values, "CACHE_BLOCK_SIZE_LOG2", 4);
    const long long indexBits = flatInt(values, "CACHE_INDEX_BITS", 5);
    TypedFields cache;
    addField(cache, "block_size_log2", TypedJson::integer(blockSizeLog2));
    addField(cache, "index_bits", TypedJson::integer(indexBits));
    addField(cache, "wbuf_depth",
             TypedJson::integer(flatInt(values, "WBUF_DEPTH", 4)));
    TypedFields icache;
    addField(icache, "enabled",
             TypedJson::boolean(flatBool(values, "ICACHE", true)));
    addField(icache, "block_size_log2",
             TypedJson::integer(flatInt(values, "ICACHE_BLOCK_SIZE_LOG2",
                                        blockSizeLog2)));
    addField(icache, "index_bits",
             TypedJson::integer(
                 flatInt(values, "ICACHE_INDEX_BITS", indexBits)));
    addField(icache, "storage",
             TypedJson::string(flatString(values, "ICACHE_STORAGE", "reg")));
    addField(icache, "coherence",
             TypedJson::string(
                 flatString(values, "ICACHE_COHERENCE", "fence_i")));
    addField(cache, "icache", TypedJson::object(std::move(icache)));
    TypedFields dcache;
    addField(dcache, "enabled",
             TypedJson::boolean(flatBool(values, "DCACHE", false)));
    addField(dcache, "block_size_log2",
             TypedJson::integer(flatInt(values, "DCACHE_BLOCK_SIZE_LOG2",
                                        blockSizeLog2)));
    addField(dcache, "index_bits",
             TypedJson::integer(
                 flatInt(values, "DCACHE_INDEX_BITS", indexBits)));
    addField(dcache, "storage",
             TypedJson::string(flatString(values, "DCACHE_STORAGE", "reg")));
    addField(cache, "dcache", TypedJson::object(std::move(dcache)));
    addField(cache, "dma_coherence",
             TypedJson::string(
                 flatString(values, "DMA_COHERENCE", "software_flush")));
    addField(root, "cache", TypedJson::object(std::move(cache)));
}
void addFrontend(TypedFields &root, const Config &values)
{
    TypedFields frontend;
    addField(frontend, "btb_bits",
             TypedJson::integer(flatInt(values, "BTB_BITS", 4)));
    addField(frontend, "btb_ways",
             TypedJson::integer(flatInt(values, "BTB_WAYS", 1)));
    addField(frontend, "jal_btb_bits",
             TypedJson::integer(flatInt(values, "JAL_BTB_BITS", 4)));
    addField(frontend, "jal_btb_ways",
             TypedJson::integer(flatInt(values, "JAL_BTB_WAYS", 1)));
    addField(frontend, "ras_bits",
             TypedJson::integer(flatInt(values, "RAS_BITS", 4)));
    addField(root, "frontend", TypedJson::object(std::move(frontend)));
}
}
