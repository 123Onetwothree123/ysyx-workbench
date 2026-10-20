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

    def test_nonstandard_page_size_is_described(self):
        values = self.base()
        values["MMU_PAGE_SIZE_BYTES"] = 8192
        profile = build_profile(Config(values))
        self.assertFalse(profile["standard_sv32"])
        self.assertFalse(profile["mmu"]["well_formed"])
        self.assertTrue(profile["warnings"])

    def test_reserved_pmp_is_visible(self):
        values = self.base()
        values["HAS_PMP"] = True
        profile = build_profile(Config(values))
        self.assertIn("HAS_PMP", profile["reserved"])

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


if __name__ == "__main__":
    unittest.main()
