package ysyx_26030103.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import _root_.ysyx_26030103.ifu._
import _root_.ysyx_26030103.mem._
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._

/** RV64 64 位地址路径定向测试。
  *
  * 覆盖 PC/NextPC、BTB、RAS、LSU 在地址高于 4GiB 时不再被截断到 32 位；
  * LSU 的 LD/SD 仍以两拍 32 位 AXI 窄传输覆盖 8 字节。
  */
class ysyx_26030103_RV64AddressTest extends AnyFlatSpec with Matchers {
  private val AddrW = 64
  private def bool(value: Bool): Boolean = value.peek().litValue == 1
  private def big(value: UInt): BigInt = value.peek().litValue

  "RV64 PC" should "保存完整 64 位复位地址和目标" in {
    val HighReset = BigInt("100000080", 16)
    simulate(new ysyx_26030103_PC(HighReset.longValue, 64)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.PCEnable.poke(false.B)
      dut.clock.step()
      big(dut.io.ysyx_26030103_PC) should be(HighReset)

      val Next = BigInt("123456788", 16)
      dut.io.ysyx_26030103_NextPC.poke(Next.U(64.W))
      dut.io.PCEnable.poke(true.B)
      dut.clock.step()
      dut.io.PCEnable.poke(false.B)
      dut.clock.step()
      big(dut.io.ysyx_26030103_PC) should be(Next)
    }
  }

  "RV64 NextPC" should "重定向到高于 4GiB 的目标且不截断" in {
    simulate(new ysyx_26030103_NextPC(64)) { dut =>
      val Snpc = BigInt("100000004", 16)
      val Target = BigInt("123456780", 16)
      dut.io.SNPC.poke(Snpc.U(64.W))
      dut.io.RedirectTarget.poke(Target.U(64.W))
      dut.io.Redirect.poke(true.B)
      dut.io.ExceptionTaken.poke(false.B)
      dut.io.ExceptionTarget.poke(0.U(64.W))
      dut.io.PredHit.poke(false.B)
      dut.io.PredTarget.poke(0.U(64.W))
      dut.clock.step(1)
      big(dut.io.ysyx_26030103_NextPC) should be(Target)
    }
  }

  "RV64 BTB" should "用完整 64 位 PC 做 tag 并保存高地址目标" in {
    simulate(new ysyx_26030103_BTB(2, 1, AddrW)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      val Pc = BigInt("100000100", 16)
      val Target = BigInt("123456780", 16)
      dut.io.flush.poke(false.B)
      dut.io.update_valid.poke(true.B)
      dut.io.update_pc.poke((Pc >> 2).U((AddrW - 2).W))
      dut.io.update_target.poke(Target.U(AddrW.W))
      dut.clock.step()
      dut.io.update_valid.poke(false.B)

      dut.io.lookup_pc.poke((Pc >> 2).U((AddrW - 2).W))
      dut.clock.step(1)
      bool(dut.io.hit) should be(true)
      big(dut.io.target) should be(Target)

      // 仅高 32 位 tag 不同、低位 index 相同的 PC 不能命中。
      val AliasPc = BigInt("200000100", 16)
      dut.io.lookup_pc.poke((AliasPc >> 2).U((AddrW - 2).W))
      dut.clock.step(1)
      bool(dut.io.hit) should be(false)
    }
  }

  "RV64 RAS" should "完整保存并返回高地址返回地址" in {
    simulate(new ysyx_26030103_RAS(2, AddrW)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      val Ret = BigInt("123456780", 16)
      dut.io.flush.poke(false.B)
      dut.io.push_valid.poke(true.B)
      dut.io.push_addr.poke(Ret.U(AddrW.W))
      dut.io.pop_valid.poke(false.B)
      dut.clock.step()
      dut.io.push_valid.poke(false.B)
      dut.clock.step(1)
      bool(dut.io.nonempty) should be(true)
      big(dut.io.top) should be(Ret)
    }
  }

