#include <am.h>
#include <klib.h>

// fence.i 反例: 考流水线内预取指令的冲刷
// victim紧随fence.i之后——fence.i到达EXU时, victim已被预取进流水线(IF/ID级)。
// 多周期处理器: victim要等fence.i执行完才被取指, 必然拿到新指令, PASS。
// 流水线处理器若不冲刷: 执行的是流水线里的旧指令 li a0,0, FAIL。
// 与smc测试的区别: smc改的是fence.i之前、经跳转重新进入的指令(考icache冲刷),
// 本测试改的是fence.i之后的指令(考流水线内已预取指令的冲刷)。
int main(const char *args)
{
    int result;
    asm volatile(
        "la   t0, 1f;"
        "li   t1, 0x00100513;" // "li a0, 1" 的机器码
        "sw   t1, 0(t0);"      // 就地覆盖 victim
        "fence.i;"             // fence.i在EXU时, victim已被预取进流水线
        "1:;"
        "li   a0, 0;"          // victim: 将被覆盖成 li a0, 1
        "mv   %0, a0;"
        : "=r"(result)
        :
        : "t0", "t1", "a0", "memory");
    if (result == 1) {
        printf("fencei-prefetch PASS: executed the NEW instruction\n");
        return 0; // halt(0) -> GOOD TRAP
    } else {
        printf("fencei-prefetch FAIL: stale prefetched instruction executed\n");
        halt(1); // -> BAD TRAP
    }
    return 1;
}
