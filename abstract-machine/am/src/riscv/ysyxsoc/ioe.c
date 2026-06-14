#include <am.h>
#include <klib-macros.h>
void __am_timer_init();
void __am_timer_rtc(AM_TIMER_RTC_T *);
void __am_timer_uptime(AM_TIMER_UPTIME_T *);
void __am_input_keybrd(AM_INPUT_KEYBRD_T *);
static void __am_timer_config(AM_TIMER_CONFIG_T *cfg)
{
    cfg->present = true;
    cfg->has_rtc = true;
}
static void __am_input_config(AM_INPUT_CONFIG_T *cfg)
{
    cfg->present = true;
}
static void __am_uart_config(AM_UART_CONFIG_T *cfg)
{
    cfg->present = false;
}
typedef void (*handler_t)(void *buf);
bool ioe_init()
{
    __am_timer_init();
    return true;
}
void ioe_read(int reg, void *buf)
{
    switch (reg)
    {
    case AM_TIMER_CONFIG:
        __am_timer_config(buf);
        break;
    case AM_TIMER_RTC:
        __am_timer_rtc(buf);
        break;
    case AM_TIMER_UPTIME:
        __am_timer_uptime(buf);
        break;
    case AM_INPUT_CONFIG:
        __am_input_config(buf);
        break;
    case AM_INPUT_KEYBRD:
        __am_input_keybrd(buf);
        break;
    case AM_UART_CONFIG:
        __am_uart_config(buf);
        break;
    default:
        panic("access nonexist register");
    }
}
void ioe_write(int reg, void *buf)
{
    switch (reg)
    {
    default:
        panic("access nonexist register");
    }
}