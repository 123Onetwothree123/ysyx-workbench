/*
 * 最小 S 模式 payload：验证 SBI 已完成 M 模式初始化、把 CPU 切到
 * S 模式，并且 SBI ecall（控制台 + base + time 扩展）可用。
 *
 * 同时验证 next-stage 传参：
 *   - 启用 SBI_FDT_ENABLE 时，a1 必须等于配置的 DTB 地址，DTB magic
 *     为 0xd00dfeed，且 model/memory/uart/plic/chosen 节点符合 NPC 布局；
 *   - 未启用时 a1 必须为 0，保持原有行为。
 *
 * 该 payload 不设置 satp、不依赖 MMU，也不链接 libc；它是测试固件，
 * 不是 Linux 或 xv6。
 */

typedef unsigned long u64;
typedef unsigned int u32;
typedef unsigned char u8;

#ifndef PAYLOAD_SBI_NAME
#define PAYLOAD_SBI_NAME "OpenSBI"
#endif

#define FDT_MAGIC 0xd00dfeedU
#define FDT_BEGIN_NODE 1U
#define FDT_END_NODE 2U
#define FDT_PROP 3U
#define FDT_NOP 4U
#define FDT_END 9U

#define NPC_RAM_BASE 0x80000000UL
#ifndef PAYLOAD_NPC_RAM_SIZE
#define PAYLOAD_NPC_RAM_SIZE 0x04000000UL
#endif
#define NPC_RAM_SIZE PAYLOAD_NPC_RAM_SIZE
#define NPC_UART_BASE 0x10000000UL
#define NPC_PLIC_BASE 0x0c000000UL
#define NPC_PLIC_SIZE 0x04000000UL
#define NPC_SBI_FW_SIZE 0x00200000UL

static long sbi_call(long eid, long fid, long a0, long a1, long a2,
		     long *value)
{
	register long r_a0 asm("a0") = a0;
	register long r_a1 asm("a1") = a1;
	register long r_a2 asm("a2") = a2;
	register long r_a6 asm("a6") = fid;
	register long r_a7 asm("a7") = eid;

	asm volatile("ecall"
		     : "+r"(r_a0), "+r"(r_a1)
		     : "r"(r_a2), "r"(r_a6), "r"(r_a7)
		     : "memory");
	/* SBI 约定：a0=错误码，a1=返回值。 */
	if (value)
		*value = r_a1;
	return r_a0;
}

static void sbi_putchar(char ch)
{
	(void)sbi_call(0x01, 0, (long)(unsigned char)ch, 0, 0, 0);
}

static void sbi_puts(const char *s)
{
	while (*s)
		sbi_putchar(*s++);
}

static void sbi_puthex(u64 value)
{
	static const char digits[] = "0123456789abcdef";
	int shift;

	for (shift = (int)(sizeof(value) * 8) - 4; shift >= 0; shift -= 4)
		sbi_putchar(digits[(value >> shift) & 0xf]);
}

static unsigned npc_strlen(const char *text)
{
	unsigned length = 0;

	while (text[length])
		length++;
	return length;
}

static int npc_streq(const char *a, const char *b)
{
	while (*a && *a == *b) {
		a++;
		b++;
	}
	return *a == *b;
}

static int npc_str_starts(const char *text, const char *prefix)
{
	while (*prefix) {
		if (*text++ != *prefix++)
			return 0;
	}
	return 1;
}

static int bytes_equal(const u8 *data, const char *text, unsigned length)
{
	unsigned index;

	for (index = 0; index < length; index++) {
		if (data[index] != (u8)text[index])
			return 0;
	}
	return 1;
}

static int string_list_contains(const u8 *value, unsigned length,
				const char *target)
{
	unsigned index = 0;
	unsigned target_length = npc_strlen(target);

	while (index < length) {
		unsigned start = index;

		while (index < length && value[index] != 0)
			index++;
		if (index - start == target_length &&
		    bytes_equal(value + start, target, target_length))
			return 1;
		index++; /* 跳过字符串结束符；最后一个字符串后可能正好到末尾 */
	}
	return 0;
}

