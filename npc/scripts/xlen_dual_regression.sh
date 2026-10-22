#!/usr/bin/env bash
# csrc XLEN 双模式回归（RV32 / RV64），含真实 NEMU DiffTest。
#
# 覆盖：
#   1. Kconfig 配置生成矩阵：所有 defconfig 的 CONFIG_XLEN 与 SoC 位宽；
#   2. RV32/RV64 NEMU REF 共享库构建；
#   3. 两种配置下编译 direct-NPC（CONFIG_DIFFTEST=y）；
#   4. readelf-test / ftrace-test / mtrace-test 的 ELF32/ELF64 单元测试；
#   5. xlen-dual-test：SDB p/x 高值、GPR/PC 64 位、mtrace 高地址、
#      RV32/RV64 DiffTest 正向比对，以及 RV64 拒绝旧 32 位 REF。
#
# 复用现有 Makefile/Kconfig 流程，不复制 arch-test 或 Nanos 大套件。
# 所有生成物放在 /tmp/opencode/xlen-dual 与各测试目录 build/ 下，不进入提交；
# 运行结束后恢复调用前的 NPC/NEMU .config 以及生成头文件。
set -Eeuo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
NPC="$ROOT/npc"
NEMU="$ROOT/nemu"
WORK=${WORK:-/tmp/opencode/xlen-dual}
JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}
BUILD_TIMEOUT=${BUILD_TIMEOUT:-900}

mkdir -p "$WORK"

log_step() { printf '\n===== %s =====\n' "$*"; }
die() { echo "错误: $*" >&2; exit 1; }

# ---- 保存/恢复 Kconfig 产物，避免测试污染工作区 -------------------------
BACKUP="$WORK/config-backup"
rm -rf "$BACKUP"
mkdir -p "$BACKUP"
backup_config() {
    local repo="$1" tag="$2"
    mkdir -p "$BACKUP/$tag"
    for file in .config .config.old; do
        if [[ -f "$repo/$file" ]]; then
            cp "$repo/$file" "$BACKUP/$tag/$file"
        fi
    done
    for dir in include/config include/generated; do
        if [[ -d "$repo/$dir" ]]; then
            mkdir -p "$BACKUP/$tag/$(dirname -- "$dir")"
            cp -a "$repo/$dir" "$BACKUP/$tag/$dir"
        fi
    done
}
restore_one() {
    local repo="$1" tag="$2"
    for file in .config .config.old; do
        if [[ -f "$BACKUP/$tag/$file" ]]; then
            cp "$BACKUP/$tag/$file" "$repo/$file"
        else
            rm -f "$repo/$file"
        fi
    done
    for dir in include/config include/generated; do
        rm -rf "$repo/$dir"
        if [[ -d "$BACKUP/$tag/$dir" ]]; then
            mkdir -p "$repo/$(dirname -- "$dir")"
            cp -a "$BACKUP/$tag/$dir" "$repo/$dir"
        fi
    done
}
backup_config "$NPC" npc
backup_config "$NEMU" nemu
restore_config() {
    restore_one "$NPC" npc
    restore_one "$NEMU" nemu
}
trap restore_config EXIT

config_value() {
    local key="$1" repo="${2:-$NPC}"
    sed -n "s/^${key}=//p" "$repo/.config" 2>/dev/null | tail -n 1
}

select_defconfig() {
    local defconfig="$1"
    timeout 120 make -s -C "$NPC" "$defconfig" \
        >"$WORK/config-$defconfig.log" 2>&1 ||
        die "生成 $defconfig 失败（见 $WORK/config-$defconfig.log）"
}

# 在当前 .config 上设置布尔项，然后让 Kconfig 重新解析依赖并导出 auto.conf。
# 测试只读 Kconfig 产物，不再通过 make 命令行另开一套功能开关。
set_config_bool() {
    local key="$1" value="$2"
    sed -i "/^${key}=/d; /^# ${key} is not set/d" "$NPC/.config"
    if [[ "$value" == y ]]; then
        printf '%s=y\n' "$key" >>"$NPC/.config"
    else
        printf '# %s is not set\n' "$key" >>"$NPC/.config"
    fi
}

