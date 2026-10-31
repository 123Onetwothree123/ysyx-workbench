package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.fudian._
import _root_.ysyx_26030103.config._

// Fudian 的多周期 FDIV/FSQRT、共享引擎回舍与响应握手。
trait ysyx_26030103_FudianFPUResponse {
  this: ysyx_26030103_FudianFPUBase
    with ysyx_26030103_FudianFPUArith
    with ysyx_26030103_FudianFPUResult =>

  // FDIV/FSQRT：多周期 valid/ready
  private val isSlowOp =
    op === ysyx_26030103_FpuOp.FDiv || op === ysyx_26030103_FpuOp.FSqrt
  private val isSqrtOp = op === ysyx_26030103_FpuOp.FSqrt

  // 除法/开方共享：F/D 共用 D 精度 FDIV，回舍时重算标志
  private val Shared = SharedDivSqrt && HasD

  private val sDiv: Option[FDIV] =
    if (!Shared) {
      Some(Module(new FDIV(SExpWidth, SPrecision)).suggestName(
        if (HasD) "sDivDedicatedDivSqrt" else "sDivOnlyDivSqrt"
      ))
    } else {
      None
    }
  private val dDiv: Option[FDIV] = if (HasD) {
    Some(
      Module(new FDIV(DExpWidth, DPrecision)).suggestName(
        if (Shared) "dDivSharedDivSqrt" else "dDivDedicatedDivSqrt"
      )
    )
  } else {
    None
  }

  // 共享模式下 F->D / D->F 的精确提升与回舍。
  private val sDivAToD =
    if (Shared)
      Some(Module(new FPToFP(SExpWidth, SPrecision, DExpWidth, DPrecision)))
    else None
  private val sDivBToD =
    if (Shared)
      Some(Module(new FPToFP(SExpWidth, SPrecision, DExpWidth, DPrecision)))
    else None
  private val divDToS =
    if (Shared)
      Some(Module(new FPToFP(DExpWidth, DPrecision, SExpWidth, SPrecision)))
    else None
  if (Shared) {
    sDivAToD.get.io.in := aS
    sDivAToD.get.io.rm := ysyx_26030103_FpuRm.RNE
    sDivBToD.get.io.in := bS
    sDivBToD.get.io.rm := ysyx_26030103_FpuRm.RNE
  }

  sDiv.foreach { div =>
    div.io.a := aS
    div.io.b := bS
    div.io.rm := rm
    div.io.specialIO.isSqrt := isSqrtOp
    div.io.specialIO.kill := false.B
    div.io.specialIO.in_valid := false.B
    div.io.specialIO.out_ready := false.B
  }
  dDiv.foreach { div =>
    val divA = if (Shared) Mux(fmtD, aD, sDivAToD.get.io.result) else aD
    val divB = if (Shared) Mux(fmtD, bD, sDivBToD.get.io.result) else bD
    div.io.a := divA
    div.io.b := divB
    div.io.rm := rm
    div.io.specialIO.isSqrt := isSqrtOp
    div.io.specialIO.kill := false.B
    div.io.specialIO.in_valid := false.B
    div.io.specialIO.out_ready := false.B
  }

  private val busy = RegInit(false.B)
  private val respValid = RegInit(false.B)
  private val activeDivIsD = RegInit(false.B)
  private val activeRm = RegInit(ysyx_26030103_FpuRm.RNE)
  // 共享模式提升 F->D 会丢 sNaN，需锁存 signaling 信息，回舍时补 NV。
  private val activeFSNaN = RegInit(false.B)
  // FDIV 的 out_valid 先拉高、结果下一拍才稳定，故先等一拍再采样。
  private val divSeenValid = RegInit(false.B)
  private val respResult = RegInit(0.U(FLEN.W))
  private val respFlags = RegInit(0.U(5.W))

  private val respPipe =
    Module(new ysyx_26030103_FPUResponsePipeline(FLEN, Config.FPUProfile.Pipeline))
  respPipe.io.flush := io.flush
  respPipe.io.in.valid := respValid
  respPipe.io.in.bits.result := respResult
  respPipe.io.in.bits.fflags := respFlags
  io.resp <> respPipe.io.out

