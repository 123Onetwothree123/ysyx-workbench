# ============================================================================
# 平台目标：SBI / Linux / U-Boot / RustSBI / rootfs
# ============================================================================

# ============================================================================
# M 模式 SBI 固件
#
# SBI_ENABLE 默认 n；关闭时下列目标会明确报错，不会静默跳过。
# 实现通过 SBI_IMPL 字符串分派到 scripts/build_<impl>.sh：
#   opensbi -> scripts/build_opensbi.sh
# 后续增加 RustSBI 时只需新增 Kconfig choice + scripts/build_rustsbi.sh，
# 本 Makefile 的目标逻辑不需要改。
# ============================================================================

# Linux 启动镜像选项。地址完全复用 SBI_NEXT_ADDR / SBI_FDT_ADDR：
# Image 没有独立地址符号，DTB 使用 SBI_FDT_ADDR。
LINUX_BOOT_ENABLE ?= $(or $(CONFIG_LINUX_BOOT_ENABLE),n)
LINUX_IMAGE_PATH ?= $(call unquote_config,$(CONFIG_LINUX_IMAGE_PATH))
LINUX_INITRAMFS_ENABLE ?= $(or $(CONFIG_LINUX_INITRAMFS_ENABLE),n)
LINUX_INITRAMFS_PATH ?= $(call unquote_config,$(CONFIG_LINUX_INITRAMFS_PATH))
LINUX_INITRAMFS_ADDR ?= $(or $(CONFIG_LINUX_INITRAMFS_ADDR),0x82000000)
LINUX_BOOTARGS ?= $(call unquote_config,$(CONFIG_LINUX_BOOTARGS))
LINUX_OUT_DIR ?= $(abspath $(BUILD_DIR)/linux)
LINUX_SBI_OUT_DIR ?= $(LINUX_OUT_DIR)/sbi
LINUX_DTB_OUT_DIR ?= $(LINUX_OUT_DIR)/dtb
LINUX_PACK_TOOL := $(abspath tools/build/pack_linux_image)
# 主构建直接调用 C++ 工具；shell 入口保留给 NEMU/直接调用。
LINUX_PACK_SCRIPT ?= $(LINUX_PACK_TOOL)

$(LINUX_PACK_TOOL): $(NPC_BUILD_TOOLS_STAMP)
	@cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/pack_linux_image" "$@"

pack-linux-tool: $(LINUX_PACK_TOOL)
.PHONY: pack-linux-tool

SBI_IMPL ?= $(or $(call remove_quote,$(CONFIG_SBI_IMPL)),opensbi)
SBI_PLATFORM ?= $(or $(call remove_quote,$(CONFIG_SBI_PLATFORM)),npc)
SBI_FW_BASE ?= $(or $(CONFIG_SBI_FW_BASE),0x80000000)
SBI_FW_SIZE ?= $(or $(CONFIG_SBI_FW_SIZE),0x00200000)
# RV32 的 FW_JUMP 目标必须 4 MiB 对齐，RV64 为 2 MiB。
SBI_XLEN ?= $(or $(CONFIG_XLEN),64)
SBI_NEXT_ADDR ?= $(or $(CONFIG_SBI_NEXT_ADDR),$(if $(filter 32,$(SBI_XLEN)),0x80400000,0x80200000))
SBI_BUILD_JOBS ?= $(or $(CONFIG_SBI_BUILD_JOBS),0)
SBI_FDT_ENABLE ?= $(or $(CONFIG_SBI_FDT_ENABLE),n)
SBI_PMU_ENABLE ?= $(or $(CONFIG_SBI_PMU_ENABLE),n)
SBI_RUSTSBI_PMU ?= $(or $(CONFIG_SBI_RUSTSBI_PMU),n)
SBI_RISCV_C ?= $(or $(CONFIG_RISCV_C),n)
SBI_FDT_PATH ?= $(or $(call unquote_config,$(CONFIG_SBI_FDT_PATH)),dts/npc.dts)
SBI_FDT_ADDR ?= $(or $(CONFIG_SBI_FDT_ADDR),0x80f00000)
# mtime 每个 CPU 周期 +1，实际增量频率 = SYNTH_FREQ(MHz) * 1,000,000。
# DTB timebase-frequency 与 OpenSBI NPC 平台的 mtime_freq 使用同一个值。
SBI_TIMEBASE_FREQ ?= $(shell expr $(SYNTH_FREQ) \* 1000000)
SBI_FDT_LINUX ?= $(LINUX_BOOT_ENABLE)
SBI_INITRAMFS_ENABLE ?= $(LINUX_INITRAMFS_ENABLE)
SBI_INITRAMFS_PATH ?= $(LINUX_INITRAMFS_PATH)
SBI_INITRAMFS_ADDR ?= $(LINUX_INITRAMFS_ADDR)
SBI_BOOTARGS ?= $(LINUX_BOOTARGS)
SBI_FDT_OUT_DIR ?= $(abspath $(BUILD_DIR)/dtb)
SBI_DTB_TOOL := $(abspath tools/build/build_dtb)
# 主构建直接调用 C++ 工具；shell 入口保留给 NEMU/直接调用。
SBI_DTB_SCRIPT ?= $(SBI_DTB_TOOL)

