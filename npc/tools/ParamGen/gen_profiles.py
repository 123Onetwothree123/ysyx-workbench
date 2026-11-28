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
The policy covers the four current presets only: RV32I, RV32IMAC Linux,
RV32GC Linux and RV64GC Linux.  RV32 Linux profiles carry a
``status_ceiling``/``evidence_scope`` so a passing L2 elaboration is not
reported as end-to-end Linux support.
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
# Predicate helpers
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# New four-preset policy (replaces the deleted six-family table)
# ---------------------------------------------------------------------------

# Cache geometry deviations that the presets deliberately support.  The
# unified geometry is kept; only block/index size may be tuned.
CACHE_BOOL_COMMON = [
    "DCACHE_STORAGE_REG",
    "DCACHE_STORAGE_SRAM",
    "DCACHE_STORAGE_SRAM_NOBYPASS",
    "ICACHE_STORAGE_REG",
    "ICACHE_STORAGE_SRAM",
    "ICACHE_STORAGE_SRAM_NOBYPASS",
]

CACHE_RANGE_RV32I = {
    "CACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "CACHE_INDEX_BITS": {"min": 4, "max": 5},
    "ICACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "ICACHE_INDEX_BITS": {"min": 4, "max": 5},
    "DCACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "DCACHE_INDEX_BITS": {"min": 4, "max": 5},
}

CACHE_RANGE_LINUX = {
    "CACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "CACHE_INDEX_BITS": {"min": 4, "max": 6},
    "ICACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "ICACHE_INDEX_BITS": {"min": 4, "max": 6},
    "DCACHE_BLOCK_SIZE_LOG2": {"min": 4, "max": 5},
    "DCACHE_INDEX_BITS": {"min": 4, "max": 6},
}

# RV32D 的 FLD/FSD 是 8 字节访问；开启 DCache 时行大小至少 8 B。
CACHE_RANGE_LINUX_RV32GC = dict(CACHE_RANGE_LINUX)
CACHE_RANGE_LINUX_RV32GC["CACHE_BLOCK_SIZE_LOG2"] = {"min": 3, "max": 5}
CACHE_RANGE_LINUX_RV32GC["ICACHE_BLOCK_SIZE_LOG2"] = {"min": 3, "max": 5}
CACHE_RANGE_LINUX_RV32GC["DCACHE_BLOCK_SIZE_LOG2"] = {"min": 3, "max": 5}

# FPU backend deviations: HardFloat is the base; Fudian is a supported
# alternative.  External/none are rejected by the Kconfig contract.
FPU_ALLOW_BOOL = [
    "FPU_IMPL_HARDFLOAT",
    "FPU_IMPL_FUDIAN",
    "FPU_HARDFLOAT_RECODE",
    "FPU_HARDFLOAT_TININESS_BEFORE_ROUNDING",
    "FPU_FUDIAN_SHARED_DIVSQRT",
]
FPU_ALLOW_RANGE = {
    "FPU_FLEN": {"min": 64, "max": 64, "step": 32},
    "FPU_PIPELINE": {"min": 0, "max": 4},
}

LINUX_BOOTARGS = [
    "console=ttyS0,115200 earlycon=sbi",
    "console=ttyS0,115200 earlycon=sbi rootwait",
]


def _predicate_isa(letter: str, expected: bool) -> Dict[str, Any]:
    return {
        "id": f"isa-{letter}-{'yes' if expected else 'no'}",
        "description": f"profile 要求 ISA.{letter.upper()}={'y' if expected else 'n'}",
        "expr": {"eq": {"path": f"isa.{letter}", "value": expected}},
    }


def _predicate_flat(symbol: str, expected: Any, label: str) -> Dict[str, Any]:
    return {
        "id": f"flat-{symbol.lower().replace('_', '-')}-{label}",
        "description": f"profile 要求 CONFIG_{symbol}={expected}",
        "expr": {"eq": {"path": f"flat.{symbol}", "value": expected}},
    }


def _predicate_soc_xlen(xlen: int) -> Dict[str, Any]:
    return {
        "id": f"soc-xlen-{xlen}",
        "description": f"SoC AXI 侧保持 {xlen} 位",
        "expr": {"eq": {"path": "isa.soc_xlen", "value": xlen}},
    }


def _predicate_ram_at_least(size: int) -> Dict[str, Any]:
    return {
        "id": f"ram-at-least-{size // (1024 * 1024)}m",
        "description": f"至少需要 {size // (1024 * 1024)} MiB RAM",
        "expr": {"gte": {"path": "platform.ram_size", "value": size}},
    }


