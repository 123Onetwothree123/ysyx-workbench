#include <unistd.h>
import npc.linux_boot_test;
import std;
using namespace npc::linux_boot_test;
struct TestCase {
    std::string name;
    std::function<void(const fs::path &)> body;
};
fs::path make_temp_root() {
    const fs::path base = fs::temp_directory_path() / "opencode";
    std::error_code error;
    fs::create_directories(base, error);
    require(!error, "cannot create temporary directory " + base.string() +
                        ": " + error.message());
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    const auto ticks =
        std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();
    std::ostringstream name;
    name << "npc-linux-boot-" << ::getpid() << '-' << ticks;
    const fs::path root = base / name.str();
    fs::remove_all(root, error);
    error.clear();
    fs::create_directories(root, error);
    require(!error, "cannot create test directory " + root.string() + ": " +
                        error.message());
    return root;
}
int main(int argc, char **argv) {
    (void)argc;
    try {
        g_ctx.npc_home = locate_npc_home(argv[0]);
        g_ctx.workbench_home = g_ctx.npc_home.parent_path();
        g_ctx.build_dtb = g_ctx.npc_home / "scripts" / "build_dtb.sh";
        g_ctx.pack_linux_image =
            g_ctx.npc_home / "scripts" / "pack_linux_image.sh";
        require(fs::is_regular_file(g_ctx.build_dtb),
                "missing script: " + g_ctx.build_dtb.string());
        require(fs::is_regular_file(g_ctx.pack_linux_image),
                "missing script: " + g_ctx.pack_linux_image.string());
        g_ctx.fdtget = find_tool("fdtget");
        g_ctx.sha256sum = find_tool("sha256sum");
    } catch (const std::exception &error) {
        std::cerr << "linux_boot_test: setup failed: " << error.what() << '\n';
        return 2;
    }
    const std::vector<TestCase> tests = {
        {"isa_matrix", test_isa_matrix},
        {"timebase_follows_synth_freq", test_timebase_follows_synth_freq},
        {"initramfs_properties", test_initramfs_properties},
        {"initramfs_disabled_has_no_properties",
         test_initramfs_disabled_has_no_properties},
        {"dtb_and_initramfs_addresses_follow_config",
         test_dtb_and_initramfs_addresses_follow_config},
        {"custom_mmu_linux_rejected", test_custom_mmu_linux_rejected},
        {"custom_mmu_non_linux_omits_mmu_type",
         test_custom_mmu_non_linux_omits_mmu_type},
        {"pack_positive_and_sha256", test_pack_positive_and_sha256},
        {"pack_without_initramfs", test_pack_without_initramfs},
        {"pack_image_bad_magic", test_pack_image_bad_magic},
        {"pack_image_text_offset_mismatch",
         test_pack_image_text_offset_mismatch},
        {"pack_image_overlaps_initramfs",
         test_pack_image_overlaps_initramfs},
        {"pack_initramfs_overlaps_dtb", test_pack_initramfs_overlaps_dtb},
        {"pack_initramfs_out_of_ram", test_pack_initramfs_out_of_ram},
        {"pack_initramfs_unaligned", test_pack_initramfs_unaligned},
        {"pack_missing_image", test_pack_missing_image},
        {"pack_fdt_address_out_of_ram", test_pack_fdt_address_out_of_ram},
    };
    fs::path temp_root;
    try {
        temp_root = make_temp_root();
    } catch (const std::exception &error) {
        std::cerr << "linux_boot_test: cannot create temporary workspace: "
                  << error.what() << '\n';
        return 2;
    }
    std::size_t passed = 0;
    std::size_t failed = 0;
    std::cout << "NPC Linux boot environment tests\n";
    std::cout << "NPC_HOME: " << g_ctx.npc_home.string() << '\n';
    std::cout << "Temporary workspace: " << temp_root.string() << "\n\n";
    std::cout.flush();
    for (const TestCase &test : tests) {
        const fs::path work = temp_root / test.name;
        std::error_code error;
        fs::create_directories(work, error);
        if (error) {
            std::cerr << "linux_boot_test: cannot create " << work << ": "
                      << error.message() << '\n';
            return 2;
        }
        std::cout << "[ RUN      ] " << test.name << '\n';
        std::cout.flush();
        try {
            test.body(work);
            std::cout << "[       OK ] " << test.name << '\n';
            ++passed;
        } catch (const std::exception &exception) {
            std::cout << "[  FAILED  ] " << test.name << ": "
                      << exception.what() << '\n';
            ++failed;
        }
        std::cout.flush();
        fs::remove_all(work, error);
    }
    std::error_code cleanup_error;
    fs::remove_all(temp_root, cleanup_error);
    std::cout << '\n' << passed << " passed, " << failed << " failed\n";
    return failed == 0 ? 0 : 1;
}
