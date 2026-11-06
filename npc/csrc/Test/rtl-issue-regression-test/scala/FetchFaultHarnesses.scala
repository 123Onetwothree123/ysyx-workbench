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
  * !pmpFault 相与），因此 PMP 拒绝的取指不能直接进入 ICache。
  */
class ViptFetchPmpHarness extends Module {
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchAddr = Input(UInt(32.W))
    val fetchReady = Output(Bool())
    val bypass = Input(Bool())
    val pmpFault = Input(Bool())
    val fault = Output(Bool())
    val faultCause = Output(UInt(4.W))
    val faultVaddr = Output(UInt(32.W))
    val kill = Input(Bool())
    val respValid = Output(Bool())
    val respFault = Output(Bool())
    val respData = Output(UInt(32.W))
    val respReady = Input(Bool())
    val axiARValid = Output(Bool())
    val axiARReady = Input(Bool())
    val axiARAddr = Output(UInt(32.W))
  })

  val icache = Module(
    new ysyx_26030103_ICacheReg(
      Enable = true,
      BlockSizeLog2 = 4,
      IndexBits = 2,
      AddressWidth = 32,
      Vipt = true
    )
  )
  val immu = Module(
    new ysyx_26030103_IFUMMU(
      ParallelVipt = true,
      XLEN = 32,
      AddressWidth = 32
    )
  )

  immu.io.privilege := Mux(io.bypass, 3.U(2.W), 1.U(2.W))
  immu.io.satp := Mux(io.bypass, 0.U(32.W), "h80000000".U(32.W))
  immu.io.sum := false.B
  immu.io.mxr := false.B
  immu.io.flush := false.B
  immu.io.pmpFault := io.pmpFault
  immu.io.fetchValid := icache.io.fetch_xlate_pending
  immu.io.fetchVaddr := icache.io.fetch_xlate_vaddr
  immu.io.cacheReady := icache.io.fetch_ready
  // bypass 场景不会发起页表请求；固定 ready 并保持无响应。
  immu.io.req.ready := true.B
  immu.io.resp.valid := false.B
  immu.io.resp.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(32, 32))

  // 与顶层 VIPT 分支完全一致的连接。
  icache.io.fetch_valid := io.fetchValid
  icache.io.fetch_vaddr := io.fetchAddr
  icache.io.fetch_addr := immu.io.fetchPaddr
  icache.io.fetch_paddr_valid := immu.io.translationValid
  icache.io.fetch_paddr_vaddr := immu.io.fetchPaddrVaddr
  icache.io.kill := io.kill || immu.io.fault
  icache.io.flush := false.B
  icache.io.resp_ready := io.respReady
  icache.io.snoop_valid := false.B
  icache.io.snoop_addr := 0.U
  icache.io.snoop_addr_hi := 0.U
  icache.io.dma_snoop_valid := false.B
  icache.io.dma_snoop_start := 0.U
  icache.io.dma_snoop_end := 0.U
  icache.io.axi.AR.ARREADY := io.axiARReady
  icache.io.axi.R.RVALID := false.B
  icache.io.axi.R.RDATA := 0.U
  icache.io.axi.R.RRESP := 0.U
  icache.io.axi.R.RLAST := false.B
  icache.io.axi.R.RID := 0.U
  icache.io.axi.B.BVALID := false.B
  icache.io.axi.B.BID := 0.U
  icache.io.axi.B.BRESP := 0.U
  icache.io.axi.AW.AWREADY := false.B
  icache.io.axi.W.WREADY := false.B

  io.fetchReady := icache.io.fetch_ready
  io.fault := immu.io.fault
  io.faultCause := immu.io.faultCause
  io.faultVaddr := immu.io.faultVaddr
  io.respValid := icache.io.resp_valid
  io.respFault := icache.io.resp_fault
  io.respData := icache.io.resp_data
  io.axiARValid := icache.io.axi.AR.ARVALID
  io.axiARAddr := icache.io.axi.AR.ARADDR
}

object ViptFetchPmpHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/vipt-fetch-pmp-rtl")
  emitVerilog(new ViptFetchPmpHarness, Array("--target-dir", targetDir))
}

/** C 模式 IFU 定向测试外壳：只暴露取指/响应/装配输出端口。 */
class IFUFetchFaultHarness extends Module {
  val io = IO(new Bundle {
    val FetchAddr = Output(UInt(32.W))
    val FetchPC = Output(UInt(32.W))
    val FetchValid = Output(Bool())
    val FetchReady = Input(Bool())

    val RespData = Input(UInt(32.W))
    val RespValid = Input(Bool())
    val RespReady = Output(Bool())
    val RespExceptionValid = Input(Bool())
    val RespExceptionCause = Input(UInt(4.W))
    val RespAccessFaultResp = Input(UInt(2.W))

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
      resetAddr = 0x80000ffeL,
      XLEN = 32,
      UseC = true
    )
  )

  io.FetchAddr := dut.io.FetchAddr
  io.FetchPC := dut.io.FetchPC
  io.FetchValid := dut.io.FetchValid
  dut.io.FetchReady := io.FetchReady

  dut.io.RespData := io.RespData
  dut.io.RespValid := io.RespValid
  io.RespReady := dut.io.RespReady
  dut.io.RespExceptionValid := io.RespExceptionValid
  dut.io.RespExceptionCause := io.RespExceptionCause
  dut.io.RespAccessFaultResp := io.RespAccessFaultResp

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
  io.OutPC := dut.io.Out.bits.pc
  io.OutExceptionValid := dut.io.Out.bits.ExceptionValid
  io.OutExceptionCause := dut.io.Out.bits.ExceptionCause
  io.OutPredTaken := dut.io.Out.bits.pred_taken
  io.OutPredTarget := dut.io.Out.bits.pred_target
}

object IFUFetchFaultHarnessElaborate extends App {
  val targetDir = args
    .sliding(2)
    .collectFirst { case Array("--target-dir", value) => value }
    .getOrElse("build/ifu-fetch-fault-rtl")
  emitVerilog(new IFUFetchFaultHarness, Array("--target-dir", targetDir))
}