sync_config() {
    ( cd "$NPC" &&
      ./tools/build/c_conf -s --olddefconfig Kconfig &&
      ./tools/build/c_conf -s --syncconfig Kconfig ) \
        >"$WORK/config-sync.log" 2>&1 ||
        die "Kconfig --olddefconfig/--syncconfig 失败（见 $WORK/config-sync.log）"
}

# ---- 1. 构建 RV32/RV64 NEMU REF -----------------------------------------
build_nemu_ref() {
    local isa="$1" out="$2" log="$3"
    log_step "构建 NEMU REF：$isa"
    (
        cd "$NEMU"
        export NEMU_HOME=$PWD
        if [[ "$isa" == riscv64 ]]; then
            make riscv64-ref_defconfig >"$log.config" 2>&1
        else
            make riscv32_defconfig >"$log.config" 2>&1
            # NEMU 的 difftest_init 不调用 init_disasm；带 ITRACE 的 REF
            # 会在 difftest_exec 内调用空 disassemble 指针。REF 只需要
            # 执行/状态 ABI，因此关闭所有 trace 功能。
            sed -i \
                -e 's/^CONFIG_TRACE=y/# CONFIG_TRACE is not set/' \
                -e 's/^CONFIG_ITRACE=y/# CONFIG_ITRACE is not set/' \
                -e 's/^CONFIG_FTRACE=y/# CONFIG_FTRACE is not set/' \
                -e 's/^CONFIG_IRINGBUF=y/# CONFIG_IRINGBUF is not set/' \
                .config
            ./tools/kconfig/build/conf -s --olddefconfig Kconfig >>"$log.config" 2>&1
            ./tools/kconfig/build/conf -s --syncconfig Kconfig >>"$log.config" 2>&1
        fi
        make -j"$JOBS" GUEST_ISA="$isa" ENGINE=interpreter \
            CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF= \
            BINARY="$out" OBJ_DIR="$WORK/nemu-obj-$isa" >>"$log" 2>&1
    ) || die "NEMU $isa REF 构建失败（见 $log / $log.config）"
    [[ -f "$out" ]] || die "NEMU $isa REF 未生成: $out"
}

# ---- 2. Kconfig 配置生成矩阵 --------------------------------------------
log_step "1. Kconfig 配置生成矩阵（CONFIG_XLEN / SoC）"
DEFCONFIGS=(mmu_default_defconfig nanos_defconfig rv64_mmu_defconfig \
            rv64_soft_defconfig rv64_sv48_defconfig)
for defconfig in "${DEFCONFIGS[@]}"; do
    select_defconfig "$defconfig"
    xlen=$(config_value CONFIG_XLEN)
    [[ -n "$xlen" ]] || xlen=32
    soc_xlen=$(config_value CONFIG_SOC_XLEN)
    [[ -n "$soc_xlen" ]] || soc_xlen=32
    soc_follow=$(config_value CONFIG_SOC_XLEN_FOLLOW_CPU)
    [[ -n "$soc_follow" ]] || soc_follow=n
    case "$defconfig:$xlen" in
        mmu_default_defconfig:32|nanos_defconfig:32|\
        rv64_mmu_defconfig:64|rv64_soft_defconfig:64|rv64_sv48_defconfig:64)
            ;;
        *)
            die "$defconfig 生成了意外的 CONFIG_XLEN=$xlen"
            ;;
    esac
    [[ "$soc_xlen" == 32 || "$soc_xlen" == 64 ]] ||
        die "$defconfig 生成了非法 CONFIG_SOC_XLEN=$soc_xlen"
    grep -qx "CONFIG_XLEN=$xlen" "$NPC/include/config/auto.conf" ||
        die "$defconfig 的 auto.conf 没有导出 CONFIG_XLEN=$xlen"
    printf '  %-28s XLEN=%s SOC_XLEN=%s FOLLOW=%s\n' \
        "$defconfig" "$xlen" "$soc_xlen" "$soc_follow"
done

