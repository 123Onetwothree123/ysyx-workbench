export module npc.linux_boot_test;
import std;
export namespace npc::linux_boot_test {
namespace fs = std::filesystem;
using std::uint8_t;
using std::uint32_t;
using std::uint64_t;

inline constexpr uint64_t IMAGE_ADDR = 0x80200000;
inline constexpr uint64_t INITRAMFS_ADDR = 0x82000000;
inline constexpr uint64_t FDT_ADDR = 0x83F00000;
inline constexpr uint64_t RAM_BASE = 0x80000000;
inline constexpr uint64_t RAM_SIZE = 0x04000000;
inline constexpr uint64_t FW_BASE = 0x80000000;
inline constexpr uint64_t FW_SIZE = 0x00200000;
inline constexpr std::string_view DEFAULT_BOOTARGS =
    "console=ttyS0,115200 earlycon=sbi";

using Env = std::map<std::string, std::string>;
struct Failure : std::runtime_error {
    using std::runtime_error::runtime_error;
};
struct CommandResult {
    int rc = 0;
    std::string out;
    std::string err;
    std::string command;
};
struct Context {
    fs::path npc_home;
    fs::path workbench_home;
    fs::path build_dtb;
    fs::path pack_linux_image;
    fs::path fdtget;
    fs::path sha256sum;
};
struct Config {
    uint64_t xlen = 64;
    bool m = true;
    bool a = true;
    bool c = false;
    bool f = false;
    bool d = false;
    bool has_s = true;
    bool has_u = true;
    bool has_mmu = true;
    uint64_t levels = 3;
    std::string geometry = "standard";
    uint64_t page_size = 4096;
    uint64_t pte_size = 8;
    uint64_t table_size = 4096;
    std::string pte_format = "riscv64";
    uint64_t synth_freq = 450;
    uint64_t fw_base = FW_BASE;
    uint64_t fw_size = FW_SIZE;
    uint64_t next_addr = IMAGE_ADDR;
    uint64_t fdt_addr = FDT_ADDR;
    bool fdt_enable = true;
    std::string fdt_path = "dts/npc.dts";
    uint64_t fdt_reserve_size = 0x10000;
    uint64_t ram_base = RAM_BASE;
    uint64_t ram_size = RAM_SIZE;
    bool linux = true;
    std::string image_path = "Image";
    std::string bootargs = std::string(DEFAULT_BOOTARGS);
    bool initramfs = false;
    std::string initramfs_path;
    uint64_t initramfs_addr = INITRAMFS_ADDR;
};
using Magic = std::array<uint8_t, 8>;
Magic default_magic();
Magic magic_from_text(std::string_view text);
struct ImageSpec {
    uint64_t file_size = 4096;
    std::optional<uint64_t> header_image_size;
    uint64_t text_offset = 0x200000;
    Magic magic = default_magic();
};
struct PackSetup {
    fs::path config;
    fs::path image;
    fs::path initramfs;
    fs::path dtb_dir;
    fs::path dtb;
    fs::path fw_dir;
    fs::path out_dir;
    Env env;
};

extern Context g_ctx;

[[noreturn]] void fail(const std::string &message);
void require(bool condition, const std::string &message);
std::string trim(std::string value);
std::string hex_u64(uint64_t value);
std::string decimal_u64(uint64_t value);
fs::path find_tool(const std::string &name);
std::string command_display(const std::vector<std::string> &argv);
CommandResult run_command(const std::vector<std::string> &argv,
                          const Env &overrides = {},
                          const std::optional<fs::path> &cwd = std::nullopt);
std::string describe(const CommandResult &result);
void require_exit(const CommandResult &result, bool expect_ok,
                  const std::string &what);
void require_contains(const std::string &haystack, const std::string &needle,
                      const std::string &what, const CommandResult &result);
std::string read_text(const fs::path &path);
std::vector<uint8_t> read_binary(const fs::path &path);
void write_binary(const fs::path &path, const std::vector<uint8_t> &data);
void write_text(const fs::path &path, const std::string &text);
uint64_t get_file_size(const fs::path &path);
std::string sha256_file(const fs::path &path);
fs::path executable_path(const char *argv0);
fs::path locate_npc_home(const char *argv0);

fs::path write_config(const fs::path &path, const Config &config);
fs::path make_image(const fs::path &path, const ImageSpec &spec = {});
fs::path make_cpio(const fs::path &path, uint64_t size = 4096);

Env common_env(const fs::path &config, const fs::path &dtb_dir);
CommandResult run_dtb(const fs::path &config, const fs::path &dtb_dir,
                      bool expect_ok = true, Env extra = {});
std::optional<std::string> fdtget(const fs::path &dtb, const std::string &node,
                                  const std::string &property,
                                  const std::string &type);
std::optional<std::string> fdtget_string(const fs::path &dtb,
                                         const std::string &node,
                                         const std::string &property);
std::optional<uint64_t> parse_hex_value(const std::string &text);
std::optional<uint64_t> fdtget_int(const fs::path &dtb,
                                   const std::string &node,
                                   const std::string &property);
std::vector<uint64_t> fdtget_cells(const fs::path &dtb, const std::string &node,
                                   const std::string &property);
std::string dtb_info(const fs::path &dtb);
std::string require_fdt_string(const fs::path &dtb, const std::string &node,
                               const std::string &property);
uint64_t require_fdt_int(const fs::path &dtb, const std::string &node,
                         const std::string &property);
void require_fdt_absent(const fs::path &dtb, const std::string &node,
                        const std::string &property);
void check_string(const std::string &actual, const std::string &expected,
                  const std::string &what);
void check_u64(uint64_t actual, uint64_t expected, const std::string &what);

fs::path write_fake_fw(const fs::path &out_dir, const fs::path &dtb,
                       uint64_t fdt_addr = FDT_ADDR,
                       uint64_t timebase = 450000000);
Env pack_env(const fs::path &config, const fs::path &fw_dir,
             const fs::path &dtb_dir, const fs::path &out_dir,
             const fs::path &image, bool with_initramfs,
             const fs::path &initramfs, uint64_t initramfs_addr,
             uint64_t fdt_addr = FDT_ADDR, uint64_t next_addr = IMAGE_ADDR);
PackSetup standard_pack_setup(const fs::path &work, const ImageSpec &image_spec,
                              bool with_initramfs = true,
                              uint64_t initramfs_size = 4096,
                              uint64_t initramfs_addr = INITRAMFS_ADDR);
CommandResult run_pack(const Env &env, bool expect_ok = true);
void require_span_equal(const std::vector<uint8_t> &binary, uint64_t offset,
                        const std::vector<uint8_t> &expected,
                        const std::string &what);

void test_isa_matrix(const fs::path &work);
void test_timebase_follows_synth_freq(const fs::path &work);
void test_initramfs_properties(const fs::path &work);
void test_initramfs_disabled_has_no_properties(const fs::path &work);
void test_dtb_and_initramfs_addresses_follow_config(const fs::path &work);
void test_custom_mmu_linux_rejected(const fs::path &work);
void test_custom_mmu_non_linux_omits_mmu_type(const fs::path &work);
void test_pack_positive_and_sha256(const fs::path &work);
void test_pack_without_initramfs(const fs::path &work);
void test_pack_image_bad_magic(const fs::path &work);
void test_pack_image_text_offset_mismatch(const fs::path &work);
void test_pack_image_overlaps_initramfs(const fs::path &work);
void test_pack_initramfs_overlaps_dtb(const fs::path &work);
void test_pack_initramfs_out_of_ram(const fs::path &work);
void test_pack_initramfs_unaligned(const fs::path &work);
void test_pack_missing_image(const fs::path &work);
void test_pack_fdt_address_out_of_ram(const fs::path &work);
}
