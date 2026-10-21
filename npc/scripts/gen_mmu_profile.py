#!/usr/bin/env python3
"""从 NPC 的 .config 生成 MMU/TLB profile。

.config 是唯一真源。这个脚本只读取配置，不修改 RTL；它生成：
  * mmu_profile.json：供测试、软件生成器和后续 RTL elaboration 读取
  * mmu_profile.h：供最小 MMU 测试运行时/裸机软件读取
  * mmu_profile.env：供 Makefile/Scala elaboration 导出的键值对

默认 profile 是标准 Sv32；非标准参数允许输入，但会进行明确的几何校验。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, Optional

SCHEMA_VERSION = 1
STANDARD_PAGE = 4096
STANDARD_LEVELS = 2
STANDARD_PTE = 4
STANDARD_TABLE = 4096


def parse_config(path: Path) -> Dict[str, object]:
    values: Dict[str, object] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"CONFIG_([A-Za-z0-9_]+)=(.*)$", line)
        if not m:
            continue
        key, text = m.group(1), m.group(2).strip()
        if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
            values[key] = text[1:-1]
        elif text == "y":
            values[key] = True
        elif text == "n":
            values[key] = False
        else:
            try:
                values[key] = int(text, 0)
            except ValueError:
                values[key] = text
    return values


class Config:
    def __init__(self, values: Dict[str, object]):
        self.values = values

    def bool(self, key: str, default: bool = False) -> bool:
        value = self.values.get(key, default)
        return bool(value)

    def string(self, key: str, default: str = "") -> str:
        value = self.values.get(key, default)
        return str(value) if value is not None else default

    def integer(self, key: str, default: int = 0) -> int:
        value = self.values.get(key, default)
        if isinstance(value, int):
            return value
        try:
            return int(str(value), 0)
        except (TypeError, ValueError):
            raise ValueError(f"{key} 必须是整数，实际为 {value!r}")


def is_power_of_two(value: int) -> bool:
    return value > 0 and (value & (value - 1)) == 0


def log2(value: int) -> int:
    if not is_power_of_two(value):
        raise ValueError(f"{value} 不是 2 的幂")
    return value.bit_length() - 1


def require_power_of_two(name: str, value: int) -> int:
    if not is_power_of_two(value):
        raise ValueError(f"{name}={value} 必须是正的 2 的幂")
    return log2(value)


def one_of(name: str, value: str, choices) -> str:
    if value not in choices:
        raise ValueError(f"{name}={value!r} 必须是 {', '.join(choices)} 之一")
    return value


def field_modes(cfg: Config, fields):
    result = {}
    default_read = one_of(
        "CSR_FIELD_DEFAULT_READ",
        cfg.string("CSR_FIELD_DEFAULT_READ", "normal"),
        ("normal", "zero", "one"),
    )
    default_write = one_of(
        "CSR_FIELD_DEFAULT_WRITE",
        cfg.string("CSR_FIELD_DEFAULT_WRITE", "normal"),
        ("normal", "ignore"),
    )
    for field in fields:
        prefix = f"CSR_{field.upper()}_"
        result[field] = {
            "read": one_of(
                prefix + "READ",
                cfg.string(prefix + "READ", default_read),
                ("normal", "zero", "one"),
            ),
            "write": one_of(
                prefix + "WRITE",
                cfg.string(prefix + "WRITE", default_write),
                ("normal", "ignore"),
            ),
        }
    return result


def build_profile(cfg: Config) -> dict:
    page_size = cfg.integer("MMU_PAGE_SIZE_BYTES", STANDARD_PAGE)
    levels = cfg.integer("MMU_LEVELS", STANDARD_LEVELS)
    pte_size = cfg.integer("MMU_PTE_SIZE_BYTES", STANDARD_PTE)
    table_size = cfg.integer("MMU_PAGE_TABLE_SIZE_BYTES", STANDARD_TABLE)
    pte_format = one_of(
        "MMU_PTE_FORMAT", cfg.string("MMU_PTE_FORMAT", "riscv32"), ("riscv32", "custom")
    )
    ad_mode = one_of(
        "MMU_AD_MODE", cfg.string("MMU_AD_MODE", "fault"), ("fault", "hardware", "ignore")
    )
    cache_mode = one_of(
        "MMU_CACHE_MODE", cfg.string("MMU_CACHE_MODE", "pipt"), ("pipt", "vipt")
    )
    ptw_path = one_of(
        "MMU_PTW_PATH", cfg.string("MMU_PTW_PATH", "dedicated"),
        ("dedicated", "reuse_dcache"),
    )
    ptw_arch = one_of(
        "MMU_PTW_ARCH", cfg.string("MMU_PTW_ARCH", "independent"),
        ("independent", "pipelined"),
    )
    ptw_order = one_of(
        "MMU_PTW_ORDER", cfg.string("MMU_PTW_ORDER", "in_order"),
        ("in_order", "out_of_order"),
    )
    miss_queue_mode = one_of(
        "TLB_MISS_QUEUE_MODE", cfg.string("TLB_MISS_QUEUE_MODE", "follow_tlb"),
        ("follow_tlb", "global"),
    )
    tlb_storage = one_of(
        "TLB_STORAGE", cfg.string("TLB_STORAGE", "reg"), ("reg", "sram")
    )
    tlb_replacement = one_of(
        "TLB_REPLACEMENT", cfg.string("TLB_REPLACEMENT", "rr"),
        ("rr", "plru", "lru"),
    )
    csr_missing_mode = one_of(
        "CSR_MISSING_MODE", cfg.string("CSR_MISSING_MODE", "illegal"),
        ("illegal", "zero"),
    )
    csr_view_mode = one_of(
        "CSR_VIEW_MODE", cfg.string("CSR_VIEW_MODE", "bound"),
        ("bound", "independent"),
    )

    page_bits = require_power_of_two("MMU_PAGE_SIZE_BYTES", page_size)
    require_power_of_two("MMU_PTE_SIZE_BYTES", pte_size)
    require_power_of_two("MMU_PAGE_TABLE_SIZE_BYTES", table_size)

    if levels < 1:
        raise ValueError(f"MMU_LEVELS={levels} 必须 >= 1")
    if table_size % pte_size != 0:
        raise ValueError(
            f"MMU_PAGE_TABLE_SIZE_BYTES={table_size} 必须能被 "
            f"MMU_PTE_SIZE_BYTES={pte_size} 整除"
        )

    table_entries = table_size // pte_size
    index_bits = require_power_of_two("每级页表项数", table_entries)
    address_bits = page_bits + levels * index_bits
    well_formed = address_bits == 32
    standard = (
        page_size == STANDARD_PAGE
        and levels == STANDARD_LEVELS
        and pte_size == STANDARD_PTE
        and table_size == STANDARD_TABLE
        and pte_format == "riscv32"
    )

    tlb_org = cfg.string("TLB_ORGANIZATION", "split")
    if tlb_org not in ("split", "unified"):
        raise ValueError(f"TLB_ORGANIZATION={tlb_org!r} 必须是 split/unified")

    tlb_entries = cfg.integer(
        "UTLB_ENTRIES" if tlb_org == "unified" else "ITLB_ENTRIES",
        32 if tlb_org == "unified" else 16,
    )
    tlb_ways = cfg.integer(
        "UTLB_WAYS" if tlb_org == "unified" else "ITLB_WAYS",
        4 if tlb_org == "unified" else 2,
    )
    require_power_of_two("TLB entries", tlb_entries)
    if tlb_ways < 1:
        raise ValueError("TLB ways 必须 >= 1")
    if tlb_entries % tlb_ways != 0:
        raise ValueError(f"TLB entries={tlb_entries} 必须能被 ways={tlb_ways} 整除")

    def tlb_side(prefix: str, default_entries: int, default_ways: int):
        if tlb_org == "unified":
            return None
        entries = cfg.integer(prefix + "_ENTRIES", default_entries)
        ways = cfg.integer(prefix + "_WAYS", default_ways)
        require_power_of_two(prefix + " entries", entries)
        if ways < 1:
            raise ValueError(f"{prefix} ways 必须 >= 1")
        if entries % ways != 0:
            raise ValueError(f"{prefix} entries={entries} 必须能被 ways={ways} 整除")
        return {
            "entries": entries,
            "ways": ways,
            "sets": entries // ways,
            "index_bits": log2(entries // ways),
        }

    ptw_cache_entries = cfg.integer("MMU_PTW_CACHE_ENTRIES", 16)
    ptw_cache_ways = cfg.integer("MMU_PTW_CACHE_WAYS", 2)
    if cfg.bool("MMU_PTW_CACHE"):
        require_power_of_two("MMU_PTW_CACHE_ENTRIES", ptw_cache_entries)
        if ptw_cache_ways < 1:
            raise ValueError("MMU_PTW_CACHE_WAYS 必须 >= 1")
        if ptw_cache_entries % ptw_cache_ways != 0:
            raise ValueError("MMU_PTW_CACHE_ENTRIES 必须能被 MMU_PTW_CACHE_WAYS 整除")

    itlb = tlb_side("ITLB", 16, 2)
    dtlb = tlb_side("DTLB", 32, 4)
    utlb = None
    if tlb_org == "unified":
        utlb = {
            "entries": tlb_entries,
            "ways": tlb_ways,
            "sets": tlb_entries // tlb_ways,
            "index_bits": log2(tlb_entries // tlb_ways),
        }

    mstatus_fields = [
        "mie", "mpie", "mpp", "sie", "spie", "spp",
        "mprv", "sum", "mxr", "tvm", "tw", "tsr",
    ]
    satp_fields = ["mode", "asid", "ppn"]

    csr = {
        "missing_mode": csr_missing_mode,
        "view_mode": csr_view_mode,
        "field_default_read": cfg.string("CSR_FIELD_DEFAULT_READ", "normal"),
        "field_default_write": cfg.string("CSR_FIELD_DEFAULT_WRITE", "normal"),
        "fields": {
            "mstatus": field_modes(cfg, mstatus_fields),
            "satp": field_modes(cfg, satp_fields),
        },
        "enabled": {
            name: cfg.bool("HAS_CSR_" + name.upper(), False)
            for name in [
                "mstatus", "misa", "mtvec", "mepc", "mcause", "mtval",
                "mscratch", "mie", "mip", "medeleg", "mideleg",
                "mcounteren", "mcountinhibit", "mcycle", "minstret",
                "sstatus", "sie", "sip", "stvec", "sscratch", "sepc",
                "scause", "stval", "satp", "scounteren",
            ]
        },
    }

    # MMU 的标准 Sv32 路径依赖 S 模式 CSR；这些 CSR 不能因为旧 .config
    # 没有显式列出而被关掉，否则 satp/sfence 会在译码阶段变成非法指令。
    if cfg.bool("HAS_MMU") or cfg.bool("HAS_S"):
        for name in ("mstatus", "mtvec", "mepc", "mcause", "mie", "mip",
                     "medeleg", "mideleg", "sstatus", "sie", "sip", "stvec",
                     "sscratch", "sepc", "scause", "stval", "satp",
                     "scounteren"):
            csr["enabled"][name] = True

    reserved = []
    if cfg.bool("HAS_PMP", False):
        reserved.append("HAS_PMP")

    timer_path = one_of(
        "TIMER_PATH", cfg.string("TIMER_PATH", "clint_mtip"),
        ("clint_mtip", "direct_stip"),
    )

    warnings = []
    if not well_formed:
        warnings.append(
            f"地址位宽=page_bits({page_bits}) + levels({levels})*index_bits({index_bits}) "
            f"= {address_bits}，不等于 32；该 profile 需要软件/RTL 额外定义高位处理"
        )
    if cfg.bool("HAS_TLB") and not cfg.bool("HAS_MMU"):
        warnings.append("HAS_TLB=y 但 HAS_MMU=n；生成器按有效 TLB=false 处理")
    if cfg.bool("HAS_MMU") and not (cfg.bool("HAS_S") or cfg.bool("HAS_U")):
        warnings.append("HAS_MMU=y 但 HAS_S/HAS_U 都为 n；标准 Sv32 需要 S/U")

    return {
        "schema_version": SCHEMA_VERSION,
        "generated_from": ".config",
        "profile": cfg.string("MMU_PROFILE", "custom"),
        "standard_sv32": standard and well_formed,
        "enabled": {
            "s": cfg.bool("HAS_S"),
            "u": cfg.bool("HAS_U"),
            "mmu": cfg.bool("HAS_MMU"),
            "tlb": cfg.bool("HAS_TLB") and cfg.bool("HAS_MMU"),
        },
        "mmu": {
            "page_size_bytes": page_size,
            "page_bits": page_bits,
            "levels": levels,
            "pte_size_bytes": pte_size,
            "page_table_size_bytes": table_size,
            "table_entries": table_entries,
            "index_bits_per_level": index_bits,
            "address_bits": 32,
            "computed_address_bits": address_bits,
            "well_formed": well_formed,
            "pte_format": pte_format,
            "superpage": cfg.bool("MMU_SUPERPAGE"),
            "ad_mode": ad_mode,
            "cache_mode": cache_mode,
            "ptw": {
                "path": ptw_path,
                "arch": ptw_arch,
                "outstanding": cfg.integer("MMU_PTW_OUTSTANDING", 2),
                "order": ptw_order,
                "cache": {
                    "enabled": cfg.bool("MMU_PTW_CACHE"),
                    "entries": cfg.integer("MMU_PTW_CACHE_ENTRIES", 16),
                    "ways": cfg.integer("MMU_PTW_CACHE_WAYS", 2),
                },
            },
        },
        "tlb": {
            "organization": tlb_org,
            "storage": tlb_storage,
            "replacement": tlb_replacement,
            "asid_bits": cfg.integer("TLB_ASID_BITS", 9),
            "global": cfg.bool("TLB_GLOBAL"),
            "itlb": itlb,
            "dtlb": dtlb,
            "utlb": utlb,
            "superpage": cfg.bool("TLB_SUPERPAGE"),
            "miss_queue": {
                "mode": miss_queue_mode,
                "itlb_entries": cfg.integer("ITLB_MISS_QUEUE_ENTRIES", 4),
                "dtlb_entries": cfg.integer("DTLB_MISS_QUEUE_ENTRIES", 4),
                "utlb_entries": cfg.integer("UTLB_MISS_QUEUE_ENTRIES", 8),
                "global_entries": cfg.integer("GLOBAL_MISS_QUEUE_ENTRIES", 8),
            },
            "refill_ports": cfg.integer("TLB_REFILL_PORTS", 1),
        },
        "csr": csr,
        "interrupts": {
            "s_timer": cfg.bool("HAS_S_TIMER_IRQ"),
            "s_external": cfg.bool("HAS_S_EXTERNAL_IRQ"),
            "timer_path": timer_path,
            "plic": {
                "sources": cfg.integer("PLIC_SOURCES", 32),
                "priority_bits": cfg.integer("PLIC_PRIORITY_BITS", 3),
                "max_priority": cfg.integer("PLIC_MAX_PRIORITY", 7),
                "base": cfg.integer("PLIC_BASE", 0x0C000000),
            },
        },
        "pmp": {
            "enabled": cfg.bool("HAS_PMP"),
            "entries": cfg.integer("PMP_ENTRIES", 16),
        },
        "reserved": reserved,
        "warnings": warnings,
    }


HEADER_TEMPLATE = """/* 由 scripts/gen_mmu_profile.py 从 .config 生成，请勿手工修改。 */
#ifndef YSYX_MMU_PROFILE_H
#define YSYX_MMU_PROFILE_H

