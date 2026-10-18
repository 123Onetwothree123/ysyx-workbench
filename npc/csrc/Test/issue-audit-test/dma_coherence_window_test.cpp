// 功能确认：DMA 写与 CPU 缓存之间的一致性窗口。
//
// 疑点（静态审计）：DMA 写的缓存失效由 Arbiter 在**匹配 B 握手**时产生
// （DMAWriteInvalidate），但写数据可能在这之前就已落到内存。因此在
// [W 落内存, B] 窗口内，CPU 命中 DCache 旧行会读到陈旧数据。
//
// 本测试用 CHIPLINK=y 的 SoC 顶层（DMA 代理启用），测试台扮演"对端 SoC"：
//   - 服务 CPU master 口的读写（程序在 MROM 0x30000000，数据在
//     ChipLink 内存 0xc0000000，两者都可缓存）；
//   - 通过 CPU slave 口发起一笔 DMA 写（0xc0000000 ← 新值），写进内存模型后
//     **故意扣住 B 响应**（把窗口拉长到可观测）；
//   - CPU 程序：读 X（miss 填充）→ 延迟 → 再读 X（命中）→ 延迟 → 再读 X → halt。
// 预期（若窗口存在）：
//   x6 = 旧值（首次读）；x7 = 旧值（窗口内命中陈旧行）；x8 = 新值（B 后失效重填）。
//
// 说明：AXI 允许从设备在 B 之前提交写，因此这是真实竞态；设计注释只承诺
// "B 之后的 load 不命中旧行"，窗口内的一致性没有机制保证。
//
// 结论（2026-10-18，已跑通并纳入回归）：
//   x6 = 旧值（miss 填充）、x7 = 旧值（B 之前的窗口内命中陈旧行）、
//   x8 = 新值（B 触发全局失效后重填）——窗口端到端确认。
//
// 窗口是"CPU 缓存 + 非一致性 AXI DMA 主设备"架构的固有性质：AXI 允许从设备
// 在 B 之前落盘，主设备在 B 之前无从得知落盘时刻，因此硬件能给出的最早保证
// 就是"匹配 B 时全局失效"。顶层已文档化契约：写直达 DCache 无脏数据，匹配 B
// 失效保证后续 load 不命中旧行；DMA 改可执行内存后由软件 fence.i 保证指令
// 一致性。软件侧的契约：DMA 传输在途时不得消费目标区域（轮询类用法天然满足，
// 且最终 B 的全局失效会覆盖窗口内可能填入的任何陈旧行）。
//
// 诊断记录：此前观察到的"无 DMA 参与也卡死"是**本测试台的时序缺陷**，非 RTL
// 缺陷——测试台从设备/DMA 主设备在判定握手时使用了一拍前的 READY（假握手），
// 导致 R 拍在总线上多保持一拍，被 Xbar 当作第二个拍重复捕获而卡住 R buffer。
// 已修复：施加 VALID 后先 eval 再判定 READY（见下方注释）。真实 AXIRAM 为
// 寄存器式 FSM（RVALID := state === sReadResp），握手后下一拍即撤销，不受影响。

#include "Vysyx_26030103.h"
#include "test_common.hpp"

#include <cstdint>
#include <map>
#include <string>
#include <vector>
#include <verilated.h>
#ifdef DIAG_TRACE
#include <verilated_vcd_c.h>
#endif

using DUT = Vysyx_26030103;

static constexpr uint32_t kProgramBase = 0x30000000U;
static constexpr uint32_t kDataAddr = 0xC0000000U;
static constexpr uint32_t kOldValue = 0xAAAA0000U;
static constexpr uint32_t kNewValue = 0xBBBB0000U;

