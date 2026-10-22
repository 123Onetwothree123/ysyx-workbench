package ysyx_26030103.infra

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** AXI5 32<->64 宽度桥定向测试。
  *
  * 覆盖 NPC 当前实际使用的访问形状：4 字节单拍、RV64 LD/SD 的
  * 8 字节两拍（32 位总线上为 ARLEN=1/AWSIZE=2），以及 64 位 SoC
  * 入站 DMA 的 8 字节单拍（size=3）拆成两笔 4 字节事务。地址越界
  * 必须在桥内返回 DECERR，且下游看不到任何 VALID。
  */
class ysyx_26030103_AXI5WidthBridgeTest extends AnyFlatSpec with Matchers {
  private def bool(Value: Bool): Boolean = Value.peek().litValue == 1
  private def big(Value: UInt): BigInt = Value.peek().litValue

  private def stepUntil(Clock: Clock, Cond: => Boolean, Limit: Int = 100): Unit = {
    var Cycles = 0
    while (!Cond && Cycles < Limit) {
      Clock.step()
      Cycles += 1
    }
    withClue(s"等待条件超过 $Limit 拍: ") { Cond should be(true) }
  }

  private def resetDut(Dut: ysyx_26030103_AXI5WidthBridge): Unit = {
    Dut.reset.poke(true.B)
    Dut.clock.step(2)
    Dut.reset.poke(false.B)

    Dut.io.in.AW.AWVALID.poke(false.B)
    Dut.io.in.AW.AWADDR.poke(0.U(Dut.io.in.AW.AWADDR.getWidth.W))
    Dut.io.in.AW.AWID.poke(0.U(4.W))
    Dut.io.in.AW.AWLEN.poke(0.U(8.W))
    Dut.io.in.AW.AWSIZE.poke(2.U(3.W))
    Dut.io.in.AW.AWBURST.poke(0.U(2.W))
    Dut.io.in.AW.AWPROT.poke(0.U(3.W))
    Dut.io.in.AW.AWCACHE.poke(0.U(4.W))
    Dut.io.in.AW.AWQOS.poke(0.U(4.W))

    Dut.io.in.W.WVALID.poke(false.B)
    Dut.io.in.W.WDATA.poke(0.U(Dut.io.in.W.WDATA.getWidth.W))
    Dut.io.in.W.WSTRB.poke(0.U(Dut.io.in.W.WSTRB.getWidth.W))
    Dut.io.in.W.WLAST.poke(false.B)

    Dut.io.in.B.BREADY.poke(true.B)

    Dut.io.in.AR.ARVALID.poke(false.B)
    Dut.io.in.AR.ARADDR.poke(0.U(Dut.io.in.AR.ARADDR.getWidth.W))
    Dut.io.in.AR.ARID.poke(0.U(4.W))
    Dut.io.in.AR.ARLEN.poke(0.U(8.W))
    Dut.io.in.AR.ARSIZE.poke(2.U(3.W))
    Dut.io.in.AR.ARBURST.poke(0.U(2.W))
    Dut.io.in.AR.ARPROT.poke(0.U(3.W))
    Dut.io.in.AR.ARCACHE.poke(0.U(4.W))
    Dut.io.in.AR.ARQOS.poke(0.U(4.W))

    Dut.io.in.R.RREADY.poke(true.B)

    Dut.io.out.AW.AWREADY.poke(true.B)
    Dut.io.out.W.WREADY.poke(true.B)
    Dut.io.out.B.BVALID.poke(false.B)
    Dut.io.out.B.BID.poke(0.U(4.W))
    Dut.io.out.B.BRESP.poke(0.U(2.W))
    Dut.io.out.AR.ARREADY.poke(true.B)
    Dut.io.out.R.RVALID.poke(false.B)
    Dut.io.out.R.RID.poke(0.U(4.W))
    Dut.io.out.R.RDATA.poke(0.U(Dut.io.out.R.RDATA.getWidth.W))
    Dut.io.out.R.RRESP.poke(0.U(2.W))
    Dut.io.out.R.RLAST.poke(false.B)
  }

