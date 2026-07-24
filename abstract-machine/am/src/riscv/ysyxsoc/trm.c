#include <am.h>

void putch(char ch) { asm volatile("csrw 0x8a0, %0" : : "r"((int)ch)); }
void halt(int code) { asm volatile("mv a0, %0; ebreak" : : "r"(code)); while (1); }

__attribute__((naked))
void _trm_init()
{
    asm volatile(
        "li a0, 0\ncall cte_init\n"
        "li a2, 'H'\ncsrw 0x8a0, a2\n"
        "li a2, 'e'\ncsrw 0x8a0, a2\n"
        "li a2, 'l'\ncsrw 0x8a0, a2\n"
        "li a2, 'l'\ncsrw 0x8a0, a2\n"
        "li a2, 'o'\ncsrw 0x8a0, a2\n"
        "li a2, ' '\ncsrw 0x8a0, a2\n"
        "li a2, 'W'\ncsrw 0x8a0, a2\n"
        "li a2, 'o'\ncsrw 0x8a0, a2\n"
        "li a2, 'r'\ncsrw 0x8a0, a2\n"
        "li a2, 'l'\ncsrw 0x8a0, a2\n"
        "li a2, 'd'\ncsrw 0x8a0, a2\n"
        "li a2, '\\n'\ncsrw 0x8a0, a2\n"
        "li a0, 0\nebreak\n"
    );
}