#ifdef DIAG_NO_LOADS
// 诊断：去掉全部 load（LSU 读），只看 IFU 取指是否仍会卡住。
static const uint32_t kProgram[] = {
    0xC00002B7U, // lui  x5, 0xc0000
    0x00100313U, // addi x6, x0, 1   （替代 lw）
    0x00100393U, // addi x7, x0, 1
    0x00200413U, // addi x8, x0, 2
    0x00000093U, // addi x1, x0, 0
    0x0C800113U, // addi x2, x0, 200
    0x00108093U, // addi x1, x1, 1
    0xFE209EE3U, // bne  x1, x2, -4
    0x00100393U, // addi x7, x0, 1
    0x00000093U, // addi x1, x0, 0
    0x0C800113U, // addi x2, x0, 200
    0x00108093U, // addi x1, x1, 1
    0xFE209EE3U, // bne  x1, x2, -4
    0x00200413U, // addi x8, x0, 2
    0x0000000BU, // halt
};
#else
static const uint32_t kProgram[] = {
    0xC00002B7U, // lui  x5, 0xc0000      ; x5 = 0xc0000000
    0x0002A303U, // lw   x6, 0(x5)        ; 第一次读：miss → 旧值
    0x00000093U, // addi x1, x0, 0        ; delay1 计数
    0x0C800113U, // addi x2, x0, 200
    0x00108093U, // addi x1, x1, 1        ; delay1 循环体
    0xFE209EE3U, // bne  x1, x2, -4       ; 回 delay1 循环体
    0x0002A383U, // lw   x7, 0(x5)        ; 第二次读：命中 → 窗口内陈旧值
    0x00000093U, // addi x1, x0, 0        ; delay2
    0x0C800113U, // addi x2, x0, 200
    0x00108093U, // addi x1, x1, 1
    0xFE209EE3U, // bne  x1, x2, -4
    0x0002A403U, // lw   x8, 0(x5)        ; 第三次读：B 已释放 → 失效重填 → 新值
    0x0000000BU, // custom-0 halt
};
#endif

struct Memory {
  std::map<uint32_t, uint32_t> words;