REF32="$WORK/riscv32-nemu-interpreter-so"
REF64="$WORK/riscv64-nemu-interpreter-so"
build_nemu_ref riscv32 "$REF32" "$WORK/nemu-rv32-build.log"
build_nemu_ref riscv64 "$REF64" "$WORK/nemu-rv64-build.log"
printf '  REF32: %s (%s bytes)\n' "$REF32" "$(stat -c %s "$REF32")"
printf '  REF64: %s (%s bytes)\n' "$REF64" "$(stat -c %s "$REF64")"

# ---- 3. 32/64 编译矩阵 ---------------------------------------------------
# 运行时观测功能通过 .config 打开；同一镜像同时用于 SDB/mtrace 与 DiffTest。
enable_observability_config() {
    set_config_bool CONFIG_SDB y
    set_config_bool CONFIG_TRACE y
    set_config_bool CONFIG_MTRACE y
    set_config_bool CONFIG_ITRACE n
    set_config_bool CONFIG_FTRACE n
    set_config_bool CONFIG_PERF_STATS n
    set_config_bool CONFIG_PERF_SAVE n
    set_config_bool CONFIG_NVBOARD n
    set_config_bool CONFIG_DIFFTEST y
    sync_config
}

build_case() {
    local name="$1" defconfig="$2" expected_xlen="$3" ref_so="$4"
    local build_dir="$WORK/npc-$name"
    local bin="$build_dir/ysyx_26030103_npc"
    local log="$WORK/npc-$name-build.log"

    log_step "2. 构建 $name（$defconfig，期望 XLEN=$expected_xlen，DIFFTEST=y）"
    select_defconfig "$defconfig"
    xlen=$(config_value CONFIG_XLEN)
    [[ -n "$xlen" ]] || xlen=32
    [[ "$xlen" == "$expected_xlen" ]] ||
        die "$defconfig 的 CONFIG_XLEN=$xlen，期望 $expected_xlen"
    enable_observability_config

    rm -rf "$build_dir"
    timeout "$BUILD_TIMEOUT" make -C "$NPC" -j"$JOBS" \
        BUILD_DIR="$build_dir" git_commit= "$bin" >"$log" 2>&1 ||
        die "$name 构建失败（见 $log）"
    [[ -x "$bin" ]] || die "$name 没有生成 $bin"

    log_step "3. $name 单元测试（readelf/ftrace ELF$([[ "$xlen" == 64 ]] && echo 64 || echo 32)）"
    timeout 300 make -s -C "$NPC/csrc/Test/readelf-test" \
        BUILD_DIR="$WORK/readelf-$name" test \
        >"$WORK/readelf-$name.log" 2>&1 ||
        die "readelf-test 在 $name 下失败（见 $WORK/readelf-$name.log）"
    timeout 300 make -s -C "$NPC/csrc/Test/ftrace-test" \
        BUILD_DIR="$WORK/ftrace-$name" test \
        >"$WORK/ftrace-$name.log" 2>&1 ||
        die "ftrace-test 在 $name 下失败（见 $WORK/ftrace-$name.log）"
    timeout 300 make -s -C "$NPC/csrc/Test/mtrace-test" \
        BUILD_DIR="$WORK/mtrace-$name" test \
        >"$WORK/mtrace-$name.log" 2>&1 ||
        die "mtrace-test 在 $name 下失败（见 $WORK/mtrace-$name.log）"

    log_step "4. $name 集成测试（SDB / mtrace / DiffTest 正反向）"
    timeout 240 make -s -C "$NPC/csrc/Test/xlen-dual-test" \
        BUILD_DIR="$WORK/xlen-dual-$name" NPC_BIN="$bin" REF_SO="$ref_so" test \
        >"$WORK/xlen-dual-$name.log" 2>&1 ||
        die "xlen-dual-test 在 $name 下失败（见 $WORK/xlen-dual-$name.log）"
    grep -Fq "[PASS] difftest" "$WORK/xlen-dual-$name.log" ||
        die "xlen-dual-test 在 $name 下没有通过 DiffTest 正向用例"
}

build_case rv32 nanos_defconfig 32 "$REF32"
build_case rv64 rv64_soft_defconfig 64 "$REF64"

printf '\nPASS: csrc XLEN 双模式回归（RV32 + RV64，含 DiffTest）。日志目录: %s\n' "$WORK"