  private def offerAw(
      Dut: ysyx_26030103_AXI5WidthBridge,
      Address: BigInt,
      Len: Int,
      Size: Int,
      Burst: Int = 1
  ): Unit = {
    Dut.io.in.AW.AWVALID.poke(true.B)
    Dut.io.in.AW.AWADDR.poke(Address.U(Dut.io.in.AW.AWADDR.getWidth.W))
    Dut.io.in.AW.AWID.poke(1.U(4.W))
    Dut.io.in.AW.AWLEN.poke(Len.U(8.W))
    Dut.io.in.AW.AWSIZE.poke(Size.U(3.W))
    Dut.io.in.AW.AWBURST.poke(Burst.U(2.W))
    stepUntil(Dut.clock, bool(Dut.io.in.AW.AWREADY))
    Dut.clock.step()
    Dut.io.in.AW.AWVALID.poke(false.B)
  }

  private def offerW(
      Dut: ysyx_26030103_AXI5WidthBridge,
      Data: BigInt,
      Strb: BigInt,
      Last: Boolean
  ): Unit = {
    Dut.io.in.W.WVALID.poke(true.B)
    Dut.io.in.W.WDATA.poke(Data.U(Dut.io.in.W.WDATA.getWidth.W))
    Dut.io.in.W.WSTRB.poke(Strb.U(Dut.io.in.W.WSTRB.getWidth.W))
    Dut.io.in.W.WLAST.poke(Last.B)
    stepUntil(Dut.clock, bool(Dut.io.in.W.WREADY))
    Dut.clock.step()
    Dut.io.in.W.WVALID.poke(false.B)
  }

  private def offerAr(
      Dut: ysyx_26030103_AXI5WidthBridge,
      Address: BigInt,
      Len: Int,
      Size: Int,
      Burst: Int = 1
  ): Unit = {
    Dut.io.in.AR.ARVALID.poke(true.B)
    Dut.io.in.AR.ARADDR.poke(Address.U(Dut.io.in.AR.ARADDR.getWidth.W))
    Dut.io.in.AR.ARID.poke(1.U(4.W))
    Dut.io.in.AR.ARLEN.poke(Len.U(8.W))
    Dut.io.in.AR.ARSIZE.poke(Size.U(3.W))
    Dut.io.in.AR.ARBURST.poke(Burst.U(2.W))
    stepUntil(Dut.clock, bool(Dut.io.in.AR.ARREADY))
    Dut.clock.step()
    Dut.io.in.AR.ARVALID.poke(false.B)
  }

  private def newBridge(
      UpAddr: Int,
      DownAddr: Int,
      UpData: Int,
      DownData: Int
  ): ysyx_26030103_AXI5WidthBridge =
    new ysyx_26030103_AXI5WidthBridge(
      UpstreamAddressWidth = UpAddr,
      DownstreamAddressWidth = DownAddr,
      UpstreamDataWidth = UpData,
      DownstreamDataWidth = DownData
    )

