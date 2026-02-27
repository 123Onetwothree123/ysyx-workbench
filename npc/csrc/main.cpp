#include <array>
#include <cstdint>
#include <filesystem>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>

#include <verilated.h>

#include "Vtop.h"

#ifdef TEST_MODE
#include <gtest/gtest.h>
#include "Vtop___024root.h"
#else
#include <nvboard.h>
void nvboard_bind_all_pins(Vtop *top);
#endif

namespace
{
void PrepareRomWorkingDir()
{
  namespace fs = std::filesystem;
  auto is_valid_rom_file = [&](const fs::path &p) -> bool {
    if (!fs::exists(p) || !fs::is_regular_file(p))
    {
      return false;
    }
    std::error_code ec;
    const auto sz = fs::file_size(p, ec);
    if (ec)
    {
      return false;
    }
    return sz > 0;
  };

  if (is_valid_rom_file("rom_data.hex"))
  {
    return;
  }
  const std::array<fs::path, 4> candidates = {
      fs::path("vsrc"), fs::path("../vsrc"), fs::path("npc/vsrc"),
      fs::path("/home/abc/ysyx-workbench/npc/vsrc")};
  for (const auto &dir : candidates)
  {
    const fs::path src = dir / "rom_data.hex";
    if (is_valid_rom_file(src))
    {
      fs::copy_file(src, "rom_data.hex", fs::copy_options::overwrite_existing);
      return;
    }
  }
  throw std::runtime_error("Cannot locate rom_data.hex for Verilator $readmemh");
}
} // namespace

#ifdef TEST_MODE
namespace
{

  constexpr int kMaxCycles = 256;

  constexpr std::array<uint8_t, 16> kExpectedRom = {
      0x81,
      0x90,
      0xAB,
      0xB1,
      0x14,
      0x03,
      0xD2,
      0x50,
      0xDF,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
      0x00,
  };

  constexpr std::array<uint8_t, 16> kHexToSeg = {
      0x40,
      0x79,
      0x24,
      0x30,
      0x19,
      0x12,
      0x02,
      0x78,
      0x00,
      0x10,
      0x08,
      0x03,
      0x46,
      0x21,
      0x06,
      0x0E,
  };

  constexpr uint8_t EncodeSegWithDpOff(uint8_t hex_digit)
  {
    const uint8_t gfedcba_active_low = kHexToSeg[hex_digit & 0xF];
    return static_cast<uint8_t>(
        ((gfedcba_active_low >> 0) & 0x1) << 7 | // A
        ((gfedcba_active_low >> 1) & 0x1) << 6 | // B
        ((gfedcba_active_low >> 2) & 0x1) << 5 | // C
        ((gfedcba_active_low >> 3) & 0x1) << 4 | // D
        ((gfedcba_active_low >> 4) & 0x1) << 3 | // E
        ((gfedcba_active_low >> 5) & 0x1) << 2 | // F
        ((gfedcba_active_low >> 6) & 0x1) << 1 | // G
        0x1                                       // DP off
    );
  }

  class TopCpuTest : public ::testing::Test
  {
  protected:
    void SetUp() override
    {
      PrepareRomWorkingDir();
      top_ = std::make_unique<Vtop>();
      top_->clk = 0;
      top_->rst_n = 0;
      top_->WE = 0;
      top_->DATAIn = 0;
      top_->eval();
      Tick();
      top_->rst_n = 1;
      top_->eval();
    }

    void TearDown() override { top_.reset(); }

    void Tick()
    {
      top_->clk = 0;
      top_->eval();
      top_->clk = 1;
      top_->eval();
      top_->clk = 0;
      top_->eval();
    }

