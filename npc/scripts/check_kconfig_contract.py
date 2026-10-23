#!/usr/bin/env python3
"""Kconfig 配置契约校验：把“能配置”收紧到 RTL 真正支持的范围。

Kconfig 的 int/range 只能表达连续区间，无法直接写出
``XLEN ∈ {32,64}``、``MMU_PTE_SIZE_BYTES ∈ {4,8}`` 或“页大小必须是
2 的幂”。本脚本在配置阶段补上这些约束，并给 make defconfig /
menuconfig 调用；非法值不会静默进入 RTL elaboration。

用法：
  python3 scripts/check_kconfig_contract.py --config .config
  python3 scripts/check_kconfig_contract.py --defconfigs
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Dict, List, Mapping, Tuple

# 当前 RTL 契约。若 RTL 扩展这些上限（例如大页代理放宽 1MiB），
# 必须同时修改 Kconfig 的 range 和这里的常量，并补充测试。
MAX_MMU_LEVELS = 4
PTE_SIZE_VALUES = (4, 8)
PAGE_SIZE_MIN = 4
PAGE_SIZE_MAX = 1 << 20          # 1MiB，来自 RV64 satp.PPN 44 位
PAGE_TABLE_SIZE_MIN = 8          # 至少容纳 2 个 4B PTE
SATP_PPN_BITS = 44
XLEN_VALUES = (32, 64)
SOC_XLEN_VALUES = (32, 64)

_CONFIG_RE = re.compile(r"CONFIG_([A-Za-z0-9_]+)=(.*)$")


def parse_config(path: Path) -> Dict[str, object]:
    """读取 .config/defconfig，返回去掉 CONFIG_ 前缀的键值表。"""
    values: Dict[str, object] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = _CONFIG_RE.match(line)
        if not match:
            continue
        key, text = match.group(1), match.group(2).strip()
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


def _as_int(values: Mapping[str, object], key: str, default: int) -> int:
    value = values.get(key, default)
    if isinstance(value, bool):
        return 1 if value else 0
    try:
        return int(str(value), 0)
    except (TypeError, ValueError):
        raise ValueError(f"{key}={value!r} 不是整数") from None


def _as_bool(values: Mapping[str, object], key: str, default: bool = False) -> bool:
    value = values.get(key, default)
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in ("y", "1", "true")


def _as_str(values: Mapping[str, object], key: str, default: str) -> str:
    value = values.get(key, default)
    return str(value) if value is not None else default


def _is_power_of_two(value: int) -> bool:
    return value > 0 and (value & (value - 1)) == 0


def _log2(value: int) -> int:
    return value.bit_length() - 1


def _standard_pte_layout(values: Mapping[str, object], pte_bits: int) -> bool:
    """标准 PTE 布局：标志位 0..7，PPN 从 bit10 到 31/53。"""
    ppn_high = 31 if pte_bits == 32 else 53
    return (
        _as_int(values, "MMU_PTE_VALID_BIT", 0) == 0
        and _as_int(values, "MMU_PTE_READ_BIT", 1) == 1
        and _as_int(values, "MMU_PTE_WRITE_BIT", 2) == 2
        and _as_int(values, "MMU_PTE_EXEC_BIT", 3) == 3
        and _as_int(values, "MMU_PTE_USER_BIT", 4) == 4
        and _as_int(values, "MMU_PTE_GLOBAL_BIT", 5) == 5
        and _as_int(values, "MMU_PTE_A_BIT", 6) == 6
        and _as_int(values, "MMU_PTE_D_BIT", 7) == 7
        and _as_int(values, "MMU_PTE_PPN_LOW", 10) == 10
        and _as_int(values, "MMU_PTE_PPN_HIGH", ppn_high) == ppn_high
    )


def _derived_choice_string(
    values: Mapping[str, object],
    key: str,
    choices: Tuple[Tuple[str, str], ...],
    default: str,
) -> str:
    """字符串派生符号可能被 savedefconfig 省略，此时从 choice 反推。"""
    if key in values:
        return _as_str(values, key, default)
    for symbol, value in choices:
        if _as_bool(values, symbol, False):
            return value
    return default


def validate_config(values: Mapping[str, object], source: str = ".config") -> List[str]:
    """返回配置契约错误列表；空列表表示可以进入 elaboration。

    这里只检查配置阶段能确定的离散值/几何约束。RTL 内部的面积、时序
    等实现限制仍由 elaboration 负责。
    """
    errors: List[str] = []

    def err(message: str) -> None:
        errors.append(f"{source}: {message}")

    xlen = _as_int(values, "XLEN", 32)
    if xlen not in XLEN_VALUES:
        err(
            f"XLEN={xlen} 非法：只允许 32 或 64。Kconfig 的 int range "
            "无法表达离散集合，33..63 由本校验拒绝"
        )

    if not _as_bool(values, "SOC_XLEN_FOLLOW_CPU", False):
        soc_xlen = _as_int(values, "SOC_XLEN", 32)
        if soc_xlen not in SOC_XLEN_VALUES:
            err(
                f"SOC_XLEN={soc_xlen} 非法：只允许 32 或 64"
                "（SOC_XLEN_FOLLOW_CPU=n 时生效）"
            )

    has_s = _as_bool(values, "HAS_S", False)
    has_mmu = _as_bool(values, "HAS_MMU", False)
    if has_mmu and not has_s:
        err("HAS_MMU=y 需要 HAS_S=y：MMU 依赖 S 模式 satp/页错误入口")

    levels = _as_int(values, "MMU_LEVELS", 3 if xlen == 64 else 2)
    if not 1 <= levels <= MAX_MMU_LEVELS:
        err(
            f"MMU_LEVELS={levels} 非法：当前 RTL fillLevel 只支持 "
            f"1..{MAX_MMU_LEVELS} 级"
        )

    pte_4b = _as_bool(values, "MMU_PTE_SIZE_4B", False)
    pte_8b = _as_bool(values, "MMU_PTE_SIZE_8B", False)
    pte_size_present = "MMU_PTE_SIZE_BYTES" in values
    if pte_size_present:
        pte_size = _as_int(values, "MMU_PTE_SIZE_BYTES", 8 if xlen == 64 else 4)
    elif pte_4b and not pte_8b:
        # savedefconfig 可能只保留 choice，省略派生整数；按 choice 推导。
        pte_size = 4
    elif pte_8b and not pte_4b:
        pte_size = 8
    else:
        pte_size = 8 if xlen == 64 else 4
    if pte_size not in PTE_SIZE_VALUES:
        err(
            f"MMU_PTE_SIZE_BYTES={pte_size} 非法：RTL 只支持 4 或 8 字节 PTE"
        )

    # choice 与派生整数必须一致；数值符号缺省时以 choice 为准。
    if pte_4b and pte_8b:
        err("MMU_PTE_SIZE_4B 与 MMU_PTE_SIZE_8B 不能同时为 y")
    elif pte_4b and pte_size != 4:
        err(f"PTE 大小 choice=4B，但 MMU_PTE_SIZE_BYTES={pte_size}")
    elif pte_8b and pte_size != 8:
        err(f"PTE 大小 choice=8B，但 MMU_PTE_SIZE_BYTES={pte_size}")

    page_size = _as_int(values, "MMU_PAGE_SIZE_BYTES", 4096)
    page_bits = 0
    if not _is_power_of_two(page_size):
        err(
            f"MMU_PAGE_SIZE_BYTES={page_size} 非法：必须是 2 的幂，"
            f"当前支持 {PAGE_SIZE_MIN}B..{PAGE_SIZE_MAX}B"
        )
    elif not PAGE_SIZE_MIN <= page_size <= PAGE_SIZE_MAX:
        err(
            f"MMU_PAGE_SIZE_BYTES={page_size} 超出当前支持范围 "
            f"{PAGE_SIZE_MIN}..{PAGE_SIZE_MAX}（RV64 satp.PPN 44 位，"
            "页大小上限 1MiB）"
        )
    else:
        page_bits = _log2(page_size)

    table_size = _as_int(values, "MMU_PAGE_TABLE_SIZE_BYTES", 4096)
    if not _is_power_of_two(table_size):
        err(f"MMU_PAGE_TABLE_SIZE_BYTES={table_size} 非法：必须是 2 的幂")
    elif table_size < PAGE_TABLE_SIZE_MIN:
        err(
            f"MMU_PAGE_TABLE_SIZE_BYTES={table_size} 非法：至少 "
            f"{PAGE_TABLE_SIZE_MIN}B（每级至少 2 个 PTE）"
        )
    if pte_size > 0 and table_size % pte_size != 0:
        err(
            f"MMU_PAGE_TABLE_SIZE_BYTES={table_size} 必须能被 "
            f"MMU_PTE_SIZE_BYTES={pte_size} 整除"
        )
    elif pte_size > 0:
        entries = table_size // pte_size
        if entries < 2 or not _is_power_of_two(entries):
            err(
                f"每级页表项数={entries} 非法：必须是至少为 2 的 2 的幂 "
                f"（table={table_size}, pte={pte_size}）"
            )

    geometry = _derived_choice_string(
        values,
        "MMU_GEOMETRY_MODE",
        (("MMU_GEOMETRY_CUSTOM", "custom"), ("MMU_GEOMETRY_STANDARD", "standard")),
        "standard",
    ).lower()
    if geometry not in ("standard", "custom"):
        err(f"MMU_GEOMETRY_MODE={geometry!r} 非法：只允许 standard/custom")

    pte_format = _derived_choice_string(
        values,
        "MMU_PTE_FORMAT",
        (
            ("MMU_PTE_CUSTOM", "custom"),
            ("MMU_PTE_RISCV64", "riscv64"),
            ("MMU_PTE_RISCV32", "riscv32"),
        ),
        "riscv64" if xlen == 64 else "riscv32",
    )
    if pte_format not in ("riscv32", "riscv64", "custom"):
        err(f"MMU_PTE_FORMAT={pte_format!r} 非法：只允许 riscv32/riscv64/custom")

    if has_mmu and xlen == 64 and geometry == "standard":
        # RV64 STANDARD 必须完整匹配标准 Sv39 或 Sv48。
        if levels not in (3, 4):
            err(
                f"RV64 STANDARD 的 MMU_LEVELS={levels} 非法：只允许 3(Sv39) "
                "或 4(Sv48)；非标准级数请显式选择 MMU_GEOMETRY_CUSTOM"
            )
        if pte_size != 8:
            err("RV64 STANDARD 要求 MMU_PTE_SIZE_BYTES=8")
        if page_size != 4096:
            err("RV64 STANDARD 要求 MMU_PAGE_SIZE_BYTES=4096")
        if table_size != 4096:
            err("RV64 STANDARD 要求 MMU_PAGE_TABLE_SIZE_BYTES=4096")
        if pte_format != "riscv64":
            err("RV64 STANDARD 要求 MMU_PTE_FORMAT=riscv64")
        if page_size == 4096 and table_size == 4096 and pte_size == 8:
            address_bits = 12 + levels * 9
            if address_bits not in (39, 48):
                err(
                    f"RV64 STANDARD 几何算出 {address_bits} 位 VA，"
                    "只支持 39(Sv39)/48(Sv48)"
                )
        if pte_size == 8 and not _standard_pte_layout(values, 64):
            err("RV64 STANDARD 要求标准 64-bit PTE 字段布局")

    if has_mmu and geometry == "custom":
        if xlen != 64:
            err("MMU_GEOMETRY_MODE=custom 只用于 RV64；RV32 自定义几何使用 STANDARD")
        satp_mode = _as_int(values, "MMU_CUSTOM_SATP_MODE", 10)
        if not 10 <= satp_mode <= 15:
            err(
                f"MMU_CUSTOM_SATP_MODE={satp_mode} 非法：必须使用保留的 "
                "satp MODE 10..15，不能复用标准 8/9"
            )
        if pte_size in PTE_SIZE_VALUES:
            pte_bits = pte_size * 8
            ppn_low = _as_int(values, "MMU_PTE_PPN_LOW", 10)
            ppn_high = _as_int(
                values, "MMU_PTE_PPN_HIGH", 53 if pte_size == 8 else 31
            )
            flags = [
                _as_int(values, "MMU_PTE_VALID_BIT", 0),
                _as_int(values, "MMU_PTE_READ_BIT", 1),
                _as_int(values, "MMU_PTE_WRITE_BIT", 2),
                _as_int(values, "MMU_PTE_EXEC_BIT", 3),
                _as_int(values, "MMU_PTE_USER_BIT", 4),
                _as_int(values, "MMU_PTE_GLOBAL_BIT", 5),
                _as_int(values, "MMU_PTE_A_BIT", 6),
                _as_int(values, "MMU_PTE_D_BIT", 7),
            ]
            if any(bit < 0 or bit >= pte_bits for bit in flags):
                err(f"PTE 标志位必须位于 0..{pte_bits - 1}")
            if len(set(flags)) != len(flags):
                err("PTE 标志位不能重复")
            if not 8 <= ppn_low <= ppn_high < pte_bits:
                err(
                    f"PTE PPN 位范围非法：[{ppn_high}:{ppn_low}]，"
                    f"要求 8 <= low <= high < {pte_bits}"
                )
            if any(ppn_low <= bit <= ppn_high for bit in flags):
                err(
                    f"PTE 标志位不能与 PPN 字段 [{ppn_high}:{ppn_low}] 重叠"
                )
            if any(bit > ppn_high for bit in flags):
                err(f"PTE 标志位必须位于 PPN 高位({ppn_high})以下")
            if pte_size == 8 and pte_format not in ("custom", "riscv64"):
                err(
                    f"8B PTE 只能使用 MMU_PTE_FORMAT=custom 或 riscv64，"
                    f"收到 {pte_format}"
                )
            if pte_size == 4 and pte_format not in ("custom", "riscv32"):
                err(
                    f"4B PTE 只能使用 MMU_PTE_FORMAT=custom 或 riscv32，"
                    f"收到 {pte_format}"
                )
            if pte_format == "riscv64" and not _standard_pte_layout(values, 64):
                err("MMU_PTE_FORMAT=riscv64 要求标准 64-bit PTE 字段布局")
            if pte_format == "riscv32" and not _standard_pte_layout(values, 32):
                err("MMU_PTE_FORMAT=riscv32 要求标准 32-bit PTE 字段布局")
            ad_mode = _derived_choice_string(
                values,
                "MMU_AD_MODE",
                (
                    ("MMU_AD_HARDWARE", "hardware"),
                    ("MMU_AD_IGNORE", "ignore"),
                    ("MMU_AD_FAULT", "fault"),
                ),
                "fault",
            )
            if (
                ad_mode == "hardware"
                and pte_size == 8
                and (flags[6] >= 32 or flags[7] >= 32)
            ):
                err(
                    "MMU_AD_MODE=hardware 且 PTE=8B 时，A/D 位必须在低 "
                    "32 位字内（<32）；当前 32 位页表写端口无法更新高字"
                )
            if page_bits:
                ppn_bits = ppn_high - ppn_low + 1
                if page_bits + ppn_bits > 64:
                    err(
                        f"pageBits({page_bits}) + PPN 位宽({ppn_bits}) "
                        "超过 64 位物理地址"
                    )
                if page_bits + SATP_PPN_BITS > 64:
                    err(
                        f"pageBits({page_bits}) + {SATP_PPN_BITS} 位 "
                        "satp.PPN 超过 64，页大小上限为 1MiB"
                    )
                index_bits = _log2(table_size // pte_size) if (
                    pte_size > 0
                    and table_size % pte_size == 0
                    and _is_power_of_two(table_size // pte_size)
                ) else 0
                address_bits = page_bits + levels * index_bits
                if not 2 <= address_bits <= 64:
                    err(
                        f"CUSTOM 几何地址位宽={address_bits} 非法："
                        "pageBits + levels * indexBits 必须在 2..64"
                    )
                if _as_bool(values, "MMU_SUPERPAGE", False) and (
                    ppn_bits < (levels - 1) * index_bits
                ):
                    err(
                        f"超级页对齐位({(levels - 1) * index_bits})超过 "
                        f"PTE PPN 位宽({ppn_bits})，无法正确检查"
                    )

    return errors


def _old_style_pte_warning(values: Mapping[str, object]) -> List[str]:
    """旧 defconfig 可能仍写数值 PTE；choice 会让它失效，给出迁移提示。"""
    if "MMU_PTE_SIZE_BYTES" not in values:
        return []
    if _as_bool(values, "MMU_PTE_SIZE_4B", False) or _as_bool(
        values, "MMU_PTE_SIZE_8B", False
    ):
        return []
    return [
        "检测到旧式 CONFIG_MMU_PTE_SIZE_BYTES=... 赋值：PTE 大小现由 "
        "MMU_PTE_SIZE_4B/8B choice 决定，Kconfig 会忽略该数值并采用默认值"
        "（XLEN=64 -> 8B，XLEN=32 -> 4B）；请迁移到 choice 符号"
    ]


def check_file(path: Path) -> Tuple[List[str], List[str]]:
    values = parse_config(path)
    return validate_config(values, path.name), _old_style_pte_warning(values)


def _default_configs_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "configs"


def main(argv: List[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--config", type=Path, help="校验一个 .config")
    group.add_argument(
        "--defconfigs",
        action="store_true",
        help="校验 npc/configs/ 下所有 *_defconfig",
    )
    parser.add_argument(
        "--defconfigs-dir",
        type=Path,
        default=None,
        help="配合 --defconfigs 指定目录（默认 npc/configs）",
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    if args.config is not None:
        if not args.config.is_file():
            print(f"错误: 配置文件不存在: {args.config}", file=sys.stderr)
            return 2
        errors, warnings = check_file(args.config)
        for warning in warnings:
            print(f"[warn] {warning}", file=sys.stderr)
        if errors:
            for message in errors:
                print(f"[FAIL] {message}", file=sys.stderr)
            return 1
        if not args.quiet:
            print(f"[OK] Kconfig 配置契约校验通过: {args.config}")
        return 0

    configs_dir = args.defconfigs_dir or _default_configs_dir()
    files = sorted(configs_dir.glob("*_defconfig"))
    if not files:
        print(f"错误: 没有找到 {configs_dir}/*_defconfig", file=sys.stderr)
        return 2
    failed = 0
    for path in files:
        errors, warnings = check_file(path)
        for warning in warnings:
            print(f"[warn] {path.name}: {warning}", file=sys.stderr)
        if errors:
            failed += 1
            for message in errors:
                print(f"[FAIL] {message}", file=sys.stderr)
        elif not args.quiet:
            print(f"[OK] {path.name}")
    if failed:
        print(f"[FAIL] {failed}/{len(files)} 个 defconfig 违反配置契约", file=sys.stderr)
        return 1
    if not args.quiet:
        print(f"[OK] {len(files)} 个 defconfig 全部通过配置契约校验")
    return 0


if __name__ == "__main__":
    sys.exit(main())
