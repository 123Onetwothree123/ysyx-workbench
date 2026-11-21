package ysyx

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._

class SDRAMIO(DataWidth: Int = 16) extends Bundle {
  val clk = Output(Bool())
  val cke = Output(Bool())
  val cs = Output(Bool())
  val ras = Output(Bool())
  val cas = Output(Bool())
  val we = Output(Bool())
  val a = Output(UInt(13.W))
  val ba = Output(UInt(2.W))
  val dqm = Output(UInt((DataWidth / 8).W))
  // 位扩展: dq拆成DataWidth/16根16位Analog, 每根接一个16位颗粒
  val dq = Vec(DataWidth / 16, Analog(16.W))
}

class sdram_top_axi extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Flipped(
      new AXI4Bundle(
        AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 4)
      )
    )
    val sdram = new SDRAMIO(32)
  })
}

class sdram_top_apb extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in =
      Flipped(new APBBundle(APBBundleParameters(addrBits = 32, dataBits = 32)))
    val sdram = new SDRAMIO(32)
  })
}

class sdram extends BlackBox {
  val io = IO(Flipped(new SDRAMIO))
}

class sdramChisel extends RawModule {
  val io = IO(Flipped(new SDRAMIO))
  val output = Wire(UInt(16.W))
  val en = Wire(Bool())
  val input = TriStateInBuf(io.dq(0), output, en)

  withClockAndReset(io.clk.asClock, false.B.asAsyncReset) {
    // 命令总线每拍都有效, 新命令(如流水式READ)可以打断正在进行的突发
    val Command_NOP = io.cs || (io.ras && io.cas && io.we)
    val Command_ACTIVE = !io.cs && !io.ras && io.cas && io.we
    val Command_READ = !io.cs && io.ras && !io.cas && io.we
    val Command_WRITE = !io.cs && io.ras && !io.cas && !io.we
    val Command_TERMINATE = !io.cs && io.ras && io.cas && !io.we
    val Command_PRECHARGE = !io.cs && !io.ras && io.cas && !io.we
    val Command_REFRESH = !io.cs && !io.ras && !io.cas && io.we
    val Command_LOAD_MR = !io.cs && !io.ras && !io.cas && !io.we

    // 4个bank, 每bank 8192行x512列x16bit, 展平成一维(Cat(行,列)寻址)
    // 写直接落存储阵列, 不做行缓冲回写, 避免整行重构的仿真开销
    val memory = Seq.fill(4)(Mem(8192 * 512, UInt(16.W)))
    val ActiveRow = Reg(Vec(4, UInt(13.W))) // 每个bank各自打开的行
    val ModeRegister = RegInit(0x20.U(13.W))
    val MR_Burst_Length = MuxLookup(ModeRegister(2, 0), 1.U)(
      Seq(0.U -> 1.U, 1.U -> 2.U, 2.U -> 4.U, 3.U -> 8.U, 7.U -> 256.U)
    )
    val MR_CAS_Latency = ModeRegister(6, 4)

    when(Command_ACTIVE) { ActiveRow(io.ba) := io.a }
    when(Command_LOAD_MR) { ModeRegister := io.a }
    // PRECHARGE/REFRESH按官方要求实现为NOP

    // ---- 读: 任何一拍都可接READ(读可以打断读), 经CAS延迟流水线后驱动DQ ----
    val PipeValid = RegInit(VecInit(Seq.fill(3)(false.B)))
    val PipeBank = Reg(Vec(3, UInt(2.W)))
    val PipeCol = Reg(Vec(3, UInt(9.W)))
    PipeValid(0) := Command_READ
    PipeBank(0) := io.ba
    PipeCol(0) := io.a(8, 0)
    for (i <- 1 until 3) {
      PipeValid(i) := PipeValid(i - 1)
      PipeBank(i) := PipeBank(i - 1)
      PipeCol(i) := PipeCol(i - 1)
    }
    val pipeFire = MuxLookup(MR_CAS_Latency, PipeValid(1))(
      Seq(1.U -> PipeValid(0), 2.U -> PipeValid(1), 3.U -> PipeValid(2))
    )
    val pipeBank = MuxLookup(MR_CAS_Latency, PipeBank(1))(
      Seq(1.U -> PipeBank(0), 2.U -> PipeBank(1), 3.U -> PipeBank(2))
    )
    val pipeCol = MuxLookup(MR_CAS_Latency, PipeCol(1))(
      Seq(1.U -> PipeCol(0), 2.U -> PipeCol(1), 3.U -> PipeCol(2))
    )

    // 突发输出引擎: pipeFire当拍出第一个数据, 之后按突发长度连续出
    val RdLeft = RegInit(0.U(9.W))
    val RdBank = Reg(UInt(2.W))
    val RdCol = Reg(UInt(9.W))
    val driving = RdLeft =/= 0.U
    val outBank = Mux(pipeFire, pipeBank, RdBank)
    val outCol = Mux(pipeFire, pipeCol, RdCol)
    val rdDataVec =
      (0 until 4).map(b => memory(b).read(Cat(ActiveRow(b), outCol)))
    en := pipeFire || driving
    output := rdDataVec(outBank)
    when(pipeFire) {
      RdLeft := MR_Burst_Length - 1.U
      RdBank := pipeBank
      RdCol := pipeCol + 1.U
    }.elsewhen(driving) {
      RdLeft := RdLeft - 1.U
      RdCol := RdCol + 1.U
    }
    when(Command_TERMINATE) {
      RdLeft := 0.U
      PipeValid.foreach(_ := false.B)
    }

    // ---- 写: 命令与第一拍数据同拍出现, 后续拍连续, 可被新命令打断 ----
    val WrLeft = RegInit(0.U(9.W))
    val WrBank = Reg(UInt(2.W))
    val WrCol = Reg(UInt(9.W))
    val wrFire = WireDefault(false.B)
    val wrBank = WireDefault(0.U(2.W))
    val wrCol = WireDefault(0.U(9.W))
    when(Command_WRITE) {
      wrFire := true.B
      wrBank := io.ba
      wrCol := io.a(8, 0)
      WrLeft := MR_Burst_Length - 1.U
      WrBank := io.ba
      WrCol := io.a(8, 0) + 1.U
    }.elsewhen(WrLeft =/= 0.U) {
      wrFire := true.B
      wrBank := WrBank
      wrCol := WrCol
      WrLeft := WrLeft - 1.U
      WrCol := WrCol + 1.U
    }
    when(Command_READ || Command_TERMINATE || Command_PRECHARGE) {
      WrLeft := 0.U
    }

    // 写落盘: 按dqm做字节掩码(读改写), 直接写存储阵列
    when(wrFire) {
      val waddr = (0 until 4).map(b => Cat(ActiveRow(b), wrCol))
      val oldVec = (0 until 4).map(b => memory(b).read(waddr(b)))
      val OldWord = oldVec(wrBank)
      val NewWord = Cat(
        Mux(!io.dqm(1), input(15, 8), OldWord(15, 8)),
        Mux(!io.dqm(0), input(7, 0), OldWord(7, 0))
      )
      when(wrBank === 0.U) { memory(0).write(waddr(0), NewWord) }
      when(wrBank === 1.U) { memory(1).write(waddr(1), NewWord) }
      when(wrBank === 2.U) { memory(2).write(waddr(2), NewWord) }
      when(wrBank === 3.U) { memory(3).write(waddr(3), NewWord) }
    }
  }
}

