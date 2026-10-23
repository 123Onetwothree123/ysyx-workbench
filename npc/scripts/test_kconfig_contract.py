#!/usr/bin/env python3
"""配置契约校验的轻量单元测试（不需要 mill/Verilator）。"""

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
            "MMU_PTE_SIZE_8B": True,
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
                errors, _ = check_file(path)
                self.assertEqual(errors, [], f"{path.name} 校验失败")

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

    def test_mmu_levels_5_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_LEVELS"] = 5
        self.assert_error(values, "MMU_LEVELS=5")

    def test_mmu_levels_0_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_LEVELS"] = 0
        self.assert_error(values, "1..4")

    def test_pte_size_6_rejected(self):
        values = self.base_rv64()
        values.pop("MMU_PTE_SIZE_8B")
        values["MMU_PTE_SIZE_BYTES"] = 6
        self.assert_error(values, "只支持 4 或 8")

    def test_pte_choice_only_defconfig_is_inferred(self):
        """savedefconfig 可只保留 choice，省略派生的数值符号。"""
        values = self.base_custom_rv64()
        values.pop("MMU_PTE_SIZE_BYTES")
        values.pop("MMU_PTE_SIZE_8B")
        values["MMU_PTE_SIZE_4B"] = True
        values["MMU_PAGE_TABLE_SIZE_BYTES"] = 8192
        self.assertEqual(validate_config(values), [])

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

    def test_pte_choice_mismatch_rejected(self):
        values = self.base_rv64()
        values["MMU_PTE_SIZE_8B"] = False
        values["MMU_PTE_SIZE_4B"] = True
        self.assert_error(values, "choice=4B")

    def test_page_size_not_power_of_two_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_PAGE_SIZE_BYTES"] = 3072
        self.assert_error(values, "必须是 2 的幂")

    def test_page_size_above_1mib_rejected(self):
        values = self.base_custom_rv64()
        values["MMU_PAGE_SIZE_BYTES"] = 2 * 1024 * 1024
        self.assert_error(values, "1MiB")

    def test_custom_geometry_requires_rv64(self):
        values = self.base_rv64()
        values.update({
            "XLEN": 32,
            "MMU_LEVELS": 2,
            "MMU_PTE_SIZE_8B": False,
            "MMU_PTE_SIZE_4B": True,
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

    def test_rv32_standard_still_passes(self):
        values = {
            "XLEN": 32,
            "HAS_S": True,
            "HAS_MMU": True,
            "MMU_LEVELS": 2,
            "MMU_PTE_SIZE_4B": True,
            "MMU_PTE_SIZE_BYTES": 4,
            "MMU_PAGE_SIZE_BYTES": 4096,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
            "MMU_GEOMETRY_MODE": "standard",
            "MMU_PTE_FORMAT": "riscv32",
        }
        self.assertEqual(validate_config(values), [])


if __name__ == "__main__":
    unittest.main()