  "AXI5WidthBridge" should "32->64 单拍写落到高半区并保持 WSTRB/RLAST 语义" in {
    simulate(newBridge(64, 64, 32, 64)) { dut =>
      resetDut(dut)
      offerAw(dut, 0x104L, 0, 2)
      stepUntil(dut.clock, bool(dut.io.out.AW.AWVALID))
      big(dut.io.out.AW.AWADDR) should be(0x104L)
      big(dut.io.out.AW.AWLEN) should be(0)
      dut.clock.step()

      dut.io.in.W.WVALID.poke(true.B)
      dut.io.in.W.WDATA.poke(0x11223344L.U(32.W))
      dut.io.in.W.WSTRB.poke(0xf.U(4.W))
      dut.io.in.W.WLAST.poke(true.B)
      stepUntil(dut.clock, bool(dut.io.out.W.WVALID))
      big(dut.io.out.W.WDATA) should be(BigInt("1122334400000000", 16))
      big(dut.io.out.W.WSTRB) should be(BigInt(0xf0))
      bool(dut.io.out.W.WLAST) should be(true)
      dut.clock.step()
      dut.io.in.W.WVALID.poke(false.B)

      dut.io.out.B.BVALID.poke(true.B)
      dut.io.out.B.BID.poke(1.U(4.W))
      dut.io.out.B.BRESP.poke(0.U(2.W))
      stepUntil(dut.clock, bool(dut.io.in.B.BVALID))
      big(dut.io.in.B.BID) should be(1)
      big(dut.io.in.B.BRESP) should be(0)
      dut.clock.step()
      dut.io.out.B.BVALID.poke(false.B)
    }
  }

  it should "32->64 单拍读保持 OKAY/RLAST 语义" in {
    simulate(newBridge(64, 64, 32, 64)) { dut =>
      resetDut(dut)
      offerAr(dut, 0x100L, 0, 2)
      stepUntil(dut.clock, bool(dut.io.out.AR.ARVALID))
      big(dut.io.out.AR.ARADDR) should be(0x100L)
      dut.clock.step()

      dut.io.out.R.RVALID.poke(true.B)
      dut.io.out.R.RID.poke(1.U(4.W))
      dut.io.out.R.RDATA.poke(BigInt("00000000deadbeef", 16).U(64.W))
      dut.io.out.R.RRESP.poke(0.U(2.W))
      dut.io.out.R.RLAST.poke(true.B)
      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      dut.clock.step()
      dut.io.out.R.RVALID.poke(false.B)
    }
  }

  it should "32->64 8B 两拍写逐拍映射到低/高半区" in {
    simulate(newBridge(64, 64, 32, 64)) { dut =>
      resetDut(dut)
      offerAw(dut, 0x200L, 1, 2)
      stepUntil(dut.clock, bool(dut.io.out.AW.AWVALID))
      dut.clock.step()

      dut.io.in.W.WVALID.poke(true.B)
      dut.io.in.W.WDATA.poke(0x11112222L.U(32.W))
      dut.io.in.W.WSTRB.poke(0xf.U(4.W))
      dut.io.in.W.WLAST.poke(false.B)
      stepUntil(dut.clock, bool(dut.io.out.W.WVALID))
      big(dut.io.out.W.WDATA) should be(BigInt("0000000011112222", 16))
      big(dut.io.out.W.WSTRB) should be(BigInt(0x0f))
      bool(dut.io.out.W.WLAST) should be(false)
      dut.clock.step()

      dut.io.in.W.WDATA.poke(0x33334444L.U(32.W))
      dut.io.in.W.WLAST.poke(true.B)
      stepUntil(dut.clock, bool(dut.io.out.W.WVALID))
      big(dut.io.out.W.WDATA) should be(BigInt("3333444400000000", 16))
      big(dut.io.out.W.WSTRB) should be(BigInt(0xf0))
      bool(dut.io.out.W.WLAST) should be(true)
      dut.clock.step()
      dut.io.in.W.WVALID.poke(false.B)

      dut.io.out.B.BVALID.poke(true.B)
      dut.io.out.B.BID.poke(1.U(4.W))
      stepUntil(dut.clock, bool(dut.io.in.B.BVALID))
      dut.clock.step()
      dut.io.out.B.BVALID.poke(false.B)
    }
  }

