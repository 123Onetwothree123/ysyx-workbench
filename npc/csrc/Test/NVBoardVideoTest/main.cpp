#include <am.h>
#include <klib-macros.h>

int main(const char *)
{
    for (const char *p{"FB R/W Test\n"}; *p; ++p) putch(*p);

    volatile unsigned int *FB = (volatile unsigned int *)0x21000000U;

    FB[100] = 0x00FF0000U;
    FB[200] = 0x0000FF00U;
    FB[300] = 0x000000FFU;

    unsigned int A = FB[100];
    unsigned int B = FB[200];
    unsigned int C = FB[300];

    if (A == 0x00FF0000U && B == 0x0000FF00U && C == 0x000000FFU)
    {
        for (const char *p{"FB OK\n"}; *p; ++p) putch(*p);
    }
    else
    {
        for (const char *p{"FB FAIL\n"}; *p; ++p) putch(*p);
    }

    while (1) {}
    return 0;
}
