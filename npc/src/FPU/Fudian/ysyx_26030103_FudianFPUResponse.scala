package ysyx_26030103.fpu

import chisel3._
import chisel3.util._
import _root_.fudian._

// Fudian 的多周期 FDIV/FSQRT、共享引擎回舍与响应握手。
trait ysyx_26030103_FudianFPUResponse {
  this: ysyx_26030103_FudianFPUBase
    with ysyx_26030103_FudianFPUArith
    with ysyx_26030103_FudianFPUResult
    with ysyx_26030103_FPUResponseShell =>

  // FDIV/FSQRT：多周期 valid/ready
  private val isSlowOp =
    op === ysyx_26030103_FPUOp.FDiv || op === ysyx_26030103_FPUOp.FSqrt
  private val isSqrtOp = op === ysyx_26030103_FPUOp.FSqrt

  // 除法/开方共享：F/D 共用 D 精度 FDIV，回舍时重算标志
  private val shared = sharedDivSqrt && hasD

  private val sDiv: Option[FDIV] =
    if (!shared) {
      Some(Module(new FDIV(sExpWidth, sPrecision)).suggestName(
        if (hasD) "sDivDedicatedDivSqrt" else "sDivOnlyDivSqrt"
      ))
    } else {
      None
    }
  private val dDiv: Option[FDIV] = if (hasD) {
    Some(
      Module(new FDIV(dExpWidth, dPrecision)).suggestName(
        if (shared) "dDivSharedDivSqrt" else "dDivDedicatedDivSqrt"
      )
    )
  } else {
    None
  }

  // 共享模式下 F->D / D->F 的精确提升与回舍。
  private val sDivAToD =
    if (shared)
      Some(Module(new FPToFP(sExpWidth, sPrecision, dExpWidth, dPrecision)))
    else None
  private val sDivBToD =
    if (shared)
      Some(Module(new FPToFP(sExpWidth, sPrecision, dExpWidth, dPrecision)))
    else None
  private val divDToS =
    if (shared)
      Some(Module(new FPToFP(dExpWidth, dPrecision, sExpWidth, sPrecision)))
    else None
  if (shared) {
    sDivAToD.get.io.in := aS
    sDivAToD.get.io.rm := ysyx_26030103_FPURm.RNE
    sDivBToD.get.io.in := bS
    sDivBToD.get.io.rm := ysyx_26030103_FPURm.RNE
  }

  sDiv.foreach { div =>
    div.io.a := aS
    div.io.b := bS
    div.io.rm := rm
    div.io.specialIO.isSqrt := isSqrtOp
  }
  dDiv.foreach { div =>
    val divA = if (shared) Mux(fmtD, aD, sDivAToD.get.io.result) else aD
    val divB = if (shared) Mux(fmtD, bD, sDivBToD.get.io.result) else bD
    div.io.a := divA
    div.io.b := divB
    div.io.rm := rm
    div.io.specialIO.isSqrt := isSqrtOp
  }

  private val busy = RegInit(false.B)
  private val ActiveDivIsDouble = RegInit(false.B)
  private val activeRm = RegInit(ysyx_26030103_FPURm.RNE)
  // 共享模式提升 F->D 会丢 sNaN，需锁存 signaling 信息，回舍时补 NV。
  private val ActiveSingleSNaN = RegInit(false.B)
  // FDIV 的 out_valid 先拉高、结果下一拍才稳定，故先等一拍再采样。
  private val divSeenValid = RegInit(false.B)

  private val slowReady: Bool = if (shared) {
    dDiv.get.io.specialIO.in_ready
  } else if (hasD) {
    Mux(fmtD, dDiv.get.io.specialIO.in_ready, sDiv.get.io.specialIO.in_ready)
  } else {
    sDiv.get.io.specialIO.in_ready
  }

  io.request.ready := !busy && !responseValid && (!isSlowOp || slowReady)
  private val requestFire = io.request.valid && io.request.ready