    uint8_t Pc() const { return Root()->top__DOT__u_scpu__DOT__pc_result & 0xF; }
    uint8_t PcNext() const { return Root()->top__DOT__u_scpu__DOT__pc_next & 0xF; }
    uint8_t Reg0() const
    {
      return Root()->top__DOT__u_scpu__DOT__gpr_inst__DOT__reg_dout0;
    }
    uint8_t Reg1() const
    {
      return Root()->top__DOT__u_scpu__DOT__gpr_inst__DOT__reg_dout1;
    }
    uint8_t Reg2() const
    {
      return Root()->top__DOT__u_scpu__DOT__gpr_inst__DOT__reg_dout2;
    }
    uint8_t Reg3() const
    {
      return Root()->top__DOT__u_scpu__DOT__gpr_inst__DOT__reg_dout3;
    }
    uint8_t RegWe() const { return Root()->top__DOT__u_scpu__DOT__reg_we_result; }
    uint8_t RdSel() const { return Root()->top__DOT__u_scpu__DOT__rd_select_result; }
    uint8_t GprWData() const { return Root()->top__DOT__u_scpu__DOT__gpr_wdata; }
    uint8_t RomAt(uint8_t addr) const
    {
      return Root()->top__DOT__u_scpu__DOT__scpuROM__DOT__rom_mem[addr & 0xF];
    }

    void RunUntilPcAndRegs(uint8_t target_pc, uint8_t target_r0, uint8_t target_r1,
                           int max_cycles = kMaxCycles)
    {
      for (int i = 0; i < max_cycles; ++i)
      {
        if (Pc() == target_pc && Reg0() == target_r0 && Reg1() == target_r1)
        {
          return;
        }
        Tick();
      }
      FAIL() << "Timeout waiting for state pc=" << static_cast<int>(target_pc)
             << " r0=" << static_cast<int>(target_r0)
             << " r1=" << static_cast<int>(target_r1)
             << ", current pc=" << static_cast<int>(Pc())
             << " r0=" << static_cast<int>(Reg0())
             << " r1=" << static_cast<int>(Reg1());
    }

    Vtop___024root *Root() const { return top_->rootp; }

    std::unique_ptr<Vtop> top_;
  };

  TEST_F(TopCpuTest, RomImageIsLoadedAsExpected)
  {
    for (size_t i = 0; i < kExpectedRom.size(); ++i)
    {
      EXPECT_EQ(RomAt(static_cast<uint8_t>(i)), kExpectedRom[i]) << "ROM addr " << i;
    }
  }

  TEST_F(TopCpuTest, ResetStateIsClean)
  {
    EXPECT_EQ(Pc(), 0);
    EXPECT_EQ(Reg0(), 0);
    EXPECT_EQ(Reg1(), 0);
    EXPECT_EQ(Reg2(), 0);
    EXPECT_EQ(Reg3(), 0);
  }

  TEST_F(TopCpuTest, LiBootSequenceLoadsFourRegisters)
  {
    Tick();
    EXPECT_EQ(Pc(), 1);
    EXPECT_EQ(Reg0(), 1);

    Tick();
    EXPECT_EQ(Pc(), 2);
    EXPECT_EQ(Reg1(), 0);

    Tick();
    EXPECT_EQ(Pc(), 3);
    EXPECT_EQ(Reg2(), 11);

    Tick();
    EXPECT_EQ(Pc(), 4);
    EXPECT_EQ(Reg3(), 1);
  }

  TEST_F(TopCpuTest, ControlAndWritebackSignalsMatchCurrentInstruction)
  {
    EXPECT_EQ(Pc(), 0);
    EXPECT_EQ(RegWe(), 1);
    EXPECT_EQ(RdSel(), 0);
    EXPECT_EQ(GprWData(), 1);

    Tick();
    EXPECT_EQ(Pc(), 1);
    EXPECT_EQ(RegWe(), 1);
    EXPECT_EQ(RdSel(), 1);
    EXPECT_EQ(GprWData(), 0);

    Tick();
    Tick();
    Tick();
    EXPECT_EQ(Pc(), 4);
    EXPECT_EQ(RegWe(), 1);
    EXPECT_EQ(RdSel(), 1);
    EXPECT_EQ(GprWData(), 1);

    Tick();
    Tick();
    EXPECT_EQ(Pc(), 6);
    EXPECT_EQ(RegWe(), 0);
  }

