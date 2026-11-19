package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarWrite {
  this: ysyx_26030103_AXI5Xbar =>

  def writeBody(): Unit = {
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
    val writeCannotForward = WriteTargetAfterAW === targetInvalid ||
      // CLINT 只支持自然递增的两拍写；FIXED/WRAP 地址不递增，直接本地 DECERR。
      (WriteTargetAfterAW === TargetCLINT && WriteLengthAfterAW =/= 0.U &&
        AWBurstAfterAW =/= 1.U) ||
      (WriteTargetAfterAW === TargetPLIC && WriteLengthAfterAW =/= 0.U)
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
      // AW 只发一次；首个W beat取自缓存，之后直接ready/valid流过直到WLAST。
      when(writeTargetReg === targetSoCBus) {
        val SendAW = !DownstreamAWDone
        val useBufferedW = wValidReg
        val streamW = !wValidReg && !downstreamWDone

        io.SoCBus.AW.AWVALID := SendAW
        io.SoCBus.AW.AWID := AWIDReg
        io.SoCBus.AW.AWADDR := AWAddressReg
        io.SoCBus.AW.AWLEN := AWLengthReg
        io.SoCBus.AW.AWSIZE := AWSizeReg
        io.SoCBus.AW.AWBURST := AWBurstReg
        io.SoCBus.AW.AWPROT := AWProtReg
        io.SoCBus.AW.AWCACHE := AWCacheReg
        io.SoCBus.AW.AWQOS := AWQosReg

        io.SoCBus.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
        io.SoCBus.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
        io.SoCBus.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
        io.SoCBus.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
        inWReady := streamW && io.SoCBus.W.WREADY

        val AWFire = SendAW && io.SoCBus.AW.AWREADY
        val bufferedWFire = useBufferedW && io.SoCBus.W.WREADY
        val streamWFire = streamW && io.in.W.WVALID && io.SoCBus.W.WREADY
        val wFire = bufferedWFire || streamWFire
        val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

        when(AWFire) {
          DownstreamAWDone := true.B
        }
        when(bufferedWFire) {
          wValidReg := false.B
        }
        when(wLastFire) {
          downstreamWDone := true.B
        }

        when((DownstreamAWDone || AWFire) && (downstreamWDone || wLastFire)) {
          writeState := stateWriteResponse
        }
      }.elsewhen(writeTargetReg === TargetCLINT) {
        val SendAW = !DownstreamAWDone
        val useBufferedW = wValidReg
        val streamW = !wValidReg && !downstreamWDone

        io.CLINT.AW.AWVALID := SendAW
        io.CLINT.AW.AWID := AWIDReg
        io.CLINT.AW.AWADDR := AWAddressReg
        io.CLINT.AW.AWLEN := AWLengthReg
        io.CLINT.AW.AWSIZE := AWSizeReg
        io.CLINT.AW.AWBURST := AWBurstReg
        io.CLINT.AW.AWPROT := AWProtReg
        io.CLINT.AW.AWCACHE := AWCacheReg
        io.CLINT.AW.AWQOS := AWQosReg

        io.CLINT.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
        io.CLINT.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
        io.CLINT.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
        io.CLINT.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
        inWReady := streamW && io.CLINT.W.WREADY

        val AWFire = SendAW && io.CLINT.AW.AWREADY
        val bufferedWFire = useBufferedW && io.CLINT.W.WREADY
        val streamWFire = streamW && io.in.W.WVALID && io.CLINT.W.WREADY
        val wFire = bufferedWFire || streamWFire
        val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

        when(AWFire) {
          DownstreamAWDone := true.B
        }
        when(bufferedWFire) {
          wValidReg := false.B
        }
        when(wLastFire) {
          downstreamWDone := true.B
        }

        when((DownstreamAWDone || AWFire) && (downstreamWDone || wLastFire)) {
          writeState := stateWriteResponse
        }
      }.elsewhen(writeTargetReg === TargetPLIC) {
        val SendAW = !DownstreamAWDone
        val useBufferedW = wValidReg
        val streamW = !wValidReg && !downstreamWDone

        io.PLIC.AW.AWVALID := SendAW
        io.PLIC.AW.AWID := AWIDReg
        io.PLIC.AW.AWADDR := AWAddressReg
        io.PLIC.AW.AWLEN := AWLengthReg
        io.PLIC.AW.AWSIZE := AWSizeReg
        io.PLIC.AW.AWBURST := AWBurstReg
        io.PLIC.AW.AWPROT := AWProtReg
        io.PLIC.AW.AWCACHE := AWCacheReg
        io.PLIC.AW.AWQOS := AWQosReg

        io.PLIC.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
        io.PLIC.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
        io.PLIC.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
        io.PLIC.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
        inWReady := streamW && io.PLIC.W.WREADY

        val AWFire = SendAW && io.PLIC.AW.AWREADY
        val bufferedWFire = useBufferedW && io.PLIC.W.WREADY
        val streamWFire = streamW && io.in.W.WVALID && io.PLIC.W.WREADY
        val wFire = bufferedWFire || streamWFire
        val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

        when(AWFire) {
          DownstreamAWDone := true.B
        }
        when(bufferedWFire) {
          wValidReg := false.B
        }
        when(wLastFire) {
          downstreamWDone := true.B
        }

        when((DownstreamAWDone || AWFire) && (downstreamWDone || wLastFire)) {
          writeState := stateWriteResponse
        }
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
        io.in.B.BID := io.SoCBus.B.BID
        io.in.B.BVALID := io.SoCBus.B.BVALID
        io.in.B.BRESP := io.SoCBus.B.BRESP
        // B没有地址，BID必须等于本级发出的AWID，错配说明从设备违规。
        when(io.SoCBus.B.BVALID) {
          assert(
            io.SoCBus.B.BID === AWIDReg,
            "SoCBus returned a write response with an unknown ID"
          )
        }

        when(io.SoCBus.B.BVALID && io.in.B.BREADY) {
          AWValidReg := false.B
          wValidReg := false.B
          DownstreamAWDone := false.B
          downstreamWDone := false.B
          writeState := stateWriteIdle
        }
      }.elsewhen(writeTargetReg === TargetCLINT) {
        io.in.B.BID := io.CLINT.B.BID
        io.in.B.BVALID := io.CLINT.B.BVALID
        io.in.B.BRESP := io.CLINT.B.BRESP
        when(io.CLINT.B.BVALID) {
          assert(
            io.CLINT.B.BID === AWIDReg,
            "CLINT returned a write response with an unknown ID"
          )
        }

        when(io.CLINT.B.BVALID && io.in.B.BREADY) {
          AWValidReg := false.B
          wValidReg := false.B
          DownstreamAWDone := false.B
          downstreamWDone := false.B
          writeState := stateWriteIdle
        }
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
      io.in.B.BID := io.PLIC.B.BID
      io.in.B.BVALID := io.PLIC.B.BVALID
      io.in.B.BRESP := io.PLIC.B.BRESP
      when(io.PLIC.B.BVALID) {
        assert(
          io.PLIC.B.BID === AWIDReg,
          "PLIC returned a write response with an unknown ID"
        )
      }
    }
    when(PLICWriteResponse && io.PLIC.B.BVALID && io.in.B.BREADY) {
      AWValidReg := false.B
      wValidReg := false.B
      DownstreamAWDone := false.B
      downstreamWDone := false.B
      writeState := stateWriteIdle
    }
    // 防御：无效目标本应在进入转发前走 DECERR，不能停在 Response 等 B。
    when(inWriteResponse && writeTargetReg === targetInvalid) {
      writeState := stateWriteDECERR
    }
  }
}
