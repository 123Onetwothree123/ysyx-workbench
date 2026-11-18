#include "NVBoardDIPSwitch.hpp"
#include "NVBoardLEDChaser.hpp"
int main(const char *)
{
    nvboard_dip_switch_password(0x10002004U, 0xFFFF);
    nvboard_led_chaser(0x10002000U, 16, 200, 0);
    return 0;
}
