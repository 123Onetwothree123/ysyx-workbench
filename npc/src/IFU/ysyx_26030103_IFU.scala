package ysyx_26030103.ifu
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_IFUMessage

// 取指流水化后的IFU。
//
// 未启用 C 扩展：退化为纯 PC 发生器，每拍向 ICache 发一个 4 字节取指请求，
// 被接受就 PC+4；重定向/异常时停发一拍并改写 PC。指令的锁存、交付和冲刷
// 全部由 ICache 响应级+下游流水寄存器完成。
//
// 启用 C 扩展：IFU 自己持有一个串行取指状态机，按 IALIGN=16 逐半字推进。
// ICache 仍然每次返回请求地址所在的 32 位对齐字，IFU 负责：
//   - 根据 PC[1] 选择低/高半字；
//   - 遇到低半字为 32 位编码且 PC[1]=1 时，再取下一个字并拼接；
//   - 只在指令完整后输出 IFUMessage，并据此选择 PC+2/PC+4 或 BTB 目标；
//   - redirect/异常时丢弃未完成的半字状态，绝不把旧半字重放到新 PC。
// 这样跨 cache line/页边界的 32 位指令会自然拆成两次独立取指；BTB 预测
// 也要等指令完整后才生效，不会跳过 32 位指令的第二个半字。
//
// FetchAddr 是实际发给 ICache 的字对齐地址；FetchPC 是架构 PC（指令起点），
// 供 BTB/PMP/MMU 故障报告使用。未启用 C 时两者相同。
class ysyx_26030103_IFU(
    resetAddr: Long = 0x30000000L,
    val XLEN: Int = 32,
    val UseC: Boolean = false
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"IFU XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    // 发给 ICache 的取指请求地址（C 模式下按 4 字节对齐）。
    val FetchAddr = Output(UInt(XLEN.W))
    // 当前指令的架构 PC（C 模式下可能位于字内高半字）。
    val FetchPC = Output(UInt(XLEN.W))
    val FetchValid = Output(Bool())
    val FetchReady = Input(Bool())

    // ICache 响应：C 模式下由 IFU 消费并组装成完整指令。
    val RespData = Input(UInt(32.W))
    val RespValid = Input(Bool())
    val RespReady = Output(Bool())
    val RespExceptionValid = Input(Bool())
    val RespExceptionCause = Input(UInt(4.W))
    val RespAccessFaultResp = Input(UInt(2.W))

    val RedirectTarget = Input(UInt(XLEN.W))
    val Redirect = Input(Bool())
    val ExceptionTaken = Input(Bool())
    val ExceptionTarget = Input(UInt(XLEN.W))

    // BTB查询结果(由顶层例化的BTB用当前FetchPC查后回送)
    val PredHit = Input(Bool())
    val PredTarget = Input(UInt(XLEN.W))

    // C 模式组装完成的指令；非 C 模式保持 valid=0，由顶层直接使用
    // ICache 响应（旧数据通路）。
    val Out = Decoupled(new ysyx_26030103_IFUMessage(XLEN))

    val StallIdle = Output(Bool())
  })

  if (!UseC) {
    val PCModule = Module(new ysyx_26030103_PC(resetAddr, XLEN))
    val NextPCModule = Module(new ysyx_26030103_NextPC(XLEN))
    val snpc = PCModule.io.ysyx_26030103_PC + 4.U(XLEN.W)

    val redirect = io.Redirect || io.ExceptionTaken
    io.FetchValid := !redirect
    io.FetchAddr := PCModule.io.ysyx_26030103_PC
    io.FetchPC := PCModule.io.ysyx_26030103_PC

    NextPCModule.io.SNPC := snpc
    NextPCModule.io.Redirect := io.Redirect
    NextPCModule.io.RedirectTarget := io.RedirectTarget
    NextPCModule.io.ExceptionTaken := io.ExceptionTaken
    NextPCModule.io.ExceptionTarget := io.ExceptionTarget
    NextPCModule.io.PredHit := io.PredHit && !redirect
    NextPCModule.io.PredTarget := io.PredTarget
    PCModule.io.ysyx_26030103_NextPC := NextPCModule.io.ysyx_26030103_NextPC
    PCModule.io.PCEnable := redirect || (io.FetchValid && io.FetchReady)

    io.StallIdle := !io.FetchValid
    io.RespReady := false.B
    io.Out.valid := false.B
    io.Out.bits := 0.U.asTypeOf(new ysyx_26030103_IFUMessage(XLEN))
  } else {
    // sIdle：可以发下一个取指请求；sWait：等待 ICache 返回当前字。
    val sIdle :: sWait :: Nil = Enum(2)
    val state = RegInit(sIdle)
    // 架构 PC：始终指向当前指令的起点，按 2 字节对齐。
    val pcReg = RegInit(resetAddr.U(XLEN.W))
    // 当前取指是否是跨字 32 位指令的第二个字。
    val secondWord = RegInit(false.B)
    // 跨字指令已经取到的低 16 位。
    val firstHalf = RegInit(0.U(16.W))
    // 在第一条取指被接受时快照的预测信息；跨字指令要等第二个字到达后
    // 才能使用，不能沿用响应阶段的 BTB/RAS 状态。
    val savedPredTaken = RegInit(false.B)
    val savedPredTarget = RegInit(0.U(XLEN.W))
    // 取指访问错误已经交付后，停发新请求直到 EXU 提交 trap 并重定向，
    // 避免同一个故障 PC 反复灌入流水线。
    val faultHeld = RegInit(false.B)

    val redirect = io.Redirect || io.ExceptionTaken
    val redirectTarget =
      Mux(io.ExceptionTaken, io.ExceptionTarget, io.RedirectTarget)
    val wordAlignedPC = Cat(pcReg(XLEN - 1, 2), "b00".U(2.W))
    // 跨字指令的第二个字固定是当前字的下一个 4 字节字。
    val fetchAddr = Mux(secondWord, wordAlignedPC + 4.U, wordAlignedPC)

    io.FetchAddr := fetchAddr
    io.FetchPC := pcReg
    io.FetchValid := state === sIdle && !redirect && !faultHeld
    io.StallIdle := !io.FetchValid

    // 当前响应中属于本指令的半字：正常情况下 PC[1]=1 时取高半字；
    // 第二个字按字对齐取低半字。
    val halfSel = Mux(secondWord, 0.U(1.W), pcReg(1))
    val halfword = Mux(halfSel === 0.U, io.RespData(15, 0), io.RespData(31, 16))
    val is32 = halfword(1, 0) === "b11".U(2.W)

    val respIsSecond = secondWord
    val respIsFirstHalf = !secondWord && is32 && pcReg(1)
    val respIsComplete32 = !secondWord && is32 && !pcReg(1)
    val respIs16 = !secondWord && !is32

    val emitInstr = WireDefault(false.B)
    val emitFault = WireDefault(false.B)
    val nextPC = WireDefault(pcReg)
    val outInstruction = WireDefault(0.U(32.W))
    val outIsCompressed = WireDefault(false.B)
    val outException = WireDefault(false.B)
    val outCause = WireDefault(0.U(4.W))
    val outAccessFaultResp = WireDefault(0.U(2.W))
    val outPredTaken = WireDefault(false.B)
    val outPredTarget = WireDefault(0.U(XLEN.W))

    when(state === sWait && io.RespValid) {
      when(io.RespExceptionValid) {
        // 取指访问错误优先于半字拼装。pc 仍指向指令起点；mtval 由 EXU
        // 按 PC 回退，mepc 因此能精确指到这条指令。
        emitFault := true.B
        outException := true.B
        outCause := io.RespExceptionCause
        outAccessFaultResp := io.RespAccessFaultResp
        outInstruction := "h00000013".U(32.W)
        nextPC := pcReg + Mux(secondWord, 4.U, 2.U)
      }.elsewhen(respIsSecond) {
        // 跨字 32 位指令：低半字来自第一次取指的高 16 位，高半字来自
        // 第二个字的低 16 位。
        emitInstr := true.B
        outInstruction := Cat(io.RespData(15, 0), firstHalf)
        outIsCompressed := false.B
        nextPC := Mux(savedPredTaken, savedPredTarget, pcReg + 4.U)
        outPredTaken := savedPredTaken
        outPredTarget := savedPredTarget
      }.elsewhen(respIsFirstHalf) {
        // 只消费第一个字，不产生指令；下一拍取同一个 32 位指令的后半。
      }.elsewhen(respIsComplete32) {
        emitInstr := true.B
        outInstruction := io.RespData
        outIsCompressed := false.B
        nextPC := Mux(savedPredTaken, savedPredTarget, pcReg + 4.U)
        outPredTaken := savedPredTaken
        outPredTarget := savedPredTarget
      }.otherwise {
        // respIs16：PC[1] 决定取的是低半字还是高半字，零扩展后交给 IDU。
        emitInstr := true.B
        outInstruction := Cat(0.U(16.W), halfword)
        outIsCompressed := true.B
        nextPC := Mux(savedPredTaken, savedPredTarget, pcReg + 2.U)
        outPredTaken := savedPredTaken
        outPredTarget := savedPredTarget
      }
    }

    io.Out.valid := emitInstr || emitFault
    io.Out.bits.Instruction := outInstruction
    io.Out.bits.IsCompressed := outIsCompressed && !outException
    io.Out.bits.pc := pcReg
    io.Out.bits.ExceptionValid := outException
    io.Out.bits.ExceptionCause := Mux(outException, outCause, 0.U(4.W))
    io.Out.bits.AccessFaultResp := outAccessFaultResp
    io.Out.bits.pred_taken := outPredTaken && !outException
    io.Out.bits.pred_target := Mux(outException, 0.U, outPredTarget)

    // 第一个半字只是内部中间结果，可以直接消费；产生输出或故障的响应
    // 必须等下游握手，避免在反压时丢指令。
    io.RespReady := state === sWait && io.RespValid &&
      Mux(io.RespExceptionValid, io.Out.ready,
        Mux(respIsFirstHalf, true.B, io.Out.ready))

    when(redirect) {
      // 重定向/异常优先：清掉未完成的半字和预测快照，从新目标重新开始。
      pcReg := redirectTarget
      state := sIdle
      secondWord := false.B
      faultHeld := false.B
    }.elsewhen(io.Out.fire && emitInstr) {
      pcReg := nextPC
      state := sIdle
      secondWord := false.B
    }.elsewhen(io.Out.fire && emitFault) {
      // 故障指令已经交付；停发请求，直到 EXU 提交 trap 产生重定向。
      state := sIdle
      secondWord := false.B
      faultHeld := true.B
    }.elsewhen(state === sWait && io.RespValid && respIsFirstHalf) {
      firstHalf := io.RespData(31, 16)
      secondWord := true.B
      state := sIdle
    }.elsewhen(io.FetchValid && io.FetchReady) {
      state := sWait
      when(!secondWord) {
        savedPredTaken := io.PredHit
        savedPredTarget := io.PredTarget
      }
    }
  }
}
