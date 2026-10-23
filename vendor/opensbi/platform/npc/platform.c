// SPDX-License-Identifier: BSD-2-Clause
/*
 * NPC（ysyx-workbench）OpenSBI 平台适配。
 *
 * 固定内存映射：
 *   UART  0x10000000  (8250 兼容，只实现 TX)
 *   CLINT 0x02000000  (MTIMER；MSIP 未实现，不注册 IPI 设备)
 *   PLIC  0x0c000000  (单 hart，M context 0 / S context 1)
 *   RAM   0x80000000  (direct-NPC 默认 64 MiB)
 *
 * 本平台不解析设备树：上一级（NPC 复位）不会传入有效 FDT，所有设备
 * 地址都是编译期常量。单 hart 时 OpenSBI 的 lottery、IPI、HSM 都会
 * 退化到安全路径。
 */

#include <sbi/riscv_asm.h>
#include <sbi/riscv_encoding.h>
#include <sbi/sbi_const.h>
#include <sbi/sbi_platform.h>

#include <sbi_utils/irqchip/plic.h>
#include <sbi_utils/serial/uart8250.h>
#include <sbi_utils/timer/aclint_mtimer.h>

/* NPC direct-NPC / ysyxSoC 的固定外设窗口。 */
#define NPC_UART_ADDR			0x10000000UL
#define NPC_UART_INPUT_FREQ		10000000UL
#define NPC_UART_BAUDRATE		115200UL

#define NPC_CLINT_ADDR			0x02000000UL
#define NPC_ACLINT_MTIMER_FREQ		10000000UL

#define NPC_PLIC_ADDR			0x0c000000UL
#define NPC_PLIC_SIZE			0x04000000UL
/*
 * NPC 的 PLIC 共 32 个 source 编号（0..31），其中 0 保留。
 * OpenSBI 的 num_src 表示“最大 source ID”，因此填 31，初始化循环
 * 1..31，避免访问 source 32 的 priority（0x0c000080）触发 SLVERR。
 */
#define NPC_PLIC_NUM_SOURCES		31
#define NPC_HART_COUNT			1

/*
 * NPC 的 PLIC 同时兼容标准布局和早期简化布局：
 *   - M context 0：enable 0x2000，threshold/claim 0x200000；
 *   - S context 1：enable 0x2080，threshold/claim 0x201000。
 * OpenSBI 只需要 S context，因此映射到 context 1。
 */
static struct plic_data plic = {
	.unique_id = 0,
	.addr = NPC_PLIC_ADDR,
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

const struct sbi_platform_operations platform_ops = {
	.early_init		= npc_early_init,
	.final_init		= npc_final_init,
	.irqchip_init		= npc_irqchip_init,
	.timer_init		= npc_timer_init,
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
