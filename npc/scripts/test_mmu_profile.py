#!/usr/bin/env python3
"""config-only 阶段的最小 profile 生成器测试。"""

import unittest

from gen_mmu_profile import Config, build_profile


class MmuProfileTest(unittest.TestCase):
    def base(self):
        return {
            "HAS_S": True,
            "HAS_U": True,
            "HAS_MMU": True,
            "HAS_TLB": True,
            "MMU_PROFILE": "balanced",
            "MMU_PAGE_SIZE_BYTES": 4096,
            "MMU_LEVELS": 2,
            "MMU_PTE_SIZE_BYTES": 4,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
            "MMU_PTE_FORMAT": "riscv32",
            "MMU_AD_MODE": "fault",
            "MMU_CACHE_MODE": "pipt",
            "MMU_PTW_PATH": "dedicated",
            "MMU_PTW_ARCH": "independent",
            "MMU_PTW_OUTSTANDING": 2,
            "MMU_PTW_BUS_OUTSTANDING": 1,
            "MMU_PTW_ORDER": "in_order",
            "TLB_ORGANIZATION": "split",
            "TLB_STORAGE": "reg",
            "TLB_REPLACEMENT": "plru",
            "TLB_ASID_BITS": 9,
            "ITLB_ENTRIES": 16,
            "ITLB_WAYS": 2,
            "DTLB_ENTRIES": 32,
            "DTLB_WAYS": 4,
            "HAS_CSR_SATP": True,
            "CSR_MSTATUS_MIE_READ": "normal",
            "CSR_MSTATUS_MIE_WRITE": "normal",
        }

    def test_standard_sv32(self):
        profile = build_profile(Config(self.base()))
        self.assertTrue(profile["standard_sv32"])
        self.assertEqual(profile["mmu"]["page_size_bytes"], 4096)
        self.assertEqual(profile["mmu"]["table_entries"], 1024)
        self.assertEqual(profile["mmu"]["computed_address_bits"], 32)
        self.assertEqual(profile["tlb"]["itlb"]["entries"], 16)

    def test_standard_sv39(self):
        values = self.base()
        values.update({
            "XLEN": 64,
            "MMU_LEVELS": 3,
            "MMU_PTE_SIZE_BYTES": 8,
            "MMU_PTE_FORMAT": "riscv64",
            "TLB_ASID_BITS": 16,
        })
        profile = build_profile(Config(values))
        self.assertTrue(profile["standard_sv39"])
        self.assertFalse(profile["standard_sv32"])
        self.assertEqual(profile["mmu"]["virtual_address_bits"], 39)
        self.assertEqual(profile["mmu"]["satp_mode"], 8)
        self.assertEqual(profile["mmu"]["pte"]["ppn_high"], 53)

    def test_rv64_rejects_nonstandard_geometry_in_standard_mode(self):
        values = self.base()
        values.update({"XLEN": 64})
        with self.assertRaisesRegex(ValueError, "Sv39"):
            build_profile(Config(values))

    def test_rv64_custom_1k_page_geometry(self):
        values = self.base()
        values.update({
            "XLEN": 64,
            "MMU_GEOMETRY_MODE": "custom",
            "MMU_CUSTOM_SATP_MODE": 10,
            "MMU_PAGE_SIZE_BYTES": 1024,
            "MMU_LEVELS": 3,
            "MMU_PTE_SIZE_BYTES": 8,
            "MMU_PAGE_TABLE_SIZE_BYTES": 16384,
            "MMU_PTE_FORMAT": "custom",
            "TLB_ASID_BITS": 16,
        })
        profile = build_profile(Config(values))
        self.assertTrue(profile["mmu"]["non_standard"])
        self.assertEqual(profile["mmu"]["geometry_mode"], "custom")
        self.assertEqual(profile["mmu"]["satp_mode"], 10)
        self.assertEqual(profile["mmu"]["page_bits"], 10)
        self.assertEqual(profile["mmu"]["table_entries"], 2048)
        self.assertEqual(profile["mmu"]["index_bits_per_level"], 11)
        self.assertEqual(profile["mmu"]["virtual_address_bits"], 43)
        self.assertFalse(profile["standard_sv39"])
        self.assertTrue(profile["warnings"])

    def test_rv64_custom_2k_page_4level_geometry(self):
        values = self.base()
        values.update({
            "XLEN": 64,
            "MMU_GEOMETRY_MODE": "custom",
            "MMU_CUSTOM_SATP_MODE": 11,
            "MMU_PAGE_SIZE_BYTES": 2048,
            "MMU_LEVELS": 4,
            "MMU_PTE_SIZE_BYTES": 8,
            "MMU_PAGE_TABLE_SIZE_BYTES": 4096,
            "MMU_PTE_FORMAT": "custom",
            "TLB_ASID_BITS": 16,
        })
        profile = build_profile(Config(values))
        self.assertEqual(profile["mmu"]["virtual_address_bits"], 47)
        self.assertEqual(profile["mmu"]["satp_mode"], 11)

    def test_rv64_custom_4byte_pte(self):
        values = self.base()
        values.update({
            "XLEN": 64,
            "MMU_GEOMETRY_MODE": "custom",
            "MMU_CUSTOM_SATP_MODE": 12,
            "MMU_PAGE_SIZE_BYTES": 1024,
            "MMU_LEVELS": 3,
            "MMU_PTE_SIZE_BYTES": 4,
            "MMU_PAGE_TABLE_SIZE_BYTES": 8192,
            "MMU_PTE_FORMAT": "custom",
            "TLB_ASID_BITS": 16,
        })
        profile = build_profile(Config(values))
        self.assertEqual(profile["mmu"]["pte_size_bytes"], 4)
        self.assertEqual(profile["mmu"]["virtual_address_bits"], 43)

    def test_rv64_custom_rejects_standard_satp_mode(self):
        values = self.base()
        values.update({
            "XLEN": 64,
            "MMU_GEOMETRY_MODE": "custom",
            "MMU_CUSTOM_SATP_MODE": 8,
            "MMU_PAGE_SIZE_BYTES": 1024,
            "MMU_LEVELS": 3,
            "MMU_PTE_SIZE_BYTES": 8,
            "MMU_PAGE_TABLE_SIZE_BYTES": 16384,
            "MMU_PTE_FORMAT": "custom",
        })
        with self.assertRaisesRegex(ValueError, "satp MODE"):
            build_profile(Config(values))

    def test_rv64_custom_rejects_unrepresentable_geometry(self):
        values = self.base()
        values.update({
            "XLEN": 64,
            "MMU_GEOMETRY_MODE": "custom",
            "MMU_CUSTOM_SATP_MODE": 10,
            "MMU_PAGE_SIZE_BYTES": 2 * 1024 * 1024,
            "MMU_LEVELS": 3,
            "MMU_PTE_SIZE_BYTES": 8,
            "MMU_PAGE_TABLE_SIZE_BYTES": 16384,
            "MMU_PTE_FORMAT": "custom",
        })
        with self.assertRaisesRegex(ValueError, "无法表达"):
            build_profile(Config(values))

    def test_custom_mode_is_rv64_only(self):
        values = self.base()
        values["MMU_GEOMETRY_MODE"] = "custom"
        with self.assertRaisesRegex(ValueError, "RV64"):
            build_profile(Config(values))

    def test_rv32_legacy_custom_geometry_still_works(self):
        values = self.base()
        values.update({
            "MMU_PAGE_SIZE_BYTES": 1024,
            "MMU_LEVELS": 2,
            "MMU_PAGE_TABLE_SIZE_BYTES": 8192,
        })
        profile = build_profile(Config(values))
        self.assertEqual(profile["mmu"]["satp_mode"], 1)
        self.assertEqual(profile["mmu"]["virtual_address_bits"], 32)
        self.assertFalse(profile["mmu"]["non_standard"])

    def test_nonstandard_page_size_is_described(self):
        values = self.base()
        values["MMU_PAGE_SIZE_BYTES"] = 8192
        profile = build_profile(Config(values))
        self.assertFalse(profile["standard_sv32"])
        self.assertFalse(profile["mmu"]["well_formed"])
        self.assertTrue(profile["warnings"])

    def test_vipt_alias_safe_is_visible(self):
        values = self.base()
        values["MMU_CACHE_MODE"] = "vipt"
        values["MMU_CACHE_VIPT_ALIAS_SAFE"] = True
        profile = build_profile(Config(values))
        self.assertEqual(profile["mmu"]["cache_mode"], "vipt")
        self.assertTrue(profile["mmu"]["cache_vipt_alias_safe"])
        self.assertEqual(profile["mmu"]["cache_vipt_alias_scan_mode"], "all_sets")

    def test_vipt_alias_scan_mode_is_visible(self):
        values = self.base()
        values["MMU_CACHE_MODE"] = "vipt"
        values["MMU_CACHE_VIPT_ALIAS_SAFE"] = True
        values["MMU_CACHE_VIPT_ALIAS_SCAN_MODE"] = "serial"
        profile = build_profile(Config(values))
        self.assertEqual(profile["mmu"]["cache_vipt_alias_scan_mode"], "serial")

    def test_invalid_vipt_alias_scan_mode(self):
        values = self.base()
        values["MMU_CACHE_VIPT_ALIAS_SCAN_MODE"] = "directory"
        with self.assertRaisesRegex(ValueError, "all_sets"):
            build_profile(Config(values))

    def test_pmp_is_visible(self):
        values = self.base()
        values["HAS_PMP"] = True
        profile = build_profile(Config(values))
        self.assertTrue(profile["pmp"]["enabled"])
        self.assertNotIn("HAS_PMP", profile["reserved"])

    def test_invalid_tlb_geometry(self):
        values = self.base()
        values["ITLB_ENTRIES"] = 16
        values["ITLB_WAYS"] = 3
        with self.assertRaises(ValueError):
            build_profile(Config(values))

    def test_unified_tlb(self):
        values = self.base()
        values["TLB_ORGANIZATION"] = "unified"
        values["UTLB_ENTRIES"] = 32
        values["UTLB_WAYS"] = 8
        profile = build_profile(Config(values))
        self.assertIsNone(profile["tlb"]["itlb"])
        self.assertIsNone(profile["tlb"]["dtlb"])
        self.assertEqual(profile["tlb"]["utlb"]["sets"], 4)

    def test_mmu_requires_s(self):
        values = self.base()
        values["HAS_S"] = False
        values["HAS_U"] = False
        with self.assertRaisesRegex(ValueError, "HAS_MMU=y"):
            build_profile(Config(values))

    def test_u_without_s_is_m_plus_u(self):
        values = self.base()
        values["HAS_MMU"] = False
        values["HAS_TLB"] = False
        values["HAS_S"] = False
        values["HAS_U"] = True
        profile = build_profile(Config(values))
        self.assertFalse(profile["enabled"]["s"])
        self.assertTrue(profile["enabled"]["u"])
        self.assertFalse(profile["enabled"]["mmu"])
        self.assertFalse(profile["enabled"]["tlb"])

    def test_mmu_requires_s_even_with_u(self):
        values = self.base()
        values["HAS_S"] = False
        values["HAS_U"] = True
        with self.assertRaisesRegex(ValueError, "HAS_MMU=y"):
            build_profile(Config(values))

    def test_m_only_without_mmu(self):
        values = self.base()
        values["HAS_MMU"] = False
        values["HAS_S"] = False
        values["HAS_U"] = False
        profile = build_profile(Config(values))
        self.assertFalse(profile["enabled"]["s"])
        self.assertFalse(profile["enabled"]["u"])
        self.assertFalse(profile["enabled"]["mmu"])
        self.assertFalse(profile["enabled"]["tlb"])


if __name__ == "__main__":
    unittest.main()
