// branch-nest: 嵌套推测执行测试
// 连续分支/跳转、错误路径冲刷、分支目标是分支、jal+ret链
// 每个用例向 SRAM 签名区写一个值(1=通过, 其它=失败码), 最后汇总打印
#include <stdio.h>
#include <am.h>
#include <cstdint>

static volatile unsigned int *const sig = reinterpret_cast<volatile unsigned int *>(0x0f000000U);

int main(const char *)
{
    // T1: 连续 taken 分支链(beq;beq;beq)必须到达正确终点
    asm volatile(
        "li t0, 1\n"
        "beq t0, t0, 1f\n"
        "j 99f\n"
        "1: beq t0, t0, 2f\n"
        "j 99f\n"
        "2: beq t0, t0, 3f\n"
        "j 99f\n"
        "3: li t1, 1\n"
        "j 100f\n"
        "99: li t1, 0x11\n"
        "100: sw t1, 0(%[sig])\n"
        ::[sig] "r"(sig)
        : "t0", "t1", "memory");

    // T2: 错误路径上的 taken 分支必须被冲刷(若被执行会跳到 99)
    asm volatile(
        "li t0, 1\n"
        "beq t0, t0, 1f\n"
        "beq t0, t0, 99f\n"
        "1: li t1, 1\n"
        "j 100f\n"
        "99: li t1, 0x12\n"
        "100: sw t1, 4(%[sig])\n"
        ::[sig] "r"(sig)
        : "t0", "t1", "memory");

    // T3: 错误路径上的 jal 必须被冲刷
    asm volatile(
        "li t0, 1\n"
        "beq t0, t0, 1f\n"
        "jal zero, 99f\n"
        "1: li t1, 1\n"
        "j 100f\n"
        "99: li t1, 0x13\n"
        "100: sw t1, 8(%[sig])\n"
        ::[sig] "r"(sig)
        : "t0", "t1", "memory");

    // T4: 错误路径上的 store 必须被冲刷(先清零, 错误路径写0x14, 正确路径写1)
    asm volatile(
        "li t0, 1\n"
        "sw zero, 12(%[sig])\n"
        "beq t0, t0, 1f\n"
        "li t1, 0x14\n"
        "sw t1, 12(%[sig])\n"
        "1: li t1, 1\n"
        "sw t1, 12(%[sig])\n"
        ::[sig] "r"(sig)
        : "t0", "t1", "memory");

    // T5: 分支的目标本身是分支(嵌套重定向)
    asm volatile(
        "li t0, 1\n"
        "li t1, 2\n"
        "beq t0, t0, 1f\n"
        "j 99f\n"
        "1: beq t1, t1, 2f\n"
        "j 99f\n"
        "2: li t2, 1\n"
        "j 100f\n"
        "99: li t2, 0x15\n"
        "100: sw t2, 16(%[sig])\n"
        ::[sig] "r"(sig)
        : "t0", "t1", "t2", "memory");

    // T6: jal 后面紧跟错误路径 taken 分支; 函数通过 ra+4 正确返回成功路径
    asm volatile(
        "li t0, 1\n"
        "jal ra, 1f\n"
        "beq t0, t0, 99f\n"
        "2: li t2, 1\n"
        "j 100f\n"
        "1: jalr zero, 4(ra)\n"
        "99: li t2, 0x16\n"
        "100: sw t2, 20(%[sig])\n"
        ::[sig] "r"(sig)
        : "t0", "t2", "ra", "memory");

    // T7: 循环回跳 + 循环退出后紧跟 taken 分支
    asm volatile(
        "li t0, 3\n"
        "li t1, 0\n"
        "1: addi t1, t1, 1\n"
        "addi t0, t0, -1\n"
        "bne t0, zero, 1b\n"
        "beq t1, t1, 2f\n"
        "j 99f\n"
        "2: li t3, 3\n"
        "bne t1, t3, 99f\n"
        "li t2, 1\n"
        "j 100f\n"
        "99: li t2, 0x17\n"
        "100: sw t2, 24(%[sig])\n"
        ::[sig] "r"(sig)
        : "t0", "t1", "t2", "t3", "memory");

    // 汇总
    unsigned fails = 0;
    for (int i = 0; i < 7; i++)
    {
        if (sig[i] != 1)
        {
            printf("T%d FAIL (sig=%x)\n", i + 1, sig[i]);
            fails++;
        }
    }
    if (fails == 0)
    {
        printf("branch-nest: ALL 7 PASS\n");
    }
    return 0;
}
