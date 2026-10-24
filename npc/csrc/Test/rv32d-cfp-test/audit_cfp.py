#!/usr/bin/env python3
"""审计 GCC 生成的压缩浮点访存编码是否真实存在。

不依赖反汇编器的助记符（RISC-V objdump 对 C.FLD 和 FLD 都打印 fld），
而是直接读取 objdump 的原始指令字，按 RVC 象限/funct3 分类：

  quadrant 0: 001=C.FLD 011=C.FLW(RV32)/C.LD(RV64)
              101=C.FSD 111=C.FSW(RV32)/C.SD(RV64)
  quadrant 2: 001=C.FLDSP 011=C.FLWSP(RV32)/C.LDSP(RV64)
              101=C.FSDSP 111=C.FSWSP(RV32)/C.SDSP(RV64)

RV32 必须覆盖 8 类浮点压缩访存；RV64 只存在 4 类，且同编码位的
011/111 必须仍是整数 C.LD/C.SD。
"""
import re
import os
import subprocess
import sys
from collections import Counter

LINE_RE = re.compile(r"^\s*[0-9a-f]+:\s+([0-9a-f]+(?:\s+[0-9a-f]+)*)\s+(\S+)")


def classify_rv32(half):
    q = half & 0x3
    funct3 = (half >> 13) & 0x7
    if q == 0:
        return {
            0b001: "C.FLD",
            0b011: "C.FLW",
            0b101: "C.FSD",
            0b111: "C.FSW",
        }.get(funct3)
    if q == 2:
        return {
            0b001: "C.FLDSP",
            0b011: "C.FLWSP",
            0b101: "C.FSDSP",
            0b111: "C.FSWSP",
        }.get(funct3)
    return None


def classify_rv64(half):
    q = half & 0x3
    funct3 = (half >> 13) & 0x7
    if q == 0:
        return {0b001: "C.FLD", 0b101: "C.FSD"}.get(funct3)
    if q == 2:
        return {0b001: "C.FLDSP", 0b101: "C.FSDSP"}.get(funct3)
    return None


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("32", "64"):
        print("用法: audit_cfp.py <32|64> <elf>", file=sys.stderr)
        return 2
    xlen = sys.argv[1]
    elf = sys.argv[2]
    objdump = os.environ.get("OBJDUMP", "objdump")
    dump = subprocess.run(
        [objdump, "-d", elf],
        check=True,
        capture_output=True,
        text=True,
    ).stdout

    counts = Counter()
    integer_same_encoding = 0
    for line in dump.splitlines():
        match = LINE_RE.match(line)
        if not match:
            continue
        raw = match.group(1).strip()
        tokens = raw.split()
        if not tokens:
            continue
        # 只有 2 字节指令是 RVC；4 字节指令的 raw 包含两个 16 位字。
        if len(tokens) != 1 or len(tokens[0]) != 4:
            continue
        half = int(tokens[0], 16)
        if half & 0x3 == 0x3:
            continue
        if xlen == "32":
            name = classify_rv32(half)
        else:
            name = classify_rv64(half)
            # 该编码位在 RV64 是 C.LD/C.SD/C.LDSP/C.SDSP，必须没被
            # 误分类成浮点压缩访存。
            if (half & 0x3) == 0:
                funct3 = (half >> 13) & 0x7
                if funct3 in (0b011, 0b111):
                    integer_same_encoding += 1
            elif (half & 0x3) == 2:
                funct3 = (half >> 13) & 0x7
                if funct3 in (0b011, 0b111):
                    integer_same_encoding += 1
        if name:
            counts[name] += 1

    required = (
        ["C.FLD", "C.FSD", "C.FLW", "C.FSW",
         "C.FLDSP", "C.FSDSP", "C.FLWSP", "C.FSWSP"]
        if xlen == "32"
        else ["C.FLD", "C.FSD", "C.FLDSP", "C.FSDSP"]
    )
    missing = [name for name in required if counts[name] == 0]
    if missing:
        print("[FAIL] 缺少压缩浮点访存编码: " + ", ".join(missing),
              file=sys.stderr)
        print("       实际统计: " + ", ".join(
            f"{k}={v}" for k, v in sorted(counts.items())), file=sys.stderr)
        return 1
    if xlen == "64" and integer_same_encoding < 4:
        print("[FAIL] RV64 同编码位未覆盖 C.LD/C.SD/C.LDSP/C.SDSP "
              f"(仅 {integer_same_encoding} 条)", file=sys.stderr)
        return 1

    print("[PASS] GCC 生成的真实压缩浮点访存编码: " + ", ".join(
        f"{name}={counts[name]}" for name in required))
    if xlen == "64":
        print(f"[PASS] RV64 同编码位整数 C.LD/C.SD 共 {integer_same_encoding} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
