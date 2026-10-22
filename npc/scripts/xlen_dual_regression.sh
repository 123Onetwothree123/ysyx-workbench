#!/usr/bin/env bash
# csrc XLEN 双模式回归（RV32 / RV64）。
#
# 覆盖：
#   1. Kconfig 配置生成矩阵：所有 defconfig 的 CONFIG_XLEN 与 SoC 位宽；
#   2. 两种配置下编译 direct-NPC（32/64 编译矩阵）；
#   3. readelf-test / ftrace-test 的 ELF32/ELF64 单元测试；
#   4. xlen-dual-test：SDB p/x 高值、GPR/PC 64 位、mtrace 高地址、
#      DiffTest 在 RV64 的明确拒绝。
#
# 复用现有 Makefile/Kconfig 流程，不复制 arch-test 或 Nanos 大套件。
# 所有生成物放在 /tmp/opencode/xlen-dual 与各测试目录 build/ 下，不进入提交；
# 运行结束后恢复调用前的 .config 以及 include/config、include/generated。
set -Eeuo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
NPC="$ROOT/npc"
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
for file in .config .config.old; do
    if [[ -f "$NPC/$file" ]]; then
        cp "$NPC/$file" "$BACKUP/$file"
    fi
done
for dir in include/config include/generated; do
    if [[ -d "$NPC/$dir" ]]; then
        mkdir -p "$BACKUP/$(dirname -- "$dir")"
        cp -a "$NPC/$dir" "$BACKUP/$dir"
    fi
done
restore_config() {
    for file in .config .config.old; do
        if [[ -f "$BACKUP/$file" ]]; then
            cp "$BACKUP/$file" "$NPC/$file"
        else
            rm -f "$NPC/$file"
        fi
    done
    for dir in include/config include/generated; do
        rm -rf "$NPC/$dir"
        if [[ -d "$BACKUP/$dir" ]]; then
            mkdir -p "$NPC/$(dirname -- "$dir")"
            cp -a "$BACKUP/$dir" "$NPC/$dir"
        fi
    done
}
trap restore_config EXIT

config_value() {
    local key="$1"
    sed -n "s/^${key}=//p" "$NPC/.config" 2>/dev/null | tail -n 1
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

# ---- 1. 配置生成矩阵 -----------------------------------------------------
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

# ---- 2. 32/64 编译矩阵 ---------------------------------------------------
# 运行时观测功能通过 .config 打开；main direct 构建先关闭 DiffTest，
# 避免触发 difftest.cpp 当前针对 direct RAM 的 256 KiB 编译期约束。
enable_observability_config() {
    set_config_bool CONFIG_SDB y
    set_config_bool CONFIG_TRACE y
    set_config_bool CONFIG_MTRACE y
    set_config_bool CONFIG_ITRACE n
    set_config_bool CONFIG_FTRACE n
    set_config_bool CONFIG_PERF_STATS n
    set_config_bool CONFIG_PERF_SAVE n
    set_config_bool CONFIG_NVBOARD n
    set_config_bool CONFIG_DIFFTEST n
    sync_config
}

build_case() {
    local name="$1" defconfig="$2" expected_xlen="$3"
    local build_dir="$WORK/npc-$name"
    local bin="$build_dir/ysyx_26030103_npc"
    local log="$WORK/npc-$name-build.log"
    local diff_build="$WORK/npc-diff-$name"
    local diff_bin="$diff_build/ysyx_26030103_npc"
    local diff_log="$WORK/npc-diff-$name-build.log"

    log_step "2. 构建 $name（$defconfig，期望 XLEN=$expected_xlen）"
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

    # DiffTest 拒绝路径需要 CONFIG_DIFFTEST=y。当前 difftest.cpp 的
    # direct-RAM 编译期约束按 256 KiB 校验，而普通 direct 构建的镜像窗口
    # 宏是 64 MiB；这里仅为该拒绝测试覆盖宏，使“加载 REF 前拒绝”的路径
    # 可编译。测试不会真正执行 guest，也不会同步 REF 内存。
    log_step "2b. 构建 $name DiffTest 拒绝镜像（direct-NPC，CONFIG_DIFFTEST=y）"
    set_config_bool CONFIG_DIFFTEST y
    sync_config
    rm -rf "$diff_build"
    timeout "$BUILD_TIMEOUT" make -C "$NPC" -j"$JOBS" \
        BUILD_DIR="$diff_build" git_commit= \
        USER_CXXFLAGS='-std=c++26 -fmodules -DCONFIG_MSIZE=0x40000' \
        "$diff_bin" >"$diff_log" 2>&1 ||
        die "$name DiffTest 镜像构建失败（见 $diff_log）"
    [[ -x "$diff_bin" ]] || die "$name 没有生成 $diff_bin"

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

    log_step "4. $name 集成测试（SDB / mtrace / DiffTest 拒绝）"
    timeout 180 make -s -C "$NPC/csrc/Test/xlen-dual-test" \
        BUILD_DIR="$WORK/xlen-dual-$name" NPC_BIN="$bin" \
        NPC_DIFF_BIN="$diff_bin" test \
        >"$WORK/xlen-dual-$name.log" 2>&1 ||
        die "xlen-dual-test 在 $name 下失败（见 $WORK/xlen-dual-$name.log）"
    grep -Fq "[PASS]" "$WORK/xlen-dual-$name.log" ||
        die "xlen-dual-test 在 $name 下没有输出 PASS"
}

build_case rv32 nanos_defconfig 32
build_case rv64 rv64_soft_defconfig 64

printf '\nPASS: csrc XLEN 双模式回归（RV32 + RV64）。日志目录: %s\n' "$WORK"