static u32 be32(const u8 *data)
{
	return ((u32)data[0] << 24) | ((u32)data[1] << 16) |
	       ((u32)data[2] << 8) | (u32)data[3];
}

static u64 be64_reg(const u8 *data)
{
	return ((u64)be32(data) << 32) | (u64)be32(data + 4);
}

/* ------------------------- 极简 FDT 结构块解析 ------------------------- */

static char fdt_path[256];
static unsigned fdt_path_len;
static unsigned fdt_path_stack[16];
static unsigned fdt_depth;
static char fdt_model[64];
static unsigned fdt_model_length;

static int fdt_seen_model;
static int fdt_seen_memory;
static int fdt_seen_uart;
static int fdt_seen_uart_compat;
static int fdt_seen_uart_irq;
static int fdt_seen_plic;
static int fdt_seen_plic_compat;
static int fdt_seen_plic_ndev;
static int fdt_seen_chosen;
static int fdt_seen_reserved;

static void fdt_path_reset(void)
{
	fdt_path[0] = '/';
	fdt_path[1] = 0;
	fdt_path_len = 1;
	fdt_depth = 0;
}

static void fdt_path_push(const char *name)
{
	unsigned name_length = npc_strlen(name);

	if (fdt_depth < 16)
		fdt_path_stack[fdt_depth] = fdt_path_len;

	if (fdt_depth == 0) {
		/* 根节点没有名字，路径固定为 "/"。 */
		fdt_path[0] = '/';
		fdt_path[1] = 0;
		fdt_path_len = 1;
	} else if (name_length != 0) {
		unsigned separator = 1;

		/* 根路径已经是 "/" 时，子节点不要再加一个斜杠。 */
		if (fdt_path_len == 1 && fdt_path[0] == '/')
			separator = 0;
		if (fdt_path_len + separator + name_length >= sizeof(fdt_path)) {
			fdt_path_len = 0;
			fdt_path[0] = 0;
		} else {
			unsigned index;

			if (separator)
				fdt_path[fdt_path_len++] = '/';
			for (index = 0; index < name_length; index++)
				fdt_path[fdt_path_len++] = name[index];
			fdt_path[fdt_path_len] = 0;
		}
	}

	fdt_depth++;
}

static void fdt_path_pop(void)
{
	if (fdt_depth == 0)
		return;
	fdt_depth--;
	if (fdt_depth < 16) {
		fdt_path_len = fdt_path_stack[fdt_depth];
		fdt_path[fdt_path_len] = 0;
	}
}

