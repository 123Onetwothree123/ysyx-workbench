int main(const char *args)
{
    volatile int *target = (volatile int *)0x80000000;

    target[0] = 0x00000013;
    target[1] = 0x00008067;

    asm volatile("li t0, 0x80000000; jalr ra, t0, 0" ::: "t0", "ra", "memory");

    // NPC/ysyxSoC 的仿真退出 ABI 使用 exact custom-0。标准 EBREAK
    // 现在保留给 breakpoint 异常处理程序，不能再用作宿主机停机指令。
    target[0] = 0x0000000b;

    asm volatile("fence.i");

    asm volatile("li t0, 0x80000000; jalr zero, t0, 0" ::: "t0", "memory");

    return 0;
}
