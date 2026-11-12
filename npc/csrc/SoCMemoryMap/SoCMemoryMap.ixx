module;
export module npc.SoCMemoryMap.SoCMemoryMap;
import npc.config;
import std;
import npc.SoCMemoryMap.AddressRange;

export class SoCMemoryMap
{
public:
    SoCMemoryMap() = delete;
    ~SoCMemoryMap() = default;
    static inline const AddressRange CLINT{"clint", npc::PmaClintBase, npc::PmaClintSize};
    static inline const AddressRange FLASH{"flash", npc::SocMBase, npc::SocMSize};
    static inline const AddressRange UART{"uart", npc::PmaUartBase, npc::PmaUartSize};
    static inline const AddressRange SPI{"spi", npc::PmaSpiBase, npc::PmaSpiSize};
    static inline const AddressRange GPIO{"gpio", npc::PmaGpioBase, npc::PmaGpioSize};
    static inline const AddressRange KEYBOARD{"keyboard", npc::PmaKeyboardBase, npc::PmaKeyboardSize};
    static inline const AddressRange VGA{"vga", npc::PmaVgaBase, npc::PmaVgaSize};
    static inline const AddressRange SRAM{"sram", npc::PmaSramBase, npc::PmaSramSize};
    static inline const AddressRange PSRAM{"psram", npc::PmaPsramBase, npc::PmaPsramSize};
    static inline const AddressRange SDRAM{"sdram", npc::PmaSdramBase, npc::PmaSdramSize};
    // 看属于 SoC 地址空间的哪一块区域。查询使用完整 XWord，内部再按
    // 有效 SocXLen 拒绝超出 SoC 总线宽度的地址，避免静默截断。
    static std::optional<AddressRange> find(npc::XWord address,
                                            npc::XWord length = 1);

private:
    inline static const std::array Ranges{
        CLINT, FLASH, UART, SPI, GPIO, KEYBOARD, VGA, SRAM, PSRAM, SDRAM};
};
