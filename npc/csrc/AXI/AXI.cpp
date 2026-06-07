#include "AXI.hpp"
#include "../NPCTrap.hpp"
#include <cstdio>
// 临时debug计数器，后面删掉
static int debug_inst_count = 0;
AXI::AXI(Memory &memory) : memory(memory) {}
void AXI::reset(VRV32I &CPU)
{
    CPU.io_InstructionsBus_AR_ARREADY = 0;
    CPU.io_InstructionsBus_R_RVALID = 0;
    CPU.io_InstructionsBus_R_RDATA = 0;
    CPU.io_InstructionsBus_R_RRESP = 0;
    CPU.io_InstructionsBus_AW_AWREADY = 0;
    CPU.io_InstructionsBus_W_WREADY = 0;
    CPU.io_InstructionsBus_B_BVALID = 0;
    CPU.io_InstructionsBus_B_BRESP = 0;
    CPU.io_DataBus_AR_ARREADY = 0;
    CPU.io_DataBus_R_RVALID = 0;
    CPU.io_DataBus_R_RDATA = 0;
    CPU.io_DataBus_R_RRESP = 0;
    CPU.io_DataBus_AW_AWREADY = 0;
    CPU.io_DataBus_W_WREADY = 0;
    CPU.io_DataBus_B_BVALID = 0;
    CPU.io_DataBus_B_BRESP = 0;
    InstructionReadPending = false;
    DataReadPending = false;
    DataWritePending = false;
}
void AXI::HandleInstructionAR(VRV32I &CPU)
{
    CPU.io_InstructionsBus_AR_ARREADY = 1; // 临时的，后面改成条件判断，现在就直接接VCC
    if (CPU.io_InstructionsBus_AR_ARVALID && CPU.io_InstructionsBus_AR_ARREADY)
    {
        // 得，scala有fire，C++又得手写
        InstructionReadAddress = CPU.io_InstructionsBus_AR_ARADDR;
        InstructionReadPending = true;
    }
}
void AXI::HandleInstructionR(VRV32I &CPU)
{
    if (!InstructionReadPending)
    {
        // 没需要处理的直接跳
        return;
    }
    auto result{memory.LoadWord(InstructionReadAddress)};
    CPU.io_InstructionsBus_R_RVALID = true;
    CPU.io_InstructionsBus_R_RDATA = result.value_or(0);
    CPU.io_InstructionsBus_R_RRESP = 0;
    if (CPU.io_InstructionsBus_R_RREADY)
    {
        // 临时debug，PC变化时打印
        static std::uint32_t debug_last_pc = 0;
        if (InstructionReadAddress != debug_last_pc + 4 && InstructionReadAddress != debug_last_pc) {
            printf("跳 PC=0x%08x 指令=0x%08x\n", InstructionReadAddress, result.value_or(0));
            fflush(stdout);
        }
        debug_last_pc = InstructionReadAddress;
        InstructionReadPending = false;
    }
}
void AXI::HandleDataAR(VRV32I &CPU)
{
    CPU.io_DataBus_AR_ARREADY = true;
    if (CPU.io_DataBus_AR_ARVALID && CPU.io_DataBus_AR_ARREADY)
    {
        DataReadAddress = CPU.io_DataBus_AR_ARADDR;
        DataReadPending = true;
    }
}
void AXI::HandleDataR(VRV32I &CPU)
{
    if (!DataReadPending)
    {
        return;
    }
    CPU.io_DataBus_R_RVALID = true;
    auto result{memory.LoadWord(DataReadAddress)};
    CPU.io_DataBus_R_RDATA = result.value_or(0);
    CPU.io_DataBus_R_RRESP = 0;
    if (CPU.io_DataBus_R_RREADY)
    {
        // 临时debug
        printf("Load 地址=0x%08x 数据=0x%08x\n", DataReadAddress, result.value_or(0));
        fflush(stdout);
        DataReadPending = false;
    }
}
void AXI::HandleDataAW_W(VRV32I &CPU)
{
    CPU.io_DataBus_AW_AWREADY = true;
    CPU.io_DataBus_W_WREADY = true;
    if (CPU.io_DataBus_AW_AWVALID && CPU.io_DataBus_AW_AWREADY && CPU.io_DataBus_W_WVALID && CPU.io_DataBus_W_WREADY)
    {
        auto address{CPU.io_DataBus_AW_AWADDR};
        auto value{CPU.io_DataBus_W_WDATA};
        auto mask{static_cast<std::uint8_t>(CPU.io_DataBus_W_WSTRB)};

        // 临时debug，看能不能跑到串口输出
        static constexpr std::uint32_t SerialPort = 0x10000000;
        if (address == SerialPort) {
            printf("SERIAL: addr=0x%08x data=0x%08x char='%c'\n", address, value, static_cast<char>(value & 0xFF));
            fflush(stdout);
        }

        // 这是写使能
        bool WriteByte0{mask & 0b0001};
        bool WriteByte1{mask & 0b0010};
        bool WriteByte2{mask & 0b0100};
        bool WriteByte3{mask & 0b1000};
        uint8_t Byte0 = static_cast<uint8_t>(value >> 0);
        uint8_t Byte1 = static_cast<uint8_t>(value >> 8);
        uint8_t Byte2 = static_cast<uint8_t>(value >> 16);
        uint8_t Byte3 = static_cast<uint8_t>(value >> 24);
        if (WriteByte0)
        {
            memory.StoreByte(address, Byte0);
        }
        if (WriteByte1)
        {
            memory.StoreByte(address + 1, Byte1);
        }
        if (WriteByte2)
        {
            memory.StoreByte(address + 2, Byte2);
        }
        if (WriteByte3)
        {
            memory.StoreByte(address + 3, Byte3);
        }
        static constexpr std::uint32_t HaltAddress = 0xa0000000;
        if (address == HaltAddress)
        {
            auto exit_code = static_cast<std::uint32_t>(value & 0xFF); // 这行代码是ai写的，AI：AM 的 halt() 往 0xa0000000 写了一个 32 位数字，最低字节就是退出码
            NPCTrap::Halt(0, exit_code);
        }
        DataWritePending = true;
    }
}
void AXI::HandleDataB(VRV32I &CPU)
{
    if (!DataWritePending)
    {
        return;
    }
    CPU.io_DataBus_B_BVALID = true;
    CPU.io_DataBus_B_BRESP = 0;
    if (CPU.io_DataBus_B_BREADY)
    {
        DataWritePending = false;
    }
}
void AXI::eval(VRV32I &CPU)
{
    HandleDataAR(CPU);
    HandleDataAW_W(CPU);
    HandleDataB(CPU);
    HandleDataR(CPU);
    HandleInstructionAR(CPU);
    HandleInstructionR(CPU);
}