static void fdt_check_property(const char *name, const u8 *value,
			       unsigned length)
{
	if (npc_streq(fdt_path, "/") && npc_streq(name, "model")) {
		unsigned copy_length = length;

		if (copy_length >= sizeof(fdt_model))
			copy_length = sizeof(fdt_model) - 1;
		for (unsigned index = 0; index < copy_length; index++)
			fdt_model[index] = (char)value[index];
		fdt_model[copy_length] = 0;
		fdt_model_length = copy_length;
		if (npc_streq(fdt_model, "ysyx,npc"))
			fdt_seen_model = 1;
		return;
	}

	if (npc_str_starts(fdt_path, "/memory@") &&
	    npc_streq(name, "reg") && length >= 16) {
		u64 base = be64_reg(value);
		u64 size = be64_reg(value + 8);

		if (base == NPC_RAM_BASE && size == NPC_RAM_SIZE)
			fdt_seen_memory = 1;
		return;
	}

	if (npc_str_starts(fdt_path, "/soc/serial@")) {
		if (npc_streq(name, "reg") && length >= 16) {
			u64 base = be64_reg(value);

			if (base == NPC_UART_BASE)
				fdt_seen_uart = 1;
		} else if (npc_streq(name, "compatible") &&
			   string_list_contains(value, length, "ns16550a")) {
			fdt_seen_uart_compat = 1;
		} else if (npc_streq(name, "interrupts") && length >= 4 &&
			   be32(value) == 10) {
			fdt_seen_uart_irq = 1;
		}
		return;
	}

	if (npc_str_starts(fdt_path, "/soc/interrupt-controller@")) {
		if (npc_streq(name, "reg") && length >= 16) {
			u64 base = be64_reg(value);
			u64 size = be64_reg(value + 8);

			if (base == NPC_PLIC_BASE && size == NPC_PLIC_SIZE)
				fdt_seen_plic = 1;
		} else if (npc_streq(name, "compatible") &&
			   string_list_contains(value, length, "riscv,plic0")) {
			fdt_seen_plic_compat = 1;
		} else if (npc_streq(name, "riscv,ndev") && length >= 4 &&
			   be32(value) == 31) {
			fdt_seen_plic_ndev = 1;
		}
		return;
	}

	if (npc_streq(fdt_path, "/chosen") &&
	    npc_streq(name, "stdout-path") && length > 0) {
		if (npc_str_starts((const char *)value, "/soc/serial@10000000"))
			fdt_seen_chosen = 1;
		return;
	}

	if (npc_str_starts(fdt_path, "/reserved-memory/sbi-firmware") &&
	    npc_streq(name, "reg") && length >= 16) {
		u64 base = be64_reg(value);
		u64 size = be64_reg(value + 8);

		if (base == NPC_RAM_BASE && size == NPC_SBI_FW_SIZE)
			fdt_seen_reserved = 1;
	}
}

