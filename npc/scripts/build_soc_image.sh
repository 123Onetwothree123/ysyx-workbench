#!/usr/bin/env bash
# 把 payload 打包成 C5 SoC 启动镜像：
#   MROM 0x20000000 侧：4 KiB stub（入口 0x20000000，size 在 +0xff0）
#   Flash 0x30000000 侧：payload 原样放在 +0x1000
# ImageLoader 会识别这个 4 KiB 前缀，把 stub 放进 Mrom、payload 放进 Flash。
set -euo pipefail

usage() {
    echo "用法: $0 <payload.bin> <output.image>" >&2
    echo "可选环境变量: CROSS_COMPILE, XLEN" >&2
    exit 2
}

[[ $# -eq 2 ]] || usage
payload=$1
output=$2
[[ -f "$payload" ]] || { echo "payload 不存在: $payload" >&2; exit 2; }

script_dir=$(cd "$(dirname "$0")" && pwd)
npc_dir=$(cd "$script_dir/.." && pwd)
stub_src="$npc_dir/boot/act_stub.S"
config_file="$npc_dir/include/config/auto.conf"
[[ -f "$config_file" ]] || config_file="$npc_dir/.config"
[[ -f "$stub_src" && -f "$config_file" ]] || {
    echo "找不到 stub 源文件或 Kconfig 输出: $stub_src / $config_file" >&2
    exit 2
}

read_cfg() {
    local key=$1 fallback=$2 value
    value=$(grep -E "^${key}=" "$config_file" | tail -1 | cut -d= -f2- || true)
    printf '%s' "${value:-$fallback}"
}

xlen=${XLEN:-$(read_cfg CONFIG_XLEN 64)}
mrom_base=$(read_cfg CONFIG_PMA_MROM_BASE 0x20000000)
flash_base=$(read_cfg CONFIG_PMA_FLASH_BASE 0x30000000)
psram_base=$(read_cfg CONFIG_PMA_PSRAM_BASE 0x80000000)

case "$xlen" in
    64)
        default_cross=riscv64-unknown-elf-
        march=rv64gc
        mabi=lp64d
        ;;
    32)
        default_cross=riscv32-unknown-elf-
        march=rv32imac
        mabi=ilp32
        ;;
    *) echo "XLEN 只支持 32/64，当前 $xlen" >&2; exit 2 ;;
esac
cross=${CROSS_COMPILE:-$default_cross}
cc="${cross}gcc"
objcopy="${cross}objcopy"
command -v "$cc" >/dev/null || { echo "找不到交叉编译器: $cc" >&2; exit 2; }
command -v "$objcopy" >/dev/null || { echo "找不到 objcopy: $objcopy" >&2; exit 2; }

tmpdir=$(mktemp -d "${TMPDIR:-/tmp}/soc-image.XXXXXX")
trap 'rm -rf "$tmpdir"' EXIT

"$cc" -march="$march" -mabi="$mabi" -mcmodel=medany \
    -nostdlib -nostartfiles -Wl,--no-relax \
    -Wl,-Ttext="$mrom_base" \
    -DMROM_BASE="$mrom_base" \
    -DFLASH_BASE="$flash_base" \
    -DPSRAM_BASE="$psram_base" \
    -o "$tmpdir/stub.elf" "$stub_src"
"$objcopy" -O binary "$tmpdir/stub.elf" "$tmpdir/stub.bin"

stub_size=$(stat -c %s "$tmpdir/stub.bin")
payload_size=$(stat -c %s "$payload")
if (( stub_size > 0xff0 )); then
    echo "stub 超过 0xff0 字节: $stub_size" >&2
    exit 2
fi
if (( payload_size > 0xffffffff )); then
    echo "payload 超过 32 位大小" >&2
    exit 2
fi

python3 - "$tmpdir/stub.bin" "$payload" "$output" "$payload_size" <<'PY'
import struct
import sys
from pathlib import Path

stub_path, payload_path, output_path, payload_size = sys.argv[1:]
size = int(payload_size)
stub = Path(stub_path).read_bytes()
if len(stub) > 0xff0:
    raise SystemExit("stub too large")
image = bytearray(0x1000)
image[:len(stub)] = stub
struct.pack_into("<I", image, 0xff0, size)
image.extend(Path(payload_path).read_bytes())
Path(output_path).write_bytes(image)
PY

echo "生成 $output: MROM stub $stub_size B @ $mrom_base, Flash payload $payload_size B @ $flash_base, PSRAM $psram_base"