#define YSYX_MMU_PROFILE_SCHEMA {schema}
#define YSYX_MMU_PROFILE_NAME "{profile}"
#define YSYX_MMU_ENABLED {mmu}
#define YSYX_MMU_STANDARD_SV32 {standard}
#define YSYX_MMU_PAGE_SIZE_BYTES {page_size}u
#define YSYX_MMU_PAGE_BITS {page_bits}u
#define YSYX_MMU_LEVELS {levels}u
#define YSYX_MMU_PTE_SIZE_BYTES {pte_size}u
#define YSYX_MMU_PAGE_TABLE_SIZE_BYTES {table_size}u
#define YSYX_MMU_TABLE_ENTRIES {table_entries}u
#define YSYX_MMU_INDEX_BITS {index_bits}u
#define YSYX_MMU_ASID_BITS {asid_bits}u
#define YSYX_MMU_TLB_ORGANIZATION "{tlb_org}"
#define YSYX_MMU_TLB_REPLACEMENT "{replacement}"
#define YSYX_MMU_CACHE_MODE "{cache_mode}"
#define YSYX_MMU_PTW_PATH "{ptw_path}"
#define YSYX_MMU_PTW_OUTSTANDING {ptw_outstanding}u
#define YSYX_MMU_AD_MODE "{ad_mode}"
#define YSYX_MMU_HAS_S {has_s}
#define YSYX_MMU_HAS_U {has_u}
#define YSYX_MMU_HAS_TLB {has_tlb}
#define YSYX_MMU_HAS_S_TIMER {has_s_timer}
#define YSYX_MMU_HAS_S_EXTERNAL {has_s_external}

