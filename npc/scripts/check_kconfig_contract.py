#!/usr/bin/env python3
"""Kconfig 配置契约校验：把“能配置”收紧到 RTL 真正支持的范围。

Kconfig 的 int/range 只能表达连续区间，无法直接写出
``XLEN ∈ {32,64}``、``SOC_XLEN ∈ {32,64}`` 或“页大小/PTE 大小必须是
2 的幂”。本脚本在配置阶段补上这些约束，并给 make defconfig /
menuconfig 调用；非标准但可表达的几何会放行，不可表达的几何明确报错。

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

# 当前 RTL/大页实现的几何上限。Kconfig 不再用 range 锁死页大小和
# 级数；这里只拒绝明确不可能或当前 RTL 无法表达的几何。若 RTL 扩展
# 这些上限，必须同时更新本文件和测试。
MAX_MMU_LEVELS = 8
PAGE_SIZE_MIN = 4
# pageSizeBytes 是有符号 32 位 Int，最大的 2 的幂是 1GiB。具体组合
# 还受 pageBits + levels*indexBits <= 64 等几何约束限制。
PAGE_SIZE_MAX = 1 << 30
PAGE_TABLE_SIZE_MIN = 8          # 至少容纳 2 个 4B PTE
SATP_PPN_BITS = 44
XLEN_VALUES = (32, 64)
SOC_XLEN_VALUES = (32, 64)
FPU_FLEN_VALUES = (32, 64)
FPU_IMPL_VALUES = ("none", "hardfloat", "fudian", "external")
FPU_IMPL_CHOICES = (
    ("FPU_IMPL_HARDFLOAT", "hardfloat"),
    ("FPU_IMPL_FUDIAN", "fudian"),
    ("FPU_IMPL_EXTERNAL", "external"),
    ("FPU_IMPL_NONE", "none"),
)

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


def _linux_mmu_type(values: Mapping[str, object], xlen: int) -> str:
    """返回 Linux DTB 可声明的标准 mmu-type；非标准几何返回空串。

    这里与 npc/scripts/build_dtb.sh 的推导保持一致：只有标准 Sv32
    （RV32，4KiB 页/4B PTE/2 级）和 Sv39/Sv48（RV64，4KiB 页/8B PTE/
    3/4 级）才能写入 mmu-type。CUSTOM 或任意非标准几何不能伪装成标准
    类型，Linux 打包必须在配置阶段就拒绝。
    """
    if not _as_bool(values, "HAS_MMU", False):
        return ""
    geometry = _derived_choice_string(
        values,
        "MMU_GEOMETRY_MODE",
        (("MMU_GEOMETRY_CUSTOM", "custom"),
         ("MMU_GEOMETRY_STANDARD", "standard")),
        "standard",
    ).strip().lower()
    if geometry != "standard":
        return ""
    levels = _as_int(values, "MMU_LEVELS", 3 if xlen == 64 else 2)
    page_size = _as_int(values, "MMU_PAGE_SIZE_BYTES", 4096)
    table_size = _as_int(values, "MMU_PAGE_TABLE_SIZE_BYTES", 4096)
    pte_format = _derived_choice_string(
        values,
        "MMU_PTE_FORMAT",
        (
            ("MMU_PTE_CUSTOM", "custom"),
            ("MMU_PTE_RISCV64", "riscv64"),
            ("MMU_PTE_RISCV32", "riscv32"),
        ),
        "riscv64" if xlen == 64 else "riscv32",
    ).strip().lower()
    if xlen == 32:
        pte_size = _as_int(values, "MMU_PTE_SIZE_BYTES", 4)
        if (levels == 2 and page_size == 4096 and table_size == 4096
                and pte_size == 4 and pte_format == "riscv32"):
            return "riscv,sv32"
        return ""
    pte_size = _as_int(values, "MMU_PTE_SIZE_BYTES", 8)
    if (page_size != 4096 or table_size != 4096
            or pte_size != 8 or pte_format != "riscv64"):
        return ""
    if levels == 3:
        return "riscv,sv39"
    if levels == 4:
        return "riscv,sv48"
    return ""


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

    # direct-NPC RAM 窗口：AXIRAM 深度、PMA、C++ CONFIG_MSIZE 和
    # DTB/打包脚本共用这一个值。只接受 2 的幂，避免 SyncReadMem 索引
    # 与 AXI 窗口出现未覆盖的高地址。
    ram_size = _as_int(values, "NPC_RAM_SIZE", 0x04000000)
    ram_base = 0x80000000
    if ram_size < 0x1000:
        err(f"NPC_RAM_SIZE=0x{ram_size:x} 太小：至少 4 KiB")
    elif not _is_power_of_two(ram_size):
        err(f"NPC_RAM_SIZE=0x{ram_size:x} 非法：必须是 2 的幂")
    elif ram_size > 0x80000000:
        err(f"NPC_RAM_SIZE=0x{ram_size:x} 超过 32 位物理地址窗口")
    ram_end = ram_base + ram_size

    if not _as_bool(values, "SOC_XLEN_FOLLOW_CPU", False):
        soc_xlen = _as_int(values, "SOC_XLEN", 32)
        if soc_xlen not in SOC_XLEN_VALUES:
            err(
                f"SOC_XLEN={soc_xlen} 非法：只允许 32 或 64"
                "（SOC_XLEN_FOLLOW_CPU=n 时生效）"
            )

    # ---- F/D 与 FPU 后端 -------------------------------------------------
    use_f = _as_bool(values, "RISCV_F", False)
    use_d = _as_bool(values, "RISCV_D", False)
    fpu_impl = _derived_choice_string(
        values, "FPU_IMPL", FPU_IMPL_CHOICES, "none"
    ).strip().lower()
    if fpu_impl not in FPU_IMPL_VALUES:
        err(
            f"FPU_IMPL={fpu_impl!r} 非法：只允许 "
            f"{'/'.join(FPU_IMPL_VALUES)}"
        )
    # 字符串派生项和 choice 布尔项必须一致；否则 savedefconfig 或手工
    # 修改 .config 会让 Makefile 和 Kconfig 菜单看到不同后端。
    for symbol, choice in FPU_IMPL_CHOICES:
        if _as_bool(values, symbol, False) and choice != fpu_impl:
            err(
                f"{symbol}=y 与 FPU_IMPL={fpu_impl!r} 不一致；"
                "请重新运行 menuconfig/olddefconfig"
            )
    if use_d and not use_f:
        err("RISCV_D=y 依赖 RISCV_F=y：D 扩展不能脱离 F 单独启用")
    if not use_f and fpu_impl != "none":
        err(
            f"RISCV_F=n 时 FPU_IMPL 必须为 none，收到 {fpu_impl!r}；"
            "请同时启用 RISCV_F 或清除后端选择"
        )
    if use_f and fpu_impl == "none":
        err("RISCV_F=y 但 FPU_IMPL=none：必须选择 hardfloat/fudian 等后端")
    if fpu_impl == "external":
        err(
            "FPU_IMPL=external 只是预留接口，当前没有外部 FPU 实现；"
            "请选择 hardfloat/fudian，或关闭 RISCV_F"
        )

    fpu_flen = _as_int(values, "FPU_FLEN", 64)
    if fpu_flen not in FPU_FLEN_VALUES:
        err(f"FPU_FLEN={fpu_flen} 非法：只允许 32 或 64")
    if use_d and fpu_flen != 64:
        err(
            f"启用 RISCV_D 要求 FPU_FLEN=64，收到 FPU_FLEN={fpu_flen}"
        )
    if use_f:
        fpu_pipeline = _as_int(values, "FPU_PIPELINE", 0)
        if not 0 <= fpu_pipeline <= 8:
            err(
                f"FPU_PIPELINE={fpu_pipeline} 非法：必须在 0..8 之间"
            )
        # FDIV/FSQRT、完整舍入和 fflags 是 F/D 的必备语义；允许关闭
        # 会让构建声称支持 F/D 却缺少指令或异常状态。
        if not _as_bool(values, "FPU_DIVSQRT", True):
            err("启用 RISCV_F/D 要求 FPU_DIVSQRT=y：FDIV/FSQRT 是必备指令")
        if not _as_bool(values, "FPU_ROUNDING", True):
            err("启用 RISCV_F/D 要求 FPU_ROUNDING=y：必须支持完整舍入模式")
        if not _as_bool(values, "FPU_FLAGS", True):
            err("启用 RISCV_F/D 要求 FPU_FLAGS=y：fflags 是必备状态")

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

    pte_size = _as_int(values, "MMU_PTE_SIZE_BYTES", 8 if xlen == 64 else 4)
    if pte_size <= 0 or not _is_power_of_two(pte_size):
        err(
            f"MMU_PTE_SIZE_BYTES={pte_size} 非法：必须是正的 2 的幂；"
            "当前 RTL 实际只实现 4B/8B，其他值由生成器/elaboration 明确拒绝"
        )

    page_size = _as_int(values, "MMU_PAGE_SIZE_BYTES", 4096)
    page_bits = 0
    if not _is_power_of_two(page_size):
        err(
            f"MMU_PAGE_SIZE_BYTES={page_size} 非法：必须是 2 的幂，"
            f"当前实现可表示 {PAGE_SIZE_MIN}B..{PAGE_SIZE_MAX}B"
        )
    elif not PAGE_SIZE_MIN <= page_size <= PAGE_SIZE_MAX:
        err(
            f"MMU_PAGE_SIZE_BYTES={page_size} 超出当前可实现范围 "
            f"{PAGE_SIZE_MIN}..{PAGE_SIZE_MAX}（pageSizeBytes 为 32 位 "
            "有符号 Int，最大 2 的幂为 1GiB）"
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
        if pte_size > 0 and _is_power_of_two(pte_size):
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
            # 标准编码名必须与 PTE 位宽/字段布局完全一致；CUSTOM 不限制
            # 4/8 之外的值，交由生成器/elaboration 按 RTL 实际能力拒绝。
            if pte_format == "riscv64" and (
                pte_bits != 64 or not _standard_pte_layout(values, 64)
            ):
                err("MMU_PTE_FORMAT=riscv64 要求 64-bit PTE 与标准字段布局")
            if pte_format == "riscv32" and (
                pte_bits != 32 or not _standard_pte_layout(values, 32)
            ):
                err("MMU_PTE_FORMAT=riscv32 要求 32-bit PTE 与标准字段布局")
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
                and pte_size > 4
                and (flags[6] >= 32 or flags[7] >= 32)
            ):
                err(
                    f"MMU_AD_MODE=hardware 且 PTE={pte_size}B 时，A/D 位必须"
                    "在低 32 位字内（<32）；当前 32 位页表写端口无法更新高字"
                )
            if page_bits:
                ppn_bits = ppn_high - ppn_low + 1
                # PTE/satp 的 PPN 字段可以比物理地址更宽；超出 64 位的
                # 高位不可用，RTL walk 会按保留位检查并要求为 0。这里只
                # 要求每个字段仍至少有 1 位可寻址的 PPN。
                paddr_ppn_bits = min(ppn_bits, 64 - page_bits)
                satp_ppn_bits = min(SATP_PPN_BITS, 64 - page_bits)
                if paddr_ppn_bits < 1:
                    err(
                        f"pageBits({page_bits}) 已占满 64 位物理地址，"
                        f"PTE PPN 位宽({ppn_bits}) 没有可用位"
                    )
                if satp_ppn_bits < 1:
                    err(
                        f"pageBits({page_bits}) 已占满 64 位物理地址，"
                        f"satp.PPN({SATP_PPN_BITS} 位) 没有可用位"
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

    # SBI 固件布局：Kconfig 的 hex 只表达数值，这里补齐对齐和区间约束。
    # 默认 SBI_ENABLE=n，不会影响现有 xv6/NPC 配置。
    if _as_bool(values, "SBI_ENABLE", False):
        sbi_impl = _derived_choice_string(
            values,
            "SBI_IMPL",
            (
                ("SBI_IMPL_OPENSBI", "opensbi"),
                ("SBI_IMPL_RUSTSBI", "rustsbi"),
            ),
            "none",
        )
        if sbi_impl == "none":
            err("SBI_ENABLE=y 但未选择 SBI 实现（SBI_IMPL_OPENSBI/RUSTSBI）")

        base = _as_int(values, "SBI_FW_BASE", 0x80000000)
        size = _as_int(values, "SBI_FW_SIZE", 0x00200000)
        next_addr = _as_int(values, "SBI_NEXT_ADDR", 0x80200000)
        if base <= 0 or base % 4096 != 0:
            err(f"SBI_FW_BASE=0x{base:x} 必须是正的 4 KiB 对齐地址")
        if size <= 0 or size % 4096 != 0:
            err(f"SBI_FW_SIZE=0x{size:x} 必须是正的 4 KiB 倍数")
        if next_addr <= base:
            err("SBI_NEXT_ADDR 必须大于 SBI_FW_BASE")
        elif base > 0 and size > 0 and base + size > next_addr:
            err(
                f"SBI_FW_BASE+SBI_FW_SIZE=0x{base + size:x} 越过 "
                f"SBI_NEXT_ADDR=0x{next_addr:x}，固件会覆盖 next-stage"
            )
        else:
            jump_align = 2 * 1024 * 1024 if xlen == 64 else 4 * 1024 * 1024
            if next_addr % jump_align != 0:
                err(
                    f"SBI_NEXT_ADDR=0x{next_addr:x} 必须按 "
                    f"{jump_align // 1024 // 1024} MiB 对齐（RV{xlen}）"
                )

        if _as_bool(values, "SBI_FDT_ENABLE", False):
            # DTB 由 SBI 复制到 SBI_FDT_ADDR 后通过 a1 传给 next-stage。
            # 这里只做与固定 NPC RAM/固件布局有关的硬约束；DTB 实际大小
            # 在 scripts/build_dtb.sh 中按生成物再次校验。
            fdt_addr = _as_int(values, "SBI_FDT_ADDR", 0x80F00000)
            fdt_path = str(values.get("SBI_FDT_PATH", "dts/npc.dts"))
            if not fdt_path:
                err("SBI_FDT_ENABLE=y 但 SBI_FDT_PATH 为空")
            if fdt_addr % 8 != 0:
                err(f"SBI_FDT_ADDR=0x{fdt_addr:x} 必须按 8 字节对齐")
            fdt_reserve = 0x10000
            if fdt_addr < ram_base or fdt_addr + fdt_reserve > ram_end:
                err(
                    f"SBI_FDT_ADDR=0x{fdt_addr:x} 超出 NPC RAM "
                    f"[0x{ram_base:x}, 0x{ram_end:x})，或末尾越过 RAM"
                )
            fw_end = base + size
            if fdt_addr < fw_end and fdt_addr + fdt_reserve > base:
                err(
                    f"SBI_FDT_ADDR=0x{fdt_addr:x} 与 SBI 固件区 "
                    f"[0x{base:x}, 0x{fw_end:x}) 重叠"
                )
            if fdt_addr < next_addr:
                if fdt_addr + fdt_reserve > next_addr:
                    err(
                        f"SBI_FDT_ADDR=0x{fdt_addr:x} 位于 next-stage 之前，"
                        f"但 64 KiB 保留区越过 SBI_NEXT_ADDR=0x{next_addr:x}"
                    )
            elif fdt_addr < next_addr + 0x1000:
                err(
                    f"SBI_FDT_ADDR=0x{fdt_addr:x} 覆盖 next-stage 入口页 "
                    f"0x{next_addr:x}..0x{next_addr + 0x1000:x}"
                )

        if sbi_impl == "opensbi":
            # OpenSBI 的 _start 需要 A 扩展；S 模式服务和 PMP 隔离分别
            # 依赖 HAS_S/HAS_PMP。缺失时在配置阶段就明确拒绝。
            if not _as_bool(values, "RISCV_A", False):
                err("SBI_IMPL_OPENSBI 需要 RISCV_A=y（_start 使用 amoswap.w）")
            if not has_s:
                err("SBI_IMPL_OPENSBI 需要 HAS_S=y")
            if not _as_bool(values, "HAS_PMP", False):
                err("SBI_IMPL_OPENSBI 需要 HAS_PMP=y（隔离 M/S 访问）")

    # Linux 启动镜像：地址完全复用 SBI_NEXT_ADDR / SBI_FDT_ADDR，
    # 这里只检查配置阶段能确定的布局与标准 MMU 约束；Image/initramfs
    # 的实际大小与最终不重叠由 pack_linux_image.sh 再检查。
    if _as_bool(values, "LINUX_BOOT_ENABLE", False):
        if not _as_bool(values, "SBI_ENABLE", False):
            err("LINUX_BOOT_ENABLE=y 需要 SBI_ENABLE=y")
        if not _as_bool(values, "SBI_FDT_ENABLE", False):
            err("LINUX_BOOT_ENABLE=y 需要 SBI_FDT_ENABLE=y（Linux 需要 a1=DTB）")
        if not has_s:
            err("LINUX_BOOT_ENABLE=y 需要 HAS_S=y")
        if not has_mmu:
            err("LINUX_BOOT_ENABLE=y 需要 HAS_MMU=y")
        if not _as_bool(values, "RISCV_M", False):
            err("LINUX_BOOT_ENABLE=y 需要 RISCV_M=y（Linux 使用乘除法）")
        if not _as_bool(values, "RISCV_A", False):
            err("LINUX_BOOT_ENABLE=y 需要 RISCV_A=y（Linux 原子操作）")
        if not _as_str(values, "LINUX_IMAGE_PATH", ""):
            err("LINUX_BOOT_ENABLE=y 但 LINUX_IMAGE_PATH 为空")

        next_addr = _as_int(values, "SBI_NEXT_ADDR", 0x80200000)
        fdt_addr = _as_int(values, "SBI_FDT_ADDR", 0x83F00000)
        fdt_reserve = 0x10000
        fw_base = _as_int(values, "SBI_FW_BASE", 0x80000000)
        fw_size = _as_int(values, "SBI_FW_SIZE", 0x00200000)
        fw_end = fw_base + fw_size
        mmu_type = _linux_mmu_type(values, xlen)
        if not mmu_type:
            err(
                "LINUX_BOOT_ENABLE=y 要求标准 Sv32/Sv39/Sv48 MMU 几何；"
                "CUSTOM 或其他非标准几何不能伪装成标准 mmu-type"
            )
        if _as_bool(values, "LINUX_INITRAMFS_ENABLE", False):
            initramfs_path = _as_str(
                values, "LINUX_INITRAMFS_PATH", ""
            )
            if not initramfs_path:
                err(
                    "LINUX_INITRAMFS_ENABLE=y 但 LINUX_INITRAMFS_PATH 为空"
                )
            initrd_addr = _as_int(
                values, "LINUX_INITRAMFS_ADDR", 0x82000000
            )
            if initrd_addr % 4096 != 0:
                err(
                    f"LINUX_INITRAMFS_ADDR=0x{initrd_addr:x} 必须按 "
                    "4 KiB 对齐"
                )
            if initrd_addr < ram_base or initrd_addr >= ram_end:
                err(
                    f"LINUX_INITRAMFS_ADDR=0x{initrd_addr:x} 超出 NPC RAM "
                    f"[0x{ram_base:x}, 0x{ram_end:x})"
                )
            if initrd_addr < next_addr + 0x1000:
                err(
                    f"LINUX_INITRAMFS_ADDR=0x{initrd_addr:x} 落在 "
                    f"Linux Image 入口 0x{next_addr:x} 附近；initramfs "
                    "必须位于 Image 之后"
                )
            if initrd_addr < fw_end and initrd_addr + 0x1000 > fw_base:
                err(
                    f"LINUX_INITRAMFS_ADDR=0x{initrd_addr:x} 与 SBI 固件区 "
                    f"[0x{fw_base:x}, 0x{fw_end:x}) 重叠"
                )
            if fdt_addr <= initrd_addr < fdt_addr + fdt_reserve:
                err(
                    f"LINUX_INITRAMFS_ADDR=0x{initrd_addr:x} 落在 DTB "
                    f"保留区 [0x{fdt_addr:x}, 0x{fdt_addr + fdt_reserve:x}) 内"
                )

    # U-Boot EFI 路径：U-Boot 自己占 0x80200000 起的映像区，EFI 应用/
    # initramfs/内核源文件依次放在固定地址，DTB 放在 RAM 末尾前 1 MiB。
    # 实际大小由 scripts/build_uboot_npc.sh 检查；这里先拒绝明显越界、
    # 未对齐和相互重叠的配置。
    if _as_bool(values, "NPC_UBOOT_ENABLE", False):
        if not _as_bool(values, "SBI_ENABLE", False):
            err("NPC_UBOOT_ENABLE=y 需要 SBI_ENABLE=y")
        if not _as_bool(values, "SBI_FDT_ENABLE", False):
            err("NPC_UBOOT_ENABLE=y 需要 SBI_FDT_ENABLE=y（U-Boot 需要 a1=DTB）")
        next_addr = _as_int(values, "SBI_NEXT_ADDR", 0x80200000)
        fdt_addr = _as_int(values, "SBI_FDT_ADDR", 0x80F00000)
        fdt_reserve = 0x10000
        uboot_payloads = (
            ("NPC_UBOOT_EFI_APP_ADDR", 0x80600000),
            ("NPC_UBOOT_INITRAMFS_ADDR", 0x80800000),
            ("NPC_UBOOT_KERNEL_ADDR", 0x81000000),
        )
        previous_end = next_addr + 0x1000
        for symbol, default in uboot_payloads:
            address = _as_int(values, symbol, default)
            if address % 4096 != 0:
                err(f"{symbol}=0x{address:x} 必须按 4 KiB 对齐")
            if address < next_addr + 0x1000:
                err(
                    f"{symbol}=0x{address:x} 覆盖 U-Boot 入口区 "
                    f"0x{next_addr:x}..0x{next_addr + 0x1000:x}"
                )
            if address < previous_end:
                err(
                    f"{symbol}=0x{address:x} 与前一个载荷区结束地址 "
                    f"0x{previous_end:x} 重叠或顺序错误"
                )
            if address + 0x1000 > ram_end:
                err(f"{symbol}=0x{address:x} 超出 NPC RAM 结束 0x{ram_end:x}")
            previous_end = address + 0x1000
        if fdt_addr < ram_base or fdt_addr + fdt_reserve > ram_end:
            err(
                f"SBI_FDT_ADDR=0x{fdt_addr:x} 的 64 KiB 保留区超出 "
                f"NPC RAM [0x{ram_base:x}, 0x{ram_end:x})"
            )
        if fdt_addr < previous_end:
            err(
                f"SBI_FDT_ADDR=0x{fdt_addr:x} 与 EFI 载荷区结束地址 "
                f"0x{previous_end:x} 重叠或顺序错误"
            )

    return errors


def check_file(path: Path) -> List[str]:
    values = parse_config(path)
    return validate_config(values, path.name)


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
        errors = check_file(args.config)
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
        errors = check_file(path)
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
