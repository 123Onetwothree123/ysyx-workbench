// SPDX-License-Identifier: BSD-2-Clause
/*
 * NPC（ysyx-workbench）OpenSBI 平台适配。
 *
 * 默认内存映射（UART/CLINT/PLIC 可由 npc Kconfig 覆盖）：
 *   UART  0x10000000  (8250 兼容，TX 轮询发送；NPC 模型也实现 RX FIFO)
 *   CLINT 0x02000000  (MTIMER；MSIP 未实现，不注册 IPI 设备)
 *   PLIC  0x0c000000  (单 hart，M context 0 / S context 1)
 *   RAM   0x80000000  (direct-NPC 默认 64 MiB)
 *
 * 本平台不解析设备树：上一级（NPC 复位/WR）不会传入有效 FDT，所有
 * 设备地址都是编译期常量。但平台显式接受 FDT：使用 FW_FDT_PATH/
 * FW_JUMP_FDT_ADDR 时，OpenSBI 通用固件会把内嵌 DTB 复制到
 * SBI_FDT_ADDR，并通过 fw_platform_init 的返回值/next_arg1 传给
 * Linux（a1=DTB 物理地址）。单 hart 时 OpenSBI 的 lottery、IPI、HSM
 * 都会退化到安全路径。
 */

#include <sbi/riscv_asm.h>
#include <sbi/riscv_encoding.h>
#include <sbi/sbi_const.h>
#include <sbi/sbi_ecall_interface.h>
#include <sbi/sbi_error.h>
#include <sbi/sbi_platform.h>

#include <sbi_utils/fdt/fdt_helper.h>
#include <sbi_utils/fdt/fdt_pmu.h>
#include <sbi_utils/irqchip/plic.h>
#include <sbi_utils/serial/uart8250.h>
#include <sbi_utils/timer/aclint_mtimer.h>

/* NPC direct-NPC / ysyxSoC 的外设窗口。 */
#ifndef NPC_UART_BASE
#define NPC_UART_BASE			0x10000000UL
#endif
#define NPC_UART_ADDR			NPC_UART_BASE
#define NPC_UART_INPUT_FREQ		10000000UL
#define NPC_UART_BAUDRATE		115200UL

#ifndef NPC_CLINT_BASE
#define NPC_CLINT_BASE			0x02000000UL
#endif
#define NPC_CLINT_ADDR			NPC_CLINT_BASE
/*
 * NPC 的 mtime 每个 CPU 周期 +1，实际增量频率等于综合目标频率
 * SYNTH_FREQ(MHz)。构建脚本通过 NPC_MTIME_FREQ 传入该值，并与 DTB
 * timebase-frequency 保持一致；不要再写死 10MHz。
 */
#ifndef NPC_MTIME_FREQ
#define NPC_MTIME_FREQ			450000000UL
#endif
#define NPC_ACLINT_MTIMER_FREQ		((unsigned long)NPC_MTIME_FREQ)

/*
 * PLIC 参数由 Kconfig 的 PLIC_BASE/PLIC_SIZE/PLIC_SOURCES 经
 * npc/scripts/build_opensbi.sh -> objects.mk -> platform-genflags-y 传入。
 * 直接在上游树构建时退回 NPC 默认值。
 */
#ifndef NPC_PLIC_BASE
#define NPC_PLIC_BASE			0x0c000000UL
#endif
#ifndef NPC_PLIC_SIZE
#define NPC_PLIC_SIZE			0x00300000UL
#endif
#ifndef NPC_PLIC_SOURCES
#define NPC_PLIC_SOURCES		32
#endif
/*
 * OpenSBI 的 num_src 表示“最大 source ID”，因此取
 * PLIC_SOURCES - 1；source 0 保留。初始化循环只访问 1..最大 ID，
 * 避免访问不存在的 priority 触发 SLVERR。
 */
#define NPC_PLIC_NUM_SOURCES		(NPC_PLIC_SOURCES - 1)
#define NPC_HART_COUNT			1

/*
 * 平台显式接受 FDT。
 *
 * NPC 的设备地址固定，不需要从 FDT 发现 UART/PLIC/CLINT；这里保留
 * 上一级或 FW_FDT_PATH 传入的 FDT 指针并原样返回，交给 OpenSBI 通用
 * 固件完成 FDT relocation，并最终作为 a1 传给 S 模式 next-stage。
 * 返回值会被 fw_base.S 写回 a1，因此不能在这里改成 0。
 */
unsigned long fw_platform_init(unsigned long arg0, unsigned long arg1,
			       unsigned long arg2, unsigned long arg3,
			       unsigned long arg4)
{
	(void)arg0;
	(void)arg2;
	(void)arg3;
	(void)arg4;
	return arg1;
}

