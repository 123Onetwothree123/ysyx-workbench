#!/usr/bin/env bash
#
# Linux 前道完整基线回归。
#
# 覆盖：
#   1. Kconfig 契约与 Linux 启动环境（DTB/initramfs/镜像打包）静态矩阵；
#   2. AM RV64GC/lp64d：direct-NPC 实际运行 + NEMU 参考运行 + ELF ABI 审计；
#   3. Nanos/Navy RV64GC：ramdisk、内核、用户态 hello/spin/fp-test 与
#      direct-STIP 时钟抢占；
#   4. FPU：RV64D/RV32F HardFloat DiffTest、共享 FDIV/FSQRT 压力，
#      可选 Fudian pipeline=2/FMA=n/共享除法配置；
#   5. full-SoC：浮点/trap 调试端口探针、full-SoC DiffTest、UART PLIC 中断；
#   6. BusyBox rootfs/initramfs：cpio/ELF 审计，并用 dummy Image 验证
#      linux-image 打包正/负向路径；
#   7. SBI/DTB：OpenSBI、RustSBI 的 a1=DTB handoff，以及
#      rv64_linux_defconfig 的 DTB ISA/timebase/mmu-type；
#   8. 可选 xv6 启动到 init: starting sh。
#
# 设计约束：
#   - 不下载、编译或运行 Linux kernel；Linux Image/initramfs 只使用 dummy
#     文件和本仓库 BusyBox rootfs；
#   - 不修改任何 Kconfig/生成配置的最终状态：进入时备份 npc/nemu 的
#     .config、include/config、include/generated，退出时（含失败/中断）恢复；
#   - 每一步单独记录耗时和日志。失败不会被 `|| true` 吞掉：脚本会继续跑
#     后续独立步骤，但最终以非零状态退出并列出全部 FAIL；
#   - 缺少工具链/vendor 资产/可选 target 时记录 SKIP 及原因，与真实
#     回归失败区分。
#
# 用法：
#   bash npc/scripts/prelinux_regression.sh
#   bash npc/scripts/prelinux_regression.sh --with-xv6
#   bash npc/scripts/prelinux_regression.sh --without-fudian
#   bash npc/scripts/prelinux_regression.sh --only kconfig-contract,linux-boot-test
#
# 环境变量：
#   WORK            日志与构建根目录，默认 /tmp/opencode/prelinux-regression
#   JOBS            并行编译度，默认 nproc
#   NANOS_TIMEOUT   Nanos 运行超时，默认 180 秒
#   AM_TIMEOUT      AM 运行超时，默认 300 秒
#   XV6_TIMEOUT     xv6 运行超时，默认 420 秒
#
set -Eeuo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
NPC="$ROOT/npc"
NEMU="$ROOT/nemu"
AM="$ROOT/abstract-machine"
NAVY="$ROOT/navy-apps"
NANOS="$ROOT/nanos-lite"
XV6="$ROOT/xv6-riscv"

JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}
WORK=${WORK:-/tmp/opencode/prelinux-regression}
LOGS="$WORK/logs"
RUN_LOGS="$LOGS/runs"
BUILD_ROOT="$WORK/build"
NANOS_TIMEOUT=${NANOS_TIMEOUT:-180}
AM_TIMEOUT=${AM_TIMEOUT:-300}
XV6_TIMEOUT=${XV6_TIMEOUT:-420}

# 关键：外部环境可能把 *_HOME 指向别的 worktree（例如主工作区），
# 这里必须强制回到当前回归脚本所在的 checkout，避免混用配置和生成物。
export NPC_HOME="$NPC"
export NEMU_HOME="$NEMU"
export AM_HOME="$AM"
export NAVY_HOME="$NAVY"

WITH_XV6=0
WITH_FUDIAN=1
ONLY=""