#endif
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(".config"))
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--header", type=Path, required=True)
    parser.add_argument("--env", type=Path)
    parser.add_argument("--allow-reserved", action="store_true",
                        help="只生成 profile，即使选择了 RESERVED 项也不报错")
    args = parser.parse_args()

    if not args.config.exists():
        raise SystemExit(f"配置文件不存在: {args.config}")
    profile = build_profile(Config(parse_config(args.config)))

    if profile["reserved"] and not args.allow_reserved:
        raise SystemExit(
            "选择了未实现的 RESERVED 功能: " + ", ".join(profile["reserved"])
            + "；当前 config-only 阶段请关闭它们，或使用 --allow-reserved 仅生成描述。"
        )

    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    args.header.parent.mkdir(parents=True, exist_ok=True)
    args.header.write_text(HEADER_TEMPLATE.format(
        schema=profile["schema_version"],
        profile=profile["profile"],
        mmu=int(profile["enabled"]["mmu"]),
        standard=int(profile["standard_sv32"]),
        page_size=profile["mmu"]["page_size_bytes"],
        page_bits=profile["mmu"]["page_bits"],
        levels=profile["mmu"]["levels"],
        pte_size=profile["mmu"]["pte_size_bytes"],
        table_size=profile["mmu"]["page_table_size_bytes"],
        table_entries=profile["mmu"]["table_entries"],
        index_bits=profile["mmu"]["index_bits_per_level"],
        asid_bits=profile["tlb"]["asid_bits"],
        tlb_org=profile["tlb"]["organization"],
        replacement=profile["tlb"]["replacement"],
        cache_mode=profile["mmu"]["cache_mode"],
        ptw_path=profile["mmu"]["ptw"]["path"],
        ptw_outstanding=profile["mmu"]["ptw"]["outstanding"],
        ad_mode=profile["mmu"]["ad_mode"],
        has_s=int(profile["enabled"]["s"]),
        has_u=int(profile["enabled"]["u"]),
        has_tlb=int(profile["enabled"]["tlb"]),
        has_s_timer=int(profile["interrupts"]["s_timer"]),
        has_s_external=int(profile["interrupts"]["s_external"]),
    ), encoding="utf-8")

    if args.env:
        args.env.parent.mkdir(parents=True, exist_ok=True)
        csr_names = {
            "mstatus": 0x300, "misa": 0x301, "mtvec": 0x305, "mepc": 0x341,
            "mcause": 0x342, "mtval": 0x343, "mscratch": 0x340, "mie": 0x304,
            "mip": 0x344, "medeleg": 0x302, "mideleg": 0x303,
            "mcounteren": 0x306, "mcountinhibit": 0x320, "mcycle": 0xb00,
            "minstret": 0xb02, "sstatus": 0x100, "sie": 0x104, "sip": 0x144,
            "stvec": 0x105, "sscratch": 0x140, "sepc": 0x141,
            "scause": 0x142, "stval": 0x143, "satp": 0x180,
            "scounteren": 0x106,
        }
        enabled_csr = [
            f"0x{csr_names[name]:x}"
            for name, value in profile["csr"]["enabled"].items()
            if value and name in csr_names
        ]
        env_lines = [
            f"MMU_PROFILE={profile['profile']}",
            f"MMU_ENABLED={int(profile['enabled']['mmu'])}",
            f"MMU_PAGE_SIZE_BYTES={profile['mmu']['page_size_bytes']}",
            f"MMU_LEVELS={profile['mmu']['levels']}",
            f"MMU_PTE_SIZE_BYTES={profile['mmu']['pte_size_bytes']}",
            f"MMU_PAGE_TABLE_SIZE_BYTES={profile['mmu']['page_table_size_bytes']}",
            f"MMU_PTE_FORMAT={profile['mmu']['pte_format']}",
            f"MMU_SUPERPAGE={int(profile['mmu']['superpage'])}",
            f"MMU_AD_MODE={profile['mmu']['ad_mode']}",
            f"MMU_CACHE_MODE={profile['mmu']['cache_mode']}",
            f"MMU_PTW_PATH={profile['mmu']['ptw']['path']}",
            f"MMU_PTW_ARCH={profile['mmu']['ptw']['arch']}",
            f"MMU_PTW_OUTSTANDING={profile['mmu']['ptw']['outstanding']}",
            f"MMU_PTW_ORDER={profile['mmu']['ptw']['order']}",
            f"MMU_PTW_CACHE={int(profile['mmu']['ptw']['cache']['enabled'])}",
            f"MMU_PTW_CACHE_ENTRIES={profile['mmu']['ptw']['cache']['entries']}",
            f"MMU_PTW_CACHE_WAYS={profile['mmu']['ptw']['cache']['ways']}",
            f"MMU_TLB_ENABLED={int(profile['enabled']['tlb'])}",
            f"MMU_TLB_ORGANIZATION={profile['tlb']['organization']}",
            f"MMU_TLB_STORAGE={profile['tlb']['storage']}",
            f"MMU_TLB_REPLACEMENT={profile['tlb']['replacement']}",
            f"MMU_TLB_ASID_BITS={profile['tlb']['asid_bits']}",
            f"MMU_TLB_GLOBAL={int(profile['tlb']['global'])}",
            f"MMU_ITLB_ENTRIES={profile['tlb']['itlb']['entries'] if profile['tlb']['itlb'] else 16}",
            f"MMU_ITLB_WAYS={profile['tlb']['itlb']['ways'] if profile['tlb']['itlb'] else 2}",
            f"MMU_DTLB_ENTRIES={profile['tlb']['dtlb']['entries'] if profile['tlb']['dtlb'] else 32}",
            f"MMU_DTLB_WAYS={profile['tlb']['dtlb']['ways'] if profile['tlb']['dtlb'] else 4}",
            f"MMU_UTLB_ENTRIES={profile['tlb']['utlb']['entries'] if profile['tlb']['utlb'] else 32}",
            f"MMU_UTLB_WAYS={profile['tlb']['utlb']['ways'] if profile['tlb']['utlb'] else 4}",
            f"MMU_TLB_SUPERPAGE={int(profile['tlb']['superpage'])}",
            f"MMU_TLB_MISS_QUEUE_MODE={profile['tlb']['miss_queue']['mode']}",
            f"MMU_ITLB_MISS_QUEUE_ENTRIES={profile['tlb']['miss_queue']['itlb_entries']}",
            f"MMU_DTLB_MISS_QUEUE_ENTRIES={profile['tlb']['miss_queue']['dtlb_entries']}",
            f"MMU_UTLB_MISS_QUEUE_ENTRIES={profile['tlb']['miss_queue']['utlb_entries']}",
            f"MMU_GLOBAL_MISS_QUEUE_ENTRIES={profile['tlb']['miss_queue']['global_entries']}",
            f"MMU_TLB_REFILL_PORTS={profile['tlb']['refill_ports']}",
            f"HAS_S={int(profile['enabled']['s'])}",
            f"HAS_U={int(profile['enabled']['u'])}",
            f"HAS_S_TIMER_IRQ={int(profile['interrupts']['s_timer'])}",
            f"HAS_S_EXTERNAL_IRQ={int(profile['interrupts']['s_external'])}",
            f"TIMER_PATH={profile['interrupts']['timer_path']}",
            f"PLIC_SOURCES={profile['interrupts']['plic']['sources']}",
            f"PLIC_PRIORITY_BITS={profile['interrupts']['plic']['priority_bits']}",
            f"PLIC_MAX_PRIORITY={profile['interrupts']['plic']['max_priority']}",
            f"PLIC_BASE=0x{profile['interrupts']['plic']['base']:x}",
            f"CSR_MISSING_MODE={profile['csr']['missing_mode']}",
            f"CSR_VIEW_MODE={profile['csr']['view_mode']}",
            f"CSR_FIELD_DEFAULT_READ={profile['csr']['field_default_read']}",
            f"CSR_FIELD_DEFAULT_WRITE={profile['csr']['field_default_write']}",
            "CSR_ENABLED=" + ",".join(enabled_csr),
        ]
        for csr_name, fields in profile["csr"]["fields"].items():
            for field_name, modes in fields.items():
                upper = field_name.upper()
                env_lines.append(f"CSR_{csr_name.upper()}_{upper}_READ={modes['read']}")
                env_lines.append(f"CSR_{csr_name.upper()}_{upper}_WRITE={modes['write']}")
        args.env.write_text("\n".join(env_lines) + "\n", encoding="utf-8")

    for warning in profile["warnings"]:
        print(f"[mmu-profile] warning: {warning}", file=sys.stderr)
    print(
        f"[mmu-profile] {profile['profile']}: mmu={profile['enabled']['mmu']} "
        f"tlb={profile['enabled']['tlb']} standard={profile['standard_sv32']} "
        f"page={profile['mmu']['page_size_bytes']} levels={profile['mmu']['levels']} "
        f"ptw_outstanding={profile['mmu']['ptw']['outstanding']}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"[mmu-profile] error: {exc}", file=sys.stderr)
        raise SystemExit(2)
