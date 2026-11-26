#!/usr/bin/env bash
# 构建 RISCV_F=n / RISCV_D=n 的 direct-NPC，供 negative 探针使用。
#
# 不切换 npc/.config：F/D/FPU 后端通过 make 命令行覆盖，配置投影只写入
# 独立 BUILD_DIR。但 csrc/generated/npc_config.ixx 的路径在 NPC Makefile
# 里是固定的源码路径，构建仍会改写它，因此构建前后必须快照/恢复
# .config、include/config、include/generated、csrc/generated、src/generated。
# trap 保证构建失败或收到信号时也恢复，避免污染用户配置。
set -uo pipefail

fail() {
    echo "[FAIL] $*" >&2
    exit 1
}

[[ $# -eq 3 ]] || fail "用法: $0 <npc-dir> <negative-build-dir> <expected-binary>"

NPC_DIR="$1"
NEG_BUILD_DIR="$2"
NEG_BIN="$3"
[[ -d "$NPC_DIR" ]] || fail "NPC 源码目录不存在：$NPC_DIR"
[[ -f "$NPC_DIR/Makefile" && -d "$NPC_DIR/src" ]] ||
    fail "不是 NPC 源码目录：$NPC_DIR"

mkdir -p "$NEG_BUILD_DIR" || fail "无法创建独立构建目录：$NEG_BUILD_DIR"

# 与其它并发构建串行化；快照/恢复期间不能有第二个构建改写同一批源码生成物。
if command -v flock >/dev/null 2>&1; then
    exec 9>"$NEG_BUILD_DIR/.build.lock"
    flock 9 || fail "无法获取构建锁：$NEG_BUILD_DIR/.build.lock"
fi

SNAPSHOT_DIR="$NEG_BUILD_DIR/source-snapshot"
SNAPSHOT_PATHS=(
    .config
    .config.old
    include/config
    include/generated
    csrc/generated
    src/generated
)
RESTORED=0

restore_generated_artifacts() {
    [[ "$RESTORED" == 1 ]] && return 0
    RESTORED=1
    local rel
    if [[ -f "$SNAPSHOT_DIR/present.list" ]]; then
        while IFS= read -r rel; do
            rm -rf -- "$NPC_DIR/$rel"
            mkdir -p -- "$(dirname -- "$NPC_DIR/$rel")"
            cp -a -- "$SNAPSHOT_DIR/$rel" "$NPC_DIR/$rel" ||
                echo "[WARN] 恢复失败：$NPC_DIR/$rel" >&2
        done <"$SNAPSHOT_DIR/present.list"
    fi
    if [[ -f "$SNAPSHOT_DIR/absent.list" ]]; then
        while IFS= read -r rel; do
            rm -rf -- "$NPC_DIR/$rel"
        done <"$SNAPSHOT_DIR/absent.list"
    fi
}

trap restore_generated_artifacts EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

rm -rf -- "$SNAPSHOT_DIR"
mkdir -p -- "$SNAPSHOT_DIR"
: >"$SNAPSHOT_DIR/present.list"
: >"$SNAPSHOT_DIR/absent.list"
for rel in "${SNAPSHOT_PATHS[@]}"; do
    if [[ -e "$NPC_DIR/$rel" || -L "$NPC_DIR/$rel" ]]; then
        mkdir -p -- "$(dirname -- "$SNAPSHOT_DIR/$rel")"
        cp -a -- "$NPC_DIR/$rel" "$SNAPSHOT_DIR/$rel" ||
            fail "无法备份 $NPC_DIR/$rel"
        printf '%s\n' "$rel" >>"$SNAPSHOT_DIR/present.list"
    else
        printf '%s\n' "$rel" >>"$SNAPSHOT_DIR/absent.list"
    fi
done

echo "[INFO] 构建 no-F/D NPC：$NEG_BUILD_DIR"
make -C "$NPC_DIR" \
    BUILD_DIR="$NEG_BUILD_DIR" \
    CONFIG_RISCV_F=n \
    CONFIG_RISCV_D=n \
    CONFIG_FPU_IMPL=none \
    NPC_CONFIG_SCALA_DEFAULTS="$NEG_BUILD_DIR/generated/ConfigDefaults.scala" \
    git_commit= \
    "$NEG_BIN" ||
    fail "no-F/D NPC 构建失败"

[[ -x "$NEG_BIN" ]] || fail "构建结束但二进制不存在或不可执行：$NEG_BIN"

EFFECTIVE_JSON="$NEG_BUILD_DIR/generated/npc_config.effective.json"
[[ -f "$EFFECTIVE_JSON" ]] || fail "缺少配置投影：$EFFECTIVE_JSON"
grep -Eq '"RISCV_F"[[:space:]]*:[[:space:]]*"n"' "$EFFECTIVE_JSON" ||
    fail "负向构建没有关闭 RISCV_F：$EFFECTIVE_JSON"
grep -Eq '"RISCV_D"[[:space:]]*:[[:space:]]*"n"' "$EFFECTIVE_JSON" ||
    fail "负向构建没有关闭 RISCV_D：$EFFECTIVE_JSON"

echo "[PASS] 已生成 no-F/D NPC：$NEG_BIN"