$(SBI_DTB_TOOL): $(NPC_BUILD_TOOLS_STAMP)
	@cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/build_dtb" "$@"

build-dtb-tool: $(SBI_DTB_TOOL)
.PHONY: build-dtb-tool
SBI_TOOLCHAIN_PREFIX ?= riscv64-unknown-elf-
# OpenSBI 固件的最小 ISA/ABI 跟随 CPU XLEN：RV32 用 ilp32，RV64 用 lp64。
# F/D/C 不需要出现在固件 ISA 中，Linux/用户态的 ISA 由 CPU 配置决定。
# 环境变量或命令行显式给出的 SBI_ISA/SBI_ABI 优先。
ifeq ($(strip $(SBI_XLEN)),32)
SBI_ISA_DEFAULT := rv32ima_zicsr_zifencei
SBI_ABI_DEFAULT := ilp32
else
SBI_ISA_DEFAULT := rv64ima_zicsr_zifencei
SBI_ABI_DEFAULT := lp64
endif
SBI_ISA ?= $(SBI_ISA_DEFAULT)
SBI_ABI ?= $(SBI_ABI_DEFAULT)
SBI_LD ?= ld.lld
SBI_VENDOR_DIR ?= $(abspath $(NPC_HOME)/../vendor/opensbi)
SBI_OUT_DIR ?= $(abspath $(BUILD_DIR)/$(SBI_IMPL))
SBI_BUILD_SCRIPT ?= $(abspath scripts/build_$(SBI_IMPL).sh)
SBI_TEST_SCRIPT ?= $(abspath scripts/test_$(SBI_IMPL).sh)