/** 64位AXI4到32位AXI4的窄化桥
  *
  * 每拍按8字节窗口拆成两笔32位单拍访问, 读回按窗口拼成一拍
  * 同一时刻只放一笔事务, 响应按序返回
  */
class AXI64To32(val up: AXI4BundleParameters) extends Module {
  require(up.dataBits == 64, s"AXI64To32上游必须是64位, 收到${up.dataBits}")
  require(up.addrBits >= 32, s"AXI64To32上游地址至少32位, 收到${up.addrBits}")

  val io = IO(new Bundle {
    val in = Flipped(new AXI4Bundle(up))
    val out = new AXI4Bundle(up.copy(addrBits = 32, dataBits = 32))
  })

  private val aw = io.in.aw
  private val w = io.in.w
  private val b = io.in.b
  private val ar = io.in.ar
  private val r = io.in.r

  private def beatAddr(
      base: UInt,
      beat: UInt,
      size: UInt,
      burst: UInt,
      len: UInt
  ): UInt = {
    val inc = (base + (beat << size))(31, 0)
    val wrapMask = ((((len +& 1.U) << size) - 1.U)).pad(32)
    Mux(
      burst === AXI4Parameters.BURST_WRAP,
      (base & ~wrapMask) | (inc & wrapMask),
      Mux(burst === AXI4Parameters.BURST_FIXED, base, inc)
    )
  }

  private def winAddr(addr: UInt): UInt = Cat(addr(31, 3), 0.U(3.W))

  // ------------------------------ 读 ------------------------------
  private val rIdle :: rAddrLo :: rWaitLo :: rAddrHi :: rWaitHi :: rOut :: Nil =
    Enum(6)
  private val rState = RegInit(rIdle)
  private val rId = Reg(UInt(up.idBits.W))
  private val rAddr = Reg(UInt(32.W))
  private val rLen = Reg(UInt(up.lenBits.W))
  private val rSize = Reg(UInt(up.sizeBits.W))
  private val rBurst = Reg(UInt(up.burstBits.W))
  private val rBeat = Reg(UInt(up.lenBits.W))
  private val rLo = Reg(UInt(32.W))
  private val rHi = Reg(UInt(32.W))
  private val rResp = Reg(UInt(AXI4Parameters.respBits.W))