  uint32_t read(uint32_t address) const {
    const auto it = words.find(address);
    return it == words.end() ? 0U : it->second;
  }
  void write(uint32_t address, uint32_t data) { words[address] = data; }
};

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  return run_test("DMA write vs DCache coherence window", [] {
    DUT dut;
#ifdef DIAG_TRACE
    Verilated::traceEverOn(true);
    VerilatedVcdC *tfp = new VerilatedVcdC;
    dut.trace(tfp, 99);
    tfp->open("dma_window_diag.vcd");
#endif
    Memory mem;

    // 程序与数据初始化
    for (size_t index = 0; index < sizeof(kProgram) / sizeof(kProgram[0]);
         ++index) {
      mem.write(kProgramBase + static_cast<uint32_t>(index) * 4U,
                kProgram[index]);
    }
    for (uint32_t word = 0; word < 4; ++word) {
      mem.write(kDataAddr + word * 4U, kOldValue + word);
    }

    // ---- 端口默认值 ----
    dut.clock = 0;
    dut.reset = 1;
    dut.io_interrupt = 0;
    // CPU master 口（本测试台是下游从设备）
    dut.io_master_arready = 0;
    dut.io_master_rvalid = 0;
    dut.io_master_rdata = 0;
    dut.io_master_rresp = 0;
    dut.io_master_rlast = 0;
    dut.io_master_rid = 0;
    dut.io_master_awready = 0;
    dut.io_master_wready = 0;
    dut.io_master_bvalid = 0;
    dut.io_master_bresp = 0;
    dut.io_master_bid = 0;
    // CPU slave 口（本测试台是 DMA 主设备）
    dut.io_slave_awvalid = 0;
    dut.io_slave_awaddr = 0;
    dut.io_slave_awid = 0;
    dut.io_slave_awlen = 0;
    dut.io_slave_awsize = 2;
    dut.io_slave_awburst = 1;
    dut.io_slave_awprot = 0;
    dut.io_slave_wvalid = 0;
    dut.io_slave_wdata = 0;
    dut.io_slave_wstrb = 0xF;
    dut.io_slave_wlast = 0;
    dut.io_slave_bready = 1;
    dut.io_slave_arvalid = 0;
    dut.io_slave_araddr = 0;
    dut.io_slave_arid = 0;
    dut.io_slave_arlen = 0;
    dut.io_slave_arsize = 2;
    dut.io_slave_arburst = 1;
    dut.io_slave_arprot = 0;
    dut.io_slave_rready = 1;
    dut.io_debug_gpr_raddr = 0;

    auto edge = [&] {
      dut.clock = 1;
      dut.eval();
      dut.clock = 0;
      dut.eval();
    };
    edge();
    edge();
    dut.reset = 0;
    dut.eval();

    // ---- 测试台状态 ----
    // 下游从设备（CPU master 口）的读通道：与 AXIRAM 相同的三态 FSM
    // （Idle → Wait → Resp，每拍一个 beat，RVALID 仅在 Resp，等待 RREADY）。
    enum class RdState { Idle, Wait, Resp };
    RdState rd_state = RdState::Idle;
    int rd_beats_left = 0;
    uint32_t rd_address = 0;
    uint8_t rd_id = 0;
    // 写通道
    bool aw_pending = false;
    uint32_t aw_address = 0;
    uint8_t aw_id = 0;
    bool b_pending = false;      // 需要回 B
    bool b_is_dma = false;       // 该 B 属于 DMA 写（可扣住）
    bool b_held = false;         // 当前扣住不放
    uint8_t b_id = 0;
    // DMA 主设备（CPU slave 口）
    enum class DmaState { Idle, SendAw, SendW, WaitB, Done };
    DmaState dma_state = DmaState::Idle;
    bool dma_b_seen = false;
    // 观察
    int data_loads = 0;          // 对 0xc0000000 的 load 次数（按提交顺序）
    bool dma_started = false;
    int r_stuck_cycles = 0;
    bool b_released = false;

    long cycle = 0;
    for (; cycle < 20000; ++cycle) {
      dut.eval();

      // ---- 观察 mtrace：统计对数据地址的 load ----
      if (dut.io_debug_mtrace_valid && !dut.io_debug_mtrace_wen &&
          dut.io_debug_mtrace_addr == kDataAddr) {
        data_loads++;
        if (data_loads == 1 && !dma_started) {
          // 诊断模式：DIAG_DMA_AFTER_ALL=1 时改到全部 load 之后再发 DMA
#ifdef DIAG_DMA_AFTER_ALL
          if (false) {
            dma_state = DmaState::SendAw;
          }
#else
          dma_state = DmaState::SendAw; // 第一次读完成后发起 DMA 写
#endif
        }
#ifdef DIAG_DMA_AFTER_ALL
        if (data_loads == 3 && !dma_started) {
          dma_state = DmaState::SendAw;
        }
#endif
        if (data_loads == 2) {
          // 第二次读已在窗口内命中陈旧行：现在释放 B
          b_held = false;
          b_released = true;
        }
      }

      // ---- 下游从设备：读通道（AXIRAM 同构 FSM）----
      dut.io_master_arready = (rd_state == RdState::Idle) ? 1 : 0;
      if (dut.io_master_arready && dut.io_master_arvalid) {
        rd_address = dut.io_master_araddr;
        rd_id = dut.io_master_arid;
        rd_beats_left = static_cast<int>(dut.io_master_arlen) + 1;
        rd_state = RdState::Wait;
        #ifdef DIAG_EV_LOG
        if (cycle < 2000) {
          std::cerr << "EV AR c=" << cycle << " id=" << (int)rd_id << " addr=0x"
                    << std::hex << rd_address << std::dec << " len="
                    << (int)dut.io_master_arlen << "\n";
        }
#endif
      }
      dut.io_master_rvalid = 0;
      dut.io_master_rlast = 0;
      if (rd_state == RdState::Wait) {
        rd_state = RdState::Resp;
      } else if (rd_state == RdState::Resp) {
        dut.io_master_rvalid = 1;
        dut.io_master_rdata = mem.read(rd_address);
        dut.io_master_rid = rd_id;
        dut.io_master_rresp = 0;
        dut.io_master_rlast = (rd_beats_left == 1);
        // 关键：RVALID 施加后重新 eval，再判断握手——否则判断用的是上一拍
        // （RVALID 尚未拉高）的 RREADY，导致同一拍在总线上多保持一拍，
        // 会被 Xbar 当作第二个拍重复捕获。
        dut.eval();
#ifdef DIAG_EV_LOG
        if (cycle < 2000) {
          std::cerr << "EV R  c=" << cycle << " id=" << (int)rd_id << " addr=0x"
                    << std::hex << rd_address << std::dec << " last="
                    << (int)dut.io_master_rlast << " rready="
                    << (int)dut.io_master_rready << "\n";
        }
#endif
        if (dut.io_master_rready) {
          rd_address += 4;
          rd_beats_left--;
          rd_state = (rd_beats_left == 0) ? RdState::Idle : RdState::Wait;
        }
      }

      // 卡住检测：同一拍 R 被拒 > 60 拍时打印 RID/地址/RLAST
      if (dut.io_master_rvalid && !dut.io_master_rready) {
        r_stuck_cycles++;
        if (r_stuck_cycles == 60) {
          std::cerr << "STUCK R c=" << cycle << " rid=" << (int)rd_id
                    << " addr=0x" << std::hex << rd_address << std::dec
                    << " last=" << (int)dut.io_master_rlast
                    << " beats=" << rd_beats_left << "\n";
        }
      } else {
        r_stuck_cycles = 0;
      }

      // ---- 下游从设备：写通道 ----
      dut.io_master_awready = aw_pending ? 0 : 1;
      if (dut.io_master_awready && dut.io_master_awvalid) {
        aw_address = dut.io_master_awaddr;
        aw_id = dut.io_master_awid;
        aw_pending = true;
      }
      dut.io_master_wready = aw_pending ? 1 : 0;
      if (dut.io_master_wready && dut.io_master_wvalid) {
        // 简化：单拍写，直接写入内存模型（先于 B 落盘——这正是窗口的由来）
        mem.write(aw_address, dut.io_master_wdata);
        if (dut.io_master_wlast) {
          aw_pending = false;
          b_pending = true;
          b_id = aw_id;
          b_is_dma = (aw_id == 2);
          if (b_is_dma) {
            b_held = true; // DMA 写的 B 先扣住
          }
        }
      }
      dut.io_master_bvalid = (b_pending && !b_held) ? 1 : 0;
      dut.io_master_bid = b_id;
      dut.io_master_bresp = 0;
      if (dut.io_master_bvalid && dut.io_master_bready) {
        b_pending = false;
      }

      // ---- DMA 主设备（CPU slave 口）----
      // 握手判定必须在施加 VALID 后重新 eval，否则用的是一拍前的 READY，
      // 会出现"自认为握手成功但总线上从未发生"的假握手。
      dut.io_slave_awvalid = 0;
      dut.io_slave_wvalid = 0;
      switch (dma_state) {
      case DmaState::Idle:
        break;
      case DmaState::SendAw:
        dut.io_slave_awvalid = 1;
        dut.io_slave_awaddr = kDataAddr;
        dut.io_slave_awid = 2;
        dut.io_slave_awlen = 0;
        dut.io_slave_awsize = 2;
        dut.io_slave_awburst = 1;
        dut.eval();
        if (dut.io_slave_awready) {
          dma_state = DmaState::SendW;
        }
        break;
      case DmaState::SendW:
        dut.io_slave_wvalid = 1;
        dut.io_slave_wdata = kNewValue;
        dut.io_slave_wstrb = 0xF;
        dut.io_slave_wlast = 1;
        dut.eval();
        if (dut.io_slave_wready) {
          dma_state = DmaState::WaitB;
        }
        break;
      case DmaState::WaitB:
        if (dut.io_slave_bvalid) {
          dma_b_seen = true;
          dma_state = DmaState::Done;
        }
        break;
      case DmaState::Done:
        break;
      }

      if (dut.io_trap_valid) {
        break;
      }
      edge();
#ifdef DIAG_TRACE
      if (cycle < 400) {
        tfp->dump(static_cast<vluint64_t>(cycle * 2));
        tfp->dump(static_cast<vluint64_t>(cycle * 2 + 1));
      }
#endif
    }
#ifdef DIAG_TRACE
    tfp->close();
#endif

    if (!dut.io_trap_valid) {
      auto gpr0 = [&](int index) -> uint32_t {
        dut.io_debug_gpr_raddr = static_cast<uint8_t>(index);
        dut.eval();
        return dut.io_debug_gpr_rdata;
      };
      std::cerr << "DIAG timeout: x1=0x" << std::hex << gpr0(1) << " x2=0x"
                << gpr0(2) << " x5=0x" << gpr0(5) << " x6=0x" << gpr0(6)
                << " x7=0x" << gpr0(7) << " x8=0x" << gpr0(8) << std::dec
                << " data_loads=" << data_loads << "\n";
    }
    check(dut.io_trap_valid, "program did not reach the halt");
    check(data_loads == 3,
          "expected exactly three data loads, observed " +
              std::to_string(data_loads));
    check(b_released, "testbench never released the DMA B response");
    check(dma_b_seen, "DMA write never completed");

    // ---- 读回架构寄存器：x6/x7/x8 ----
    auto gpr = [&](int index) -> uint32_t {
      dut.io_debug_gpr_raddr = static_cast<uint8_t>(index);
      dut.eval();
      return dut.io_debug_gpr_rdata;
    };
    const uint32_t x6 = gpr(6);
    const uint32_t x7 = gpr(7);
    const uint32_t x8 = gpr(8);

    std::cout << "[info] x6(首次读)=" << std::hex << x6 << " x7(窗口内读)=" << x7
              << " x8(B 之后读)=" << x8 << std::dec << "\n";

    check(x6 == kOldValue,
          "first load should read the pre-DMA value, got 0x" +
              std::to_string(x6));
    // 窗口内命中陈旧行：这是本测试要确认的功能缺陷。
    check(x7 == kOldValue,
          "expected the in-window load to return the STALE cached value "
          "(0x" + std::to_string(kOldValue) + "), got 0x" +
              std::to_string(x7));
    check(x8 == kNewValue,
          "post-B load should observe the DMA value after invalidation, got "
          "0x" + std::to_string(x8));
    dut.final();
  });
}
