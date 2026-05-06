// 抄nemu.h的，abstract-machine/am/src/platform/nemu/include/nemu.h
#ifndef NPC_HPP
#define NPC_HPP
#define DEVICE_BASE 0xa0000000
#define RTC_ADDR    (DEVICE_BASE + 0x0000048)

#define RTC_YEAR_ADDR   (RTC_ADDR + 0x10)
#define RTC_MONTH_ADDR  (RTC_ADDR + 0x14)
#define RTC_DAY_ADDR    (RTC_ADDR + 0x18)
#define RTC_HOUR_ADDR   (RTC_ADDR + 0x1C)
#define RTC_MINUTE_ADDR (RTC_ADDR + 0x20)
#define RTC_SECOND_ADDR (RTC_ADDR + 0x24)
/*
为NPC添加串口和时钟
在NPC仿真环境中实现串口的输出功能, 并运行hello程序. 为了和后面的SoC串口地址保持一致, 此处可将NPC的串口地址设置为0x10000000.
https://ysyx.oscc.cc/docs/2407/d/5.html
*/
#define SERIAL_PORT 0x10000000
#endif