  ar.ready := rState === rIdle

  private val rBeatAddr = beatAddr(rAddr, rBeat, rSize, rBurst, rLen)
  private val rWin = winAddr(rBeatAddr)

  io.out.ar.valid := false.B
  io.out.ar.bits := DontCare
  io.out.ar.bits.id := rId
  io.out.ar.bits.addr := rWin
  io.out.ar.bits.len := 0.U
  io.out.ar.bits.size := 2.U
  io.out.ar.bits.burst := AXI4Parameters.BURST_INCR
  io.out.ar.bits.lock := 0.U
  io.out.ar.bits.cache := 0.U
  io.out.ar.bits.prot := 0.U
  io.out.ar.bits.qos := 0.U

  io.out.r.ready := false.B

  r.valid := false.B
  r.bits := DontCare
  r.bits.id := rId
  r.bits.data := Cat(rHi, rLo)
  r.bits.resp := rResp
  r.bits.last := rBeat === rLen

  when(ar.fire) {
    rId := ar.bits.id
    rAddr := ar.bits.addr(31, 0)
    rLen := ar.bits.len
    rSize := ar.bits.size
    rBurst := ar.bits.burst
    rBeat := 0.U
    rResp := AXI4Parameters.RESP_OKAY
    rState := rAddrLo
  }

  switch(rState) {
    is(rIdle) {}
    is(rAddrLo) {
      io.out.ar.valid := true.B
      when(io.out.ar.fire) { rState := rWaitLo }
    }
    is(rWaitLo) {
      io.out.r.ready := true.B
      when(io.out.r.fire) {
        rLo := io.out.r.bits.data
        when(io.out.r.bits.resp =/= AXI4Parameters.RESP_OKAY) {
          rResp := io.out.r.bits.resp
        }
        rState := rAddrHi
      }
    }
    is(rAddrHi) {
      io.out.ar.valid := true.B
      io.out.ar.bits.addr := (rWin + 4.U)(31, 0)
      when(io.out.ar.fire) { rState := rWaitHi }
    }
    is(rWaitHi) {
      io.out.r.ready := true.B
      when(io.out.r.fire) {
        rHi := io.out.r.bits.data
        when(io.out.r.bits.resp =/= AXI4Parameters.RESP_OKAY) {
          rResp := io.out.r.bits.resp
        }
        rState := rOut
      }
    }
    is(rOut) {
      r.valid := true.B
      when(r.fire) {
        when(rBeat === rLen) {
          rState := rIdle
        }.otherwise {
          rBeat := rBeat + 1.U
          rState := rAddrLo
        }
      }
    }
  }

  // ------------------------------ 写 ------------------------------
  private val wIdle :: wWaitW :: wSend :: wWaitB :: wOut :: Nil = Enum(5)
  private val wState = RegInit(wIdle)
  private val wId = Reg(UInt(up.idBits.W))
  private val wAddr = Reg(UInt(32.W))
  private val wLen = Reg(UInt(up.lenBits.W))
  private val wSize = Reg(UInt(up.sizeBits.W))
  private val wBurst = Reg(UInt(up.burstBits.W))
  private val wBeat = Reg(UInt(up.lenBits.W))
  private val wHalf = RegInit(0.U(1.W))
  private val wData = Reg(UInt(64.W))
  private val wStrb = Reg(UInt(8.W))
  private val wLast = RegInit(false.B)
  private val wResp = Reg(UInt(AXI4Parameters.respBits.W))
  private val wAwSent = RegInit(false.B)
  private val wSent = RegInit(false.B)

  private val wBeatAddr = beatAddr(wAddr, wBeat, wSize, wBurst, wLen)
  private val wWin = winAddr(wBeatAddr)
  private val wHalfStrb = Mux(wHalf === 0.U, wStrb(3, 0), wStrb(7, 4))
  private val wHalfData = Mux(wHalf === 0.U, wData(31, 0), wData(63, 32))
  private val wActive = wHalfStrb =/= 0.U
  private val wBothSent = (wAwSent || io.out.aw.fire) && (wSent || io.out.w.fire)

  aw.ready := wState === wIdle
  w.ready := wState === wWaitW

  when(aw.fire) {
    wId := aw.bits.id
    wAddr := aw.bits.addr(31, 0)
    wLen := aw.bits.len
    wSize := aw.bits.size
    wBurst := aw.bits.burst
    wBeat := 0.U
    wResp := AXI4Parameters.RESP_OKAY
    wState := wWaitW
  }