  private def resetLsu(dut: ysyx_26030103_LSU, clock: Clock, reset: Reset): Unit = {
    reset.poke(true.B)
    clock.step(2)
    reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage(64)))
    dut.io.out.ready.poke(true.B)
    dut.io.DCacheFlush.poke(false.B)
    dut.io.BusQuiet.poke(true.B)
    dut.io.ReservationClear.poke(false.B)
    dut.io.ViptPAddr.poke(0.U(AddrW.W))
    dut.io.ViptPAddrValid.poke(false.B)
    dut.io.ViptPAddrVaddr.poke(0.U(AddrW.W))
    dut.io.ViptKill.poke(false.B)
    dut.io.PmpFault.poke(false.B)
    dut.io.DataBus.AW.AWREADY.poke(false.B)
    dut.io.DataBus.W.WREADY.poke(false.B)
    dut.io.DataBus.B.BVALID.poke(false.B)
    dut.io.DataBus.B.BID.poke(0.U(4.W))
    dut.io.DataBus.B.BRESP.poke(0.U(2.W))
    dut.io.DataBus.AR.ARREADY.poke(false.B)
    dut.io.DataBus.R.RVALID.poke(false.B)
    dut.io.DataBus.R.RID.poke(0.U(4.W))
    dut.io.DataBus.R.RDATA.poke(0.U(32.W))
    dut.io.DataBus.R.RRESP.poke(0.U(2.W))
    dut.io.DataBus.R.RLAST.poke(false.B)
  }

  private def pokeWideMem(
      dut: ysyx_26030103_LSU,
      address: BigInt,
      write: Boolean
  ): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(new ysyx_26030103_EXUMessage(64)))
    dut.io.in.bits.Retire.poke(true.B)
    dut.io.in.bits.RegisterWrite.poke((!write).B)
    dut.io.in.bits.MemoryValid.poke(true.B)
    dut.io.in.bits.MemoryWrite.poke(write.B)
    dut.io.in.bits.ALUResult.poke(address.U(64.W))
    dut.io.in.bits.VAddr.poke(address.U(64.W))
    dut.io.in.bits.WidthSelect.poke(3.U(2.W))
    dut.io.in.bits.LoadSigned.poke(true.B)
    dut.io.in.bits.pc.poke(BigInt("80000000", 16).U(64.W))
  }

  "RV64 LSU" should "把高于 4GiB 的 LD 地址完整送到 AXI AR" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64)) { dut =>
      val clock = dut.clock
      val Address = BigInt("123456780", 16)
      resetLsu(dut, clock, dut.reset)
      pokeWideMem(dut, Address, write = false)
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      dut.io.in.valid.poke(true.B)

      var cycles = 0
      while (!bool(dut.io.DataBus.AR.ARVALID) && cycles < 12) {
        clock.step(); cycles += 1
      }
      bool(dut.io.DataBus.AR.ARVALID) should be(true)
      big(dut.io.DataBus.AR.ARADDR) should be(Address)
      big(dut.io.DataBus.AR.ARLEN) should be(1)
      clock.step()
      dut.io.DataBus.AR.ARREADY.poke(false.B)

      dut.io.DataBus.R.RVALID.poke(true.B)
      dut.io.DataBus.R.RRESP.poke(0.U)
      dut.io.DataBus.R.RDATA.poke("h89abcdef".U(32.W))
      dut.io.DataBus.R.RLAST.poke(false.B)
      clock.step()
      dut.io.DataBus.R.RDATA.poke("h01234567".U(32.W))
      dut.io.DataBus.R.RLAST.poke(true.B)
      clock.step()
      dut.io.DataBus.R.RVALID.poke(false.B)
      dut.io.DataBus.R.RLAST.poke(false.B)

      var done = false
      var load = BigInt(0)
      cycles = 0
      while (!done && cycles < 12) {
        if (bool(dut.io.out.valid)) {
          done = true
          load = dut.io.out.bits.LoadData.peek().litValue
        }
        clock.step(); cycles += 1
      }
      done should be(true)
      load should be(BigInt("0123456789abcdef", 16))
    }
  }

  it should "把高于 4GiB 的 SD 地址完整送到 AXI AW 并保持 8 字节两拍" in {
    simulate(new ysyx_26030103_LSU(XLEN = 64, AddressWidth = 64)) { dut =>
      val clock = dut.clock
      val Address = BigInt("234567890", 16)
      resetLsu(dut, clock, dut.reset)
      pokeWideMem(dut, Address, write = true)
      dut.io.in.bits.StoreData.poke(BigInt("0123456789abcdef", 16).U(64.W))
      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      dut.io.in.valid.poke(true.B)

      val beats = scala.collection.mutable.ArrayBuffer[BigInt]()
      var sawAw = false
      var storeAccepted = false
      var cycles = 0
      while (beats.size < 2 && cycles < 20) {
        // store 可被 LSU 连续吸收；在真正握手后的时钟沿撤销 valid，
        // 避免把同一条测试激励重复排队。
        val fireNow = bool(dut.io.in.valid) && bool(dut.io.in.ready)
        if (bool(dut.io.DataBus.AW.AWVALID)) {
          sawAw = true
          big(dut.io.DataBus.AW.AWADDR) should be(Address)
          big(dut.io.DataBus.AW.AWLEN) should be(1)
        }
        if (bool(dut.io.DataBus.W.WVALID)) {
          beats += dut.io.DataBus.W.WDATA.peek().litValue
        }
        clock.step(); cycles += 1
        if (fireNow && !storeAccepted) {
          storeAccepted = true
          dut.io.in.valid.poke(false.B)
        }
      }
      sawAw should be(true)
      beats should be(
        Seq(BigInt("89abcdef", 16), BigInt("01234567", 16))
      )
    }
  }
  private val HighRegions = Seq(
    ysyx_26030103_PMARegion(
      0x180000000L,
      0x10000L,
      Cacheable = true,
      Executable = true
    )
  )

  "RV64 LSU+DCache" should "用高地址完成 8 字节 LD/SD 且 store 更新缓存镜像" in {
    simulate(
      new ysyx_26030103_LSU(
        XLEN = 64,
        AddressWidth = 64,
        DCacheEnable = true,
        DCacheBlockSizeLog2 = 4,
        DCacheIndexBits = 2,
        DCacheableBase = 0x180000000L,
        DCacheableMask = 0x180000000L,
        DCachePMARegions = HighRegions
      )
    ) { dut =>
      val clock = dut.clock
      val Address = BigInt("180003018", 16)
      resetLsu(dut, clock, dut.reset)

      pokeWideMem(dut, Address, write = false)
      dut.io.in.valid.poke(true.B)
      var cycles = 0
      while (!bool(dut.io.DataBus.AR.ARVALID) && cycles < 20) {
        clock.step(); cycles += 1
      }
      bool(dut.io.DataBus.AR.ARVALID) should be(true)
      big(dut.io.DataBus.AR.ARADDR) should be(BigInt("180003010", 16))
      big(dut.io.DataBus.AR.ARLEN) should be(3)
      dut.io.DataBus.AR.ARREADY.poke(true.B)
      clock.step()
      dut.io.DataBus.AR.ARREADY.poke(false.B)
      dut.io.in.valid.poke(false.B)

      val words = Seq("11111111", "22222222", "33333333", "44444444")
          .map(BigInt(_, 16))
      for (beat <- 0 until 4) {
        dut.io.DataBus.R.RDATA.poke(words(beat).U(32.W))
        dut.io.DataBus.R.RRESP.poke(0.U(2.W))
        dut.io.DataBus.R.RLAST.poke((beat == 3).B)
        dut.io.DataBus.R.RVALID.poke(true.B)
        clock.step()
      }
      dut.io.DataBus.R.RVALID.poke(false.B)
      dut.io.DataBus.R.RLAST.poke(false.B)

      var load = BigInt(0)
      var done = false
      cycles = 0
      while (!done && cycles < 20) {
        if (bool(dut.io.out.valid)) {
          load = dut.io.out.bits.LoadData.peek().litValue
          done = true
        }
        clock.step(); cycles += 1
      }
      done should be(true)
      load should be(BigInt("4444444433333333", 16))

      // 8 字节 store 仍走两拍 AXI，但成功后 DCache 镜像同时更新低/高 word。
      pokeWideMem(dut, Address, write = true)
      dut.io.in.bits.StoreData.poke(BigInt("aaaabbbbccccdddd", 16).U(64.W))
      dut.io.DataBus.AW.AWREADY.poke(true.B)
      dut.io.DataBus.W.WREADY.poke(true.B)
      dut.io.in.valid.poke(true.B)
      val beats = scala.collection.mutable.ArrayBuffer[BigInt]()
      var storeAccepted = false
      cycles = 0
      while (beats.size < 2 && cycles < 20) {
        // store 可被 LSU 连续吸收；在真正握手后的时钟沿撤销 valid，
        // 避免把同一条测试激励重复排队。
        val fireNow = bool(dut.io.in.valid) && bool(dut.io.in.ready)
        if (bool(dut.io.DataBus.AW.AWVALID)) {
          big(dut.io.DataBus.AW.AWADDR) should be(Address)
          big(dut.io.DataBus.AW.AWLEN) should be(1)
        }
        if (bool(dut.io.DataBus.W.WVALID)) {
          beats += dut.io.DataBus.W.WDATA.peek().litValue
        }
        clock.step(); cycles += 1
        if (fireNow && !storeAccepted) {
          storeAccepted = true
          dut.io.in.valid.poke(false.B)
        }
      }
      dut.io.in.valid.poke(false.B)
      beats should be(
        Seq(BigInt("ccccdddd", 16), BigInt("aaaabbbb", 16))
      )
      dut.io.DataBus.B.BVALID.poke(true.B)
      dut.io.DataBus.B.BID.poke(0.U(4.W))
      dut.io.DataBus.B.BRESP.poke(0.U(2.W))
      var retired = false
      cycles = 0
      while (!retired && cycles < 20) {
        if (bool(dut.io.out.valid)) { retired = true }
        clock.step(); cycles += 1
      }
      dut.io.DataBus.B.BVALID.poke(false.B)
      retired should be(true)

      // store 后同地址 load 必须直接命中更新后的镜像，不再发 AR。
      pokeWideMem(dut, Address, write = false)
      dut.io.in.valid.poke(true.B)
      load = 0
      done = false
      cycles = 0
      while (!done && cycles < 40) {
        if (bool(dut.io.DataBus.AR.ARVALID)) {
          fail("DCache store 后 load 不应重新 refill")
        }
        if (bool(dut.io.out.valid)) {
          load = dut.io.out.bits.LoadData.peek().litValue
          done = true
        }
        clock.step(); cycles += 1
      }
      done should be(true)
      load should be(BigInt("aaaabbbbccccdddd", 16))
    }
  }

}