  it should "32->64 支持 8B 两拍读并保持 RLAST" in {
    simulate(newBridge(64, 64, 32, 64)) { dut =>
      resetDut(dut)
      offerAr(dut, 0x200L, 1, 2)
      stepUntil(dut.clock, bool(dut.io.out.AR.ARVALID))
      dut.clock.step()

      dut.io.out.R.RVALID.poke(true.B)
      dut.io.out.R.RID.poke(1.U(4.W))
      dut.io.out.R.RDATA.poke(BigInt("ffffffff11223344", 16).U(64.W))
      dut.io.out.R.RLAST.poke(false.B)
      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      big(dut.io.in.R.RDATA) should be(BigInt("11223344", 16))
      bool(dut.io.in.R.RLAST) should be(false)
      dut.clock.step()

      dut.io.out.R.RDATA.poke(BigInt("aabbccddffffffff", 16).U(64.W))
      dut.io.out.R.RLAST.poke(true.B)
      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      big(dut.io.in.R.RDATA) should be(BigInt("aabbccdd", 16))
      bool(dut.io.in.R.RLAST) should be(true)
      dut.clock.step()
      dut.io.out.R.RVALID.poke(false.B)
    }
  }

  it should "64->32 把 8B 单拍写拆成两笔 4B 事务并聚合 B" in {
    simulate(newBridge(64, 32, 64, 32)) { dut =>
      resetDut(dut)
      offerAw(dut, 0x1000L, 0, 3)
      offerW(dut, BigInt("1122334455667788", 16), BigInt(0xff), true)

      stepUntil(
        dut.clock,
        bool(dut.io.out.AW.AWVALID) && bool(dut.io.out.W.WVALID)
      )
      big(dut.io.out.AW.AWADDR) should be(0x1000L)
      big(dut.io.out.AW.AWLEN) should be(0)
      big(dut.io.out.AW.AWSIZE) should be(2)
      big(dut.io.out.W.WDATA) should be(BigInt("55667788", 16))
      big(dut.io.out.W.WSTRB) should be(BigInt(0xf))
      bool(dut.io.out.W.WLAST) should be(false)
      dut.clock.step()

      dut.io.out.B.BVALID.poke(true.B)
      dut.io.out.B.BID.poke(1.U(4.W))
      dut.io.out.B.BRESP.poke(0.U(2.W))
      dut.clock.step()
      dut.io.out.B.BVALID.poke(false.B)

      stepUntil(
        dut.clock,
        bool(dut.io.out.AW.AWVALID) && bool(dut.io.out.W.WVALID)
      )
      big(dut.io.out.AW.AWADDR) should be(0x1004L)
      big(dut.io.out.W.WDATA) should be(BigInt("11223344", 16))
      big(dut.io.out.W.WSTRB) should be(BigInt(0xf))
      bool(dut.io.out.W.WLAST) should be(true)
      dut.clock.step()

      dut.io.out.B.BVALID.poke(true.B)
      dut.io.out.B.BID.poke(1.U(4.W))
      dut.io.out.B.BRESP.poke(0.U(2.W))
      dut.clock.step()
      dut.io.out.B.BVALID.poke(false.B)

      stepUntil(dut.clock, bool(dut.io.in.B.BVALID))
      big(dut.io.in.B.BID) should be(1)
      big(dut.io.in.B.BRESP) should be(0)
      dut.clock.step()
    }
  }

  it should "64->32 把 8B 单拍读拼回完整 64 位数据" in {
    simulate(newBridge(64, 32, 64, 32)) { dut =>
      resetDut(dut)
      offerAr(dut, 0x1000L, 0, 3)

      stepUntil(dut.clock, bool(dut.io.out.AR.ARVALID))
      big(dut.io.out.AR.ARADDR) should be(0x1000L)
      big(dut.io.out.AR.ARLEN) should be(0)
      dut.clock.step()

      dut.io.out.R.RVALID.poke(true.B)
      dut.io.out.R.RID.poke(1.U(4.W))
      dut.io.out.R.RDATA.poke(0x55667788L.U(32.W))
      dut.io.out.R.RRESP.poke(0.U(2.W))
      dut.io.out.R.RLAST.poke(true.B)
      dut.clock.step()
      dut.io.out.R.RVALID.poke(false.B)

      stepUntil(dut.clock, bool(dut.io.out.AR.ARVALID))
      big(dut.io.out.AR.ARADDR) should be(0x1004L)
      dut.clock.step()

      dut.io.out.R.RVALID.poke(true.B)
      dut.io.out.R.RDATA.poke(0x11223344L.U(32.W))
      dut.io.out.R.RLAST.poke(true.B)
      dut.clock.step()
      dut.io.out.R.RVALID.poke(false.B)

      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      big(dut.io.in.R.RDATA) should be(BigInt("1122334455667788", 16))
      big(dut.io.in.R.RRESP) should be(0)
      bool(dut.io.in.R.RLAST) should be(true)
      dut.clock.step()
    }
  }

