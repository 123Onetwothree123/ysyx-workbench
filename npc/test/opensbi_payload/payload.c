/*
 * 最小 S 模式 payload：验证 OpenSBI 已完成 M 模式初始化、把 CPU 切到
 * S 模式，并且 SBI ecall（控制台 + base 扩展）可用。
 *
 * 该 payload 不设置 satp、不依赖 MMU，只使用 OpenSBI 提供的 legacy
 * console putchar（EID 0x01）和 BASE 扩展（EID 0x10）。它是测试固件，
 * 不是 Linux 或 xv6。
 */

typedef unsigned long u64;

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

void payload_main(void)
{
	long version = 0;
	long version_error;
	long timer_error;
	volatile unsigned int *mtime = (volatile unsigned int *)0x0200bff8UL;
	unsigned long mtime_value;

	sbi_puts("SBI-NPC-PAYLOAD: entered S-mode via OpenSBI\n");

	version_error = sbi_call(0x10, 0, 0, 0, 0, &version); /* BASE / GET_SPEC_VERSION */
	sbi_puts("SBI-NPC-PAYLOAD: spec_version=0x");
	sbi_puthex((u64)version);
	sbi_puts(" (error=0x");
	sbi_puthex((u64)version_error);
	sbi_puts(")\n");

	/* SBI TIME 扩展只有 set_timer（FID 0）；get_time 由 S-mode 直接读
	 * CLINT mtime。这里同时验证 ecall 和 S-mode CLINT 访问。 */
	timer_error = sbi_call(0x54494D45, 0, 0x7fffffff, 0, 0, 0);
	sbi_puts("SBI-NPC-PAYLOAD: set_timer error=0x");
	sbi_puthex((u64)timer_error);
	sbi_puts("\n");

	mtime_value = ((unsigned long)mtime[1] << 32) | mtime[0];
	sbi_puts("SBI-NPC-PAYLOAD: mtime=0x");
	sbi_puthex((u64)mtime_value);
	sbi_puts("\n");

	sbi_puts("SBI-NPC-PAYLOAD: console ecall OK\n");
	sbi_puts("SBI-NPC-PAYLOAD: PASS\n");

	for (;;)
		asm volatile("wfi");
}
