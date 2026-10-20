package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** 一个 MMU 请求端口的二选一仲裁器：IFU/IMMU 与 LSU/DMMU 串行共享。 */
class ysyx_26030103_MMURequestArbiter extends Module {
  val io = IO(new Bundle {
    val iReq = Flipped(Decoupled(new ysyx_26030103_MMURequest))
    val iResp = Decoupled(new ysyx_26030103_MMUResponse)
    val dReq = Flipped(Decoupled(new ysyx_26030103_MMURequest))
    val dResp = Decoupled(new ysyx_26030103_MMUResponse)
    val mmuReq = Decoupled(new ysyx_26030103_MMURequest)
    val mmuResp = Flipped(Decoupled(new ysyx_26030103_MMUResponse))
  })
  val idle :: iWait :: dWait :: iResp :: dResp :: Nil = Enum(5)
  val state = RegInit(idle)
  val last = RegInit(false.B) // false=IFU 优先

  io.iReq.ready := false.B
  io.dReq.ready := false.B
  io.mmuReq.valid := false.B
  io.mmuReq.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest)
  io.iResp.valid := false.B
  io.iResp.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse)
  io.dResp.valid := false.B
  io.dResp.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse)
  io.mmuResp.ready := false.B

  when(state === idle) {
    val chooseD = !last && io.dReq.valid && !io.iReq.valid ||
      last && io.dReq.valid
    val chooseI = !chooseD && io.iReq.valid
    when(chooseD) {
      io.dReq.ready := io.mmuReq.ready
      io.mmuReq.valid := io.dReq.valid
      io.mmuReq.bits := io.dReq.bits
      when(io.dReq.fire) { state := dWait; last := false.B }
    }.elsewhen(chooseI) {
      io.iReq.ready := io.mmuReq.ready
      io.mmuReq.valid := io.iReq.valid
      io.mmuReq.bits := io.iReq.bits
      when(io.iReq.fire) { state := iWait; last := true.B }
    }
  }
  when(state === iWait && io.mmuResp.valid) {
    state := iResp
  }
  when(state === dWait && io.mmuResp.valid) {
    state := dResp
  }
  when(state === iResp) {
    io.iResp.valid := io.mmuResp.valid
    io.iResp.bits := io.mmuResp.bits
    io.mmuResp.ready := io.iResp.ready
    when(io.iResp.fire) { state := idle }
  }
  when(state === dResp) {
    io.dResp.valid := io.mmuResp.valid
    io.dResp.bits := io.mmuResp.bits
    io.mmuResp.ready := io.dResp.ready
    when(io.dResp.fire) { state := idle }
  }
}

/** 取指地址翻译。翻译期间 IFU 的 FetchValid 保持，fetchReady 反压。 */
class ysyx_26030103_IFUMMU extends Module {
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchReady = Output(Bool())
    val fetchVaddr = Input(UInt(32.W))
    val fetchPaddr = Output(UInt(32.W))
    val translationValid = Output(Bool())
    val cacheReady = Input(Bool())
    val fault = Output(Bool())
    val faultVaddr = Output(UInt(32.W))
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(32.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest)
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse))
  })
  val idle :: xwait :: done :: fault :: discard :: Nil = Enum(5)
  val state = RegInit(idle)
  val paddrReg = RegInit(0.U(32.W))
  val paddrVaddrReg = RegInit(0.U(32.W))
  val reqVaddrReg = RegInit(0.U(32.W))
  val faultReg = RegInit(false.B)
  val faultVaddrReg = RegInit(0.U(32.W))
  val bypass = io.satp(31) === 0.U || io.privilege === 3.U

  io.req.valid := false.B
  io.req.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest)
  io.resp.ready := false.B
  io.fetchPaddr := Mux(bypass, io.fetchVaddr, paddrReg)
  // 重定向后 IFU 的虚拟 PC 已改变；即使 flush 晚一拍，旧 paddr 也绝不能
  // 再被当成新 PC 的物理地址送给 ICache。
  io.translationValid := bypass ||
    (state === done && paddrVaddrReg === io.fetchVaddr)
  io.fetchReady := io.translationValid && io.cacheReady
  io.fault := faultReg
  io.faultVaddr := faultVaddrReg

  when(io.flush) {
    state := idle
    faultReg := false.B
  }.otherwise {
    switch(state) {
      is(idle) {
        when(io.fetchValid && !bypass) {
          io.req.valid := true.B
          io.req.bits.vaddr := io.fetchVaddr
          io.req.bits.access := ysyx_26030103_AccessType.Fetch
          io.req.bits.privilege := io.privilege
          io.req.bits.sum := io.sum
          io.req.bits.mxr := io.mxr
          io.req.bits.satp := io.satp
          when(io.req.fire) {
            reqVaddrReg := io.fetchVaddr
            state := xwait
          }
        }
      }
      is(xwait) {
        io.resp.ready := true.B
        when(io.resp.valid) {
          when(io.resp.bits.fault) {
            faultReg := true.B
            faultVaddrReg := reqVaddrReg
            state := fault
          }.otherwise {
            paddrReg := io.resp.bits.paddr
            paddrVaddrReg := reqVaddrReg
            state := done
          }
        }
      }
      is(done) {
        when(io.fetchValid && io.cacheReady &&
             paddrVaddrReg === io.fetchVaddr) { state := idle }
        when(!io.fetchValid) { state := idle }
      }
      is(fault) {
        // 保持故障直到 EXU 的 trap 冲刷；faultReg 是单拍事件，避免重复注入。
        faultReg := false.B
      }
      is(discard) {
        io.resp.ready := true.B
        when(io.resp.valid) { state := idle }
      }
    }
    when(state === xwait && io.fetchValid === false.B) {
      state := discard
    }
  }
}

