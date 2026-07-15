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
        unsigned int Color{};
        int Band{y / 60};
        switch (Band)
        {
        case 0: Color = 0x00FF0000U; break;
        case 1: Color = 0x00FF7F00U; break;
        case 2: Color = 0x00FFFF00U; break;
        case 3: Color = 0x0000FF00U; break;
        case 4: Color = 0x000000FFU; break;
        case 5: Color = 0x004B0082U; break;
        case 6: Color = 0x00FF00FFU; break;
        default: Color = 0x00FFFFFFU; break;
        }
        for (int x{0}; x < 640; ++x)
        {
            FB[y * 640 + x] = Color;
        }
    }
    while (1) {}
    return 0;
}