  when(w.fire) {
    wData := w.bits.data
    wStrb := w.bits.strb
    wLast := w.bits.last
    wHalf := 0.U
    wAwSent := false.B
    wSent := false.B
    wState := wSend
  }

  io.out.aw.valid := wState === wSend && wActive && !wAwSent
  io.out.aw.bits := DontCare
  io.out.aw.bits.id := wId
  io.out.aw.bits.addr := (wWin + (wHalf << 2))(31, 0)
  io.out.aw.bits.len := 0.U
  io.out.aw.bits.size := 2.U
  io.out.aw.bits.burst := AXI4Parameters.BURST_INCR
  io.out.aw.bits.lock := 0.U
  io.out.aw.bits.cache := 0.U
  io.out.aw.bits.prot := 0.U
  io.out.aw.bits.qos := 0.U

  io.out.w.valid := wState === wSend && wActive && !wSent
  io.out.w.bits := DontCare
  io.out.w.bits.data := wHalfData
  io.out.w.bits.strb := wHalfStrb
  io.out.w.bits.last := true.B

  io.out.b.ready := wState === wWaitB

  b.valid := wState === wOut
  b.bits := DontCare
  b.bits.id := wId
  b.bits.resp := wResp

  when(wState === wSend) {
    when(!wActive) {
      when(wHalf === 0.U) {
        wHalf := 1.U
      }.elsewhen(wLast) {
        wState := wOut
      }.otherwise {
        wBeat := wBeat + 1.U
        wState := wWaitW
      }
    }.elsewhen(wBothSent) {
      wAwSent := false.B
      wSent := false.B
      wState := wWaitB
    }.otherwise {
      when(io.out.aw.fire) { wAwSent := true.B }
      when(io.out.w.fire) { wSent := true.B }
    }
  }

  when(wState === wWaitB) {
    when(io.out.b.fire) {
      when(io.out.b.bits.resp =/= AXI4Parameters.RESP_OKAY) {
        wResp := io.out.b.bits.resp
      }
      when(wHalf === 0.U) {
        wHalf := 1.U
        wState := wSend
      }.elsewhen(wLast) {
        wState := wOut
      }.otherwise {
        wBeat := wBeat + 1.U
        wState := wWaitW
      }
    }
  }
}

class AXI4SDRAM(
    address: Seq[AddressSet],
    beatBytes: Int = Config.peripheralXlen("sdram") / 8
)(implicit p: Parameters)
    extends LazyModule {
  require(beatBytes == 4 || beatBytes == 8, s"SDRAM只支持4/8字节beat, 收到$beatBytes")
  val node = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address = address,
            executable = true,
            supportsWrite = TransferSizes(1, beatBytes),
            supportsRead = TransferSizes(1, beatBytes),
            interleavedId = Some(0)
          )
        ),
        beatBytes = beatBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO(32))
    require(in.params.dataBits == beatBytes * 8, s"SDRAM AXI宽度不一致")

    val msdram = Module(new sdram_top_axi)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    if (beatBytes == 4) {
      msdram.io.in <> in
    } else {
      val bridge = Module(new AXI64To32(in.params))
      bridge.io.in <> in
      msdram.io.in <> bridge.io.out
    }
    sdram_bundle <> msdram.io.sdram
  }
}

class APBSDRAM(
    address: Seq[AddressSet],
    val xlen: Int = Config.peripheralXlen("sdram")
)(implicit p: Parameters)
    extends LazyModule {
  APBConfig.checkXlen(xlen, "sdram")
  // APB fanout要求所有外设同宽, 所以端口宽度取APB_XLEN
  private val busBytes = APBConfig.apbBytes
  private val coreBytes = 4 // sdram_top_apb固定32位

  val node = APBSlaveNode(
    Seq(
      APBSlavePortParameters(
        Seq(
          APBSlaveParameters(
            address = address,
            executable = true,
            supportsRead = true,
            supportsWrite = true
          )
        ),
        beatBytes = busBytes
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val (in, _) = node.in(0)
    val sdram_bundle = IO(new SDRAMIO(32))

    val msdram = Module(new sdram_top_apb)
    msdram.io.clock := clock
    msdram.io.reset := reset.asBool
    if (busBytes == coreBytes) {
      msdram.io.in <> in
    } else {
      // 内存类外设, 64位读要读两个32位半区
      val bridge = Module(new APBWidthConverter(
        in.paddr.getWidth, busBytes * 8, coreBytes * 8, readFullWidth = true))
      bridge.io.clock := clock
      bridge.io.reset := reset
      bridge.io.in <> in
      msdram.io.in <> bridge.io.out
    }
    sdram_bundle <> msdram.io.sdram
  }
}
