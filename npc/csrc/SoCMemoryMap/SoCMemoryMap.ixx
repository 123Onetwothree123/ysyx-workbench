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
    static inline const AddressRange CLINT{"clint", npc::PmaCLINTBase, npc::PmaCLINTSize};
    static inline const AddressRange MROM{"mrom", npc::PmaMromBase, npc::PmaMromSize};
    static inline const AddressRange FLASH{"flash", npc::PmaFlashBase, npc::PmaFlashSize};
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
    // SDB live-memory shadow 的窗口：SRAM/PSRAM/SDRAM 与 ChipLinkMem。
    // shadow 按 store 起始地址登记，跨窗口尾部的访问沿用旧行为；
    // length 仅用于拒绝零长度访问，不参与窗口范围判定。
    static bool IsShadowRam(npc::XWord address, npc::XWord length);
    // DiffTest REF 能建模的数据窗口：SRAM/MROM/PSRAM/SDRAM。
    // 与 shadow 集合有意不同：MROM 属于 REF 数据窗口但不建 shadow，
    // ChipLinkMem 不进 REF 模型。要求整段访问完整落在窗口内。
    static bool IsReferenceModeledRam(npc::XWord address,
                                      npc::XWord length);

private:
    inline static const std::array Ranges{
        CLINT, MROM, FLASH, UART, SPI, GPIO, KEYBOARD, VGA, SRAM, PSRAM, SDRAM};
};