  it should "下游 32 位时对 >4GiB 读返回等长 DECERR 且不下发 AR" in {
    simulate(newBridge(64, 32, 64, 32)) { dut =>
      resetDut(dut)
      offerAr(dut, BigInt("100000000", 16), 1, 2)
      bool(dut.io.out.AR.ARVALID) should be(false)

      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      big(dut.io.in.R.RID) should be(1)
      big(dut.io.in.R.RRESP) should be(3)
      bool(dut.io.in.R.RLAST) should be(false)
      dut.clock.step()

      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      big(dut.io.in.R.RRESP) should be(3)
      bool(dut.io.in.R.RLAST) should be(true)
      dut.clock.step()
    }
  }

  it should "下游 32 位时跨 4GiB 边界的 burst 也返回 DECERR" in {
    simulate(newBridge(64, 32, 64, 32)) { dut =>
      resetDut(dut)
      // 起始地址在低 4GiB，但 ARLEN=1/size=2 的最后一拍越过 4GiB。
      offerAr(dut, 0xfffffffcL, 1, 2)
      bool(dut.io.out.AR.ARVALID) should be(false)
      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      big(dut.io.in.R.RRESP) should be(3)
      dut.clock.step()
      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      bool(dut.io.in.R.RLAST) should be(true)
      dut.clock.step()
    }
  }

  it should "下游 32 位时对 >4GiB 写返回 DECERR 并吃完整笔 W burst" in {
    simulate(newBridge(64, 32, 64, 32)) { dut =>
      resetDut(dut)
      offerAw(dut, BigInt("100000000", 16), 1, 2)
      bool(dut.io.out.AW.AWVALID) should be(false)
      offerW(dut, 0x11111111L, 0xfL, false)
      offerW(dut, 0x22222222L, 0xfL, true)

      stepUntil(dut.clock, bool(dut.io.in.B.BVALID))
      big(dut.io.in.B.BID) should be(1)
      big(dut.io.in.B.BRESP) should be(3)
      dut.clock.step()
    }
  }

  it should "SoC64 时完整保留高于 4GiB 的地址" in {
    simulate(newBridge(64, 64, 32, 64)) { dut =>
      resetDut(dut)
      val Address = BigInt("180001234", 16)
      offerAr(dut, Address, 1, 2)
      stepUntil(dut.clock, bool(dut.io.out.AR.ARVALID))
      big(dut.io.out.AR.ARADDR) should be(Address)
      dut.clock.step()

      dut.io.out.R.RVALID.poke(true.B)
      dut.io.out.R.RID.poke(1.U(4.W))
      dut.io.out.R.RDATA.poke(BigInt("ffffffffa5a5a5a5", 16).U(64.W))
      dut.io.out.R.RLAST.poke(false.B)
      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      dut.clock.step()

      dut.io.out.R.RDATA.poke(BigInt("5a5a5a5affffffff", 16).U(64.W))
      dut.io.out.R.RLAST.poke(true.B)
      stepUntil(dut.clock, bool(dut.io.in.R.RVALID))
      dut.clock.step()
      dut.io.out.R.RVALID.poke(false.B)
    }
  }
}
