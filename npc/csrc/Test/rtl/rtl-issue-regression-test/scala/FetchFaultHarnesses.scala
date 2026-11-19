package ysyx_26030103.test

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.cache.ysyx_26030103_ICacheReg
import _root_.ysyx_26030103.ifu.ysyx_26030103_IFU
import _root_.ysyx_26030103.mmu._

/** VIPT 取指路径的集成外壳。
  *
  * 按顶层修复后的连接方式把 ICacheReg 与 IFUMMU 接在一起：PA 有效位与
  * translationValid 相连（translationValid 在 Bare/M bypass 时也会与
  * !PMPFault 相与），因此 PMP 拒绝的取指不能直接进入 ICache。
  */
class VIPTFetchPMPHarness extends Module {
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchAddress = Input(UInt(32.W))
    val fetchReady = Output(Bool())
    val bypass = Input(Bool())
    val PMPFault = Input(Bool())
    val fault = Output(Bool())
    val faultCause = Output(UInt(4.W))
    val faultVirtualAddress = Output(UInt(32.W))
    val kill = Input(Bool())
    val ResponseValid = Output(Bool())
    val ResponseFault = Output(Bool())
    val ResponseData = Output(UInt(32.W))
    val ResponseReady = Input(Bool())
    val AXIARValid = Output(Bool())
    val AXIARReady = Input(Bool())
    val AXIARAddress = Output(UInt(32.W))
  })

  val ICache = Module(
    new ysyx_26030103_ICacheReg(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 2,
      AddressWidth = 32,
      VIPT = true
    )
  )
  val IMMU = Module(
    new ysyx_26030103_IFUMMU(
      ParallelVIPT = true,
      XLEN = 32,
      AddressWidth = 32
    )
  )

  IMMU.io.privilege := Mux(io.bypass, 3.U(2.W), 1.U(2.W))
  IMMU.io.satp := Mux(io.bypass, 0.U(32.W), "h80000000".U(32.W))
  IMMU.io.sum := false.B
  IMMU.io.mxr := false.B
  IMMU.io.flush := false.B
  IMMU.io.PMPFault := io.PMPFault
  IMMU.io.fetchValid := ICache.io.FetchXlatePending
  IMMU.io.fetchVirtualAddress := ICache.io.FetchXlateVaddr
  IMMU.io.cacheReady := ICache.io.FetchReady
  // bypass 场景不会发起页表请求；固定 ready 并保持无响应。
  IMMU.io.request.ready := true.B
  IMMU.io.response.valid := false.B
  IMMU.io.response.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(32, 32))

  // 与顶层 VIPT 分支完全一致的连接。
  ICache.io.FetchValid := io.fetchValid
  ICache.io.FetchVaddr := io.fetchAddress
  ICache.io.FetchAddress := IMMU.io.fetchPhysicalAddress
  ICache.io.FetchPaddrValid := IMMU.io.translationValid
  ICache.io.FetchPaddrVaddr := IMMU.io.fetchPhysicalAddressVirtualAddress
  ICache.io.kill := io.kill || IMMU.io.fault
  ICache.io.flush := false.B
  ICache.io.ResponseReady := io.ResponseReady
  ICache.io.SnoopValid := false.B
  ICache.io.SnoopAddress := 0.U
  ICache.io.SnoopAddressHi := 0.U
  ICache.io.DMASnoopValid := false.B
  ICache.io.DMASnoopStart := 0.U
  ICache.io.DMASnoopEnd := 0.U
  ICache.io.AXI.AR.ARREADY := io.AXIARReady
  ICache.io.AXI.R.RVALID := false.B
  ICache.io.AXI.R.RDATA := 0.U
  ICache.io.AXI.R.RRESP := 0.U
  ICache.io.AXI.R.RLAST := false.B
  ICache.io.AXI.R.RID := 0.U
  ICache.io.AXI.B.BVALID := false.B
  ICache.io.AXI.B.BID := 0.U
  ICache.io.AXI.B.BRESP := 0.U
  ICache.io.AXI.AW.AWREADY := false.B
  ICache.io.AXI.W.WREADY := false.B

  io.fetchReady := ICache.io.FetchReady
  io.fault := IMMU.io.fault
  io.faultCause := IMMU.io.faultCause
  io.faultVirtualAddress := IMMU.io.faultVirtualAddress
  io.ResponseValid := ICache.io.ResponseValid
  io.ResponseFault := ICache.io.ResponseFault
  io.ResponseData := ICache.io.ResponseData
  io.AXIARValid := ICache.io.AXI.AR.ARVALID
  io.AXIARAddress := ICache.io.AXI.AR.ARADDR
}

object VIPTFetchPMPHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/vipt-fetch-PMP-rtl")
  emitVerilog(new VIPTFetchPMPHarness, Array("--target-dir", targetDir))
}

/** C 模式 IFU 定向测试外壳：只暴露取指/响应/装配输出端口。 */
class IFUFetchFaultHarness extends Module {
  val io = IO(new Bundle {
    val FetchAddress = Output(UInt(32.W))
    val FetchPC = Output(UInt(32.W))
    val FetchValid = Output(Bool())
    val FetchReady = Input(Bool())

    val ResponseData = Input(UInt(32.W))
    val ResponseValid = Input(Bool())
    val ResponseReady = Output(Bool())
    val ResponseExceptionValid = Input(Bool())
    val ResponseExceptionCause = Input(UInt(4.W))
    val ResponseAccessFaultResponse = Input(UInt(2.W))

    val RedirectTarget = Input(UInt(32.W))
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool())
    val ExceptionTarget = Input(UInt(32.W))

    val PredHit = Input(Bool())
    val PredTarget = Input(UInt(32.W))

    val OutValid = Output(Bool())
    val OutReady = Input(Bool())
    val OutInstruction = Output(UInt(32.W))
    val OutIsCompressed = Output(Bool())
    val OutPC = Output(UInt(32.W))
    val OutExceptionValid = Output(Bool())
    val OutExceptionCause = Output(UInt(4.W))
    val OutPredTaken = Output(Bool())
    val OutPredTarget = Output(UInt(32.W))
  })

  // 起点故意落在字内高半字，构造跨字 32 位指令 ...ffe -> ...1000。
  val dut = Module(
    new ysyx_26030103_IFU(
      resetAddress = 0x80000ffeL,
      XLEN = 32,
      UseC = true
    )
  )

  io.FetchAddress := dut.io.FetchAddress
  io.FetchPC := dut.io.FetchPC
  io.FetchValid := dut.io.FetchValid
  dut.io.FetchReady := io.FetchReady

  dut.io.ResponseData := io.ResponseData
  dut.io.ResponseValid := io.ResponseValid
  io.ResponseReady := dut.io.ResponseReady
  dut.io.ResponseExceptionValid := io.ResponseExceptionValid
  dut.io.ResponseExceptionCause := io.ResponseExceptionCause
  dut.io.ResponseAccessFaultResponse := io.ResponseAccessFaultResponse

  dut.io.RedirectTarget := io.RedirectTarget
  dut.io.Redirect := io.Redirect
  dut.io.ExceptionTaken := io.ExceptionTaken
  dut.io.ExceptionTarget := io.ExceptionTarget

  dut.io.PredHit := io.PredHit
  dut.io.PredTarget := io.PredTarget

  io.OutValid := dut.io.Out.valid
  dut.io.Out.ready := io.OutReady
  io.OutInstruction := dut.io.Out.bits.Instruction
  io.OutIsCompressed := dut.io.Out.bits.IsCompressed
  io.OutPC := dut.io.Out.bits.PC
  io.OutExceptionValid := dut.io.Out.bits.ExceptionValid
  io.OutExceptionCause := dut.io.Out.bits.ExceptionCause
  io.OutPredTaken := dut.io.Out.bits.PredTaken
  io.OutPredTarget := dut.io.Out.bits.PredTarget
}

object IFUFetchFaultHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/ifu-fetch-fault-rtl")
  emitVerilog(new IFUFetchFaultHarness, Array("--target-dir", targetDir))
}