  private val slowReady: Bool = if (Shared) {
    dDiv.get.io.specialIO.in_ready
  } else if (HasD) {
    Mux(fmtD, dDiv.get.io.specialIO.in_ready, sDiv.get.io.specialIO.in_ready)
  } else {
    sDiv.get.io.specialIO.in_ready
  }

  io.req.ready := !busy && !respValid && (!isSlowOp || slowReady)
  private val reqFire = io.req.valid && io.req.ready

  if (Shared) {
    dDiv.get.io.specialIO.in_valid := reqFire && isSlowOp && !io.flush
  } else {
    sDiv.get.io.specialIO.in_valid := reqFire && isSlowOp && !fmtD && !io.flush
    if (HasD) {
      dDiv.get.io.specialIO.in_valid :=
        reqFire && isSlowOp && fmtD && !io.flush
    }
  }

  private val divOutValid: Bool = if (Shared) {
    dDiv.get.io.specialIO.out_valid
  } else if (HasD) {
    Mux(activeDivIsD, dDiv.get.io.specialIO.out_valid,
      sDiv.get.io.specialIO.out_valid)
  } else {
    sDiv.get.io.specialIO.out_valid
  }

  // 共享模式 F 结果：D 引擎结果回舍到 S，并按 S 精度重算 UF/NX/OF。
  private val sharedFFlags: UInt = if (Shared) {
    val cvtFlags = divDToS.get.io.fflags
    val engineFlags = dDiv.get.io.fflags
    val sResult = divDToS.get.io.result
    val tiny = sResult(30, 23) === 0.U
    val nx = cvtFlags(0) || engineFlags(0)
    val of = cvtFlags(2) || engineFlags(2)
    val uf = cvtFlags(1) || (tiny && nx)
    Cat(
      engineFlags(4) || cvtFlags(4) || activeFSNaN,
      engineFlags(3),
      of,
      uf,
      nx
    )
  } else {
    0.U(5.W)
  }
  private val divOutResult: UInt = if (Shared) {
    Mux(activeDivIsD, dDiv.get.io.result, boxS(divDToS.get.io.result))
  } else if (HasD) {
    Mux(activeDivIsD, dDiv.get.io.result, boxS(sDiv.get.io.result))
  } else {
    boxS(sDiv.get.io.result)
  }
  private val divOutFlags: UInt = if (Shared) {
    Mux(activeDivIsD, dDiv.get.io.fflags, sharedFFlags)
  } else if (HasD) {
    Mux(activeDivIsD, dDiv.get.io.fflags, sDiv.get.io.fflags)
  } else {
    sDiv.get.io.fflags
  }

  if (Shared) {
    divDToS.get.io.in := dDiv.get.io.result
    divDToS.get.io.rm := activeRm
    dDiv.get.io.specialIO.out_ready :=
      busy && divSeenValid && divOutValid
    dDiv.get.io.specialIO.kill := io.flush && busy
  } else {
    sDiv.get.io.specialIO.out_ready :=
      busy && !activeDivIsD && divSeenValid && divOutValid
    sDiv.get.io.specialIO.kill := io.flush && busy && !activeDivIsD
    if (HasD) {
      dDiv.get.io.specialIO.out_ready :=
        busy && activeDivIsD && divSeenValid && divOutValid
      dDiv.get.io.specialIO.kill := io.flush && busy && activeDivIsD
    }
  }

  when(reqFire) {
    activeRm := rm
    activeFSNaN := !fmtD && Mux(
      isSqrtOp,
      isSNaN32(aS),
      isSNaN32(aS) || isSNaN32(bS)
    )
    when(isSlowOp) {
      busy := true.B
      divSeenValid := false.B
      activeDivIsD := fmtD
    }.otherwise {
      respValid := true.B
      respResult := fastResult
      respFlags := fastFlags
    }
  }

  when(busy && divOutValid) {
    when(!divSeenValid) {
      divSeenValid := true.B
    }.otherwise {
      busy := false.B
      respValid := true.B
      respResult := asResp(divOutResult)
      respFlags := divOutFlags
    }
  }

  when(respValid && respPipe.io.in.ready) {
    respValid := false.B
  }
  when(io.flush) {
    busy := false.B
    divSeenValid := false.B
    respValid := false.B
  }
}