/*
 * NPC 的 PLIC 同时兼容标准布局和早期简化布局：
 *   - M context 0：enable 0x2000，threshold/claim 0x200000；
 *   - S context 1：enable 0x2080，threshold/claim 0x201000。
 * OpenSBI 只需要 S context，因此映射到 context 1。
 */
static struct plic_data plic = {
	.unique_id = 0,
	.addr = NPC_PLIC_BASE,
	.size = NPC_PLIC_SIZE,
	.num_src = NPC_PLIC_NUM_SOURCES,
	.context_map = {
		[0] = { 0, 1 },
	},
};

/*
 * NPC 的 CLINT 采用标准 SiFive 布局：
 *   mtimecmp   0x02004000（每个 hart 8 字节）
 *   mtime      0x0200bff8
 * 注意 ACLINT_DEFAULT_* 是 ACLINT 设备的相对偏移，不是 CLINT 的布局；
 * 误用会让 mtimecmp 落到 0x02000000，CLINT 从设备返回 SLVERR。
 * 另外 NPC CLINT 未实现 MSIP，故本平台不注册 MSWI/IPI 设备。
 */
#define NPC_CLINT_MTIMECMP_OFFSET	0x4000UL
#define NPC_CLINT_MTIME_OFFSET		0xbff8UL

static struct aclint_mtimer_data mtimer = {
	.mtime_freq = NPC_ACLINT_MTIMER_FREQ,
	.mtime_addr = NPC_CLINT_ADDR + NPC_CLINT_MTIME_OFFSET,
	.mtime_size = ACLINT_DEFAULT_MTIME_SIZE,
	.mtimecmp_addr = NPC_CLINT_ADDR + NPC_CLINT_MTIMECMP_OFFSET,
	.mtimecmp_size = ACLINT_DEFAULT_MTIMECMP_SIZE,
	.first_hartid = 0,
	.hart_count = NPC_HART_COUNT,
	/*
	 * NPC 的 AXI Xbar 只接受单拍 32 位 CLINT 访问；RV64 的 64 位
	 * `sd` 会被判为多拍事务并返回 DECERR（xv6 移植也为此显式拆成
	 * 两个 32 位写）。因此这里必须关闭 64 位 MMIO，让 aclint_mtimer
	 * 走 mtimer_time_rd32/wr32。
	 */
	.has_64bit_mmio = false,
};

static int npc_early_init(bool cold_boot)
{
	int rc;

	if (!cold_boot)
		return 0;

	/* UART 只用于 OpenSBI 控制台；FDT 不参与设备发现。 */
	rc = uart8250_init(NPC_UART_ADDR, NPC_UART_INPUT_FREQ,
			   NPC_UART_BAUDRATE, 0, 1, 0, 0);
	if (rc)
		return rc;

	return 0;
}

static int npc_final_init(bool cold_boot)
{
	return 0;
}

static int npc_irqchip_init(void)
{
	return plic_cold_irqchip_init(&plic);
}

static int npc_timer_init(void)
{
	return aclint_mtimer_cold_init(&mtimer, NULL);
}

#ifdef CONFIG_FDT_PMU
static int npc_pmu_init(void)
{
	int rc;

	rc = fdt_pmu_setup(fdt_get_address());
	if (rc && rc != SBI_ENOENT)
		return rc;

	return 0;
}

static uint64_t npc_pmu_xlate_to_mhpmevent(uint32_t event_idx, uint64_t data)
{
	uint64_t value;

	if (event_idx == SBI_PMU_EVENT_RAW_IDX ||
	    event_idx == SBI_PMU_EVENT_RAW_V2_IDX) {
		value = data;
	} else {
		value = fdt_pmu_get_select_value(event_idx);
		if (!value)
			value = (uint64_t)event_idx;
	}

	return value;
}
#endif

const struct sbi_platform_operations platform_ops = {
	.early_init		= npc_early_init,
	.final_init		= npc_final_init,
	.irqchip_init		= npc_irqchip_init,
	.timer_init		= npc_timer_init,
#ifdef CONFIG_FDT_PMU
	.pmu_init		= npc_pmu_init,
	.pmu_xlate_to_mhpmevent	= npc_pmu_xlate_to_mhpmevent,
#endif
};

const struct sbi_platform platform = {
	.opensbi_version	= OPENSBI_VERSION,
	.platform_version	= SBI_PLATFORM_VERSION(0x0, 0x01),
	.name			= "npc",
	.features		= SBI_PLATFORM_DEFAULT_FEATURES,
	.hart_count		= NPC_HART_COUNT,
	.hart_stack_size	= SBI_PLATFORM_DEFAULT_HART_STACK_SIZE,
	.heap_size		= SBI_PLATFORM_DEFAULT_HEAP_SIZE(NPC_HART_COUNT),
	.platform_ops_addr	= (unsigned long)&platform_ops,
};