def _linux_predicates(
    xlen: int,
    pte_format: str,
    pte_size: int,
    levels: List[int],
    *,
    fd: bool = True,
) -> List[Dict[str, Any]]:
    return [
        _predicate_xlen(xlen),
        *_predicate_standard_mmu(pte_format, pte_size, levels),
        _predicate_isa("s", True),
        _predicate_isa("u", True),
        _predicate_isa("f", fd),
        _predicate_isa("d", fd),
        _predicate_flat("SBI_ENABLE", True, "yes"),
        _predicate_flat("SBI_IMPL_OPENSBI", True, "yes"),
        _predicate_flat("SBI_FDT_ENABLE", True, "yes"),
        _predicate_flat("LINUX_BOOT_ENABLE", True, "yes"),
        _predicate_flat("NPC_UBOOT_ENABLE", False, "no"),
        _predicate_flat("HAS_PMP", True, "yes"),
        _predicate_soc_xlen(xlen),
        _predicate_ram_at_least(64 * 1024 * 1024),
        _predicate_ysyx(),
    ]


def _linux_allow(
    xlen: int,
    *,
    cache_range: Dict[str, Any],
    levels_range: Optional[Dict[str, int]] = None,
    extra_bool: Optional[List[str]] = None,
    extra_range: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    int_range: Dict[str, Any] = {
        **cache_range,
        "LINUX_INITRAMFS_ADDR": {"min": 0x82000000, "max": 0x82000000},
        "NPC_RAM_SIZE": {
            "min": 64 * 1024 * 1024,
            "max": 128 * 1024 * 1024,
            "step": 64 * 1024 * 1024,
        },
        "SBI_BUILD_JOBS": {"min": 0, "max": 64},
        "SOC_XLEN": {"min": xlen, "max": xlen},
    }
    if levels_range:
        int_range["MMU_LEVELS"] = levels_range
    if extra_range:
        int_range.update(extra_range)
    bool_allow = [
        *CACHE_BOOL_COMMON,
        "DCACHE",
        "LINUX_INITRAMFS_ENABLE",
        "SOC_XLEN_FOLLOW_CPU",
    ]
    if extra_bool:
        bool_allow.extend(extra_bool)
    return {
        "bool": sorted(set(bool_allow)),
        "enum": {"LINUX_BOOTARGS": list(LINUX_BOOTARGS)},
        "int_range": int_range,
        "list": {},
    }


# Policy: base is always extracted from the named defconfig.  ``allow``
# contains only deviations that the profile deliberately supports.  Anything
# else is classified experimental, never rejected.
PROFILE_SPECS: List[Dict[str, Any]] = [
    {
        "id": "rv32i",
        "version": 1,
        "status": "prototype",
        "description": (
            "RV32I 最小 M 模式支持族：RV32I（M/A/C/F/D=n）、仅 M 特权级、"
            "无 MMU/TLB，不启用 SBI/Linux/U-Boot。保留 B1 的 M 模式 CSR、"
            "mcycle 和 ecall/mret；ICache 打开，DCache 关闭。PMP 可选；"
            "缓存几何和 debug 开关是允许的差异，ISA/特权级/MMU 是硬边界。"
        ),
        "defconfig": "npc/configs/rv32i_defconfig",
        "typed": "rv32i",
        "allow": {
            "bool": sorted(
                set(
                    [
                        "CACHE_CONFIG_SEPARATE",
                        "CACHE_CONFIG_UNIFIED",
                        "CACHE_ENABLE",
                        "ICACHE",
                        "DCACHE",
                        *CACHE_BOOL_COMMON,
                        "HAS_PMP",
                        "SOC_XLEN_FOLLOW_CPU",
                    ]
                )
            ),
            "enum": {},
            "int_range": {
                **CACHE_RANGE_RV32I,
                "PMP_ENTRIES": {"min": 16, "max": 16},
                "PMP_GRANULARITY_LOG2": {"min": 6, "max": 6},
                "SOC_XLEN": {"min": 32, "max": 32},
            },
            "list": {},
        },
        "predicate": [
            _predicate_xlen(32),
            _predicate_isa("m", False),
            _predicate_isa("a", False),
            _predicate_isa("c", False),
            _predicate_isa("f", False),
            _predicate_isa("d", False),
            _predicate_isa("s", False),
            _predicate_isa("u", False),
            _predicate_no_mmu(),
            _predicate_flat("SBI_ENABLE", False, "no"),
            _predicate_flat("LINUX_BOOT_ENABLE", False, "no"),
            _predicate_flat("NPC_UBOOT_ENABLE", False, "no"),
            _predicate_soc_xlen(32),
            _predicate_ram(64 * 1024 * 1024),
            _predicate_ysyx(),
        ],
        "qualification": {
            "suites": ["rv32i-baremetal", "b1-csr", "b3-icache"],
            "manifest": "npc/support/qualified/rv32i@1.json",
            "hashes": [],
        },
    },
    {
        "id": "rv32imac-linux",
        "version": 1,
        "status": "experimental",
        "description": (
            "RV32IMAC + Sv32 + OpenSBI + Linux 实验支持族：M/A/C=y、F/D=n、"
            "M/S/U、标准 Sv32（2 级、4 KiB 页、4 B PTE）、PMP 16 项、"
            "OpenSBI + FDT、直接 Linux 启动。SBI_NEXT_ADDR 固定 0x80400000"
            "（RV32 4 MiB 对齐）。DCache、initramfs 开关、64/128 MiB RAM "
            "是有意支持的差异；RV32 Linux 尚无启动证据。"
        ),
        "defconfig": "npc/configs/rv32imac_linux_defconfig",
        "typed": "rv32imac_linux",
        "allow": _linux_allow(32, cache_range=CACHE_RANGE_LINUX),
        "predicate": _linux_predicates(32, "riscv32", 4, [2], fd=False),
        "qualification": {
            "suites": ["linux-rv32", "opensbi-rv32", "sv32"],
            "manifest": "npc/support/qualified/rv32imac-linux@1.json",
            "hashes": [],
            "status_ceiling": "profile-compatible",
            "evidence_scope": "elaboration-only",
        },
    },
    {
        "id": "rv32gc-linux",
        "version": 1,
        "status": "experimental",
        "description": (
            "RV32GC + Sv32 + OpenSBI + Linux 实验支持族：RV32IMAFDC、"
            "HardFloat/Fudian、FLEN=64、M/S/U、标准 Sv32、PMP 16 项、"
            "OpenSBI + FDT、直接 Linux 启动。SBI_NEXT_ADDR 固定 0x80400000。"
            "DCache 行大小至少 8 B（默认 16 B），initramfs 与 64/128 MiB "
            "RAM 可选；RV32 Linux 尚无启动证据。"
        ),
        "defconfig": "npc/configs/rv32gc_linux_defconfig",
        "typed": "rv32gc_linux",
        "allow": _linux_allow(
            32,
            cache_range=CACHE_RANGE_LINUX_RV32GC,
            extra_bool=list(FPU_ALLOW_BOOL),
            extra_range=dict(FPU_ALLOW_RANGE),
        ),
        "predicate": _linux_predicates(32, "riscv32", 4, [2]),
        "qualification": {
            "suites": ["linux-rv32", "opensbi-rv32", "sv32", "rv32d"],
            "manifest": "npc/support/qualified/rv32gc-linux@1.json",
            "hashes": [],
            "status_ceiling": "profile-compatible",
            "evidence_scope": "elaboration-only",
        },
    },
    {
        "id": "rv64gc-linux",
        "version": 1,
        "status": "prototype",
        "description": (
            "RV64GC + Sv39/Sv48 + OpenSBI + Linux 支持族：RV64IMAFDC、"
            "HardFloat/Fudian、FLEN=64、M/S/U、标准 Sv39（默认）或 Sv48、"
            "PMP 16 项、OpenSBI + FDT、直接 Linux 启动。本族不走 U-Boot "
            "EFI/128 MiB Debian 路径；MMU_LEVELS、DCache、PMU、initramfs "
            "和 64/128 MiB RAM 是有意支持的差异。"
        ),
        "defconfig": "npc/configs/rv64gc_linux_defconfig",
        "typed": "rv64gc_linux",
        "allow": _linux_allow(
            64,
            cache_range=CACHE_RANGE_LINUX,
            levels_range={"min": 3, "max": 4},
            extra_bool=[*FPU_ALLOW_BOOL, "SBI_PMU_ENABLE"],
            extra_range=dict(FPU_ALLOW_RANGE),
        ),
        "predicate": _linux_predicates(64, "riscv64", 8, [3, 4]),
        "qualification": {
            "suites": ["linux-boot", "opensbi", "sv39", "nanos-lite"],
            "manifest": "npc/support/qualified/rv64gc-linux@1.json",
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
