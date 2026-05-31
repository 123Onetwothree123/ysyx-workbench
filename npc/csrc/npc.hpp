// 抄南京大学虚拟机项目的，在南大项目的nemu.h里面，abstract-machine/am/src/platform/nemu/include/nemu.h
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

//加的串口和时钟
#define SERIAL_PORT 0x10000000
#endif
