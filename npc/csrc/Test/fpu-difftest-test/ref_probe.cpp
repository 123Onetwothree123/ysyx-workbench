// NEMU REF 的 F/D 能力探针。
//
// 直接 dlopen REF，按 NPC 使用的 33*XLEN 状态 ABI 注入一条 FADD.S /
// FADD.D，然后观察 REF 是正常退休还是落入 illegal instruction。这样在
// DUT 支持 F/D、REF 尚未支持时可以明确 SKIP，而不是把 --diff 的第一条
// 分叉误报成 DUT 的浮点错误。
//
// 依赖的 ABI：
//   * difftest_state_size() / difftest_csr_state_size()
//   * difftest_memcpy / difftest_regcpy / difftest_csrcpy / difftest_exec
//   * GPR[32] + PC 的状态布局保持不变（扩展字段只能追加在 PC 之后）
//   * CSR 状态前两项是 mstatus/mtvec
//
// 返回码：0=F（或 F+D）可用，10=F 不可用，11=F 可用但 D 不可用，
//         2=ABI/符号错误，3=REF 执行后 PC 异常。
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <string>
#include <vector>

namespace {
constexpr std::uint64_t kCodeBase = 0x80000000ULL;
constexpr std::uint64_t kTrapVector = 0x80001000ULL;

using StateSize = std::size_t (*)();
using SetPlatform = void (*)(int);
using Init = void (*)(int);
using MemcpyFn = void (*)(std::uint64_t, void *, std::size_t, bool);
using Regcpy = void (*)(void *, bool);
using Csrcpy = void (*)(void *, bool);
using Exec = void (*)(std::uint64_t);
using GetCSR = bool (*)(std::uint32_t, std::uint64_t *);

template <typename Fn>
Fn load(void *handle, const char *name)
{
    dlerror();
    void *symbol = dlsym(handle, name);
    const char *error = dlerror();
    if (error != nullptr) {
        std::fprintf(stderr, "REF 缺少符号 %s: %s\n", name, error);
        return nullptr;
    }
    return reinterpret_cast<Fn>(symbol);
}

std::uint64_t readXWord(const std::vector<std::uint8_t> &buf, std::size_t offset)
{
    std::uint64_t value = 0;
    std::memcpy(&value, buf.data() + offset, sizeof(value));
    return value;
}

void writeXWord(std::vector<std::uint8_t> &buf, std::size_t offset, std::uint64_t value)
{
    std::memcpy(buf.data() + offset, &value, sizeof(value));
}
} // namespace

