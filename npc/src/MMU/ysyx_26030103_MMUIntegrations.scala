package ysyx_26030103.mmu

import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common.ysyx_26030103_EXUMessage

/** IFU/LSU 双端口 MMU 仲裁器。
  *
  * 每个客户端最多保留一笔在途翻译；两笔可以同时进入 MMU 的独立上下文，
  * 响应按请求 ID 回到对应客户端，不再要求 IFU 的翻译先完成。
  */
class ysyx_26030103_MMURequestArbiter(
    XLEN: Int = 32,
    AddressWidth: Int = 32
) extends Module {
  val io = IO(new Bundle {
    val iReq = Flipped(Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth)))
    val iResp = Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
    val dReq = Flipped(Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth)))
    val dResp = Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
    val mmuReq = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val mmuResp = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
  })

  val iOutstanding = RegInit(false.B)
  val dOutstanding = RegInit(false.B)
  io.iReq.ready := false.B
  io.dReq.ready := false.B
  io.mmuReq.valid := false.B
  io.mmuReq.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  io.iResp.valid := false.B
  io.iResp.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
  io.dResp.valid := false.B
  io.dResp.bits := 0.U.asTypeOf(new ysyx_26030103_MMUResponse(XLEN, AddressWidth))
  io.mmuResp.ready := false.B

  // 选择一笔尚未在途的客户端请求送入 MMU。
  val chooseD = !dOutstanding && io.dReq.valid && (iOutstanding || !io.iReq.valid)
  val chooseI = !iOutstanding && io.iReq.valid && !chooseD
  when(chooseD) {
    io.dReq.ready := io.mmuReq.ready
    io.mmuReq.valid := io.dReq.valid
    io.mmuReq.bits := io.dReq.bits
    when(io.dReq.fire) { dOutstanding := true.B }
  }.elsewhen(chooseI) {
    io.iReq.ready := io.mmuReq.ready
    io.mmuReq.valid := io.iReq.valid
    io.mmuReq.bits := io.iReq.bits
    when(io.iReq.fire) { iOutstanding := true.B }
  }

  // MMU 响应携带客户端 ID：0=IFU，1=LSU。
  when(io.mmuResp.valid) {
    when(io.mmuResp.bits.id === 0.U) {
      io.iResp.valid := true.B
      io.iResp.bits := io.mmuResp.bits
      io.mmuResp.ready := io.iResp.ready
      when(io.iResp.fire) { iOutstanding := false.B }
    }.otherwise {
      io.dResp.valid := true.B
      io.dResp.bits := io.mmuResp.bits
      io.mmuResp.ready := io.dResp.ready
      when(io.dResp.fire) { dOutstanding := false.B }
    }
  }
}

/** 取指地址翻译。
  *
  * PIPT 模式：翻译期间 IFU 的 FetchValid 保持，fetchValid 由顶层按
  * translationValid 门控。
  * VIPT 并行模式（ParallelVipt=true）：顶层把 ICache 已受理但缺 PA 的请求
  * 接到 fetchValid/fetchVaddr，翻译结果附带请求 VA 作为标签；ICache 只有在
  * 标签匹配时才接收 PA。取指 MMU 不再跑在已受理请求之前。
  */
