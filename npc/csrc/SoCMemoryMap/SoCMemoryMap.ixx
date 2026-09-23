export module npc.SoCMemoryMap.SoCMemoryMap;
import std;
import npc.SoCMemoryMap.AddressRange;

#ifndef CONFIG_MBASE
// Fixed ysyxSoC platform map (not a Kconfig tuning option).
#define CONFIG_MBASE 0x30000000u
#endif
#ifndef CONFIG_MSIZE
#define CONFIG_MSIZE 0x10000000u
#endif

export class SoCMemoryMap
{
public:
    SoCMemoryMap() = delete;
    ~SoCMemoryMap() = default;
    static inline const AddressRange CLINT{"clint", 0x02000000u, 0x10000u};
    static inline const AddressRange FLASH{"flash", CONFIG_MBASE, CONFIG_MSIZE};
    static inline const AddressRange UART{"uart", 0x10000000u, 0x1000u};
    static inline const AddressRange SPI{"spi", 0x10001000u, 0x1000u};
    static inline const AddressRange GPIO{"gpio", 0x10002000u, 0x10u};
    static inline const AddressRange KEYBOARD{"keyboard", 0x10011000u, 0x8u};
    static inline const AddressRange VGA{"vga", 0x21000000u, 0x200000u};
    static inline const AddressRange SRAM{"sram", 0x0f000000u, 0x8000u};
    static inline const AddressRange PSRAM{"psram", 0x80000000u, 0x400000u};
    static inline const AddressRange SDRAM{"sdram", 0xa0000000u, 0x2000000u};
    // 看属于soc的地址空间的哪一块区域的
    static std::optional<AddressRange> find(std::uint32_t address, std::uint32_t length = 1);

private:
    inline static const std::array Ranges{
        CLINT, FLASH, UART, SPI, GPIO, KEYBOARD, VGA, SRAM, PSRAM, SDRAM};
};