# 把配置一次传给实现脚本；脚本自身仍负责校验和明确报错。
SBI_ENV = env \
	SBI_ENABLE="$(or $(CONFIG_SBI_ENABLE),n)" \
	SBI_IMPL="$(SBI_IMPL)" \
	SBI_PLATFORM="$(SBI_PLATFORM)" \
	SBI_FW_BASE="$(SBI_FW_BASE)" \
	SBI_FW_SIZE="$(SBI_FW_SIZE)" \
	SBI_NEXT_ADDR="$(SBI_NEXT_ADDR)" \
	SBI_BUILD_JOBS="$(SBI_BUILD_JOBS)" \
	SBI_FDT_ENABLE="$(SBI_FDT_ENABLE)" \
	SBI_PMU_ENABLE="$(SBI_PMU_ENABLE)" \
	SBI_RUSTSBI_PMU="$(SBI_RUSTSBI_PMU)" \
	SBI_RISCV_C="$(SBI_RISCV_C)" \
	SBI_FDT_PATH="$(SBI_FDT_PATH)" \
	SBI_FDT_ADDR="$(SBI_FDT_ADDR)" \
	SBI_FDT_OUT_DIR="$(SBI_FDT_OUT_DIR)" \
	SBI_TIMEBASE_FREQ="$(SBI_TIMEBASE_FREQ)" \
	SBI_FDT_LINUX="$(SBI_FDT_LINUX)" \
	SBI_XLEN="$(SBI_XLEN)" \
	SBI_HAS_S="$(or $(CONFIG_HAS_S),n)" \
	SBI_HAS_PMP="$(or $(CONFIG_HAS_PMP),n)" \
	SBI_CACHE_PADDING="$(or $(CONFIG_CACHE_PADDING),0)" \
	SBI_INITRAMFS_ENABLE="$(SBI_INITRAMFS_ENABLE)" \
	SBI_INITRAMFS_PATH="$(SBI_INITRAMFS_PATH)" \
	SBI_INITRAMFS_ADDR="$(SBI_INITRAMFS_ADDR)" \
	SBI_BOOTARGS="$(SBI_BOOTARGS)" \
	PLIC_BASE="$(or $(CONFIG_PLIC_BASE),0x0c000000)" \
	PLIC_SIZE="$(or $(CONFIG_PLIC_SIZE),0x00300000)" \
	PLIC_SOURCES="$(or $(CONFIG_PLIC_SOURCES),32)" \
	UART_BASE="$(or $(CONFIG_PMA_UART_BASE),0x10000000)" \
	CLINT_BASE="$(or $(CONFIG_PMA_CLINT_BASE),0x02000000)" \
	NPC_RAM_BASE="$(NPC_RAM_BASE)" \
	NPC_RAM_SIZE="$(NPC_RAM_SIZE)" \
	SBI_TOOLCHAIN_PREFIX="$(SBI_TOOLCHAIN_PREFIX)" \
	SBI_ISA="$(SBI_ISA)" \
	SBI_ABI="$(SBI_ABI)" \
	SBI_LD="$(SBI_LD)" \
	SBI_VENDOR_DIR="$(SBI_VENDOR_DIR)" \
	SBI_OUT_DIR="$(SBI_OUT_DIR)" \
	SBI_PAYLOAD_PATH="$(SBI_PAYLOAD_PATH)" \
	NPC_HOME="$(NPC_HOME)" \
	WORKBENCH_HOME="$(abspath $(NPC_HOME)/..)"

# Linux 打包环境 = SBI 公共环境 + Linux 专属路径/参数。
LINUX_ENV = $(SBI_ENV) \
	LINUX_BOOT_ENABLE="$(LINUX_BOOT_ENABLE)" \
	LINUX_IMAGE_PATH="$(LINUX_IMAGE_PATH)" \
	LINUX_INITRAMFS_ENABLE="$(LINUX_INITRAMFS_ENABLE)" \
	LINUX_INITRAMFS_PATH="$(LINUX_INITRAMFS_PATH)" \
	LINUX_INITRAMFS_ADDR="$(LINUX_INITRAMFS_ADDR)" \
	LINUX_BOOTARGS="$(LINUX_BOOTARGS)" \
	LINUX_OUT_DIR="$(LINUX_OUT_DIR)"

# 校验开关和实现名。实现名会拼进脚本路径，必须限制字符集。
define sbi_check_enabled
	@set -eu; \
	if [ "$(or $(CONFIG_SBI_ENABLE),n)" != "y" ]; then \
		echo "错误：SBI_ENABLE=n，SBI 固件未启用。" >&2; \
		echo "      先运行 'make menuconfig'，或选择一个启用 SBI 的 defconfig（如 rv64gc_linux_defconfig / rv32imac_linux_defconfig）。" >&2; \
		exit 2; \
	fi; \
	if [ "$(SBI_IMPL)" = "none" ]; then \
		echo "错误：SBI_ENABLE=y 但没有选择 SBI 实现（SBI_IMPL=none）。" >&2; \
		exit 2; \
	fi; \
	case "$(SBI_IMPL)" in \
		''|*[!A-Za-z0-9_]*) \
			echo "错误：SBI_IMPL='$(SBI_IMPL)' 非法（只允许字母、数字、下划线）。" >&2; \
			exit 2;; \
	esac
endef

.PHONY: sbi opensbi sbi-test opensbi-test