  TEST_F(TopCpuTest, AddPathAndBranchTakenAtPc6WorkCorrectly)
  {
    for (int i = 0; i < 6; ++i)
    {
      Tick();
    }
    EXPECT_EQ(Pc(), 6);
    EXPECT_EQ(Reg0(), 2);
    EXPECT_EQ(Reg1(), 1);
    EXPECT_EQ(RegWe(), 0);
    EXPECT_EQ(PcNext(), 4);

    Tick();
    EXPECT_EQ(Pc(), 4);
  }

  TEST_F(TopCpuTest, ProgramEventuallyExitsComputeLoopAndReachesOut)
  {
    RunUntilPcAndRegs(/*target_pc=*/6, /*target_r0=*/11, /*target_r1=*/55);
    EXPECT_EQ(Reg2(), 11);
    EXPECT_EQ(PcNext(), 7);

    Tick(); // execute bner0@pc6 -> pc7
    EXPECT_EQ(Pc(), 7);
    EXPECT_EQ(Reg0(), 11);
    EXPECT_EQ(Reg1(), 55);
    EXPECT_EQ(top_->scpuResult, 0);

    Tick(); // execute out@pc7 -> pc8
    EXPECT_EQ(Pc(), 8);
    EXPECT_EQ(top_->scpuResult, Reg1());
    EXPECT_EQ(top_->seg0, EncodeSegWithDpOff(Reg1() & 0xF));
    EXPECT_EQ(top_->seg1, EncodeSegWithDpOff((Reg1() >> 4) & 0xF));
  }

  TEST_F(TopCpuTest, OutAndTailBranchFormStableTwoInstructionLoop)
  {
    RunUntilPcAndRegs(/*target_pc=*/7, /*target_r0=*/11, /*target_r1=*/55);
    Tick(); // execute out@pc7 -> pc8
    EXPECT_EQ(Pc(), 8);
    EXPECT_EQ(top_->scpuResult, Reg1());
    EXPECT_EQ(top_->seg0, EncodeSegWithDpOff(Reg1() & 0xF));
    EXPECT_EQ(top_->seg1, EncodeSegWithDpOff((Reg1() >> 4) & 0xF));

    Tick(); // execute bner0@pc8 -> pc7
    EXPECT_EQ(Pc(), 7);
    EXPECT_EQ(top_->scpuResult, Reg1());
    EXPECT_EQ(top_->seg0, EncodeSegWithDpOff(Reg1() & 0xF));
    EXPECT_EQ(top_->seg1, EncodeSegWithDpOff((Reg1() >> 4) & 0xF));

    Tick(); // execute out@pc7 -> pc8
    EXPECT_EQ(Pc(), 8);
    EXPECT_EQ(top_->scpuResult, Reg1());
  }

} // namespace
#endif

int main(int argc, char *argv[])
{
  std::cout << "开始进行仿真测试" << std::endl;
  if (argc > 0)
  {
    Verilated::commandArgs(argc, argv);
  }
#ifdef TEST_MODE
  try
  {
    PrepareRomWorkingDir();
  }
  catch (const std::exception &e)
  {
    std::cerr << "GTest setup failed: " << e.what() << std::endl;
    return 1;
  }
  std::cout << "Running GTest Mode..." << std::endl;
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
#else
  std::cout << "开始nvboard测试" << std::endl;
  try
  {
    PrepareRomWorkingDir();
  }
  catch (const std::exception &e)
  {
    std::cerr << "NVBoard setup failed: " << e.what() << std::endl;
    return 1;
  }
  auto top = std::make_unique<Vtop>();
  nvboard_bind_all_pins(top.get());
  nvboard_init();

  auto single_cycle = [&]() {
    top->clk = 0;
    top->eval();
    top->clk = 1;
    top->eval();
  };
  top->rst_n = 0;
  for (int i = 0; i < 10; ++i) {
    single_cycle();
  }
  top->rst_n = 1;
  while (1)
  {
    nvboard_update();
    top->rst_n = 1;
    single_cycle();
  }
  nvboard_quit();
  return 0;
#endif
}
