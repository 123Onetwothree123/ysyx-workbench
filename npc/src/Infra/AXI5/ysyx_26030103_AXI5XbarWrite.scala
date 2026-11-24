package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarWrite {
  this: ysyx_26030103_AXI5Xbar =>

  private case class WriteTarget(
      ID: UInt,
      name: String,
      downstream: ysyx_26030103_AXI5IO,
      reject: (UInt, UInt) => Bool
  )

  private def ForwardWriteRequest(target: WriteTarget): Unit = {
    val downstream = target.downstream
    // AW 只发一次；首个W beat取自缓存，之后直接ready/valid流过直到WLAST。
    val SendAW = !DownstreamAWDone
    val UseBufferedW = wValidReg
    val StreamW = !wValidReg && !downstreamWDone

    downstream.AW.AWVALID := SendAW
    downstream.AW.AWID := AWIDReg
    downstream.AW.AWADDR := AWAddressReg
    downstream.AW.AWLEN := AWLengthReg
    downstream.AW.AWSIZE := AWSizeReg
    downstream.AW.AWBURST := AWBurstReg
    downstream.AW.AWPROT := AWProtReg
    downstream.AW.AWCACHE := AWCacheReg
    downstream.AW.AWQOS := AWQosReg

    downstream.W.WVALID :=
      Mux(UseBufferedW, true.B, StreamW && io.in.W.WVALID)
    downstream.W.WDATA := Mux(UseBufferedW, wDataReg, io.in.W.WDATA)
    downstream.W.WSTRB := Mux(UseBufferedW, wStrobeReg, io.in.W.WSTRB)
    downstream.W.WLAST := Mux(UseBufferedW, wLastReg, io.in.W.WLAST)
    inWReady := StreamW && downstream.W.WREADY

    val AWFire = SendAW && downstream.AW.AWREADY
    val BufferedWFire = UseBufferedW && downstream.W.WREADY
    val StreamWFire = StreamW && io.in.W.WVALID && downstream.W.WREADY
    val WFire = BufferedWFire || StreamWFire
    val WLastFire = WFire && Mux(UseBufferedW, wLastReg, io.in.W.WLAST)

    when(AWFire) {
      DownstreamAWDone := true.B
    }
    when(BufferedWFire) {
      wValidReg := false.B
    }
    when(WLastFire) {
      downstreamWDone := true.B
    }

    when((DownstreamAWDone || AWFire) && (downstreamWDone || WLastFire)) {
      writeState := stateWriteResponse
    }
  }

  private def ForwardWriteResponse(target: WriteTarget): Unit = {
    val downstream = target.downstream
    io.in.B.BID := downstream.B.BID
    io.in.B.BVALID := downstream.B.BVALID
    io.in.B.BRESP := downstream.B.BRESP
    // B没有地址，BID必须等于本级发出的AWID，错配说明从设备违规。
    when(downstream.B.BVALID) {
      assert(
        downstream.B.BID === AWIDReg,
        s"${target.name} returned a write response with an unknown ID"
      )
    }

    when(downstream.B.BVALID && io.in.B.BREADY) {
      AWValidReg := false.B
      wValidReg := false.B
      DownstreamAWDone := false.B
      downstreamWDone := false.B
      writeState := stateWriteIdle
    }
  }

  def writeBody(): Unit = {
    val SOCBusWriteTarget = WriteTarget(
      targetSoCBus,
      "SoCBus",
      io.SoCBus,
      (_, _) => false.B
    )
    // CLINT 只支持自然递增的两拍写；FIXED/WRAP 地址不递增，直接本地 DECERR。
    val CLINTWriteTarget = WriteTarget(
      TargetCLINT,
      "CLINT",
      io.CLINT,
      (length, burst) => length =/= 0.U && burst =/= 1.U
    )
    // PLIC 叶子只做单拍写。
    val PLICWriteTarget = WriteTarget(
      TargetPLIC,
      "PLIC",
      io.PLIC,
      (length, _) => length =/= 0.U
    )
    val WriteTargets = Seq(SOCBusWriteTarget, CLINTWriteTarget, PLICWriteTarget)

    val WriteTargetAfterAW =
      Mux(
        InAWFire,
        decode(
          io.in.AW.AWADDR,
          io.in.AW.AWLEN,
          io.in.AW.AWSIZE,
          io.in.AW.AWBURST,
          isWrite = true
        ),
        writeTargetReg
      )
    val WriteLengthAfterAW = Mux(InAWFire, io.in.AW.AWLEN, AWLengthReg)
    val AWCollected = AWValidReg || InAWFire
    val wCollected = wValidReg || inWFire
    val firstWLastAfterW = Mux(inWFire, io.in.W.WLAST, wLastReg)
    // CLINT 已支持 AWLEN=1 的 64 位 mtimecmp 两拍写，这里只剩 PLIC 的单拍限制。
    val AWBurstAfterAW = Mux(InAWFire, io.in.AW.AWBURST, AWBurstReg)
    val WriteRequestRejected = WriteTargets
      .map { target =>
        WriteTargetAfterAW === target.ID &&
          target.reject(WriteLengthAfterAW, AWBurstAfterAW)
      }
      .reduce(_ || _)
    val writeCannotForward = WriteTargetAfterAW === targetInvalid ||
      WriteRequestRejected
    when(InAWFire) {
      AWIDReg := io.in.AW.AWID
      AWAddressReg := io.in.AW.AWADDR
      AWLengthReg := io.in.AW.AWLEN
      AWSizeReg := io.in.AW.AWSIZE
      AWBurstReg := io.in.AW.AWBURST
      AWProtReg := io.in.AW.AWPROT
      AWCacheReg := io.in.AW.AWCACHE
      AWQosReg := io.in.AW.AWQOS
      writeTargetReg := decode(
        io.in.AW.AWADDR,
        io.in.AW.AWLEN,
        io.in.AW.AWSIZE,
        io.in.AW.AWBURST,
        isWrite = true
      )
      AWValidReg := true.B
    }
    // Idle/Collect阶段只缓存首个W beat；进入转发阶段后其余beat直接流过。
    when(
      inWFire &&
        (writeState === stateWriteIdle || writeState === stateWriteCollect)
    ) {
      wDataReg := io.in.W.WDATA
      wStrobeReg := io.in.W.WSTRB
      wLastReg := io.in.W.WLAST
      wValidReg := true.B
    }
    // BREADY 必须写成“响应态 + 目标 + 上游 ready”的组合条件；放进 B 响应 when
    // 分支会触发 firtool 生成恒假表达式，PLIC 写永远等不到 B 握手。
    val inWriteResponse = writeState === stateWriteResponse
    io.SoCBus.B.BREADY :=
      inWriteResponse && writeTargetReg === targetSoCBus && io.in.B.BREADY
    io.CLINT.B.BREADY :=
      inWriteResponse && writeTargetReg === TargetCLINT && io.in.B.BREADY
    io.PLIC.B.BREADY :=
      inWriteResponse && writeTargetReg === TargetPLIC && io.in.B.BREADY
    // B 通道默认值；各目标分支和下方 PLIC 专用组合逻辑会覆盖它。
    io.in.B.BID := 0.U
    io.in.B.BVALID := false.B
    io.in.B.BRESP := OKAY

    when(writeState === stateWriteIdle) {
      val hasWriteRequest =
        io.in.AW.AWVALID || io.in.W.WVALID || AWValidReg || wValidReg
      when(hasWriteRequest) { // 哪个通道还没缓存，就对哪个通道拉ready
        InAWReady := !AWValidReg
        inWReady := !wValidReg

        // 如果本周期结束后AW和W都已经收到了，就可以进入转发阶段
        when(AWCollected && wCollected) {
          DownstreamAWDone := false.B
          downstreamWDone := false.B
          when(writeCannotForward) {
            // 首beat已由收集级接收；若它不是最后一拍，继续排空余下W。
            wValidReg := false.B
            writeState := Mux(
              firstWLastAfterW,
              stateWriteDECERR,
              stateWriteDrain
            )
          }.otherwise {
            writeState := stateWriteRequest
          }
        }.otherwise {
          writeState := stateWriteCollect
        }
      }
    }.elsewhen(writeState === stateWriteCollect) {
      // AW/W 还没都收到：可能 AW 先到，也可能 W 先到。
      InAWReady := !AWValidReg
      inWReady := !wValidReg

      when(AWCollected && wCollected) {
        DownstreamAWDone := false.B
        downstreamWDone := false.B
        when(writeCannotForward) {
          wValidReg := false.B
          writeState := Mux(
            firstWLastAfterW,
            stateWriteDECERR,
            stateWriteDrain
          )
        }.otherwise {
          writeState := stateWriteRequest
        }
      }
    }.elsewhen(writeState === stateWriteRequest) {
      when(writeTargetReg === targetSoCBus) {
        ForwardWriteRequest(SOCBusWriteTarget)
      }.elsewhen(writeTargetReg === TargetCLINT) {
        ForwardWriteRequest(CLINTWriteTarget)
      }.elsewhen(writeTargetReg === TargetPLIC) {
        ForwardWriteRequest(PLICWriteTarget)
      }.otherwise {
        writeState := Mux(
          wValidReg && wLastReg,
          stateWriteDECERR,
          stateWriteDrain
        )
      }
    }.elsewhen(writeState === stateWriteDrain) {
      // 地址无效或PLIC不支持burst：继续吞掉剩余beat，避免上游停在W等不到响应。
      inWReady := true.B
      when(io.in.W.WVALID && io.in.W.WLAST) {
        writeState := stateWriteDECERR
      }
    }.elsewhen(writeState === stateWriteResponse) {
      // B响应回来时没有地址，所以要根据之前保存的writeTargetReg选择下游
      when(writeTargetReg === targetSoCBus) {
        ForwardWriteResponse(SOCBusWriteTarget)
      }.elsewhen(writeTargetReg === TargetCLINT) {
        ForwardWriteResponse(CLINTWriteTarget)
      }
      // PLIC 的 B 在状态机外转发；这里不能写 otherwise->DECERR，否则会在
      // PLIC 的 B 返回前把合法事务判错。
    }.elsewhen(writeState === stateWriteDECERR) {
      // 地址空洞和不支持的burst在CPU内部完成，不访问任何下游设备。
      io.in.B.BID := AWIDReg
      io.in.B.BVALID := true.B
      io.in.B.BRESP := DECERR

      when(io.in.B.BREADY) {
        AWValidReg := false.B
        wValidReg := false.B
        DownstreamAWDone := false.B
        downstreamWDone := false.B
        writeState := stateWriteIdle
      }
    }

    // PLIC 的 B 单独放在写状态机之外：写进 stateWriteResponse 的最后一个
    // elsewhen 会让 firtool 生成恒假 BREADY，PLIC 写永远等不到 B 握手。
    val PLICWriteResponse =
      writeState === stateWriteResponse && writeTargetReg === TargetPLIC
    when(PLICWriteResponse) {
      ForwardWriteResponse(PLICWriteTarget)
    }
    // 防御：无效目标本应在进入转发前走 DECERR，不能停在 Response 等 B。
    when(inWriteResponse && writeTargetReg === targetInvalid) {
      writeState := stateWriteDECERR
    }
  }
}