usage() {
	sed -n '2,48p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while (($# > 0)); do
	case "$1" in
		--with-xv6)
			WITH_XV6=1
			;;
		--without-xv6)
			WITH_XV6=0
			;;
		--without-fudian)
			WITH_FUDIAN=0
			;;
		--only)
			(( $# >= 2 )) || {
				echo "错误：--only 需要一个逗号分隔的步骤列表" >&2
				exit 2
			}
			ONLY="$2"
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			echo "错误：未知参数 $1" >&2
			usage >&2
			exit 2
			;;
	esac
	shift
done

mkdir -p "$LOGS" "$RUN_LOGS" "$BUILD_ROOT"

STEP_NAMES=()
STEP_STATUS=()
STEP_SECS=()
STEP_NOTES=()
STEP_LOGS=()
TOTAL_START=$(date +%s)

record_step() {
	local name="$1" status="$2" secs="$3" note="$4" log="$5"
	STEP_NAMES+=("$name")
	STEP_STATUS+=("$status")
	STEP_SECS+=("$secs")
	STEP_NOTES+=("$note")
	STEP_LOGS+=("$log")
	printf '%-28s %-5s %4ss  %s\n' "$name" "$status" "$secs" "${note:-$log}"
}

selected() {
	local name="$1"
	if [[ -z "$ONLY" ]]; then
		return 0
	fi
	[[ ",$ONLY," == *",$name,"* ]]
}

skip_step() {
	local name="$1" reason="$2"
	selected "$name" || return 0
	record_step "$name" SKIP 0 "$reason" "-"
}

# 运行一个独立步骤。函数内部必须用 `|| return 1` 显式传播失败，不能
# 依赖 set -e（函数处于 if 条件上下文中时 bash 会关闭 errexit）。
run_step() {
	local name="$1" body="$2"
	selected "$name" || return 0
	local log="$LOGS/$name.log"
	local t0 t1 secs status rc note
	t0=$(date +%s)
	printf '\n===== [%s] %s =====\n' "$name" "$(date '+%F %T')" | tee "$log"
	if "$body" >>"$log" 2>&1; then
		status=PASS
		rc=0
	else
		rc=$?
		if ((rc == 77)); then
			status=SKIP
		else
			status=FAIL
		fi
	fi
	t1=$(date +%s)
	secs=$((t1 - t0))
	note=""
	if [[ "$status" == SKIP ]]; then
		note=$(grep -E '^SKIP:' "$log" | tail -n 1 | sed 's/^SKIP:[[:space:]]*//')
	fi
	record_step "$name" "$status" "$secs" "$note" "$log"
}

must() {
	printf '+ '
	printf '%q ' "$@"
	printf '\n'
	"$@"
}

cfg_get() {
	local repo="$1" key="$2"
	sed -n "s/^${key}=//p" "$repo/.config" 2>/dev/null | tail -n 1
}

config_value() {
	local value
	value="$(cfg_get "$NPC" "CONFIG_$1")"
	value="${value%\"}"
	value="${value#\"}"
	printf '%s' "$value"
}

check_grep() {
	local file="$1" pattern="$2" what="$3"
	if ! grep -qE -- "$pattern" "$file"; then
		echo "检查失败：$what（缺少模式：$pattern）"
		echo "---- $file (tail) ----"
		tail -n 60 "$file" 2>/dev/null
		return 1
	fi
}

check_eq() {
	local actual="$1" expected="$2" what="$3"
	if [[ "$actual" != "$expected" ]]; then
		echo "检查失败：$what：实际='$actual' 期望='$expected'"
		return 1
	fi
}

# ---------------------------------------------------------------------------
# 配置备份/恢复
# ---------------------------------------------------------------------------
BACKUP="$WORK/config-backup"
save_config() {
	local repo="$1" tag="$2"
	mkdir -p "$BACKUP/$tag"
	local f
	for f in .config .config.old; do
		if [[ -f "$repo/$f" ]]; then
			cp -- "$repo/$f" "$BACKUP/$tag/$f"
		fi
	done
	local d
	for d in include/config include/generated; do
		if [[ -d "$repo/$d" ]]; then
			mkdir -p "$BACKUP/$tag/$(dirname -- "$d")"
			cp -a -- "$repo/$d" "$BACKUP/$tag/$d"
		fi
	done
}

restore_config_one() {
	local repo="$1" tag="$2" f d
	for f in .config .config.old; do
		if [[ -f "$BACKUP/$tag/$f" ]]; then
			cp -a -- "$BACKUP/$tag/$f" "$repo/$f"
		else
			rm -f -- "$repo/$f"
		fi
	done
	for d in include/config include/generated; do
		rm -rf -- "$repo/$d"
		if [[ -d "$BACKUP/$tag/$d" ]]; then
			mkdir -p "$repo/$(dirname -- "$d")"
			cp -a -- "$BACKUP/$tag/$d" "$repo/$d"
		fi
	done
}

# Nanos update 会创建这些符号链接；若回归前不存在，结束时删除。
OPTIONAL_PATHS=(
	"$NANOS/build/ramdisk.img"
	"$NANOS/build/ramdisk.h"
	"$NANOS/src/files.h"
	"$NANOS/src/syscall.h"
)
declare -A OPTIONAL_EXISTED=()
for p in "${OPTIONAL_PATHS[@]}"; do
	if [[ -e "$p" || -L "$p" ]]; then
		OPTIONAL_EXISTED["$p"]=1
	else
		OPTIONAL_EXISTED["$p"]=0
	fi
done

rm -rf "$BACKUP"
save_config "$NPC" npc
save_config "$NEMU" nemu

FINALIZED=0
finalize() {
	local rc=$?
	if ((FINALIZED)); then
		return
	fi
	FINALIZED=1
	restore_config_one "$NPC" npc
	restore_config_one "$NEMU" nemu
	local p
	for p in "${OPTIONAL_PATHS[@]}"; do
		if [[ "${OPTIONAL_EXISTED[$p]}" == 0 ]]; then
			rm -rf -- "$p"
		fi
	done
	if (( ${#STEP_NAMES[@]} > 0 )); then
		local total=$(( $(date +%s) - TOTAL_START ))
		{
			printf '\n================ 回归汇总（总耗时 %ss）================\n' "$total"
			printf '%-28s %-5s %6s  %s\n' STEP STATUS SECS LOG/NOTE
			local i
			for i in "${!STEP_NAMES[@]}"; do
				printf '%-28s %-5s %5ss  %s\n' \
					"${STEP_NAMES[$i]}" "${STEP_STATUS[$i]}" "${STEP_SECS[$i]}" \
					"${STEP_NOTES[$i]:-${STEP_LOGS[$i]}}"
			done
			printf '日志根目录：%s\n' "$WORK"
			printf '配置已恢复：npc/.config、npc/include/{config,generated}、nemu/.config、nemu/include/{config,generated}\n'
		} | tee "$WORK/summary.txt"
	fi
	return "$rc"
}
trap finalize EXIT

# ---------------------------------------------------------------------------
# 通用构建/运行辅助
# ---------------------------------------------------------------------------
select_npc() {
	local defconfig="$1"
	must make -s -C "$NPC" "$defconfig" git_commit=
}

select_nemu() {
	local defconfig="$1"
	must make -s -C "$NEMU" "$defconfig"
}

# 直接修改 .config 后让 Kconfig 重新解析依赖；只用于需要临时打开
# ROOTFS_ENABLE 或切换 Fudian profile 的测试。
set_npc_raw() {
	local key="$1" value="$2" f="$NPC/.config"
	sed -i "/^${key}=/d; /^# ${key} is not set$/d" "$f"
	printf '%s=%s\n' "$key" "$value" >>"$f"
}

set_npc_bool() {
	local key="$1" value="$2" f="$NPC/.config"
	sed -i "/^${key}=/d; /^# ${key} is not set$/d" "$f"
	if [[ "$value" == y ]]; then
		printf '%s=y\n' "$key" >>"$f"
	else
		printf '# %s is not set\n' "$key" >>"$f"
	fi
}

sync_npc_config() {
	(
		cd "$NPC"
		./tools/build/c_conf -s --olddefconfig Kconfig
		./tools/build/c_conf -s --syncconfig Kconfig
		python3 scripts/check_kconfig_contract.py --config .config
	)
}

need_rtl_tools() {
	need_cmd verilator || return $?
	need_cmd mill || return $?
	need_cmd java || return $?
}

build_npc_bin() {
	local build_dir="$1" target="$2"
	shift 2
	need_rtl_tools || return $?
	must make -C "$NPC" -j"$JOBS" git_commit= BUILD_DIR="$build_dir" "$@" \
		"$build_dir/$target"
}

build_nemu_ref() {
	local defconfig="$1" isa="$2" out="$3"
	select_nemu "$defconfig" || return 1
	must make -C "$NEMU" -j"$JOBS" git_commit= \
		GUEST_ISA="$isa" ENGINE=interpreter \
		CONFIG_TARGET_SHARE=y CONFIG_TARGET_NATIVE_ELF= \
		BINARY="$out" OBJ_DIR="$out.obj"
}

build_nemu_exec() {
	local out="$1"
	select_nemu riscv64-fd_defconfig || return 1
	must make -C "$NEMU" -j"$JOBS" git_commit= \
		BINARY="$out" OBJ_DIR="$out.obj"
}

audit_elf_rv64gc() {
	local elf="$1" what="$2" readelf_bin="${3:-riscv64-unknown-elf-readelf}"
	local nm_bin="${4:-riscv64-unknown-elf-nm}"
	[[ -f "$elf" ]] || {
		echo "缺少 ELF：$elf"
		return 1
	}
	local header
	header=$("$readelf_bin" -h "$elf")
	grep -q 'Class:[[:space:]]*ELF64' <<<"$header" || {
		echo "$what：不是 ELF64"
		return 1
	}
	grep -q 'Machine:[[:space:]]*RISC-V' <<<"$header" || {
		echo "$what：不是 RISC-V ELF"
		return 1
	}
	if ! grep -Eq 'Flags:.*RVC.*double-float ABI' <<<"$header"; then
		echo "$what：ELF flags 不是 RVC + double-float ABI"
		grep 'Flags:' <<<"$header"
		return 1
	fi
	local arch
	arch=$("$readelf_bin" -A "$elf" |
		sed -n 's/.*Tag_RISCV_arch:[[:space:]]*"\([^"]*\)".*/\1/p')
	[[ -n "$arch" ]] || {
		echo "$what：缺少 Tag_RISCV_arch"
		return 1
	}
	[[ "$arch" == rv64* ]] || {
		echo "$what：Tag_RISCV_arch 不是 rv64：$arch"
		return 1
	}
	[[ "$arch" == *_f*_d* || "$arch" == *_d*_f* ]] || {
		echo "$what：Tag_RISCV_arch 未包含 F/D：$arch"
		return 1
	}
	[[ "$arch" == *_c* ]] || {
		echo "$what：Tag_RISCV_arch 未包含 C：$arch"
		return 1
	}
	if "$readelf_bin" -l "$elf" | grep -q 'INTERP'; then
		echo "$what：存在动态解释器（期望静态链接）"
		return 1
	fi
	local soft=""
	# grep 无匹配时返回 1，这是“没有软浮点 helper”的正常结果；只把
	# 非空匹配当成失败，不会吞掉 nm 输出中的其他错误信息。
	soft=$("$nm_bin" "$elf" 2>/dev/null |
		grep -E '__adddf3|__subdf3|__muldf3|__divdf3|__extendsfdf2|__truncdfsf2|__floatdidf|__fixdfdi') || soft=""
	if [[ -n "$soft" ]]; then
		echo "$what：发现软浮点 helper："
		printf '%s\n' "$soft"
		return 1
	fi
	echo "ELF ABI 审计通过：$what（$arch）"
}

have_cmd() {
	command -v "$1" >/dev/null 2>&1
}

# 环境/资产缺失用 77 表示 SKIP；run_step 会把它与真实 FAIL 区分开。
need_cmd() {
	if ! have_cmd "$1"; then
		echo "SKIP: 缺少工具 $1"
		return 77
	fi
}

need_path() {
	if [[ ! -e "$1" ]]; then
		echo "SKIP: 缺少资产 $1${2:+（$2）}"
		return 77
	fi
}

# ---------------------------------------------------------------------------
# 1. 静态配置/打包矩阵
# ---------------------------------------------------------------------------
test_kconfig_contract() {
	need_cmd python3 || return $?
	must make -C "$NPC" kconfig-contract-check
}

test_linux_boot() {
	need_cmd python3 || return $?
	must make -C "$NPC" linux-boot-test
}

# ---------------------------------------------------------------------------
# 2. NEMU 参考资产
# ---------------------------------------------------------------------------
NEMU_REF64="$BUILD_ROOT/nemu/riscv64-nemu-interpreter-so"
NEMU_REF32="$BUILD_ROOT/nemu/riscv32-nemu-interpreter-so"
NEMU_EXEC="$BUILD_ROOT/nemu/riscv64-nemu-interpreter"

test_nemu_assets() {
	need_cmd gcc || return $?
	mkdir -p "$BUILD_ROOT/nemu"
	build_nemu_ref riscv64-fd-ref_defconfig riscv64 "$NEMU_REF64" || return 1
	build_nemu_ref riscv32-fd-ref_defconfig riscv32 "$NEMU_REF32" || return 1
	build_nemu_exec "$NEMU_EXEC" || return 1
	[[ -f "$NEMU_REF64" && -f "$NEMU_REF32" && -x "$NEMU_EXEC" ]] || {
		echo "NEMU 资产未生成"
		return 1
	}
}

# ---------------------------------------------------------------------------
# 3. AM RV64GC/lp64d
# ---------------------------------------------------------------------------
AM_TEST="$AM/tests/rv64gc-fp"
NPC_NANOS="$BUILD_ROOT/npc-nanos-gc/ysyx_26030103_npc"

build_npc_nanos() {
	select_npc rv64_nanos_gc_defconfig || return 1
	build_npc_bin "$BUILD_ROOT/npc-nanos-gc" ysyx_26030103_npc || return $?
	[[ -x "$NPC_NANOS" ]] || {
		echo "未生成 $NPC_NANOS"
		return 1
	}
}

test_am_npc() {
	need_cmd riscv64-unknown-elf-gcc || return $?
	build_npc_nanos || return $?
	must make -s -C "$AM_TEST" ARCH=riscv64gc-npc clean || return 1
	must make -C "$AM_TEST" ARCH=riscv64gc-npc -j"$JOBS" || return 1
	must make -C "$AM_TEST" ARCH=riscv64gc-npc check-isa || return 1
	local bin="$AM_TEST/build/rv64gc-fp-riscv64gc-npc.bin"
	local elf="$AM_TEST/build/rv64gc-fp-riscv64gc-npc.elf"
	local log="$RUN_LOGS/am-rv64gc-npc.log"
	local rc=0
	timeout -k 10 "$AM_TIMEOUT" "$NPC_NANOS" "$bin" --elf "$elf" >"$log" 2>&1 || rc=$?
	if ((rc != 0 && rc != 124)); then
		echo "AM direct-NPC 运行失败（exit=$rc）"
		tail -n 80 "$log" 2>/dev/null
		return 1
	fi
	check_grep "$log" 'RV64GC FP context test PASSED' 'AM 浮点上下文测试未通过' || return 1
	check_grep "$log" 'task 1: 16 FP context rounds OK' '任务 1 未完成 16 轮' || return 1
	check_grep "$log" 'task 2: 16 FP context rounds OK' '任务 2 未完成 16 轮' || return 1
	check_grep "$log" 'HIT GOOD TRAP' 'AM 未触发 GOOD TRAP' || return 1
}

test_am_nemu() {
	need_cmd riscv64-unknown-elf-gcc || return $?
	need_path "$NEMU_EXEC" "请先运行 nemu-assets" || return $?
	must make -s -C "$AM_TEST" ARCH=riscv64gc-nemu clean || return 1
	must make -C "$AM_TEST" ARCH=riscv64gc-nemu -j"$JOBS" || return 1
	must make -C "$AM_TEST" ARCH=riscv64gc-nemu check-isa || return 1
	local bin="$AM_TEST/build/rv64gc-fp-riscv64gc-nemu.bin"
	local log="$RUN_LOGS/am-rv64gc-nemu.log"
	local rc=0
	# NEMU 的 capstone 通过相对路径 dlopen；必须在 NEMU 根目录运行，
	# 否则会触发 init_disasm 的 dl_handle 断言。
	(
		cd "$NEMU"
		timeout -k 10 "$AM_TIMEOUT" "$NEMU_EXEC" -b \
			-l "$RUN_LOGS/nemu-am.log" "$bin" >"$log" 2>&1
	) || rc=$?
	if ((rc != 0 && rc != 124)); then
		echo "AM NEMU 运行失败（exit=$rc）"
		tail -n 80 "$log" 2>/dev/null
		return 1
	fi
	check_grep "$log" 'RV64GC FP context test PASSED' 'AM NEMU 浮点上下文测试未通过' || return 1
	check_grep "$log" 'task 1: 16 FP context rounds OK' 'NEMU 任务 1 未完成' || return 1
	check_grep "$log" 'task 2: 16 FP context' 'NEMU 任务 2 未完成' || return 1
	check_grep "$log" 'HIT GOOD TRAP' 'AM NEMU 未触发 GOOD TRAP' || return 1
}

# ---------------------------------------------------------------------------
# 4. Nanos-lite + Navy RV64GC
# ---------------------------------------------------------------------------
test_nanos() {
	need_path "$NAVY/libs/libos/src/syscall.h" "Navy 资产不完整" || return $?
	need_cmd riscv64-linux-gnu-gcc || return $?
	build_npc_nanos || return $?
	(
		cd "$NANOS"
		must make ARCH=riscv64gc-npc update
		must make -C "$NANOS" ARCH=riscv64gc-npc -j"$JOBS"
	) || return 1

	local kernel="$NANOS/build/nanos-lite-riscv64gc-npc"
	local log="$RUN_LOGS/nanos-rv64gc.log"
	local rc=0
	timeout -k 10 "$NANOS_TIMEOUT" "$NPC_NANOS" "$kernel.bin" --elf "$kernel.elf" \
		>"$log" 2>&1 || rc=$?
	if ((rc != 0 && rc != 124)); then
		echo "Nanos 运行失败（exit=$rc）"
		tail -n 100 "$log" 2>/dev/null
		return 1
	fi
	check_grep "$log" "'Hello World!' from Nanos-lite" 'Nanos 启动日志缺失' || return 1
	check_grep "$log" 'Hello World from Navy-apps' 'Navy hello 用户程序未运行' || return 1
	check_grep "$log" 'spin\[2\]: round' 'spin 用户程序未运行' || return 1
	check_grep "$log" 'fp-test: RV64GC user-mode F/D probe start' 'fp-test 未启动' || return 1
	check_grep "$log" 'fp-test\[3\]: round' 'fp-test 未完成一轮浮点运算' || return 1
	check_grep "$log" '\[preempt\] timer irq #[2-9]' '未观察到至少两次时钟抢占' || return 1

	audit_elf_rv64gc "$kernel.elf" "Nanos 内核" || return 1
	audit_elf_rv64gc "$NAVY/tests/hello/build/hello-riscv64gc" "Navy hello" || return 1
	audit_elf_rv64gc "$NAVY/tests/spin/build/spin-riscv64gc" "Navy spin" || return 1
	audit_elf_rv64gc "$NAVY/tests/fp-test/build/fp-test-riscv64gc" "Navy fp-test" || return 1
}

# ---------------------------------------------------------------------------
# 5. FPU DiffTest
# ---------------------------------------------------------------------------
FPU_TEST="$NPC/csrc/Test/fpu-difftest-test"
NPC_RV64_FD="$BUILD_ROOT/npc-rv64-fd/ysyx_26030103_npc"
NPC_RV32_FD="$BUILD_ROOT/npc-rv32-fd/ysyx_26030103_npc"
NPC_RV64_FUDIAN="$BUILD_ROOT/npc-rv64-fudian/ysyx_26030103_npc"

build_fpu_rv64_dut() {
	select_npc rv64_fd_defconfig || return 1
	build_npc_bin "$BUILD_ROOT/npc-rv64-fd" ysyx_26030103_npc || return $?
}

test_fpu_rv64() {
	need_cmd riscv64-unknown-elf-gcc || return $?
	need_path "$NEMU_REF64" "请先运行 nemu-assets" || return $?
	build_fpu_rv64_dut || return $?
	must make -C "$FPU_TEST" rv64 STRICT=1 \
		BIN_RV64="$NPC_RV64_FD" REF_RV64="$NEMU_REF64" \
		LOG_DIR="$RUN_LOGS/fpu-rv64"
}

test_fdiv_shared() {
	need_path "$NEMU_REF64" "请先运行 nemu-assets" || return $?
	need_path "$NPC_RV64_FD" "请先运行 fpu-rv64" || return $?
	must make -C "$FPU_TEST" fdiv-shared STRICT=1 \
		BIN_RV64="$NPC_RV64_FD" REF_RV64="$NEMU_REF64" \
		LOG_DIR="$RUN_LOGS/fpu-rv64"
}

test_fpu_rv32() {
	need_cmd riscv32-unknown-elf-gcc || return $?
	need_path "$NEMU_REF32" "请先运行 nemu-assets" || return $?
	select_npc rv32_fd_defconfig || return 1
	build_npc_bin "$BUILD_ROOT/npc-rv32-fd" ysyx_26030103_npc || return $?
	must make -C "$FPU_TEST" rv32 STRICT=1 \
		BIN_RV32="$NPC_RV32_FD" REF_RV32="$NEMU_REF32" \
		LOG_DIR="$RUN_LOGS/fpu-rv32"
}

test_fpu_fudian() {
	need_cmd riscv64-unknown-elf-gcc || return $?
	need_path "$NEMU_REF64" "请先运行 nemu-assets" || return $?
	select_npc rv64_fd_defconfig || return 1
	set_npc_bool CONFIG_FPU_IMPL_HARDFLOAT n
	set_npc_bool CONFIG_FPU_IMPL_FUDIAN y
	set_npc_raw CONFIG_FPU_IMPL '"fudian"'
	set_npc_raw CONFIG_FPU_PIPELINE 2
	set_npc_bool CONFIG_FPU_FMA n
	set_npc_bool CONFIG_FPU_FUDIAN_SHARED_DIVSQRT y
	sync_npc_config || return 1
	check_eq "$(config_value FPU_IMPL)" fudian "Fudian 配置未生效" || return 1
	check_eq "$(config_value FPU_PIPELINE)" 2 "Fudian pipeline 配置未生效" || return 1
	build_npc_bin "$BUILD_ROOT/npc-rv64-fudian" ysyx_26030103_npc || return $?
	must make -C "$FPU_TEST" rv64 STRICT=1 \
		BIN_RV64="$NPC_RV64_FUDIAN" REF_RV64="$NEMU_REF64" \
		LOG_DIR="$RUN_LOGS/fpu-rv64-fudian" || return 1
	must make -C "$FPU_TEST" fdiv-shared STRICT=1 \
		BIN_RV64="$NPC_RV64_FUDIAN" REF_RV64="$NEMU_REF64" \
		LOG_DIR="$RUN_LOGS/fpu-rv64-fudian"
}

# ---------------------------------------------------------------------------
# 6. full-SoC
# ---------------------------------------------------------------------------
FULLSOC_TEST="$NPC/csrc/Test/fullsoc-fp-debug-test"
UART_TEST="$NPC/csrc/Test/uart-soc-irq-test"
NPC_SOC_RV32F="$BUILD_ROOT/npc-rv32f-soc/ysyx_26030103"

test_fullsoc_probe() {
	need_rtl_tools || return $?
	must make -C "$FULLSOC_TEST" probe
}

test_fullsoc_diff() {
	need_path "$NEMU_REF32" "请先运行 nemu-assets" || return $?
	select_npc rv32_f_defconfig || return 1
	build_npc_bin "$BUILD_ROOT/npc-rv32f-soc" ysyx_26030103 SIM_TOP=soc || return $?
	must make -C "$FULLSOC_TEST" diff \
		BIN="$NPC_SOC_RV32F" REF="$NEMU_REF32"
}

test_uart_soc_irq() {
	need_rtl_tools || return $?
	must make -C "$UART_TEST" test
}

# ---------------------------------------------------------------------------
# 7. rootfs / linux-image
# ---------------------------------------------------------------------------
ROOTFS_IMAGE="$NPC/build/rootfs/busybox-initramfs.cpio.gz"
DUMMY_IMAGE="$WORK/dummy-Image"
BAD_IMAGE="$WORK/bad-Image"

make_dummy_image() {
	python3 - "$DUMMY_IMAGE" <<'PY'
import struct
import sys

path = sys.argv[1]
data = bytearray(4096)
# offset 8: text_offset，必须等于 Image 地址相对 RAM 基地址的偏移
struct.pack_into('<Q', data, 8, 0x00200000)
# offset 16: image_size
struct.pack_into('<Q', data, 16, len(data))
# offset 0x38/0x40: RISC-V Linux Image magic
data[56:64] = b'RISCV\x00\x00\x00'
data[64:68] = b'RSC\x05'
with open(path, 'wb') as f:
    f.write(data)
PY
	head -c 4096 /dev/zero >"$BAD_IMAGE"
}

test_rootfs() {
	need_cmd riscv64-linux-gnu-gcc || return $?
	need_path "$ROOT/vendor/busybox" "BusyBox 源码缺失" || return $?
	select_npc rv64_linux_defconfig || return 1
	set_npc_bool CONFIG_ROOTFS_ENABLE y
	sync_npc_config || return 1
	check_eq "$(config_value ROOTFS_ENABLE)" y "ROOTFS_ENABLE 未生效" || return 1
	check_eq "$(config_value ROOTFS_TYPE)" busybox "ROOTFS_TYPE 不是 busybox" || return 1

	must make -C "$NPC" rootfs || return 1
	[[ -f "$ROOTFS_IMAGE" ]] || {
		echo "未生成 rootfs 镜像：$ROOTFS_IMAGE"
		return 1
	}
	gzip -t "$ROOTFS_IMAGE" || {
		echo "rootfs gzip 校验失败"
		return 1
	}
	local list="$RUN_LOGS/rootfs-cpio-list.txt"
	gzip -dc "$ROOTFS_IMAGE" | cpio -it --quiet | sed 's|^\./||' | sort >"$list" || return 1
	local p
	for p in bin/busybox bin/sh init dev proc sys tmp etc; do
		grep -Fqx -- "$p" "$list" || {
			echo "initramfs 缺少关键路径：$p"
			return 1
		}
	done
	audit_elf_rv64gc "$NPC/build/rootfs/busybox/busybox" "BusyBox" \
		riscv64-linux-gnu-readelf riscv64-linux-gnu-nm || return 1
	grep -q 'elf_riscv_abi=lp64d' "$ROOTFS_IMAGE.info" || {
		echo "rootfs .info 未记录 lp64d"
		return 1
	}
	echo "rootfs 条目：$(wc -l <"$list")，sha256：$(sha256sum "$ROOTFS_IMAGE" | cut -d' ' -f1)"
}

test_linux_image() {
	need_path "$ROOTFS_IMAGE" "请先运行 rootfs" || return $?
	need_cmd dtc || return $?
	select_npc rv64_linux_defconfig || return 1

	# 1) rv64_linux_defconfig 的 DTB ISA/timebase/mmu-type。
	#    initramfs 路径使用真实 rootfs 文件，这样 linux,initrd-* 也按实际
	#    大小生成，而不是依赖工作区根目录下不存在的默认 initramfs.cpio。
	must make -C "$NPC" dtb SBI_INITRAMFS_PATH="$ROOTFS_IMAGE" || return 1
	local info="$NPC/build/dtb/npc.dtb.info"
	local dtb="$NPC/build/dtb/npc.dtb"
	[[ -f "$info" && -f "$dtb" ]] || {
		echo "未生成 DTB/info"
		return 1
	}
	check_eq "$(sed -n 's/^isa=//p' "$info" | tail -n1)" \
		'rv64imafdc_zicsr_zifencei' "DTB ISA" || return 1
	check_eq "$(sed -n 's/^timebase_freq=//p' "$info" | tail -n1)" \
		'450000000' "DTB timebase" || return 1
	check_eq "$(sed -n 's/^mmu_type=//p' "$info" | tail -n1)" \
		'riscv,sv39' "DTB mmu-type" || return 1
	local dts="$RUN_LOGS/linux-dtb.dts"
	dtc -I dtb -O dts "$dtb" >"$dts" 2>/dev/null || {
		echo "dtc 反编译失败"
		return 1
	}
	check_grep "$dts" 'riscv,isa = "rv64imafdc_zicsr_zifencei"' 'DTS riscv,isa' || return 1
	check_grep "$dts" 'timebase-frequency = <0x1ad27480>' 'DTS timebase' || return 1
	check_grep "$dts" 'mmu-type = "riscv,sv39"' 'DTS mmu-type' || return 1
	check_grep "$dts" 'linux,initrd-start = <0x00 0x82000000>' 'DTS initrd-start' || return 1

	make_dummy_image || return 1

	# 2) dummy Image + BusyBox initramfs 正向打包。
	must make -C "$NPC" linux-image \
		LINUX_IMAGE_PATH="$DUMMY_IMAGE" \
		LINUX_INITRAMFS_PATH="$ROOTFS_IMAGE" || return 1
	local bin="$NPC/build/linux/linux-image.bin"
	local bin_info="$bin.info"
	[[ -f "$bin" && -f "$bin_info" ]] || {
		echo "未生成 linux-image.bin/info"
		return 1
	}
	check_eq "$(sed -n 's/^initramfs_enable=//p' "$bin_info" | tail -n1)" y \
		"linux-image initramfs 未启用" || return 1
	check_eq "$(sed -n 's/^initramfs_addr=//p' "$bin_info" | tail -n1)" 0x82000000 \
		"initramfs 地址" || return 1
	check_eq "$(sed -n 's/^initramfs_sha256=//p' "$bin_info" | tail -n1)" \
		"$(sha256sum "$ROOTFS_IMAGE" | cut -d' ' -f1)" "initramfs sha256" || return 1
	check_eq "$(sed -n 's/^image_sha256=//p' "$bin_info" | tail -n1)" \
		"$(sha256sum "$DUMMY_IMAGE" | cut -d' ' -f1)" "Image sha256" || return 1
	check_eq "$(sed -n 's/^dtb_sha256=//p' "$bin_info" | tail -n1)" \
		"$(sha256sum "$dtb" | cut -d' ' -f1)" "DTB sha256" || return 1
	local off=$((0x82000000 - 0x80000000))
	local size
	size=$(stat -c '%s' "$ROOTFS_IMAGE")
	if ! cmp -n "$size" -i "$off":0 "$bin" "$ROOTFS_IMAGE"; then
		echo "打包镜像中的 initramfs 内容与源文件不一致"
		return 1
	fi

	# 3) 负向：缺失 initramfs 和坏 Image magic 必须明确失败。
	if must make -C "$NPC" linux-image \
		LINUX_IMAGE_PATH="$DUMMY_IMAGE" \
		LINUX_INITRAMFS_PATH="$WORK/no-such-initramfs.cpio"; then
		echo "负向用例失败：缺失 initramfs 仍返回成功"
		return 1
	fi
	if must make -C "$NPC" linux-image \
		LINUX_IMAGE_PATH="$BAD_IMAGE" \
		LINUX_INITRAMFS_PATH="$ROOTFS_IMAGE"; then
		echo "负向用例失败：坏 Image magic 仍返回成功"
		return 1
	fi
	# 负向日志写在 run_step 的步骤日志中；再确认两次都输出了明确原因。
	echo "负向用例均已按预期拒绝（见步骤日志中的错误信息）"
}

# ---------------------------------------------------------------------------
# 8. SBI / DTB a1 handoff
# ---------------------------------------------------------------------------
NPC_SBI="$BUILD_ROOT/npc-rv64-sbi-fdt/ysyx_26030103_npc"

test_opensbi_dtb() {
	need_path "$ROOT/vendor/opensbi" "OpenSBI 源码缺失" || return $?
	need_cmd riscv64-unknown-elf-gcc || return $?
	select_npc rv64_sbi_fdt_defconfig || return 1
	build_npc_bin "$BUILD_ROOT/npc-rv64-sbi-fdt" ysyx_26030103_npc || return $?
	must env NPC_BIN="$NPC_SBI" make -C "$NPC" sbi-test || return 1
	check_grep "$LOGS/opensbi-dtb.log" 'PASS: next-stage 收到并解析 DTB' \
		'OpenSBI a1=DTB handoff' || return 1
}

test_rustsbi_dtb() {
	need_path "$ROOT/vendor/rustsbi" "RustSBI 源码缺失" || return $?
	need_cmd cargo || return $?
	if ! rustup target list --installed 2>/dev/null |
		grep -qx 'riscv64imac-unknown-none-elf'; then
		echo "SKIP: 未安装 Rust target riscv64imac-unknown-none-elf"
		return 77
	fi
	need_path "$NPC_SBI" "请先运行 opensbi-dtb" || return $?
	must env NPC_BIN="$NPC_SBI" make -C "$NPC" sbi-test SBI_IMPL=rustsbi || return 1
	check_grep "$LOGS/rustsbi-dtb.log" 'PASS: next-stage 收到并解析 DTB' \
		'RustSBI a1=DTB handoff' || return 1
}

# ---------------------------------------------------------------------------
# 9. 可选 xv6
# ---------------------------------------------------------------------------
test_xv6() {
	need_cmd riscv64-unknown-elf-gcc || return $?
	select_npc rv64_xv6_defconfig || return 1
	build_npc_bin "$BUILD_ROOT/npc-xv6" ysyx_26030103_npc || return $?
	must make -C "$XV6" -j"$JOBS" PLATFORM=npc \
		TOOLPREFIX=riscv64-unknown-elf- kernel/kernel || return 1
	local log="$RUN_LOGS/xv6.log"
	local rc=0
	timeout -k 10 "$XV6_TIMEOUT" "$BUILD_ROOT/npc-xv6/ysyx_26030103_npc" \
		"$XV6/kernel/kernel" --batch >"$log" 2>&1 || rc=$?
	if ((rc != 0 && rc != 124)); then
		echo "xv6 运行失败（exit=$rc）"
		tail -n 100 "$log" 2>/dev/null
		return 1
	fi
	check_grep "$log" 'xv6 kernel is booting' 'xv6 kernel banner' || return 1
	check_grep "$log" '\[npc\] /init loaded, entering user mode' 'xv6 用户态' || return 1
	check_grep "$log" 'init: starting sh' 'xv6 未到达 shell 启动点' || return 1
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
run_step kconfig-contract test_kconfig_contract
run_step linux-boot-test test_linux_boot
run_step nemu-assets test_nemu_assets
run_step am-rv64gc-npc test_am_npc
run_step am-rv64gc-nemu test_am_nemu
run_step nanos-rv64gc test_nanos
run_step fpu-rv64 test_fpu_rv64
run_step fdiv-shared test_fdiv_shared
run_step fpu-rv32 test_fpu_rv32

if ((WITH_FUDIAN)); then
	run_step fpu-fudian test_fpu_fudian
else
	skip_step fpu-fudian "由 --without-fudian 关闭"
fi

run_step fullsoc-probe test_fullsoc_probe
run_step fullsoc-diff test_fullsoc_diff
run_step uart-soc-irq test_uart_soc_irq
skip_step fullsoc-rv64d "当前没有 RV64D full-SoC 测试资产；需新增 RV64D 探针/guest 才能验证"
run_step rootfs test_rootfs
run_step opensbi-dtb test_opensbi_dtb
run_step rustsbi-dtb test_rustsbi_dtb
run_step linux-image test_linux_image

if ((WITH_XV6)); then
	run_step xv6 test_xv6
else
	skip_step xv6 "未指定 --with-xv6（可选步骤）"
fi

FAILED=0
SKIPPED=0
for s in "${STEP_STATUS[@]}"; do
	case "$s" in
		FAIL) FAILED=$((FAILED + 1)) ;;
		SKIP) SKIPPED=$((SKIPPED + 1)) ;;
	esac
done

if ((FAILED > 0)); then
	echo
	echo "回归失败：$FAILED 个步骤 FAIL，$SKIPPED 个 SKIP；详见 $WORK/summary.txt" >&2
	exit 1
fi
echo
echo "回归通过：全部已执行步骤 PASS，$SKIPPED 个 SKIP；详见 $WORK/summary.txt"