  if (shared) {
    dDiv.get.io.specialIO.in_valid := requestFire && isSlowOp && !io.flush
  } else {
    sDiv.get.io.specialIO.in_valid := requestFire && isSlowOp && !fmtD && !io.flush
    if (hasD) {
      dDiv.get.io.specialIO.in_valid :=
        requestFire && isSlowOp && fmtD && !io.flush
    }
  }

  private val divOutValid: Bool = if (shared) {
    dDiv.get.io.specialIO.out_valid
  } else if (hasD) {
    Mux(ActiveDivIsDouble, dDiv.get.io.specialIO.out_valid,
      sDiv.get.io.specialIO.out_valid)
  } else {
    sDiv.get.io.specialIO.out_valid
  }

  // 共享模式 F 结果：D 引擎结果回舍到 S，并按 S 精度重算 UF/NX/OF。
  private val sharedFFlags: UInt = if (shared) {
    val cvtFlags = divDToS.get.io.fflags
    val engineFlags = dDiv.get.io.fflags
    val sResult = divDToS.get.io.result
    val tiny = sResult(30, 23) === 0.U
    val nx = cvtFlags(ysyx_26030103_FPUFlags.NX) ||
      engineFlags(ysyx_26030103_FPUFlags.NX)
    val of = cvtFlags(ysyx_26030103_FPUFlags.OF) ||
      engineFlags(ysyx_26030103_FPUFlags.OF)
    val uf = cvtFlags(ysyx_26030103_FPUFlags.UF) || (tiny && nx)
    ysyx_26030103_FPUFlags.Pack(
      engineFlags(ysyx_26030103_FPUFlags.NV) ||
        cvtFlags(ysyx_26030103_FPUFlags.NV) || ActiveSingleSNaN,
      engineFlags(ysyx_26030103_FPUFlags.DZ),
      of,
      uf,
      nx
    )
  } else {
    0.U(ysyx_26030103_FPUFlags.Width.W)
  }
  private val divOutResult: UInt = if (shared) {
    Mux(ActiveDivIsDouble, dDiv.get.io.result, boxS(divDToS.get.io.result))
  } else if (hasD) {
    Mux(ActiveDivIsDouble, dDiv.get.io.result, boxS(sDiv.get.io.result))
  } else {
    boxS(sDiv.get.io.result)
  }
  private val divOutFlags: UInt = if (shared) {
    Mux(ActiveDivIsDouble, dDiv.get.io.fflags, sharedFFlags)
  } else if (hasD) {
    Mux(ActiveDivIsDouble, dDiv.get.io.fflags, sDiv.get.io.fflags)
  } else {
    sDiv.get.io.fflags
  }

  if (shared) {
    divDToS.get.io.in := dDiv.get.io.result
    divDToS.get.io.rm := activeRm
    dDiv.get.io.specialIO.out_ready :=
      busy && divSeenValid && divOutValid
    dDiv.get.io.specialIO.kill := io.flush && busy
  } else {
    sDiv.get.io.specialIO.out_ready :=
      busy && !ActiveDivIsDouble && divSeenValid && divOutValid
    sDiv.get.io.specialIO.kill := io.flush && busy && !ActiveDivIsDouble
    if (hasD) {
      dDiv.get.io.specialIO.out_ready :=
        busy && ActiveDivIsDouble && divSeenValid && divOutValid
      dDiv.get.io.specialIO.kill := io.flush && busy && ActiveDivIsDouble
    }
  }

  when(requestFire) {
    activeRm := rm
    ActiveSingleSNaN := !fmtD && Mux(
      isSqrtOp,
      isSNaN32(aS),
      isSNaN32(aS) || isSNaN32(bS)
    )
    when(isSlowOp) {
      busy := true.B
      divSeenValid := false.B
      ActiveDivIsDouble := fmtD
    }
    CaptureFastResponse(!isSlowOp)
  }

  when(busy && divOutValid) {
    when(!divSeenValid) {
      divSeenValid := true.B
    }.otherwise {
      busy := false.B
      PublishResponse(asResponse(divOutResult), divOutFlags)
    }
  }

  ReleaseResponse()
  when(io.flush) {
    busy := false.B
    divSeenValid := false.B
    ClearResponseOnFlush()
  }
}
