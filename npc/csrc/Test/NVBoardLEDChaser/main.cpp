#include "NVBoardLEDChaser.hpp"
#include <am.h>
int main(const char *)
{
    nvboard_led_chaser(0x10002000U, 16, 200, 0);
    return 0;
}
