package ysyx_26030103.infra

import chisel3._
import chisel3.util._

private[infra] trait AXI5XbarWrite {
  this: ysyx_26030103_AXI5Xbar =>

  def writeBody(): Unit = {
    val writeTargetAfterAW =
      Mux(
        inAWFire,
        decode(
          io.in.AW.AWADDR,
          io.in.AW.AWLEN,
          io.in.AW.AWSIZE,
          io.in.AW.AWBURST,
          isWrite = true
        ),
        writeTargetReg
      )
    val writeLenAfterAW = Mux(inAWFire, io.in.AW.AWLEN, awLenReg)
    val awCollected = awValidReg || inAWFire
    val wCollected = wValidReg || inWFire
    val firstWLastAfterW = Mux(inWFire, io.in.W.WLAST, wLastReg)
    // CLINT 已支持 AWLEN=1 的 64 位 mtimecmp 两拍写，这里只剩 PLIC 的单拍限制。
    val awBurstAfterAW = Mux(inAWFire, io.in.AW.AWBURST, awBurstReg)
    val writeCannotForward = writeTargetAfterAW === targetInvalid ||
      // CLINT 只支持自然递增的两拍写；FIXED/WRAP 地址不递增，直接本地 DECERR。
      (writeTargetAfterAW === targetCLINT && writeLenAfterAW =/= 0.U &&
        awBurstAfterAW =/= 1.U) ||
      (writeTargetAfterAW === targetPLIC && writeLenAfterAW =/= 0.U)
    when(inAWFire) {
      awIDReg := io.in.AW.AWID
      awAddressReg := io.in.AW.AWADDR
      awLenReg := io.in.AW.AWLEN
      awSizeReg := io.in.AW.AWSIZE
      awBurstReg := io.in.AW.AWBURST
      awProtReg := io.in.AW.AWPROT
      awCacheReg := io.in.AW.AWCACHE
      awQosReg := io.in.AW.AWQOS
      writeTargetReg := decode(
        io.in.AW.AWADDR,
        io.in.AW.AWLEN,
        io.in.AW.AWSIZE,
        io.in.AW.AWBURST,
        isWrite = true
      )
      awValidReg := true.B
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
      inWriteResponse && writeTargetReg === targetCLINT && io.in.B.BREADY
    io.PLIC.B.BREADY :=
      inWriteResponse && writeTargetReg === targetPLIC && io.in.B.BREADY
    // B 通道默认值；各目标分支和下方 PLIC 专用组合逻辑会覆盖它。
    io.in.B.BID := 0.U
    io.in.B.BVALID := false.B
    io.in.B.BRESP := OKAY

    when(writeState === stateWriteIdle) {
      val hasWriteRequest =
        io.in.AW.AWVALID || io.in.W.WVALID || awValidReg || wValidReg
      when(hasWriteRequest) { // 哪个通道还没缓存，就对哪个通道拉ready
        inAWReady := !awValidReg
        inWReady := !wValidReg

        // 如果本周期结束后AW和W都已经收到了，就可以进入转发阶段
        when(awCollected && wCollected) {
          downstreamAWDone := false.B
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
      inAWReady := !awValidReg
      inWReady := !wValidReg

      when(awCollected && wCollected) {
        downstreamAWDone := false.B
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
        val sendAW = !downstreamAWDone
        val useBufferedW = wValidReg
        val streamW = !wValidReg && !downstreamWDone

        io.SoCBus.AW.AWVALID := sendAW
        io.SoCBus.AW.AWID := awIDReg
        io.SoCBus.AW.AWADDR := awAddressReg
        io.SoCBus.AW.AWLEN := awLenReg
        io.SoCBus.AW.AWSIZE := awSizeReg
        io.SoCBus.AW.AWBURST := awBurstReg
        io.SoCBus.AW.AWPROT := awProtReg
        io.SoCBus.AW.AWCACHE := awCacheReg
        io.SoCBus.AW.AWQOS := awQosReg

        io.SoCBus.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
        io.SoCBus.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
        io.SoCBus.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
        io.SoCBus.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
        inWReady := streamW && io.SoCBus.W.WREADY

        val awFire = sendAW && io.SoCBus.AW.AWREADY
        val bufferedWFire = useBufferedW && io.SoCBus.W.WREADY
        val streamWFire = streamW && io.in.W.WVALID && io.SoCBus.W.WREADY
        val wFire = bufferedWFire || streamWFire
        val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

        when(awFire) {
          downstreamAWDone := true.B
        }
        when(bufferedWFire) {
          wValidReg := false.B
        }
        when(wLastFire) {
          downstreamWDone := true.B
        }

        when((downstreamAWDone || awFire) && (downstreamWDone || wLastFire)) {
          writeState := stateWriteResponse
        }
      }.elsewhen(writeTargetReg === targetCLINT) {
        val sendAW = !downstreamAWDone
        val useBufferedW = wValidReg
        val streamW = !wValidReg && !downstreamWDone

        io.CLINT.AW.AWVALID := sendAW
        io.CLINT.AW.AWID := awIDReg
        io.CLINT.AW.AWADDR := awAddressReg
        io.CLINT.AW.AWLEN := awLenReg
        io.CLINT.AW.AWSIZE := awSizeReg
        io.CLINT.AW.AWBURST := awBurstReg
        io.CLINT.AW.AWPROT := awProtReg
        io.CLINT.AW.AWCACHE := awCacheReg
        io.CLINT.AW.AWQOS := awQosReg

        io.CLINT.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
        io.CLINT.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
        io.CLINT.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
        io.CLINT.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
        inWReady := streamW && io.CLINT.W.WREADY

        val awFire = sendAW && io.CLINT.AW.AWREADY
        val bufferedWFire = useBufferedW && io.CLINT.W.WREADY
        val streamWFire = streamW && io.in.W.WVALID && io.CLINT.W.WREADY
        val wFire = bufferedWFire || streamWFire
        val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

        when(awFire) {
          downstreamAWDone := true.B
        }
        when(bufferedWFire) {
          wValidReg := false.B
        }
        when(wLastFire) {
          downstreamWDone := true.B
        }

        when((downstreamAWDone || awFire) && (downstreamWDone || wLastFire)) {
          writeState := stateWriteResponse
        }
      }.elsewhen(writeTargetReg === targetPLIC) {
        val sendAW = !downstreamAWDone
        val useBufferedW = wValidReg
        val streamW = !wValidReg && !downstreamWDone

        io.PLIC.AW.AWVALID := sendAW
        io.PLIC.AW.AWID := awIDReg
        io.PLIC.AW.AWADDR := awAddressReg
        io.PLIC.AW.AWLEN := awLenReg
        io.PLIC.AW.AWSIZE := awSizeReg
        io.PLIC.AW.AWBURST := awBurstReg
        io.PLIC.AW.AWPROT := awProtReg
        io.PLIC.AW.AWCACHE := awCacheReg
        io.PLIC.AW.AWQOS := awQosReg

        io.PLIC.W.WVALID := Mux(useBufferedW, true.B, streamW && io.in.W.WVALID)
        io.PLIC.W.WDATA := Mux(useBufferedW, wDataReg, io.in.W.WDATA)
        io.PLIC.W.WSTRB := Mux(useBufferedW, wStrobeReg, io.in.W.WSTRB)
        io.PLIC.W.WLAST := Mux(useBufferedW, wLastReg, io.in.W.WLAST)
        inWReady := streamW && io.PLIC.W.WREADY

        val awFire = sendAW && io.PLIC.AW.AWREADY
        val bufferedWFire = useBufferedW && io.PLIC.W.WREADY
        val streamWFire = streamW && io.in.W.WVALID && io.PLIC.W.WREADY
        val wFire = bufferedWFire || streamWFire
        val wLastFire = wFire && Mux(useBufferedW, wLastReg, io.in.W.WLAST)

        when(awFire) {
          downstreamAWDone := true.B
        }
        when(bufferedWFire) {
          wValidReg := false.B
        }
        when(wLastFire) {
          downstreamWDone := true.B
        }

        when((downstreamAWDone || awFire) && (downstreamWDone || wLastFire)) {
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
            io.SoCBus.B.BID === awIDReg,
            "SoCBus returned a write response with an unknown ID"
          )
        }

        when(io.SoCBus.B.BVALID && io.in.B.BREADY) {
          awValidReg := false.B
          wValidReg := false.B
          downstreamAWDone := false.B
          downstreamWDone := false.B
          writeState := stateWriteIdle
        }
      }.elsewhen(writeTargetReg === targetCLINT) {
        io.in.B.BID := io.CLINT.B.BID
        io.in.B.BVALID := io.CLINT.B.BVALID
        io.in.B.BRESP := io.CLINT.B.BRESP
        when(io.CLINT.B.BVALID) {
          assert(
            io.CLINT.B.BID === awIDReg,
            "CLINT returned a write response with an unknown ID"
          )
        }

        when(io.CLINT.B.BVALID && io.in.B.BREADY) {
          awValidReg := false.B
          wValidReg := false.B
          downstreamAWDone := false.B
          downstreamWDone := false.B
          writeState := stateWriteIdle
        }
      }
      // PLIC 的 B 在状态机外转发；这里不能写 otherwise->DECERR，否则会在
      // PLIC 的 B 返回前把合法事务判错。
    }.elsewhen(writeState === stateWriteDECERR) {
      // 地址空洞和不支持的burst在CPU内部完成，不访问任何下游设备。
      io.in.B.BID := awIDReg
      io.in.B.BVALID := true.B
      io.in.B.BRESP := DECERR

      when(io.in.B.BREADY) {
        awValidReg := false.B
        wValidReg := false.B
        downstreamAWDone := false.B
        downstreamWDone := false.B
        writeState := stateWriteIdle
      }
    }

    // PLIC 的 B 单独放在写状态机之外：写进 stateWriteResponse 的最后一个
    // elsewhen 会让 firtool 生成恒假 BREADY，PLIC 写永远等不到 B 握手。
    val plicWriteResponse =
      writeState === stateWriteResponse && writeTargetReg === targetPLIC
    when(plicWriteResponse) {
      io.in.B.BID := io.PLIC.B.BID
      io.in.B.BVALID := io.PLIC.B.BVALID
      io.in.B.BRESP := io.PLIC.B.BRESP
      when(io.PLIC.B.BVALID) {
        assert(
          io.PLIC.B.BID === awIDReg,
          "PLIC returned a write response with an unknown ID"
        )
      }
    }
    when(plicWriteResponse && io.PLIC.B.BVALID && io.in.B.BREADY) {
      awValidReg := false.B
      wValidReg := false.B
      downstreamAWDone := false.B
      downstreamWDone := false.B
      writeState := stateWriteIdle
    }
    // 防御：无效目标本应在进入转发前走 DECERR，不能停在 Response 等 B。
    when(inWriteResponse && writeTargetReg === targetInvalid) {
      writeState := stateWriteDECERR
    }
  }
}
