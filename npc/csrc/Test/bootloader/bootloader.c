int main(const char *args)
{
    volatile int *target = (volatile int *)0x80000000;

    target[0] = 0x00000013;
    target[1] = 0x00008067;

    asm volatile("li t0, 0x80000000; jalr ra, t0, 0" ::: "t0", "ra", "memory");

    target[0] = 0x00100073;

    asm volatile("fence.i");

    asm volatile("li t0, 0x80000000; jalr zero, t0, 0" ::: "t0", "memory");

    return 0;
}