sbi: $(SBI_DTB_TOOL)
	$(sbi_check_enabled)
	@set -eu; \
	if [ ! -f "$(SBI_BUILD_SCRIPT)" ]; then \
		echo "错误：SBI 实现 '$(SBI_IMPL)' 的构建脚本不存在：$(SBI_BUILD_SCRIPT)" >&2; \
		echo "      请在 Kconfig 中选择已实现的 SBI_IMPL，或补齐该脚本。" >&2; \
		exit 2; \
	fi; \
	$(SBI_ENV) "$(SBI_BUILD_SCRIPT)"

# 历史/任务要求的别名；实际实现由 SBI_IMPL 决定。
opensbi: sbi

# 测试目标：构建最小 S-mode payload + 固件，并在已构建的 direct-NPC 上运行。
sbi-test:
	$(sbi_check_enabled)
	@set -eu; \
	if [ ! -f "$(SBI_TEST_SCRIPT)" ]; then \
		echo "错误：SBI 实现 '$(SBI_IMPL)' 的测试脚本不存在：$(SBI_TEST_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(SBI_ENV) "$(SBI_TEST_SCRIPT)"

opensbi-test: sbi-test

# 单独生成设备树，便于检查 DTS 或给外部加载器使用。
# 即使 SBI_FDT_ENABLE=n 也允许显式生成（不会改变 SBI 启动行为）。
# 依赖 effective JSON，确保 .config 改变后不会继续使用旧的配置投影。
dtb: $(NPC_CONFIG_EFFECTIVE_JSON) $(SBI_DTB_TOOL)
	@$(SBI_ENV) "$(SBI_DTB_SCRIPT)"

dtb-clean:
	rm -rf $(SBI_FDT_OUT_DIR)

.PHONY: dtb dtb-clean

# 打包 Linux 启动镜像：SBI 固件 + 用户提供的 Image + 可选 initramfs + DTB。
# 本目标不会下载/编译 Linux；Image/initramfs 缺失时明确报错。
# 顺序很重要：先让 build_<impl>.sh 按当前 Kconfig/initramfs 生成并内嵌
# DTB，再由 C++ pack_linux_image 重新生成同一 DTB、校验固件内嵌哈希并打包。
linux-image: $(LINUX_PACK_TOOL) $(SBI_DTB_TOOL)
	$(sbi_check_enabled)
	@set -eu; \
	if [ "$(LINUX_BOOT_ENABLE)" != "y" ]; then \
		echo "错误：LINUX_BOOT_ENABLE=$(LINUX_BOOT_ENABLE)，Linux 启动镜像未启用。" >&2; \
		echo "      先运行 'make rv64_linux_defconfig' 或 'make menuconfig' 打开 Linux 启动镜像。" >&2; \
		exit 2; \
	fi; \
	if [ -z "$(LINUX_IMAGE_PATH)" ]; then \
		echo "错误：LINUX_IMAGE_PATH 为空；请提供用户自己的 Linux Image 路径。" >&2; \
		exit 2; \
	fi; \
	if [ "$(SBI_FDT_ENABLE)" != "y" ]; then \
		echo "错误：Linux 需要 a1=DTB，但 SBI_FDT_ENABLE=$(SBI_FDT_ENABLE)。" >&2; \
		echo "      请打开 CONFIG_SBI_FDT_ENABLE 后重试。" >&2; \
		exit 2; \
	fi; \
	if [ ! -f "$(LINUX_PACK_SCRIPT)" ]; then \
		echo "错误：找不到打包工具 $(LINUX_PACK_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	mkdir -p "$(LINUX_OUT_DIR)"; \
	$(SBI_ENV) \
		SBI_OUT_DIR="$(LINUX_SBI_OUT_DIR)" \
		SBI_FDT_OUT_DIR="$(LINUX_DTB_OUT_DIR)" \
		"$(SBI_BUILD_SCRIPT)"; \
	$(LINUX_ENV) \
		SBI_OUT_DIR="$(LINUX_SBI_OUT_DIR)" \
		SBI_FDT_OUT_DIR="$(LINUX_DTB_OUT_DIR)" \
		"$(LINUX_PACK_SCRIPT)"