int main(int argc, char **argv)
{
    if (argc != 3 || (std::strcmp(argv[2], "f") != 0 && std::strcmp(argv[2], "d") != 0)) {
        std::fprintf(stderr, "用法: %s <ref.so> <f|d>\n", argv[0]);
        return 2;
    }
    const bool need_d = std::strcmp(argv[2], "d") == 0;

    void *handle = dlopen(argv[1], RTLD_LAZY | RTLD_LOCAL);
    if (handle == nullptr) {
        std::fprintf(stderr, "dlopen REF 失败: %s\n", dlerror());
        return 2;
    }

    auto state_size = load<StateSize>(handle, "difftest_state_size");
    auto csr_size = load<StateSize>(handle, "difftest_csr_state_size");
    auto set_platform = load<SetPlatform>(handle, "difftest_set_platform");
    auto init = load<Init>(handle, "difftest_init");
    auto memcpy_fn = load<MemcpyFn>(handle, "difftest_memcpy");
    auto regcpy = load<Regcpy>(handle, "difftest_regcpy");
    auto csrcpy = load<Csrcpy>(handle, "difftest_csrcpy");
    auto exec = load<Exec>(handle, "difftest_exec");
    if (!state_size || !csr_size || !set_platform || !init || !memcpy_fn ||
        !regcpy || !csrcpy || !exec) {
        return 2;
    }

    const std::size_t total = state_size();
    // 当前 ABI 为 33 * XLEN；拒绝无法识别的布局，避免按错误偏移读写。
    if (total < 33 * 4 || total % 33 != 0) {
        std::fprintf(stderr, "REF 状态大小异常: %zu\n", total);
        return 2;
    }
    const std::size_t word = total / 33;
    if (word != 4 && word != 8) {
        std::fprintf(stderr, "REF XLEN 无法识别: state_size=%zu\n", total);
        return 2;
    }
    if (csr_size() < 4 * word) {
        std::fprintf(stderr, "REF CSR 状态大小异常: %zu\n", csr_size());
        return 2;
    }
    const std::size_t pc_offset = 32 * word;

    set_platform(0); // direct-NPC 地址图：0x80000000 是可执行 RAM
    init(0);

    // fadd.s f0, f1, f2   ; fadd.d f3, f4, f5
    const std::uint32_t code[2] = {0x0020f053U, 0x0221f1d3U};
    std::uint8_t image[sizeof(code)];
    std::memcpy(image, code, sizeof(image));
    memcpy_fn(kCodeBase, image, sizeof(image), true);

    // 先读出 REF 当前 CSR，只修改 FS 与 mtvec。
    std::vector<std::uint8_t> csr(csr_size(), 0);
    csrcpy(csr.data(), false);
    const std::uint64_t mstatus_offset = 0;
    const std::uint64_t mtvec_offset = word;
    writeXWord(csr, mstatus_offset, readXWord(csr, mstatus_offset) | 0x6000ULL);
    writeXWord(csr, mtvec_offset, kTrapVector);

    auto run_at = [&](std::uint64_t pc) -> std::uint64_t {
        std::vector<std::uint8_t> state(total, 0);
        writeXWord(state, pc_offset, pc);
        regcpy(state.data(), true);
        csrcpy(csr.data(), true);
        exec(1);
        std::vector<std::uint8_t> after(total, 0);
        regcpy(after.data(), false);
        return readXWord(after, pc_offset);
    };

    auto cause_after_trap = [&]() -> std::uint64_t {
        auto get_csr = load<GetCSR>(handle, "difftest_get_csr");
        if (!get_csr) {
            return 0xffffffffULL;
        }
        std::uint64_t cause = 0xffffffffULL;
        if (!get_csr(0x342U, &cause)) {
            return 0xffffffffULL;
        }
        return cause & 0x7fffffffULL;
    };

    const std::uint64_t f_pc = run_at(kCodeBase);
    bool f_ok = false;
    if (f_pc == kCodeBase + 4) {
        f_ok = true;
    } else if (f_pc == kTrapVector) {
        if (cause_after_trap() != 2) {
            std::fprintf(stderr, "REF 在 FADD.S 上产生了非 illegal 异常 (cause=%llu)\n",
                         static_cast<unsigned long long>(cause_after_trap()));
            return 2;
        }
    } else {
        std::fprintf(stderr, "REF 执行 FADD.S 后 PC=0x%llx\n",
                     static_cast<unsigned long long>(f_pc));
        return 3;
    }

    if (!need_d) {
        std::printf("REF F 支持: %s\n", f_ok ? "yes" : "no");
        return f_ok ? 0 : 10;
    }

    const std::uint64_t d_pc = run_at(kCodeBase + 4);
    bool d_ok = false;
    if (d_pc == kCodeBase + 8) {
        d_ok = true;
    } else if (d_pc == kTrapVector) {
        if (cause_after_trap() != 2) {
            std::fprintf(stderr, "REF 在 FADD.D 上产生了非 illegal 异常 (cause=%llu)\n",
                         static_cast<unsigned long long>(cause_after_trap()));
            return 2;
        }
    } else {
        std::fprintf(stderr, "REF 执行 FADD.D 后 PC=0x%llx\n",
                     static_cast<unsigned long long>(d_pc));
        return 3;
    }

    std::printf("REF F 支持: %s, D 支持: %s\n",
                f_ok ? "yes" : "no", d_ok ? "yes" : "no");
    if (!f_ok)
        return 10;
    if (!d_ok)
        return 11;
    return 0;
}
