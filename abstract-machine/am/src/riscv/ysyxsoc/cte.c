#include <am.h>
#include <riscv/riscv.h>
#include <klib.h>
static Context *(*user_handler)(Event, Context *) = NULL;
Context *__am_irq_handle(Context *c)
{
    if (user_handler)
    {
        Event ev = {0};
        switch (c->mcause)
        {
        case 8:
        case 9:
        case 10:
        case 11:
            c->mepc += 4;
#ifdef __riscv_e
            if ((intptr_t)c->gpr[15] == -1)
#else
            if ((intptr_t)c->gpr[17] == -1)
#endif
            {
                ev.event = EVENT_YIELD;
            }
            else
            {
                printf("我也不知道怎么解决，反正mcause跑到11了，但是没进yield事件，文档也没说该怎么去解决\n");
                ev.event = EVENT_ERROR;
            }
            break;
        default:
            printf("RISC-V Privileged Specification文档的Machine Cause Register写的是大于12保留，也不知道怎么做\n");
            ev.event = EVENT_ERROR;
            break;
        }
        c = user_handler(ev, c);
        assert(c != NULL);
    }
    return c;
}
extern void __am_asm_trap(void);
bool cte_init(Context *(*handler)(Event, Context *))
{
    // initialize exception entry
    asm volatile("csrw mtvec, %0" : : "r"(__am_asm_trap));

    // register event handler
    user_handler = handler;

    return true;
}
Context *kcontext(Area kstack, void (*entry)(void *), void *arg)
{
    Context *c = (Context *)(kstack.end - sizeof(Context)); // end是栈最高位然后减掉长度，找开头部分建指针
    memset(c, 0, sizeof(Context));                          // 和rtthreadam一样，ai建议手动清零
    c->mstatus = MODE_M << 11;                              // 也是ai建议的，清除掉原本的#，避免mstatus和mcause和寄存器是垃圾
    c->mepc = (uintptr_t)entry;
    c->gpr[10] = (uintptr_t)arg; // riscv elf psabi写的是参数写到a0
    return c;
}
void yield()
{
#ifdef __riscv_e
    asm volatile("li a5, -1; ecall");
#else
    asm volatile("li a7, -1; ecall");
#endif
}
bool ienabled()
{
    return false;
}
void iset(bool enable)
{
}