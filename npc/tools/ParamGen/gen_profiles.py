#!/usr/bin/env python3
"""Materialize the support profiles from real defconfig outputs.

The policy in ``PROFILE_SPECS`` is hand-written, but the *base values* are
not: they are extracted from typed JSON generated from the named defconfig.
Run the existing ``c_conf`` / ``npc_config_gen`` tools in an isolated work
directory first (see ``classify_defconfigs.py``), then point this script at
the typed JSON tree.

    python3 npc/tools/ParamGen/gen_profiles.py \
        --typed-dir /tmp/opencode/paramgen-work/defconfigs

The resulting profile JSON files are committed as reviewable policy inputs.
Version 1 of the first three examples is kept under ``profiles/archive/``;
version 2 widens the support family without changing the hash rules.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import paramlib as p


# ---------------------------------------------------------------------------
# Reusable allow dimensions
# ---------------------------------------------------------------------------

FPU_BOOL = [
    "FPU_FMA",
    "FPU_HARDFLOAT_RECODE",
    "FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING",
    "FPU_IMPL_EXTERNAL",
    "FPU_IMPL_FUDIAN",
    "FPU_IMPL_HARDFLOAT",
    "FPU_IMPL_NONE",
]

FPU_RANGE = {
    "FPU_FLEN": {"min": 32, "max": 64, "step": 32},
    "FPU_PIPELINE": {"min": 0, "max": 4},
}

CACHE_RANGE = {
    "CACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "CACHE_INDEX_BITS": {"min": 5, "max": 6},
    "ICACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "ICACHE_INDEX_BITS": {"min": 5, "max": 6},
    "DCACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "DCACHE_INDEX_BITS": {"min": 5, "max": 6},
}

CACHE_BOOL = [
    "DCACHE",
    "ICACHE_STORAGE_REG",
    "ICACHE_STORAGE_SRAM",
    "ICACHE_STORAGE_SRAM_NOBYPASS",
    "DCACHE_STORAGE_REG",
    "DCACHE_STORAGE_SRAM",
    "DCACHE_STORAGE_SRAM_NOBYPASS",
]

# Bare-metal RV32 configs may turn the M extension off; every MDU backend and
# MUL/DIV implementation knob then disappears from the effective config.
MDU_BOOL = [
    "RISCV_M",
    "DIV_NONRESTORING",
    "DIV_RESTORING",
    "DIV_RESTORING_EARLY_OUT",
    "DIV_SRT",
    "MDU_IMPL_CV32E40P",
    "MDU_IMPL_CVE2",
    "MDU_IMPL_EXTERNAL",
    "MDU_IMPL_IBEX",
    "MDU_IMPL_NUTSHELL",
    "MDU_IMPL_PICORV32",
    "MDU_IMPL_ROCKET",
    "MDU_IMPL_STANDARD",
    "MUL_COMPRESSOR_ALLOW_REDUNDANT",
    "MUL_COMPRESSOR_HAS_CIN",
    "MUL_COMPRESSOR_HAS_COUT",
    "MUL_DADDA",
    "MUL_ENCODING_BOOTH",
    "MUL_ENCODING_PLAIN",
    "MUL_SHIFT_ADD",
    "MUL_WALLACE",
]

MDU_RANGE = {
    "DIV_RESTORING_ITER_BITS": {"min": 1, "max": 1},
    "DIV_RESTORING_RADIX": {"min": 2, "max": 2},
    "MUL_COMPRESSOR_INPUTS": {"min": 3, "max": 3},
    "MUL_COMPRESSOR_OUTPUTS": {"min": 2, "max": 2},
    "MUL_PIPELINE": {"min": 0, "max": 0},
    "MUL_WIDTH": {"min": 32, "max": 32},
}

TIMER_BOOL = [
    "TIMER_CLINT_MTIP",
    "TIMER_DIRECT_STIP",
]

# RV32 has no privilege-qualified CSR set requirement in the small profiles:
# the minimal RV32IM test core (rv32_noc) intentionally drops S/U and the
# optional machine CSRs.  The MMU/SBI/Linux profiles keep S/U fixed.
RV32_CSR_BOOL = [
    "HAS_S",
    "HAS_U",
    "HAS_MMU",
    "HAS_S_EXTERNAL_IRQ",
    "HAS_S_TIMER_IRQ",
    "HAS_CSR_MCOUNTEREN",
    "HAS_CSR_MCOUNTINHIBIT",
    "HAS_CSR_MINSTRET",
    "HAS_CSR_MSCRATCH",
    "HAS_CSR_MTVAL",
]

# PLIC parameters disappear when S-mode interrupts are disabled.  Pinning the
# ranges to the standard platform map lets the parameter vanish but does not
# permit an arbitrary memory-map change.
RV32_PLIC_RANGE = {
    "PLIC_BASE": {"min": 0x0C000000, "max": 0x0C000000},
    "PLIC_SIZE": {"min": 0x00300000, "max": 0x00300000},
    "PLIC_SOURCES": {"min": 32, "max": 32},
    "PLIC_PRIORITY_BITS": {"min": 3, "max": 3},
    "PLIC_MAX_PRIORITY": {"min": 7, "max": 7},
}


def _predicate_xlen(xlen: int) -> Dict[str, Any]:
    return {
        "id": f"xlen-{xlen}",
        "description": f"profile 只接受 RV{xlen}",
        "expr": {"eq": {"path": "isa.xlen", "value": xlen}},
    }


def _predicate_no_mmu() -> Dict[str, Any]:
    return {
        "id": "no-mmu",
        "description": "裸机 profile 不带 MMU",
        "expr": {"eq": {"path": "mmu.enabled", "value": False}},
    }


def _predicate_standard_mmu(
    pte_format: str,
    pte_size: int,
    levels: List[int],
) -> List[Dict[str, Any]]:
    return [
        {
            "id": "mmu",
            "description": "profile 要求启用 MMU",
            "expr": {"eq": {"path": "mmu.enabled", "value": True}},
        },
        {
            "id": "mmu-standard-geometry",
            "description": "只接受标准 MMU 几何；custom 几何属于 experimental",
            "expr": {"eq": {"path": "mmu.geometry_mode", "value": "standard"}},
        },
        {
            "id": "mmu-page-4k",
            "description": "标准 profile 固定 4 KiB 页",
            "expr": {"eq": {"path": "mmu.page_size_bytes", "value": 4096}},
        },
        {
            "id": "mmu-pte-format",
            "description": f"标准 profile 使用 {pte_format} PTE 格式",
            "expr": {"eq": {"path": "mmu.pte_format", "value": pte_format}},
        },
        {
            "id": "mmu-pte-size",
            "description": f"标准 profile 使用 {pte_size} 字节 PTE",
            "expr": {"eq": {"path": "mmu.pte_size_bytes", "value": pte_size}},
        },
        {
            "id": "mmu-levels",
            "description": "MMU 页表级数在标准 family 范围内",
            "expr": {
                "in": {"path": "mmu.levels", "values": levels}
            },
        },
    ]


def _predicate_no_sbi() -> Dict[str, Any]:
    return {
        "id": "no-sbi",
        "description": "裸机 profile 不启用 SBI",
        "expr": {"eq": {"path": "flat.SBI_ENABLE", "value": False}},
    }


def _predicate_no_linux() -> List[Dict[str, Any]]:
    return [
        {
            "id": "no-linux-boot",
            "description": "裸机 profile 不直接启动 Linux",
            "expr": {"eq": {"path": "flat.LINUX_BOOT_ENABLE", "value": False}},
        },
        {
            "id": "no-uboot",
            "description": "裸机 profile 不启用 U-Boot",
            "expr": {"eq": {"path": "flat.NPC_UBOOT_ENABLE", "value": False}},
        },
    ]


def _predicate_ysyx() -> Dict[str, Any]:
    return {
        "id": "ysyx-mode",
        "description": "当前 profile 只覆盖 ysyx 模式",
        "expr": {
            "not": {"eq": {"path": "flat.NPC_MODE_FPGA_ONLY", "value": True}}
        },
    }


def _predicate_ram(size: int) -> Dict[str, Any]:
    return {
        "id": f"ram-{size // (1024 * 1024)}m",
        "description": f"标准平台 RAM 为 {size // (1024 * 1024)} MiB",
        "expr": {"eq": {"path": "platform.ram_size", "value": size}},
    }


# Policy: base is always extracted from the named defconfig.  ``allow``
# contains only deviations that the profile deliberately supports.  Anything
# else is classified experimental, never rejected.
PROFILE_SPECS: List[Dict[str, Any]] = [
    {
        "id": "small-rv32",
        "version": 2,
        "status": "prototype",
        "description": (
            "RV32 小型裸机支持族：RV32IM 基线，无 MMU。允许 A/C/F/D 扩展、"
            "硬浮点后端、S/U 与可选 machine CSR、PLIC 中断的开关；"
            "CPU XLEN、无 MMU、64 MiB RAM 与 ysyx 模式是硬边界。"
        ),
        "defconfig": "npc/configs/rv32_c_defconfig",
        "typed": "rv32_c",
        "allow": {
            "bool": [
                "RISCV_A",
                "RISCV_C",
                "RISCV_F",
                "RISCV_D",
                *FPU_BOOL,
                *RV32_CSR_BOOL,
            ],
            "enum": {},
            "int_range": {
                **FPU_RANGE,
                **RV32_PLIC_RANGE,
            },
            "list": {},
        },
        "predicate": [
            _predicate_xlen(32),
            _predicate_no_mmu(),
            {
                "id": "soc-xlen-32",
                "description": "RV32 小型平台 SoC AXI 侧保持 32 位",
                "expr": {"eq": {"path": "isa.soc_xlen", "value": 32}},
            },
            _predicate_ram(64 * 1024 * 1024),
            _predicate_ysyx(),
        ],
        "qualification": {
            "suites": ["rv32-rvc", "rv32-fpu", "rv32-noc", "difftest-rv32"],
            "manifest": "npc/support/qualified/small-rv32@2.json",
            "hashes": [],
        },
    },
    {
        "id": "rv32-mmu",
        "version": 1,
        "status": "prototype",
        "description": (
            "RV32 Sv32 MMU 支持族：标准 2 级页表、4 KiB 页、PIPT cache。"
            "允许 M/A 扩展、cache 几何与 SRAM/reg 存储、timer 路径，以及"
            "M 扩展关闭时的 MDU 实现差异；custom MMU 几何、SBI 和 Linux 超出边界。"
        ),
        "defconfig": "npc/configs/nanos_defconfig",
        "typed": "nanos",
        "allow": {
            "bool": [
                "RISCV_A",
                *MDU_BOOL,
                *CACHE_BOOL,
                *TIMER_BOOL,
            ],
            "enum": {
                "MUL_COMPRESSOR_OUTPUT_OFFSETS": ["0,1"],
            },
            "int_range": {
                **CACHE_RANGE,
                **MDU_RANGE,
            },
            "list": {},
        },
        "predicate": [
            _predicate_xlen(32),
            *_predicate_standard_mmu("riscv32", 4, [2]),
            _predicate_ram(64 * 1024 * 1024),
            _predicate_no_sbi(),
            *_predicate_no_linux(),
            _predicate_ysyx(),
        ],
        "qualification": {
            "suites": ["rv32-mmu", "nanos-lite-rv32", "difftest-rv32"],
            "manifest": "npc/support/qualified/rv32-mmu@1.json",
            "hashes": [],
        },
    },
    {
        "id": "standard-rv64",
        "version": 2,
        "status": "prototype",
        "description": (
            "RV64 标准裸机支持族：RV64IMAC 基线，无 MMU。允许 F/D 扩展和"
            "硬浮点后端开关；MMU、SBI/Linux、SoC 自定义和外设变体超出边界。"
        ),
        "defconfig": "npc/configs/rv64_c_defconfig",
        "typed": "rv64_c",
        "allow": {
            "bool": ["RISCV_F", "RISCV_D", *FPU_BOOL],
            "enum": {},
            "int_range": {**FPU_RANGE},
            "list": {},
        },
        "predicate": [
            _predicate_xlen(64),
            _predicate_no_mmu(),
            {
                "id": "soc-xlen-64",
                "description": "SoC AXI 侧保持 64 位",
                "expr": {"eq": {"path": "isa.soc_xlen", "value": 64}},
            },
            _predicate_ram(64 * 1024 * 1024),
            _predicate_ysyx(),
        ],
        "qualification": {
            "suites": ["rv64-rvc", "rv64-fpu", "difftest-rv64"],
            "manifest": "npc/support/qualified/standard-rv64@2.json",
            "hashes": [],
        },
    },
    {
        "id": "rv64-mmu",
        "version": 1,
        "status": "prototype",
        "description": (
            "RV64 标准 MMU 裸机支持族：Sv39/Sv48、4 KiB 页、标准 PTE。"
            "允许 A/C/F/D 扩展、cache 几何与使能、trace/ringbuffer、timer"
            "路径和 3/4 级页表；custom MMU 几何、PTE、SBI 和 Linux 超出边界。"
        ),
        "defconfig": "npc/configs/rv64_mmu_defconfig",
        "typed": "rv64_mmu",
        "allow": {
            "bool": [
                "RISCV_A",
                "RISCV_C",
                "RISCV_F",
                "RISCV_D",
                *FPU_BOOL,
                *CACHE_BOOL,
                *TIMER_BOOL,
                "TRACE",
            ],
            "enum": {},
            "int_range": {
                **CACHE_RANGE,
                **FPU_RANGE,
                "MMU_LEVELS": {"min": 3, "max": 4},
                "IRINGBUF_SIZE": {"min": 0, "max": 64, "step": 16},
            },
            "list": {},
        },
        "predicate": [
            _predicate_xlen(64),
            *_predicate_standard_mmu("riscv64", 8, [3, 4]),
            _predicate_ram(64 * 1024 * 1024),
            _predicate_no_sbi(),
            *_predicate_no_linux(),
            _predicate_ysyx(),
        ],
        "qualification": {
            "suites": ["rv64-mmu", "xv6", "nanos-lite-rv64", "difftest-rv64"],
            "manifest": "npc/support/qualified/rv64-mmu@1.json",
            "hashes": [],
        },
    },
    {
        "id": "rv64-sbi",
        "version": 1,
        "status": "prototype",
        "description": (
            "RV64 + OpenSBI 支持族：标准 Sv39 MMU、PMP、OpenSBI 固件，"
            "不直接启动 Linux/U-Boot。允许 FDT 与 PMU 开关及标准地址范围内的"
            "FDT 地址调整；Linux 启动、custom MMU 和 U-Boot 超出边界。"
        ),
        "defconfig": "npc/configs/rv64_sbi_defconfig",
        "typed": "rv64_sbi",
        "allow": {
            "bool": [
                "SBI_FDT_ENABLE",
                "SBI_PMU_ENABLE",
                "NPC_UBOOT_ENABLE",
            ],
            "enum": {},
            "int_range": {
                "SBI_FDT_ADDR": {"min": 0x80000000, "max": 0x8FFFFFFF},
            },
            "list": {},
        },
        "predicate": [
            _predicate_xlen(64),
            *_predicate_standard_mmu("riscv64", 8, [3]),
            _predicate_ram(64 * 1024 * 1024),
            {
                "id": "sbi",
                "description": "OpenSBI 支持族要求 SBI 固件",
                "expr": {"eq": {"path": "flat.SBI_ENABLE", "value": True}},
            },
            *_predicate_no_linux(),
            _predicate_ysyx(),
        ],
        "qualification": {
            "suites": ["opensbi", "rustsbi", "sbi-fdt"],
            "manifest": "npc/support/qualified/rv64-sbi@1.json",
            "hashes": [],
        },
    },
    {
        "id": "linux-rv64",
        "version": 2,
        "status": "prototype",
        "description": (
            "RV64GC + Sv39/Sv48 + OpenSBI + Linux 支持族（64/128 MiB）。"
            "允许 U-Boot 启动、initramfs、启动参数和标准地址范围调整；"
            "custom MMU、非 SBI、非 Linux/U-Boot 配置超出边界。"
        ),
        "defconfig": "npc/configs/rv64_linux_defconfig",
        "typed": "rv64_linux",
        "allow": {
            "bool": [
                "LINUX_BOOT_ENABLE",
                "LINUX_INITRAMFS_ENABLE",
                "NPC_UBOOT_ENABLE",
            ],
            "enum": {
                "LINUX_BOOTARGS": [
                    "console=ttyS0,115200 earlycon=sbi",
                    "console=ttyS0,115200 earlycon=sbi rootwait",
                ],
                "NPC_UBOOT_BOOTARGS": [
                    "console=ttyS0,115200 earlycon=sbi no5lvl cpuidle.off=1 loglevel=7",
                ],
            },
            "int_range": {
                "NPC_RAM_SIZE": {
                    "min": 0x04000000,
                    "max": 0x08000000,
                    "step": 0x04000000,
                },
                "MMU_LEVELS": {"min": 3, "max": 5},
                "LINUX_INITRAMFS_ADDR": {
                    "min": 0x80000000,
                    "max": 0x8FFFFFFF,
                },
                "SBI_FDT_ADDR": {"min": 0x80000000, "max": 0x8FFFFFFF},
                "NPC_UBOOT_EFI_APP_ADDR": {
                    "min": 0x80000000,
                    "max": 0x8FFFFFFF,
                },
                "NPC_UBOOT_INITRAMFS_ADDR": {
                    "min": 0x80000000,
                    "max": 0x8FFFFFFF,
                },
                "NPC_UBOOT_KERNEL_ADDR": {
                    "min": 0x80000000,
                    "max": 0x8FFFFFFF,
                },
            },
            "list": {},
        },
        "predicate": [
            _predicate_xlen(64),
            *_predicate_standard_mmu("riscv64", 8, [3, 4, 5]),
            {
                "id": "fpu-fd",
                "description": "Linux 支持族要求 F/D",
                "expr": {
                    "all": [
                        {"eq": {"path": "isa.f", "value": True}},
                        {"eq": {"path": "isa.d", "value": True}},
                    ]
                },
            },
            {
                "id": "sbi",
                "description": "Linux 支持族需要 SBI 固件",
                "expr": {"eq": {"path": "flat.SBI_ENABLE", "value": True}},
            },
            {
                "id": "linux-or-uboot",
                "description": "直接 Linux 启动或 U-Boot 启动二选一",
                "expr": {
                    "any": [
                        {"eq": {"path": "flat.LINUX_BOOT_ENABLE", "value": True}},
                        {"eq": {"path": "flat.NPC_UBOOT_ENABLE", "value": True}},
                    ]
                },
            },
            {
                "id": "ram-at-least-64m",
                "description": "Linux 至少需要 64 MiB RAM",
                "expr": {
                    "gte": {"path": "platform.ram_size", "value": 64 * 1024 * 1024}
                },
            },
            _predicate_ysyx(),
        ],
        "qualification": {
            "suites": ["linux-boot", "opensbi", "nanos-lite", "uboot"],
            "manifest": "npc/support/qualified/linux-rv64@2.json",
            "hashes": [],
        },
    },
]


def build_profile(spec: Dict[str, Any], typed_dir: Path) -> Dict[str, Any]:
    typed_path = typed_dir / spec["typed"] / "typed.json"
    if not typed_path.exists():
        raise p.ParamError(
            f"typed JSON not found: {typed_path}\n"
            "Run classify_defconfigs.py first (it uses an isolated /tmp workdir)."
        )
    typed = p.read_json(typed_path)
    schema = p.load_schema()
    canonical = p.canonical_from_typed(typed, schema)
    flat = canonical["flat"]
    primary = p.schema_primary_names(schema)
    base_values = {key: flat[key] for key in sorted(flat) if key in primary}
    return {
        "id": spec["id"],
        "version": spec["version"],
        "status": spec["status"],
        "description": spec["description"],
        "base": {
            "source": spec["defconfig"],
            "source_effective_hash": p.effective_hash(canonical),
            "source_npc_config_hash": typed.get("config_hash"),
            "parameter_count": len(base_values),
            "values": base_values,
        },
        "allow": spec["allow"],
        "predicate": spec["predicate"],
        "qualification": spec["qualification"],
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--typed-dir",
        default="/tmp/opencode/paramgen-work/defconfigs",
        help="directory containing <name>/typed.json",
    )
    parser.add_argument(
        "--out-dir",
        default=str(p.DEFAULT_PROFILE_DIR),
        help="profile output directory",
    )
    args = parser.parse_args(argv)
    typed_dir = Path(args.typed_dir)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    for spec in PROFILE_SPECS:
        profile = build_profile(spec, typed_dir)
        p.validate_profile(profile)
        out_path = out_dir / f"{spec['id']}@{spec['version']}.json"
        p.write_json(out_path, profile)
        print(
            f"[param-profile] wrote {out_path} "
            f"({profile['base']['parameter_count']} base parameters, "
            f"hash {profile['base']['source_effective_hash'][:16]}...)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