class ysyx_26030103_IFUMMU(
    ParallelVipt: Boolean = false,
    XLEN: Int = 32,
    AddressWidth: Int = 32
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"IFUMMU XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val fetchValid = Input(Bool())
    val fetchReady = Output(Bool())
    val fetchVaddr = Input(UInt(XLEN.W))
    val fetchPaddr = Output(UInt(AddressWidth.W))
    // 该 PA 对应的虚拟地址。VIPT 下 ICache 用它校验“迟到的 PA”属于
    // 当前已受理的请求，而不是上一笔/错路请求。
    val fetchPaddrVaddr = Output(UInt(XLEN.W))
    val translationValid = Output(Bool())
    val cacheReady = Input(Bool())
    val fault = Output(Bool())
    val faultCause = Output(UInt(4.W))
    val faultVaddr = Output(UInt(XLEN.W))
    val pmpFault = Input(Bool())
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(XLEN.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
  })
  val idle :: xwait :: done :: fault :: discard :: Nil = Enum(5)
  val state = RegInit(idle)
  val paddrReg = RegInit(0.U(AddressWidth.W))
  val paddrVaddrReg = RegInit(0.U(XLEN.W))
  val reqVaddrReg = RegInit(0.U(XLEN.W))
  val faultReg = RegInit(false.B)
  val faultCauseReg = RegInit(0.U(4.W))
  val faultVaddrReg = RegInit(0.U(XLEN.W))
  // RV64 satp.MODE 在 bit 63:60；RV32 在 bit 31。
  private def satpMode(satp: UInt): UInt =
    if (XLEN == 32) satp(31) else satp(63, 60)
  val bypass = satpMode(io.satp) === 0.U || io.privilege === 3.U

  io.req.valid := false.B
  io.req.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  // flush 后旧 walk 的响应仍可能返回；非等待状态也要接收并丢弃，避免
  // MMU 请求仲裁器卡在响应转发状态。
  io.resp.ready := true.B
  io.fetchPaddr := Mux(bypass, io.fetchVaddr, paddrReg)
  io.fetchPaddrVaddr := Mux(bypass, io.fetchVaddr, paddrVaddrReg)
  // 重定向后 IFU 的虚拟 PC 已改变；即使 flush 晚一拍，旧 paddr 也绝不能
  // 再被当成新 PC 的物理地址送给 ICache。
  // VIPT 下 PMP 也必须在 PA 有效后先通过，才能把 PA 交给 ICache。
  io.translationValid := bypass ||
    (state === done && paddrVaddrReg === io.fetchVaddr &&
      (!ParallelVipt.B || !io.pmpFault))
  io.fetchReady := io.translationValid && io.cacheReady
  io.fault := faultReg
  io.faultCause := faultCauseReg
  io.faultVaddr := faultVaddrReg

  when(io.flush) {
    state := idle
    faultReg := false.B
  }.otherwise {
    switch(state) {
      is(idle) {
        when(io.fetchValid && !bypass) {
          io.req.valid := true.B
          io.req.bits.id := 0.U // IFU
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
            faultCauseReg := io.resp.bits.cause(3, 0)
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
        when(ParallelVipt.B && io.fetchValid &&
            paddrVaddrReg =/= io.fetchVaddr) {
          // VIPT: 已完成的翻译属于旧请求，直接丢弃，下一拍重新为当前
          // ICache 请求发起翻译。
          state := idle
        }.elsewhen(io.pmpFault) {
          faultReg := true.B
          faultCauseReg := 1.U
          faultVaddrReg := paddrVaddrReg
          state := fault
        }.elsewhen(!ParallelVipt.B && io.fetchValid && io.cacheReady &&
             paddrVaddrReg === io.fetchVaddr) { state := idle }
        // VIPT 下只有 ICache 消费 PA 后 fetchValid 才会撤销；不能因为
        // cacheReady 提前回 idle，否则会对同一请求重复发起翻译。
        when(!io.fetchValid && !io.pmpFault) { state := idle }
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
  * PIPT（ParallelVipt=false）保持原行为：完整翻译完成后再把指令交给 LSU，
  * MMU 故障不会在 LSU/总线上留下任何副作用。
  *
  * VIPT 并行模式（ParallelVipt=true）下，load/store 都会先以 VA 交给 LSU
  * （DCache 用 VA 选 index），MMU 同时翻译；PA 带 VA 标签通过独立端口返回。
  * store 只登记到写缓冲，LSU 必须等 PA 标签匹配后才允许发 AW/W 或更新
  * DCache。翻译故障通过 memKill 取消 LSU 中已受理的请求，并由 faultCommit
  * 向 EXU 提交精确 page fault，故障 store 不产生任何写副作用。
  */
class ysyx_26030103_LSUMMU(
    val ParallelVipt: Boolean = false,
    val XLEN: Int = 32,
    val AddressWidth: Int = 32
) extends Module {
  require(XLEN == 32 || XLEN == 64, s"LSUMMU XLEN 非法: $XLEN")
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ysyx_26030103_EXUMessage(XLEN)))
    val out = Decoupled(new ysyx_26030103_EXUMessage(XLEN))
    val privilege = Input(UInt(2.W))
    val satp = Input(UInt(XLEN.W))
    val sum = Input(Bool())
    val mxr = Input(Bool())
    val flush = Input(Bool())
    val faultCommit = Output(Bool())
    val faultCause = Output(UInt(32.W))
    val faultTval = Output(UInt(XLEN.W))
    val faultPC = Output(UInt(XLEN.W))
    val busy = Output(Bool())
    val pmpAddr = Output(UInt(AddressWidth.W))
    val pmpWrite = Output(Bool())
    val pmpFault = Input(Bool())
    // VIPT 并行模式下，已先行交给 LSU 的 load 所使用的翻译结果。
    val memPaddr = Output(UInt(AddressWidth.W))
    val memPaddrValid = Output(Bool())
    val memPaddrVaddr = Output(UInt(XLEN.W))
    // MMU 翻译故障单拍脉冲：撤销 LSU/DCache 中尚未拿到 PA 的请求。
    val memKill = Output(Bool())
    // LSU 是否仍持有更老/当前指令。VIPT 并行模式用它保证翻译结果在
    // 被消费前不被下一笔请求覆盖。
    val memActive = Input(Bool())
    // LSU 已为更老/当前指令提交访存陷阱（misaligned/PMP/总线故障）。
    // VIPT 下该指令可能仍在 DMMU 翻译中；必须撤销翻译，避免迟到的
    // page fault 在陷阱处理程序中造成第二次异常。
    val memTrap = Input(Bool())
    val req = Decoupled(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
    val resp = Flipped(Decoupled(new ysyx_26030103_MMUResponse(XLEN, AddressWidth)))
  })
  // fwd 是 VIPT 并行专用：先把原始 VA 请求交给 LSU，再等待 MMU。
  val idle :: xreq :: xwait :: done :: fault :: fwd :: Nil = Enum(6)
  val state = RegInit(idle)
  val msgReg = RegInit(0.U.asTypeOf(new ysyx_26030103_EXUMessage(XLEN)))
  val msgBypass = RegInit(false.B)
  val paddrReg = RegInit(0.U(AddressWidth.W))
  val paddrVaddrReg = RegInit(0.U(XLEN.W))
  // VIPT 并行：PA 有效标志独立于状态机保持，直到 LSU 真正排空；避免
  // 流水冲刷使 state 提前离开 done 时 DCache 永远等不到 PA。
  val paddrValidReg = RegInit(false.B)
  val faultReg = RegInit(false.B)
  val faultCauseReg = RegInit(0.U(32.W))
  val faultTvalReg = RegInit(0.U(XLEN.W))
  val faultPCReg = RegInit(0.U(XLEN.W))
  private def satpMode(satp: UInt): UInt =
    if (XLEN == 32) satp(31) else satp(63, 60)
  val bypass = satpMode(io.satp) === 0.U || io.privilege === 3.U
  val isMem = io.in.bits.MemoryValid

  io.in.ready := false.B
  io.out.valid := false.B
  io.out.bits := io.in.bits
  io.req.valid := false.B
  io.req.bits := 0.U.asTypeOf(new ysyx_26030103_MMURequest(XLEN, AddressWidth))
  io.resp.ready := true.B
  io.faultCommit := faultReg
  io.faultCause := faultCauseReg
  io.faultTval := faultTvalReg
  io.faultPC := faultPCReg
  io.busy := state =/= idle
  io.pmpAddr := Mux(state === xwait, io.resp.bits.paddr, paddrReg)
  io.pmpWrite := msgReg.MemoryWrite
  io.memPaddr := paddrReg
  if (ParallelVipt) {
    io.memPaddrValid := paddrValidReg
  } else {
    io.memPaddrValid := state === done
  }
  io.memPaddrVaddr := paddrVaddrReg
  io.memKill := false.B

  if (!ParallelVipt) {
    // ---------------- 原有 PIPT 路径：翻译完成后才发 LSU ----------------
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
      io.req.bits.id := 1.U // LSU
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
          paddrVaddrReg := msgReg.ALUResult
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
  } else {
    // ---------------- VIPT 并行路径 ----------------
    // 只有 LSU 完全空闲时才受理新的内存指令，保证 paddrReg 在 DCache
    // 取用前不会被下一笔请求覆盖，同时让翻译故障的 kill 不会误伤更老指令。
    // 注意 in.ready 不能依赖 in.bits（否则 EXU 形成组合环），因此统一取
    // “LSU 空闲且下游可接收”；非访存指令在 LSU 忙时也会被暂缓。
    when(state === idle) {
      io.in.ready := io.out.ready && !io.memActive
      when(io.in.fire) {
        // 新请求受理时先撤销上一笔 PA，防止相同 VA 的旧 PA 被误用。
        paddrValidReg := false.B
      }
      when(!isMem) {
        // 非访存指令组合直通。
        io.out.valid := io.in.valid
        io.out.bits := io.in.bits
      }.elsewhen(io.in.fire) {
        // load/store 都先锁存原始 VA 并转发给 LSU：store 此时只进入写
        // 缓冲登记，不会发 AW/W；PA 由翻译结果带标签返回。
        msgReg := io.in.bits
        msgBypass := bypass
        state := fwd
        when(bypass) {
          // bypass 时 VA==PA，可以在 LSU 受理前就给出有效 PA。
          paddrReg := io.in.bits.ALUResult
          paddrVaddrReg := io.in.bits.VAddr
          paddrValidReg := true.B
        }
      }
    }
    when(state === fwd) {
      // 这里交给 LSU 的 ALUResult 仍是 VA；物理地址只通过 memPaddr 提供。
      // store 也只是在此登记到 LSU 写缓冲，PA 有效前不会发 AW/W。
      io.out.valid := true.B
      io.out.bits := msgReg
      when(io.out.fire) {
        when(msgBypass) { state := done }
          .otherwise { state := xreq }
      }
    }
    when(state === xreq) {
      io.req.valid := true.B
      io.req.bits.id := 1.U
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
          // load 已经进入 LSU。先撤销它和 DCache 的等待请求，
          // 下一拍 faultReg 作为精确 page fault 提交给 EXU/CSR。
          faultReg := true.B
          faultCauseReg := io.resp.bits.cause
          faultTvalReg := msgReg.ALUResult
          faultPCReg := msgReg.pc
          state := fault
        }.otherwise {
          paddrReg := io.resp.bits.paddr
          paddrVaddrReg := msgReg.ALUResult
          paddrValidReg := true.B
          state := done
        }
      }
    }
    when(state === xwait) {
      io.memKill := io.resp.valid && io.resp.bits.fault
    }
    when(state === done) {
      // load/store 都已交给 LSU；翻译结果保持到 LSU 完全排空，之后才允许
      // 下一笔请求覆盖 paddrReg。store 的 LSU 写缓冲会在 PA 标签匹配后
      // 自行完成总线提交，因此这里不再重复转发。
      when(!io.memActive) {
        paddrValidReg := false.B
        state := idle
      }
    }
    when(io.flush) {
      // xreq/xwait/done 表示请求已经交给更老的 LSU 流水级，来自更年轻指令
      // 的冲刷不能撤销它；否则 store/load 会永远等不到 PA。仍留在 fwd 的
      // 请求尚未进入 LSU，属于年轻指令，必须丢弃。
      when(state === idle || state === fwd || state === fault) {
        state := idle
        faultReg := false.B
        paddrValidReg := false.B
      }
    }
    // LSU 已为当前指令提交 misaligned/PMP/总线陷阱：该指令不会再消费
    // 翻译结果。撤销在途翻译；迟到的 MMU 响应会在 idle 被 resp.ready
    // 排空，仲裁器在该响应消费前不会为 DMMU 发起下一笔请求。
    when(io.memTrap) {
      state := idle
      faultReg := false.B
      paddrValidReg := false.B
    }
  }
}
