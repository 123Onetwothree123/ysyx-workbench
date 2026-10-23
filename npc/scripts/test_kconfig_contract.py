#!/usr/bin/env python3
"""配置契约校验的轻量单元测试（不需要 mill/Verilator）。

原则：非标准但几何上可表达的页大小/PTE 大小/级数应被接受；只有
非 2 的幂、字段重叠/越界、地址位宽溢出等明确不可能的几何才在配置
阶段失败。RTL 实际只实现 4B/8B PTE 等限制由生成器/elaboration 拒绝。
"""

import unittest
from pathlib import Path

from check_kconfig_contract import check_file, validate_config


class KconfigContractTest(unittest.TestCase):
    def base_rv64(self):
        return {
            "XLEN": 64,
            "HAS_S": True,
            "HAS_MMU": True,
            "MMU_LEVELS": 3,
            "MMU_PTE_SIZE_BYTES": 8,
            "MMU_PAGE_SIZE_BYTES": 4096,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
            "MMU_GEOMETRY_MODE": "standard",
            "MMU_PTE_FORMAT": "riscv64",
            "MMU_AD_MODE": "fault",
        }

    def base_custom_rv64(self):
        values = self.base_rv64()
        values.update({
            "MMU_LEVELS": 3,
            "MMU_PAGE_SIZE_BYTES": 1024,
            "MMU_PAGE_TABLE_SIZE_BYTES": 16384,
            "MMU_GEOMETRY_MODE": "custom",
            "MMU_CUSTOM_SATP_MODE": 10,
            "MMU_PTE_FORMAT": "custom",
            "MMU_SUPERPAGE": False,
        })
        return values

    def assert_error(self, values, needle):
        errors = validate_config(values)
        self.assertTrue(errors, "期望配置被拒绝，但校验通过")
        self.assertTrue(
            any(needle in message for message in errors),
            f"错误信息未包含 {needle!r}: {errors}",
        )

    def test_repo_defconfigs_pass(self):
        configs = Path(__file__).resolve().parent.parent / "configs"
        files = sorted(configs.glob("*_defconfig"))
        self.assertTrue(files, "没有找到 defconfig")
        for path in files:
            with self.subTest(defconfig=path.name):
                self.assertEqual(check_file(path), [], f"{path.name} 校验失败")

    def test_xlen_33_rejected(self):
        values = self.base_rv64()
        values["XLEN"] = 33
        self.assert_error(values, "XLEN=33")

    def test_soc_xlen_48_rejected(self):
        values = self.base_rv64()
        values["SOC_XLEN_FOLLOW_CPU"] = False
        values["SOC_XLEN"] = 48
        self.assert_error(values, "SOC_XLEN=48")

    def test_soc_xlen_follow_ignores_value(self):
        values = self.base_rv64()
        values["SOC_XLEN_FOLLOW_CPU"] = True
        values["SOC_XLEN"] = 48
        self.assertEqual(validate_config(values), [])

    def test_mmu_levels_5_accepted_with_4k_table(self):
        values = self.base_custom_rv64()
        values.update({
            "MMU_LEVELS": 5,
            "MMU_PAGE_SIZE_BYTES": 4096,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
        })
        self.assertEqual(validate_config(values), [])

    def test_mmu_levels_9_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_LEVELS"] = 9
        self.assert_error(values, "1..8")

    def test_mmu_levels_0_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_LEVELS"] = 0
        self.assert_error(values, "1..8")

    def test_6level_4k_table_rejected(self):
        values = self.base_custom_rv64()
        values.update({
            "MMU_LEVELS": 6,
            "MMU_PAGE_SIZE_BYTES": 4096,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
        })
        # 12 + 6*9 = 66 位 VA，超过 64。
        self.assert_error(values, "地址位宽=66")

    def test_pte_size_6_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_PTE_SIZE_BYTES"] = 6
        self.assert_error(values, "2 的幂")

    def test_pte_size_16_is_geometry_checked_not_locked(self):
        """16B PTE 不是当前 RTL 能力，但配置阶段不做 4/8 锁定。"""
        values = self.base_custom_rv64()
        values["MMU_PTE_SIZE_BYTES"] = 16
        self.assertEqual(validate_config(values), [])

    def test_pte_field_overlap_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_PTE_READ_BIT"] = 2
        values["MMU_PTE_WRITE_BIT"] = 2
        self.assert_error(values, "不能重复")

    def test_savedefconfig_sparse_choices_are_inferred(self):
        """savedefconfig 会省略字符串派生符号，只保留 choice。"""
        values = self.base_custom_rv64()
        values.pop("MMU_GEOMETRY_MODE")
        values.pop("MMU_PTE_FORMAT")
        values.pop("MMU_AD_MODE")
        values["MMU_GEOMETRY_CUSTOM"] = True
        values["MMU_PTE_CUSTOM"] = True
        values["MMU_AD_FAULT"] = True
        self.assertEqual(validate_config(values), [])

    def test_page_size_not_power_of_two_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_PAGE_SIZE_BYTES"] = 3072
        self.assert_error(values, "必须是 2 的幂")

    def test_page_size_2mib_accepted_for_large_geometry(self):
        values = self.base_custom_rv64()
        values.update({
            "MMU_LEVELS": 4,
            "MMU_PAGE_SIZE_BYTES": 2 * 1024 * 1024,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
            "MMU_SUPERPAGE": True,
        })
        self.assertEqual(validate_config(values), [])

    def test_page_size_2mib_with_5level_rejected(self):
        values = self.base_custom_rv64()
        values.update({
            "MMU_LEVELS": 5,
            "MMU_PAGE_SIZE_BYTES": 2 * 1024 * 1024,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
        })
        # 21 + 5*9 = 66 位 VA，超过 64。
        self.assert_error(values, "地址位宽=66")

    def test_page_size_2mib_5level_small_table_accepted(self):
        values = self.base_custom_rv64()
        values.update({
            "MMU_LEVELS": 5,
            "MMU_PAGE_SIZE_BYTES": 2 * 1024 * 1024,
            "MMU_PAGE_TABLE_SIZE_BYTES": 2048,
            "MMU_SUPERPAGE": True,
        })
        self.assertEqual(validate_config(values), [])

    def test_4mib_6level_small_table_accepted(self):
        values = self.base_custom_rv64()
        values.update({
            "MMU_LEVELS": 6,
            "MMU_PAGE_SIZE_BYTES": 4 * 1024 * 1024,
            "MMU_PAGE_TABLE_SIZE_BYTES": 512,
            "MMU_SUPERPAGE": True,
        })
        self.assertEqual(validate_config(values), [])

    def test_page_size_beyond_int_power_of_two_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_PAGE_SIZE_BYTES"] = 2 * 1024 * 1024 * 1024
        self.assert_error(values, "1GiB")

    def test_custom_geometry_requires_rv64(self):
        values = self.base_rv64()
        values.update({
            "XLEN": 32,
            "MMU_LEVELS": 2,
            "MMU_PTE_SIZE_BYTES": 4,
            "MMU_PAGE_SIZE_BYTES": 1024,
            "MMU_PAGE_TABLE_SIZE_BYTES": 8192,
            "MMU_GEOMETRY_MODE": "custom",
            "MMU_PTE_FORMAT": "riscv32",
        })
        self.assert_error(values, "只用于 RV64")

    def test_custom_satp_mode_8_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_CUSTOM_SATP_MODE"] = 8
        self.assert_error(values, "satp MODE 10..15")

    def test_custom_ad_high_bits_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_AD_MODE"] = "hardware"
        values["MMU_PTE_A_BIT"] = 32
        values["MMU_PTE_D_BIT"] = 33
        self.assert_error(values, "A/D 位")

    def test_standard_rv64_levels_2_rejected(self):
        values = self.base_rv64()
        values["MMU_LEVELS"] = 2
        self.assert_error(values, "Sv39")

    def test_standard_rv64_rejects_nonstandard_pte_size(self):
        values = self.base_rv64()
        values["MMU_PTE_SIZE_BYTES"] = 16
        self.assert_error(values, "PTE_SIZE_BYTES=8")

    # ---- F/D 与 FPU 后端 -------------------------------------------------
    def test_fpu_defaults_keep_fd_off_and_backend_none(self):
        values = {"XLEN": 32}
        self.assertEqual(validate_config(values), [])

    def test_fpu_f_without_backend_rejected(self):
        values = {"XLEN": 32, "RISCV_F": True, "FPU_IMPL": "none"}
        self.assert_error(values, "FPU_IMPL=none")

    def test_fpu_d_without_f_rejected(self):
        values = {"XLEN": 32, "RISCV_D": True, "FPU_IMPL": "hardfloat"}
        self.assert_error(values, "依赖 RISCV_F=y")

    def test_fpu_d_with_flen32_rejected(self):
        values = {
            "XLEN": 32,
            "RISCV_F": True,
            "RISCV_D": True,
            "FPU_IMPL": "hardfloat",
            "FPU_FLEN": 32,
        }
        self.assert_error(values, "FPU_FLEN=64")

    def test_fpu_flen_48_rejected(self):
        values = {
            "XLEN": 32,
            "RISCV_F": True,
            "FPU_IMPL": "hardfloat",
            "FPU_FLEN": 48,
        }
        self.assert_error(values, "FPU_FLEN=48")

    def test_fpu_external_rejected(self):
        values = {
            "XLEN": 32,
            "RISCV_F": True,
            "FPU_IMPL": "external",
            "FPU_FLEN": 64,
        }
        self.assert_error(values, "external")

    def test_fpu_hardfloat_f32_accepted(self):
        values = {
            "XLEN": 32,
            "RISCV_F": True,
            "FPU_IMPL": "hardfloat",
            "FPU_FLEN": 32,
            "FPU_PIPELINE": 2,
        }
        self.assertEqual(validate_config(values), [])

    def test_fpu_fudian_fd64_accepted(self):
        values = {
            "XLEN": 64,
            "RISCV_F": True,
            "RISCV_D": True,
            "FPU_IMPL": "fudian",
            "FPU_FLEN": 64,
        }
        self.assertEqual(validate_config(values), [])

    def test_fpu_savedefconfig_choice_inferred(self):
        values = {
            "XLEN": 64,
            "RISCV_F": True,
            "RISCV_D": True,
            "FPU_IMPL_HARDFLOAT": True,
            "FPU_FLEN": 64,
        }
        self.assertEqual(validate_config(values), [])

    def test_fpu_divsqrt_off_rejected(self):
        values = {
            "XLEN": 32,
            "RISCV_F": True,
            "FPU_IMPL": "fudian",
            "FPU_FLEN": 64,
            "FPU_DIVSQRT": False,
        }
        self.assert_error(values, "FPU_DIVSQRT=y")

    def test_fpu_rounding_off_rejected(self):
        values = {
            "XLEN": 32,
            "RISCV_F": True,
            "FPU_IMPL": "fudian",
            "FPU_FLEN": 64,
            "FPU_ROUNDING": False,
        }
        self.assert_error(values, "FPU_ROUNDING=y")

    def test_fpu_flags_off_rejected(self):
        values = {
            "XLEN": 32,
            "RISCV_F": True,
            "FPU_IMPL": "fudian",
            "FPU_FLEN": 64,
            "FPU_FLAGS": False,
        }
        self.assert_error(values, "FPU_FLAGS=y")

    def test_fpu_backend_selected_without_f_rejected(self):
        values = {"XLEN": 32, "FPU_IMPL": "fudian", "FPU_FLEN": 64}
        self.assert_error(values, "FPU_IMPL 必须为 none")

    def test_rv32_standard_still_passes(self):
        values = {
            "XLEN": 32,
            "HAS_S": True,
            "HAS_MMU": True,
            "MMU_LEVELS": 2,
            "MMU_PTE_SIZE_BYTES": 4,
            "MMU_PAGE_SIZE_BYTES": 4096,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
            "MMU_GEOMETRY_MODE": "standard",
            "MMU_PTE_FORMAT": "riscv32",
        }
        self.assertEqual(validate_config(values), [])


if __name__ == "__main__":
    unittest.main()
