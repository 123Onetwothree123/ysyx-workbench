#include <am.h>

int main(const char *)
{
    for (const char *p{"Fill\n"}; *p; ++p)
    {
        putch(*p);
    }

    volatile unsigned int *FB{reinterpret_cast<volatile unsigned int *>(0x21000000U)};
    for (int y{0}; y < 480; ++y)
    {
        for (int x{0}; x < 640; ++x)
        {
            FB[y * 640 + x] = 0x00FF0000U;
        }
    }

    for (const char *p{"Done\n"}; *p; ++p)
    {
        putch(*p);
    }
    while (1) {}
    return 0;
}