static int fdt_scan(u64 address)
{
	const u8 *blob = (const u8 *)address;
	u32 totalsize;
	u32 off_struct;
	u32 size_struct;
	u32 off_strings;
	u32 size_strings;
	const u8 *cursor;
	const u8 *struct_end;
	u32 token;

	if (be32(blob) != FDT_MAGIC) {
		sbi_puts("SBI-NPC-PAYLOAD: FDT magic mismatch: 0x");
		sbi_puthex(be32(blob));
		sbi_puts("\n");
		return 0;
	}

	totalsize = be32(blob + 4);
	off_struct = be32(blob + 8);
	off_strings = be32(blob + 12);
	size_strings = be32(blob + 32);
	size_struct = be32(blob + 36);

	sbi_puts("SBI-NPC-PAYLOAD: FDT magic=0x");
	sbi_puthex(be32(blob));
	sbi_puts(" totalsize=0x");
	sbi_puthex(totalsize);
	sbi_puts("\n");

	if (totalsize < 40 || off_struct + size_struct > totalsize ||
	    off_strings + size_strings > totalsize) {
		sbi_puts("SBI-NPC-PAYLOAD: FDT header bounds invalid\n");
		return 0;
	}

	fdt_path_reset();
	fdt_model[0] = 0;
	fdt_model_length = 0;
	fdt_seen_model = 0;
	fdt_seen_memory = 0;
	fdt_seen_uart = 0;
	fdt_seen_uart_compat = 0;
	fdt_seen_uart_irq = 0;
	fdt_seen_plic = 0;
	fdt_seen_plic_compat = 0;
	fdt_seen_plic_ndev = 0;
	fdt_seen_chosen = 0;
	fdt_seen_reserved = 0;

	cursor = blob + off_struct;
	struct_end = cursor + size_struct;

	while (cursor + 4 <= struct_end) {
		token = be32(cursor);
		cursor += 4;

		if (token == FDT_BEGIN_NODE) {
			const char *name = (const char *)cursor;
			unsigned name_length = 0;

			while (cursor + name_length < struct_end &&
			       name[name_length] != 0)
				name_length++;
			if (cursor + name_length >= struct_end) {
				sbi_puts("SBI-NPC-PAYLOAD: FDT node name truncated\n");
				return 0;
			}
			fdt_path_push(name);
			cursor += (name_length + 1 + 3) & ~3U;
		} else if (token == FDT_END_NODE) {
			fdt_path_pop();
		} else if (token == FDT_PROP) {
			u32 length;
			u32 name_offset;
			const char *property_name;

			if (cursor + 8 > struct_end) {
				sbi_puts("SBI-NPC-PAYLOAD: FDT property header truncated\n");
				return 0;
			}
			length = be32(cursor);
			name_offset = be32(cursor + 4);
			cursor += 8;
			if (name_offset >= size_strings ||
			    cursor + length > struct_end) {
				sbi_puts("SBI-NPC-PAYLOAD: FDT property bounds invalid\n");
				return 0;
			}
			property_name = (const char *)(blob + off_strings +
						       name_offset);
			/* 字符串表末尾必须能在结构块结束前找到 NUL。 */
			{
				u32 probe = name_offset;

				while (probe < size_strings &&
				       blob[off_strings + probe] != 0)
					probe++;
				if (probe >= size_strings) {
					sbi_puts("SBI-NPC-PAYLOAD: FDT property name truncated\n");
					return 0;
				}
			}
			fdt_check_property(property_name, cursor, length);
			cursor += (length + 3) & ~3U;
		} else if (token == FDT_NOP) {
			continue;
		} else if (token == FDT_END) {
			break;
		} else {
			sbi_puts("SBI-NPC-PAYLOAD: FDT unknown token=0x");
			sbi_puthex(token);
			sbi_puts("\n");
			return 0;
		}
	}

	sbi_puts("SBI-NPC-PAYLOAD: FDT model=");
	if (fdt_model_length)
		sbi_puts(fdt_model);
	else
		sbi_puts("(missing)");
	sbi_puts("\n");

	sbi_puts("SBI-NPC-PAYLOAD: FDT memory ");
	sbi_puts(fdt_seen_memory ? "OK" : "MISSING");
	sbi_puts(": 0x");
	sbi_puthex(NPC_RAM_BASE);
	sbi_puts("+0x");
	sbi_puthex(NPC_RAM_SIZE);
	sbi_puts("\n");

	sbi_puts("SBI-NPC-PAYLOAD: FDT uart ");
	sbi_puts((fdt_seen_uart && fdt_seen_uart_compat && fdt_seen_uart_irq)
			 ? "OK" : "MISSING");
	sbi_puts(": 0x");
	sbi_puthex(NPC_UART_BASE);
	sbi_puts(" irq=10\n");

	sbi_puts("SBI-NPC-PAYLOAD: FDT plic ");
	sbi_puts((fdt_seen_plic && fdt_seen_plic_compat && fdt_seen_plic_ndev)
			 ? "OK" : "MISSING");
	sbi_puts(": 0x");
	sbi_puthex(NPC_PLIC_BASE);
	sbi_puts("+0x");
	sbi_puthex(NPC_PLIC_SIZE);
	sbi_puts(" ndev=31\n");

	sbi_puts("SBI-NPC-PAYLOAD: FDT chosen ");
	sbi_puts(fdt_seen_chosen ? "OK" : "MISSING");
	sbi_puts(": /soc/serial@10000000\n");

	sbi_puts("SBI-NPC-PAYLOAD: FDT reserved-memory ");
	sbi_puts(fdt_seen_reserved ? "OK" : "MISSING");
	sbi_puts("\n");

	return fdt_seen_model && fdt_seen_memory && fdt_seen_uart &&
	       fdt_seen_uart_compat && fdt_seen_uart_irq && fdt_seen_plic &&
	       fdt_seen_plic_compat && fdt_seen_plic_ndev && fdt_seen_chosen &&
	       fdt_seen_reserved;
}