linux-image-clean:
	rm -rf $(LINUX_OUT_DIR)

# 配置矩阵测试：dummy Image/initramfs + DTB 生成 + 打包负向测试。
# 不构建/运行 Linux，也不需要 NPC 仿真器。
# 测试器是纯 C++ 工具，通过兼容脚本调用 C++ DTB/pack 工具；
# 不再依赖 Python。
LINUX_BOOT_TEST_DIR := $(abspath tools/LinuxBootTest)
LINUX_BOOT_TEST_SOURCES := $(wildcard $(LINUX_BOOT_TEST_DIR)/*.cpp)
LINUX_BOOT_TEST_MODULE := $(LINUX_BOOT_TEST_DIR)/npc.linux_boot_test.ixx
LINUX_BOOT_TEST_CMAKE := $(LINUX_BOOT_TEST_DIR)/CMakeLists.txt
LINUX_BOOT_TEST_BUILD_DIR := $(abspath tools/build/LinuxBootTest)
LINUX_BOOT_TEST_STAMP := $(LINUX_BOOT_TEST_BUILD_DIR)/.built
LINUX_BOOT_TEST_BIN := $(abspath tools/build/linux_boot_test)
LINUX_BOOT_TEST_JOBS ?= $(shell nproc 2>/dev/null || echo 4)

$(LINUX_BOOT_TEST_STAMP): $(LINUX_BOOT_TEST_SOURCES) $(LINUX_BOOT_TEST_MODULE) $(LINUX_BOOT_TEST_CMAKE)
	@cmake -S "$(LINUX_BOOT_TEST_DIR)" -B "$(LINUX_BOOT_TEST_BUILD_DIR)" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER="$(CXX)"
	@cmake --build "$(LINUX_BOOT_TEST_BUILD_DIR)" -j $(LINUX_BOOT_TEST_JOBS)
	@touch $@

$(LINUX_BOOT_TEST_BIN): $(LINUX_BOOT_TEST_STAMP)
	@cp -f "$(LINUX_BOOT_TEST_BUILD_DIR)/linux_boot_test" "$@"

linux-boot-test: $(LINUX_BOOT_TEST_BIN) $(LINUX_PACK_TOOL) $(SBI_DTB_TOOL) $(NPC_CONFIG_GEN_BIN)
	$(LINUX_BOOT_TEST_BIN)

.PHONY: linux-image linux-image-clean linux-boot-test

# ============================================================================
# U-Boot EFI 启动镜像（NPC）
#
# 与 NEMU 共用 vendor/u-boot 的 nemu_riscv64_smode_defconfig：两者在
# S-mode 看到的 UART/CLINT/PLIC 地址和 SBI 接口相同，区别只在 DTB
# timebase（NPC 450MHz）与 RAM 大小。地址全部来自 Kconfig/Makefile，
# 不在脚本中另写一套。默认 64 MiB 布局与 NEMU 相同；128 MiB 配置把
# DTB 移到 0x87f00000，其余载荷地址保持不变。
# ============================================================================
NPC_UBOOT_ENABLE ?= $(or $(CONFIG_NPC_UBOOT_ENABLE),n)
NPC_UBOOT_OUT_DIR ?= $(abspath $(BUILD_DIR)/uboot)
NPC_UBOOT_BUILD_SCRIPT ?= $(abspath scripts/build_uboot_npc.sh)
NPC_UBOOT_DEFCONFIG ?= nemu_riscv64_smode_defconfig
NPC_UBOOT_EFI_APP_PATH ?= $(call unquote_config,$(CONFIG_NPC_UBOOT_EFI_APP_PATH))
NPC_UBOOT_INITRAMFS_PATH ?= $(call unquote_config,$(CONFIG_NPC_UBOOT_INITRAMFS_PATH))
NPC_UBOOT_KERNEL_PATH ?= $(call unquote_config,$(CONFIG_NPC_UBOOT_KERNEL_PATH))
NPC_UBOOT_EFI_APP_ADDR ?= $(or $(CONFIG_NPC_UBOOT_EFI_APP_ADDR),0x80600000)
NPC_UBOOT_INITRAMFS_ADDR ?= $(or $(CONFIG_NPC_UBOOT_INITRAMFS_ADDR),0x80800000)
NPC_UBOOT_KERNEL_ADDR ?= $(or $(CONFIG_NPC_UBOOT_KERNEL_ADDR),0x81000000)
NPC_UBOOT_BOOTARGS ?= $(or $(call unquote_config,$(CONFIG_NPC_UBOOT_BOOTARGS)),console=ttyS0,115200 earlycon=sbi no5lvl cpuidle.off=1 loglevel=7)
NPC_UBOOT_JOBS ?= $(or $(CONFIG_NPC_UBOOT_JOBS),0)
NPC_UBOOT_PACK_SRC := $(abspath tools/pack_uboot_image.cpp)
NPC_UBOOT_PACK_BIN := $(abspath tools/build/pack_uboot_image)
NPC_UBOOT_PACK_CXXFLAGS ?= -std=c++23 -O2 -Wall -Wextra

$(NPC_UBOOT_PACK_BIN): $(NPC_UBOOT_PACK_SRC)
	@mkdir -p $(@D)
	$(CXX) $(NPC_UBOOT_PACK_CXXFLAGS) -o $@ $<

NPC_UBOOT_ENV = $(SBI_ENV) \
	NPC_UBOOT_PACK_BIN="$(NPC_UBOOT_PACK_BIN)" \
	NPC_UBOOT_OUT_DIR="$(NPC_UBOOT_OUT_DIR)" \
	NPC_UBOOT_DEFCONFIG="$(NPC_UBOOT_DEFCONFIG)" \
	NPC_UBOOT_EFI_APP_PATH="$(NPC_UBOOT_EFI_APP_PATH)" \
	NPC_UBOOT_INITRAMFS_PATH="$(NPC_UBOOT_INITRAMFS_PATH)" \
	NPC_UBOOT_KERNEL_PATH="$(NPC_UBOOT_KERNEL_PATH)" \
	NPC_UBOOT_EFI_APP_ADDR="$(NPC_UBOOT_EFI_APP_ADDR)" \
	NPC_UBOOT_INITRAMFS_ADDR="$(NPC_UBOOT_INITRAMFS_ADDR)" \
	NPC_UBOOT_KERNEL_ADDR="$(NPC_UBOOT_KERNEL_ADDR)" \
	NPC_UBOOT_BOOTARGS="$(NPC_UBOOT_BOOTARGS)" \
	NPC_UBOOT_JOBS="$(NPC_UBOOT_JOBS)"

uboot-image: $(NPC_UBOOT_PACK_BIN) $(SBI_DTB_TOOL)
	@set -eu; \
	if [ "$(NPC_UBOOT_ENABLE)" != "y" ]; then \
		echo "错误：NPC_UBOOT_ENABLE=$(NPC_UBOOT_ENABLE)，U-Boot EFI 镜像未启用。" >&2; \
		echo "      先运行 'make rv64_linux_uboot_128m_defconfig' 或 'make menuconfig'。" >&2; \
		exit 2; \
	fi; \
	if [ ! -f "$(NPC_UBOOT_BUILD_SCRIPT)" ]; then \
		echo "错误：找不到 $(NPC_UBOOT_BUILD_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(NPC_UBOOT_ENV) "$(NPC_UBOOT_BUILD_SCRIPT)"

uboot-image-clean:
	rm -rf $(NPC_UBOOT_OUT_DIR)

.PHONY: uboot-image uboot-image-clean

# 显式构建 RustSBI 固件，不影响默认构建，也不与 make opensbi 共用产物。
# 脚本读取 Kconfig 参数；这里复用 SBI_ENV 传入 SBI_FW_*/SBI_FDT_*，
# 但把输出目录固定到 build/rustsbi，避免 SBI_IMPL=opensbi 时覆盖
# OpenSBI 的 fw.bin。
RUSTSBI_BUILD_SCRIPT := $(abspath scripts/build_rustsbi.sh)
RUSTSBI_ENV = $(SBI_ENV) \
	SBI_OUT_DIR="$(abspath $(BUILD_DIR)/rustsbi)" \
	RUSTSBI_OUT_DIR="$(abspath $(BUILD_DIR)/rustsbi)"
rustsbi: $(SBI_DTB_TOOL)
	@$(RUSTSBI_ENV) "$(RUSTSBI_BUILD_SCRIPT)"

rustsbi-clean:
	rm -rf $(BUILD_DIR)/rustsbi

.PHONY: rustsbi rustsbi-clean

# ============================================================================
# 根文件系统（BusyBox initramfs）
#
# ROOTFS_ENABLE=n 时 make rootfs 明确报错，不静默跳过。当前只实现
# BusyBox；Makefile 传入 ROOTFS_ENV，脚本在 npc/build/rootfs/ 下做 out-of-tree
# 构建，不修改 vendor/busybox。ELF 审计由 C++ 工具
# tools/BuildTools/audit_rootfs_elf.cpp 完成；Linux kernel 的下载、编译和启动不在
# 本目标范围内，rootfs 只产出可被内核加载的 initramfs。
# ============================================================================
ROOTFS_TYPE ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_TYPE)),none)
ROOTFS_OUT_DIR ?= $(abspath $(or $(call remove_quote,$(CONFIG_ROOTFS_OUT_DIR)),build/rootfs))
ROOTFS_BUSYBOX_BUILD_DIR ?= $(ROOTFS_OUT_DIR)/busybox
ROOTFS_TREE ?= $(ROOTFS_OUT_DIR)/tree
ROOTFS_IMAGE ?= $(ROOTFS_OUT_DIR)/busybox-initramfs.cpio.gz
ROOTFS_BUILD_SCRIPT ?= $(abspath scripts/build_rootfs_busybox.sh)
ROOTFS_BUSYBOX_PATH ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_PATH)),vendor/busybox)
ROOTFS_BUSYBOX_VERSION ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_VERSION)),1.36.1)
ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX)),riscv64-linux-gnu-)
ROOTFS_BUSYBOX_MARCH ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_MARCH)),rv64gc)
ROOTFS_BUSYBOX_MABI ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_BUSYBOX_MABI)),lp64d)
# auto.conf 只记录 y；缺失即表示 n，不能在这里回填 Kconfig 的 y 默认值。
ROOTFS_BUSYBOX_STATIC ?= $(or $(CONFIG_ROOTFS_BUSYBOX_STATIC),n)
ROOTFS_BUSYBOX_INIT_SCRIPT ?= $(or $(CONFIG_ROOTFS_BUSYBOX_INIT_SCRIPT),n)
# applet 列表本身含空格，不能使用按词匹配的 remove_quote。
ROOTFS_BUSYBOX_APPLETS ?= $(or $(subst ",,$(CONFIG_ROOTFS_BUSYBOX_APPLETS)),sh ls cat echo mount umount mkdir mknod ps kill sleep dmesg ifconfig ip vi grep sed awk find cp mv rm ln chmod chown df du free uname hostname reboot poweroff halt switch_root mdev insmod lsmod rmmod modprobe tar gzip gunzip wc head tail sort uniq tr cut printf test true false date id whoami env pwd touch sync init)
ROOTFS_BUSYBOX_JOBS ?= $(or $(CONFIG_ROOTFS_BUSYBOX_JOBS),0)
ROOTFS_FASTFETCH ?= $(or $(CONFIG_ROOTFS_FASTFETCH),y)
ROOTFS_FASTFETCH_PATH ?= $(or $(call remove_quote,$(CONFIG_ROOTFS_FASTFETCH_PATH)),build/fastfetch/fastfetch)
ROOTFS_ELF_AUDIT_TOOL := $(abspath tools/build/audit_rootfs_elf)

$(ROOTFS_ELF_AUDIT_TOOL): $(NPC_BUILD_TOOLS_STAMP)
	@cp -f "$(NPC_BUILD_TOOLS_BUILD_DIR)/audit_rootfs_elf" "$@"

audit-rootfs-elf-tool: $(ROOTFS_ELF_AUDIT_TOOL)
.PHONY: audit-rootfs-elf-tool

# 把配置通过环境传给实现脚本；脚本不再自行解析 .config。
ROOTFS_ENV = env \
	ROOTFS_ENABLE="$(or $(CONFIG_ROOTFS_ENABLE),n)" \
	ROOTFS_TYPE="$(ROOTFS_TYPE)" \
	ROOTFS_BUSYBOX="$(or $(CONFIG_ROOTFS_BUSYBOX),n)" \
	ROOTFS_BUSYBOX_PATH="$(ROOTFS_BUSYBOX_PATH)" \
	ROOTFS_BUSYBOX_VERSION="$(ROOTFS_BUSYBOX_VERSION)" \
	ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX="$(ROOTFS_BUSYBOX_TOOLCHAIN_PREFIX)" \
	ROOTFS_BUSYBOX_MARCH="$(ROOTFS_BUSYBOX_MARCH)" \
	ROOTFS_BUSYBOX_MABI="$(ROOTFS_BUSYBOX_MABI)" \
	ROOTFS_BUSYBOX_STATIC="$(ROOTFS_BUSYBOX_STATIC)" \
	ROOTFS_BUSYBOX_INIT_SCRIPT="$(ROOTFS_BUSYBOX_INIT_SCRIPT)" \
	ROOTFS_BUSYBOX_APPLETS="$(ROOTFS_BUSYBOX_APPLETS)" \
	ROOTFS_BUSYBOX_JOBS="$(ROOTFS_BUSYBOX_JOBS)" \
	ROOTFS_FASTFETCH="$(ROOTFS_FASTFETCH)" \
	ROOTFS_FASTFETCH_PATH="$(ROOTFS_FASTFETCH_PATH)" \
	ROOTFS_OUT_DIR="$(ROOTFS_OUT_DIR)" \
	ROOTFS_BUSYBOX_BUILD_DIR="$(ROOTFS_BUSYBOX_BUILD_DIR)" \
	ROOTFS_TREE="$(ROOTFS_TREE)" \
	ROOTFS_IMAGE="$(ROOTFS_IMAGE)" \
	ELF_AUDIT_BIN="$(ROOTFS_ELF_AUDIT_TOOL)" \
	NPC_HOME="$(NPC_HOME)" \
	WORKBENCH_HOME="$(abspath $(NPC_HOME)/..)"

define rootfs_check_enabled
	@set -eu; \
	if [ "$(or $(CONFIG_ROOTFS_ENABLE),n)" != "y" ]; then \
		echo "错误：ROOTFS_ENABLE=n，rootfs 未启用。" >&2; \
		echo "      先运行 'make menuconfig' 打开“根文件系统 (rootfs)”，或使用带 CONFIG_ROOTFS_ENABLE=y 的 defconfig。" >&2; \
		exit 2; \
	fi; \
	if [ "$(ROOTFS_TYPE)" != "busybox" ]; then \
		echo "错误：ROOTFS_ENABLE=y 但 ROOTFS_TYPE='$(ROOTFS_TYPE)'；当前只实现 BusyBox。" >&2; \
		exit 2; \
	fi
endef

.PHONY: rootfs rootfs-clean

rootfs: $(ROOTFS_ELF_AUDIT_TOOL)
	$(rootfs_check_enabled)
	@set -eu; \
	if [ ! -f "$(ROOTFS_BUILD_SCRIPT)" ]; then \
		echo "错误：rootfs 构建脚本不存在：$(ROOTFS_BUILD_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(ROOTFS_ENV) "$(ROOTFS_BUILD_SCRIPT)"

# 清理只删除 rootfs 输出目录（默认 build/rootfs），不触碰 vendor/busybox。
rootfs-clean:
	@set -eu; \
	if [ ! -f "$(ROOTFS_BUILD_SCRIPT)" ]; then \
		echo "错误：rootfs 构建脚本不存在：$(ROOTFS_BUILD_SCRIPT)" >&2; \
		exit 2; \
	fi; \
	$(ROOTFS_ENV) "$(ROOTFS_BUILD_SCRIPT)" --clean