/** 把 EXU 的虚拟访存地址翻译成物理地址，再交给 LSU。
  *
  * 非访存指令直通；MMU 故障不进入 LSU，而是产生一个精确的 MemTrap 脉冲，
  * 由顶层交给 EXU/CSR 的后门异常入口。这样 store 不会在页错误时产生总线副作用。
  */
class ysyx_26030103_LSUMMU extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage))
    val out = Decoupled(new ysyx_26030103_EXUMessage)
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(32.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val faultCommit = Output(Bool())
    val faultCause = Output(UInt(32.W))
    val faultTval = Output(UInt(32.W))
    val faultPC = Output(UInt(32.W))
    val busy = Output(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest)
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse))
  })
  val idle :: xreq :: xwait :: done :: fault :: Nil = Enum(5)
  val state = RegInit(idle)
  val msgReg = RegInit(0.U.asTypeOf(new ysyx_26030103_EXUMessage))
  val paddrReg = RegInit(0.U(32.W))
  val faultReg = RegInit(false.B)
  val faultCauseReg = RegInit(0.U(32.W))
  val faultTvalReg = RegInit(0.U(32.W))
  val faultPCReg = RegInit(0.U(32.W))
  val bypass = io.satp(31) === 0.U || io.privilege === 3.U
  val isMem = io.in.bits.MemoryValid

  io.in.ready := false.B
  io.out.valid := false.B
  io.out.bits := io.in.bits
  io.req.valid := false.B
  io.req.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest)
  io.resp.ready := false.B
  io.faultCommit := faultReg
  io.faultCause := faultCauseReg
  io.faultTval := faultTvalReg
  io.faultPC := faultPCReg
  io.busy := state =/= idle

  when(state === idle) {
    // 始终使用同一个握手条件，避免 in.ready 依赖 in.bits 形成组合环。
    io.in.ready := io.out.ready
    when(!isMem || bypass) {
      io.out.valid := io.in.valid
      io.out.bits := io.in.bits
    }.otherwise {
      when(io.in.fire) {
        msgReg := io.in.bits
        state := xreq
      }
    }
  }
  when(state === xreq) {
    io.req.valid := true.B
    io.req.bits.vaddr := msgReg.ALUResult
    io.req.bits.access := Mux(msgReg.MemoryWrite,
      ysyx_26030103_AccessType.Store, ysyx_26030103_AccessType.Load)
    io.req.bits.privilege := io.privilege
    io.req.bits.sum := io.sum
    io.req.bits.mxr := io.mxr
    io.req.bits.satp := io.satp
    when(io.req.fire) { state := xwait }
  }
  when(state === xwait) {
    io.resp.ready := true.B
    when(io.resp.valid) {
      when(io.resp.bits.fault) {
        faultReg := true.B
        faultCauseReg := io.resp.bits.cause
        faultTvalReg := msgReg.ALUResult
        faultPCReg := msgReg.pc
        state := fault
      }.otherwise {
        paddrReg := io.resp.bits.paddr
        state := done
      }
    }
  }
  when(state === done) {
    io.in.ready := io.out.ready
    io.out.valid := true.B
    io.out.bits := msgReg
    io.out.bits.ALUResult := paddrReg
    when(io.out.fire) { state := idle }
  }
  when(io.flush) {
    state := idle
    faultReg := false.B
  }
}