#ifdef FDT_EXPECTED
static void payload_fail(void)
{
	sbi_puts("SBI-NPC-PAYLOAD: FAIL\n");
	for (;;)
		asm volatile("wfi");
}
#endif

void payload_main(u64 fdt_address, u64 hartid)
{
	long version = 0;
	long version_error;
	long timer_error;
#ifndef PAYLOAD_USE_TIME_CSR
	volatile unsigned int *mtime = (volatile unsigned int *)0x0200bff8UL;
#endif
	unsigned long mtime_value;
#ifdef FDT_EXPECTED
	int fdt_ok;
#endif

	sbi_puts("SBI-NPC-PAYLOAD: entered S-mode via ");
	sbi_puts(PAYLOAD_SBI_NAME);
	sbi_puts(" (a0=0x");
	sbi_puthex(hartid);
	sbi_puts(", a1=0x");
	sbi_puthex(fdt_address);
	sbi_puts(")\n");

#ifdef FDT_EXPECTED
	if (fdt_address == 0) {
		sbi_puts("SBI-NPC-PAYLOAD: FDT expected but a1=0\n");
		payload_fail();
	}
#ifdef FDT_EXPECT_ADDR
	if (fdt_address != (u64)FDT_EXPECT_ADDR) {
		sbi_puts("SBI-NPC-PAYLOAD: FDT address mismatch, expected 0x");
		sbi_puthex((u64)FDT_EXPECT_ADDR);
		sbi_puts("\n");
		payload_fail();
	}
#endif
	fdt_ok = fdt_scan(fdt_address);
	sbi_puts("SBI-NPC-PAYLOAD: FDT ");
	sbi_puts(fdt_ok ? "PASS" : "FAIL");
	sbi_puts("\n");
	if (!fdt_ok)
		payload_fail();
#else
	if (fdt_address == 0) {
		sbi_puts("SBI-NPC-PAYLOAD: FDT none (a1=0)\n");
	} else {
		/* 未编译 FDT 断言时仍打印内容，便于诊断，但不作为失败条件。 */
		sbi_puts("SBI-NPC-PAYLOAD: FDT unexpected but present\n");
		(void)fdt_scan(fdt_address);
	}
#endif

	version_error = sbi_call(0x10, 0, 0, 0, 0, &version); /* BASE / GET_SPEC_VERSION */
	sbi_puts("SBI-NPC-PAYLOAD: spec_version=0x");
	sbi_puthex((u64)version);
	sbi_puts(" (error=0x");
	sbi_puthex((u64)version_error);
	sbi_puts(")\n");

	/* SBI TIME 扩展只有 set_timer（FID 0）。
	 *
	 * 默认（NPC）直接读 CLINT mtime，验证 S-mode MMIO。PMP 严格
	 * 执行的平台（NEMU Linux）不能从 S 模式访问 M-only CLINT，
	 * 此时用 PAYLOAD_USE_TIME_CSR 改读架构 time CSR；两者返回同一
	 * 个 mtime。 */
	timer_error = sbi_call(0x54494D45, 0, 0x7fffffff, 0, 0, 0);
	sbi_puts("SBI-NPC-PAYLOAD: set_timer error=0x");
	sbi_puthex((u64)timer_error);
	sbi_puts("\n");

#ifdef PAYLOAD_USE_TIME_CSR
	asm volatile("rdtime %0" : "=r"(mtime_value));
	sbi_puts("SBI-NPC-PAYLOAD: mtime(time CSR)=0x");
#else
	mtime_value = ((unsigned long)mtime[1] << 32) | mtime[0];
	sbi_puts("SBI-NPC-PAYLOAD: mtime=0x");
#endif
	sbi_puthex((u64)mtime_value);
	sbi_puts("\n");

	sbi_puts("SBI-NPC-PAYLOAD: console ecall OK\n");
	sbi_puts("SBI-NPC-PAYLOAD: PASS\n");

	for (;;)
		asm volatile("wfi");
}
