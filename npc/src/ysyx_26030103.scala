package ysyx_26030103
import chisel3._
import chisel3.util._

//他妈的，我们伟大的scala插件和编译器设计专家应该要以死谢罪，是哪个天才想到的，如果直接写ysyx_26030103，因为我这个顶层模块类和包同名了
//能被解读成ysyx_26030103的ysyx_26030103的AXI模块，还得手动指定从最顶层的根目录去找
import _root_.ysyx_26030103.common._
import _root_.ysyx_26030103.config._
import _root_.ysyx_26030103.cache._
import _root_.ysyx_26030103.infra._
import _root_.ysyx_26030103.ifu._
import _root_.ysyx_26030103.idu._
import _root_.ysyx_26030103.exu._
import _root_.ysyx_26030103.mem._
import _root_.ysyx_26030103.wbu._

class ysyx_26030103(val config: ysyx_26030103_NPCConfig = ysyx_26030103_NPCConfig())
    extends Module {
  val io = IO(new ysyx_26030103_IO)
  val ifu = Module(new ysyx_26030103_IFU(config.ResetAddr))
  val idu = Module(new ysyx_26030103_IDU(config))
  val exu = Module(new ysyx_26030103_EXU(config))
  val wbu = Module(new ysyx_26030103_WBU)
  val lsu = Module(
    new ysyx_26030103_LSU(
      WBufDepth = config.WBufDepth,
      DCacheEnable = config.DCacheEnable,
      DCacheStorage = config.DCacheStorage,
      DCacheBlockSizeLog2 = config.BlockSizeLog2,
      DCacheIndexBits = config.IndexBits,
      DCacheableBase = config.CacheableBase,
      DCacheableMask = config.CacheableMask,
      DCachePMARegions = config.PMARegions
    )
  )
  val gpr = Module(new ysyx_26030103_GPR(config.EnableDynamicGPRDebug))
  val icache = ysyx_26030103_ICacheFactory.Create(config)
  val arbiter = Module(new ysyx_26030103_AXI5Arbiter)
  val xbar = Module(
    new ysyx_26030103_AXI5Xbar(config.AddressWidth, config.PMARegions)
  )
  val clint = Module(new ysyx_26030103_AXI5CLINTSlave)
  // ysyxSoC的ChipLink内存窗口。只有当当前PMA明确覆盖完整
  // 0xc0000000--0xffffffff可读写区域时，才允许CPU slave端口代理
  // 入站DMA请求。其他配置继续用DECERR安全终止，不会意外暴露
  // CPU本地CLINT或SoC其他地址。
  private val ChipLinkMemoryBase = BigInt("c0000000", 16)
  private val ChipLinkMemoryEnd = BigInt(1) << 32
  private val DMAProxyEnable = config.PMARegions.exists { Region =>
    Region.Readable && Region.Writable &&
    BigInt(Region.Base) <= ChipLinkMemoryBase &&
    BigInt(Region.EndExclusive) >= ChipLinkMemoryEnd
  }
  val dmaErrorSlave =
    if (DMAProxyEnable) None
    else Some(Module(new ysyx_26030103_AXI5DMAErrorSlave))
  val pipe_flush = exu.io.FlushIF
  // 分支目标缓冲(BTB): IFU取指级查询决定下一PC,取指被icache接受时快照预测标签随请求保存,
  // EXU提交时更新分支的真实target; 另设独立jal BTB(方案B: 与分支表零干扰, 表项带kind区分Jal/Call/Ret)
  // + 返回地址栈RAS(ret的预测目标=栈顶, EXU提交call/ret时压/弹, 非投机无需修复)
  val btb = Module(new ysyx_26030103_BTB(config.BTBBits, config.BTBWays))
  val jal_btb =
    Module(new ysyx_26030103_BTB(config.JalBTBBits, config.JalBTBWays, 32, 2))
  val ras = Module(new ysyx_26030103_RAS(config.RASBits))
  val predictorSelect = Module(new ysyx_26030103_PredictorSelect)
  // icache响应(携带取指地址和错误标志)经冲刷流水寄存器直接进IDU
  val ifuResp = Wire(Decoupled(new ysyx_26030103_IFUMessage))
  ifuResp.valid := icache.io.resp_valid
  ifuResp.bits.Instruction := icache.io.resp_data
  ifuResp.bits.pc := icache.io.resp_addr
  ifuResp.bits.ExceptionValid := icache.io.resp_fault
  ifuResp.bits.ExceptionCause := 1.U(4.W)
  ifuResp.bits.AccessFaultResp := icache.io.access_fault_resp
  // 预测标签快照: 取指请求被icache接受时,把取指时刻的预测随请求保存,响应时贴给指令.
  // 根治"取指/响应两阶段查找之间BTB/RAS状态变化,导致标签与实际取指路径不一致"的致命漏洞
  // (反例: ret取指时RAS顶是A(错),响应时弹栈后变B(对),标签=B与实际执行一致=>不冲刷,
  //  错路指令漏网提交,ra被污染. icache最多1个outstanding,单表项快照即够;
  //  背靠背=响应与接受同拍,响应读的是旧值,时序正确)
  val accept_fetch = ifu.io.FetchValid && icache.io.fetch_ready
  val pred_tag_taken = RegEnable(ifu.io.PredHit, false.B, accept_fetch)
  val pred_tag_target = RegEnable(ifu.io.PredTarget, 0.U(32.W), accept_fetch)
  ifuResp.bits.pred_taken := pred_tag_taken
  ifuResp.bits.pred_target := pred_tag_target
  // 响应级不再重新查表, 预测标签在取指接受时快照保存
  icache.io.resp_ready := ifuResp.ready
  ysyx_26030103_StageConnect(ifuResp, idu.io.in, pipe_flush)
  icache.io.kill := pipe_flush
  // EXU 重定向或异常必须杀死 IDU->EXU 边界中排队的年轻指令，同时让当前 EXU
  // 指令在本周期保持可见，以便它提交并产生重定向。
  ysyx_26030103_StageConnect(
    idu.io.out,
    exu.io.in,
    lsu.io.FlushIDEX || pipe_flush,
    // 当前 EXU 项产生重定向或陷阱时必须允许该项握手；更老的 MEM 故障则必须
    // 杀死它，即使 EXU 正在承受反压也一样。
    flushCurrent = lsu.io.FlushIDEX
  )
  // 此边界保存的是比 LSU 当前指令更年轻的指令，只有更老的 MEM 故障可以丢弃
  // 它；不要在这里接入 pipe_flush。
  ysyx_26030103_StageConnect(exu.io.out, lsu.io.in, lsu.io.FlushEXMEM)
  ysyx_26030103_StageConnect(lsu.io.out, wbu.io.in)
  icache.io.axi <> arbiter.io.ifu
  icache.io.fetch_addr := ifu.io.FetchAddr
  icache.io.fetch_valid := ifu.io.FetchValid
  ifu.io.FetchReady := icache.io.fetch_ready
  // BTB查询1: 用当前取指地址同时查两张表,合并结果回送IFU决定下一PC
  // 传入PC[31:2]; 低位恒为0, 无需参与索引和tag匹配
  btb.io.lookup_pc := ifu.io.FetchAddr(31, 2)
  jal_btb.io.lookup_pc := ifu.io.FetchAddr(31, 2)
  // Ret只有在RAS非空时才有资格取得优先级；Ret表项的target保存静态
  // JALR immediate，由选择器与动态栈顶相加。分支仍采用BTFN方向预测。
  predictorSelect.io.fetchPC := ifu.io.FetchAddr
  predictorSelect.io.branchHit := btb.io.hit
  predictorSelect.io.branchTarget := btb.io.target
  predictorSelect.io.jalHit := jal_btb.io.hit
  predictorSelect.io.jalTarget := jal_btb.io.target
  predictorSelect.io.jalKind := jal_btb.io.hit_kind.get
  predictorSelect.io.rasNonempty := ras.io.nonempty
  predictorSelect.io.rasTop := ras.io.top
  ifu.io.PredHit := predictorSelect.io.predHit
  ifu.io.PredTarget := predictorSelect.io.predTarget
  // BTB更新: EXU提交时按指令类型路由, 分支写分支表, jal/ret写jal表(带kind)
  btb.io.update_valid := exu.io.BTBUpdateValid
  btb.io.update_pc := exu.io.BTBUpdatePC(31, 2)
  btb.io.update_target := exu.io.BTBUpdateTarget
  btb.io.flush := exu.io.FenceIFlush
  jal_btb.io.update_valid := exu.io.JalBTBUpdateValid
  jal_btb.io.update_pc := exu.io.JalBTBUpdatePC(31, 2)
  jal_btb.io.update_target := exu.io.JalBTBUpdateTarget
  jal_btb.io.update_kind.get := exu.io.JalBTBUpdateKind
  jal_btb.io.flush := exu.io.FenceIFlush
  // RAS更新: call压栈, ret弹栈
  ras.io.push_valid := exu.io.RASPushValid
  ras.io.push_addr := exu.io.RASPushAddr
  ras.io.pop_valid := exu.io.RASPopValid
  ras.io.flush := exu.io.FenceIFlush
  arbiter.io.lsu <> lsu.io.DataBus
  arbiter.io.memory.AW <> xbar.io.in.AW
  arbiter.io.memory.W <> xbar.io.in.W
  arbiter.io.memory.B <> xbar.io.in.B
  arbiter.io.memory.AR <> xbar.io.in.AR
  arbiter.io.memory.R <> xbar.io.in.R
  val soc = xbar.io.SoCBus
  io.master_awvalid := soc.AW.AWVALID
  io.master_awaddr := soc.AW.AWADDR
  io.master_awid := soc.AW.AWID
  io.master_awlen := soc.AW.AWLEN
  io.master_awsize := soc.AW.AWSIZE
  io.master_awburst := soc.AW.AWBURST
  io.master_awlock := 0.U
  io.master_awcache := soc.AW.AWCACHE
  io.master_awprot := soc.AW.AWPROT
  io.master_awqos := soc.AW.AWQOS
  soc.AW.AWREADY := io.master_awready

  io.master_wvalid := soc.W.WVALID
  io.master_wdata := soc.W.WDATA
  io.master_wstrb := soc.W.WSTRB
  io.master_wlast := soc.W.WLAST
  soc.W.WREADY := io.master_wready

  io.master_bready := soc.B.BREADY
  soc.B.BID := io.master_bid
  soc.B.BVALID := io.master_bvalid
  soc.B.BRESP := io.master_bresp

  io.master_arvalid := soc.AR.ARVALID
  io.master_araddr := soc.AR.ARADDR
  io.master_arid := soc.AR.ARID
  io.master_arlen := soc.AR.ARLEN
  io.master_arsize := soc.AR.ARSIZE
  io.master_arburst := soc.AR.ARBURST
  io.master_arlock := 0.U
  io.master_arcache := soc.AR.ARCACHE
  io.master_arprot := soc.AR.ARPROT
  io.master_arqos := soc.AR.ARQOS
  soc.AR.ARREADY := io.master_arready

  io.master_rready := soc.R.RREADY
  soc.R.RID := io.master_rid
  soc.R.RVALID := io.master_rvalid
  soc.R.RRESP := io.master_rresp
  soc.R.RDATA := io.master_rdata
  soc.R.RLAST := io.master_rlast

  // ChipLink对端的DMA请求从CPU slave入站。该窗口的实体存储位于
  // FPGA侧：请求经本仲裁器和CPU master送回ChipLink后，在对端
  // master_mem/SimAXIMem终止。这是受控的hairpin proxy，不是无限环回。
  if (DMAProxyEnable) {
    arbiter.io.dmaAWLock := io.slave_awlock
    arbiter.io.dmaARLock := io.slave_arlock
    arbiter.io.dma.AW.AWVALID := io.slave_awvalid
    arbiter.io.dma.AW.AWADDR := io.slave_awaddr
    arbiter.io.dma.AW.AWID := io.slave_awid
    arbiter.io.dma.AW.AWLEN := io.slave_awlen
    arbiter.io.dma.AW.AWSIZE := io.slave_awsize
    arbiter.io.dma.AW.AWBURST := io.slave_awburst
    arbiter.io.dma.AW.AWPROT := io.slave_awprot
    arbiter.io.dma.AW.AWCACHE := io.slave_awcache
    arbiter.io.dma.AW.AWQOS := io.slave_awqos
    io.slave_awready := arbiter.io.dma.AW.AWREADY

    arbiter.io.dma.W.WVALID := io.slave_wvalid
    arbiter.io.dma.W.WDATA := io.slave_wdata
    arbiter.io.dma.W.WSTRB := io.slave_wstrb
    arbiter.io.dma.W.WLAST := io.slave_wlast
    io.slave_wready := arbiter.io.dma.W.WREADY

    arbiter.io.dma.B.BREADY := io.slave_bready
    io.slave_bvalid := arbiter.io.dma.B.BVALID
    io.slave_bresp := arbiter.io.dma.B.BRESP
    io.slave_bid := arbiter.io.dma.B.BID

    arbiter.io.dma.AR.ARVALID := io.slave_arvalid
    arbiter.io.dma.AR.ARADDR := io.slave_araddr
    arbiter.io.dma.AR.ARID := io.slave_arid
    arbiter.io.dma.AR.ARLEN := io.slave_arlen
    arbiter.io.dma.AR.ARSIZE := io.slave_arsize
    arbiter.io.dma.AR.ARBURST := io.slave_arburst
    arbiter.io.dma.AR.ARPROT := io.slave_arprot
    arbiter.io.dma.AR.ARCACHE := io.slave_arcache
    arbiter.io.dma.AR.ARQOS := io.slave_arqos
    io.slave_arready := arbiter.io.dma.AR.ARREADY

    arbiter.io.dma.R.RREADY := io.slave_rready
    io.slave_rvalid := arbiter.io.dma.R.RVALID
    io.slave_rresp := arbiter.io.dma.R.RRESP
    io.slave_rdata := arbiter.io.dma.R.RDATA
    io.slave_rlast := arbiter.io.dma.R.RLAST
    io.slave_rid := arbiter.io.dma.R.RID
  } else {
    val ErrorSlave = dmaErrorSlave.get
    ErrorSlave.io.AW.AWVALID := io.slave_awvalid
    ErrorSlave.io.AW.AWADDR := io.slave_awaddr
    ErrorSlave.io.AW.AWID := io.slave_awid
    ErrorSlave.io.AW.AWLEN := io.slave_awlen
    ErrorSlave.io.AW.AWSIZE := io.slave_awsize
    ErrorSlave.io.AW.AWBURST := io.slave_awburst
    ErrorSlave.io.AW.AWPROT := io.slave_awprot
    ErrorSlave.io.AW.AWCACHE := io.slave_awcache
    ErrorSlave.io.AW.AWQOS := io.slave_awqos
    io.slave_awready := ErrorSlave.io.AW.AWREADY

    ErrorSlave.io.W.WVALID := io.slave_wvalid
    ErrorSlave.io.W.WDATA := io.slave_wdata
    ErrorSlave.io.W.WSTRB := io.slave_wstrb
    ErrorSlave.io.W.WLAST := io.slave_wlast
    io.slave_wready := ErrorSlave.io.W.WREADY

    ErrorSlave.io.B.BREADY := io.slave_bready
    io.slave_bvalid := ErrorSlave.io.B.BVALID
    io.slave_bresp := ErrorSlave.io.B.BRESP
    io.slave_bid := ErrorSlave.io.B.BID

    ErrorSlave.io.AR.ARVALID := io.slave_arvalid
    ErrorSlave.io.AR.ARADDR := io.slave_araddr
    ErrorSlave.io.AR.ARID := io.slave_arid
    ErrorSlave.io.AR.ARLEN := io.slave_arlen
    ErrorSlave.io.AR.ARSIZE := io.slave_arsize
    ErrorSlave.io.AR.ARBURST := io.slave_arburst
    ErrorSlave.io.AR.ARPROT := io.slave_arprot
    ErrorSlave.io.AR.ARCACHE := io.slave_arcache
    ErrorSlave.io.AR.ARQOS := io.slave_arqos
    io.slave_arready := ErrorSlave.io.AR.ARREADY

    ErrorSlave.io.R.RREADY := io.slave_rready
    io.slave_rvalid := ErrorSlave.io.R.RVALID
    io.slave_rresp := ErrorSlave.io.R.RRESP
    io.slave_rdata := ErrorSlave.io.R.RDATA
    io.slave_rlast := ErrorSlave.io.R.RLAST
    io.slave_rid := ErrorSlave.io.R.RID

    // 未开启ChipLink时仲裁器的DMA输入不可悬空。
    arbiter.io.dmaAWLock := false.B
    arbiter.io.dmaARLock := false.B
    arbiter.io.dma.AW.AWVALID := false.B
    arbiter.io.dma.AW.AWADDR := 0.U
    arbiter.io.dma.AW.AWID := 0.U
    arbiter.io.dma.AW.AWLEN := 0.U
    arbiter.io.dma.AW.AWSIZE := 0.U
    arbiter.io.dma.AW.AWBURST := 0.U
    arbiter.io.dma.AW.AWPROT := 0.U
    arbiter.io.dma.AW.AWCACHE := 0.U
    arbiter.io.dma.AW.AWQOS := 0.U
    arbiter.io.dma.W.WVALID := false.B
    arbiter.io.dma.W.WDATA := 0.U
    arbiter.io.dma.W.WSTRB := 0.U
    arbiter.io.dma.W.WLAST := false.B
    arbiter.io.dma.B.BREADY := false.B
    arbiter.io.dma.AR.ARVALID := false.B
    arbiter.io.dma.AR.ARADDR := 0.U
    arbiter.io.dma.AR.ARID := 0.U
    arbiter.io.dma.AR.ARLEN := 0.U
    arbiter.io.dma.AR.ARSIZE := 0.U
    arbiter.io.dma.AR.ARBURST := 0.U
    arbiter.io.dma.AR.ARPROT := 0.U
    arbiter.io.dma.AR.ARCACHE := 0.U
    arbiter.io.dma.AR.ARQOS := 0.U
    arbiter.io.dma.R.RREADY := false.B
  }
  xbar.io.CLINT.AW <> clint.io.AW
  xbar.io.CLINT.W <> clint.io.W
  xbar.io.CLINT.B <> clint.io.B
  xbar.io.CLINT.AR <> clint.io.AR
  xbar.io.CLINT.R <> clint.io.R
  idu.io.ex_valid := exu.io.HazardValid
  idu.io.ex_rd := exu.io.HazardRd
  idu.io.ex_regWrite := exu.io.HazardRegWrite
  idu.io.ex_fwd_ready := exu.io.FwdReady
  idu.io.ex_fwd_data := exu.io.FwdData
  idu.io.ex_mdu_hidden_writes := exu.io.HazardMDUHiddenWrites
  idu.io.wb_fwd_data := wbu.io.wdata
  idu.io.wb_valid := wbu.io.in.valid
  idu.io.wb_rd := wbu.io.in.bits.Rd
  idu.io.wb_regWrite := wbu.io.in.bits.RegisterWrite
  idu.io.pipeline_mode := true.B
  // 手动连线了
  idu.io.ReadDATA1 := gpr.io.ReadDATA1
  idu.io.ReadDATA2 := gpr.io.ReadDATA2
  gpr.io.Read1SELECT := idu.io.Read1SELECT
  gpr.io.Read2SELECT := idu.io.Read2SELECT
  // 年长访存事务与年长普通指令必须分开。控制转移只需等
  // 可能故障的访存，不应被LSU中单拍直通的普通ALU指令拖慢；
  // IRQ/CSR/异常则仍使用全流水级Busy保证精确顺序。
  exu.io.MEMBusy := lsu.io.MemoryBusy
  exu.io.PipelineBusy := lsu.io.Busy
  exu.io.MemTrapCommit := lsu.io.MemTrapCommit
  exu.io.MemTrapCause := lsu.io.MemTrapCause
  exu.io.MemTrapPC := lsu.io.MemTrapPC
  // LSU(MEM级)给IDU做冒险检测和转发
  idu.io.me_valid := lsu.io.HazardValid
  idu.io.me_rd := lsu.io.HazardRd
  idu.io.me_regWrite := lsu.io.HazardRegWrite
  idu.io.me_memop := lsu.io.HazardMemOp
  idu.io.me_fwd_ready := lsu.io.FwdReady
  idu.io.me_fwd_data := lsu.io.FwdData
  // LSU等待槽(EX/MEM流水寄存器)也给IDU做冒险检测和转发
  idu.io.me2_valid := lsu.io.Hazard2Valid
  idu.io.me2_rd := lsu.io.Hazard2Rd
  idu.io.me2_regWrite := lsu.io.Hazard2RegWrite
  idu.io.me2_memop := lsu.io.Hazard2MemOp
  idu.io.me2_fwd_ready := lsu.io.Hazard2FwdReady
  idu.io.me2_fwd_data := lsu.io.Hazard2FwdData
  ifu.io.Redirect := exu.io.Redirect
  ifu.io.RedirectTarget := exu.io.RedirectTarget
  ifu.io.ExceptionTaken := exu.io.ExceptionTaken
  ifu.io.ExceptionTarget := exu.io.ExceptionTarget
  icache.io.flush := exu.io.FenceIFlush // 仅 FenceI 冲 ICache，分支不冲
  // 当前 DCache 是写直达/不写分配，成功 store 会更新命中行，并且
  // Fence.I 在提交前已经等待更老的 store 排空。因此指令一致性只需
  // 失效 ICache/取指流水；清空 DCache 只会制造无意义的数据 miss。
  // DMA写是CPU缓存之外的副作用。写直达DCache没有脏数据，
  // 因此在匹配B响应出现时全局失效即可保证后续load不命中旧行。
  // 每笔DMA写在匹配BVALID首次出现时产生单拍失效；后续B反压不会持续
  // 阻止CPU load。错误B也同样失效，以覆盖从设备可能的部分写副作用。
  // 不在这里异步冲ICache：现有ICache flush与流水线重定向成对，
  // DMA修改可执行内存后由软件执行fence.i完成指令一致性。
  lsu.io.DCacheFlush := arbiter.io.DMAWriteInvalidate
  gpr.io.WriteSELECT := wbu.io.WriteSELECT
  gpr.io.WriteEN := wbu.io.WriteEN
  gpr.io.wdata := wbu.io.wdata
  // 临时新加的处理中断的
  exu.io.Interrupt := io.interrupt
  // 仿真 halt 是 custom-0 指令的提交事件，而不是架构 EBREAK。再寄存
  // 一拍后暴露给 C++，保证上升沿上的所有更老 GPR 写回已经生效。
  val SimHaltCommit = exu.io.SimHaltValid
  io.trap_valid := RegNext(SimHaltCommit, false.B)
  io.trap_pc := RegEnable(exu.io.SimHaltPC, config.ResetAddr.U(32.W), SimHaltCommit)
  // 固定 ABI：关闭动态调试读口时，GPR 模块忽略地址并将这里直连到 a0，
  // 供 custom halt 判定 GOOD/BAD；不会为此保留第三个 32 路读 mux。
  gpr.io.DebugRaddr := io.debug_gpr_raddr
  io.debug_gpr_rdata := gpr.io.DebugRdata
  // WBUCommit 与最小架构 PC 不属于可选观测逻辑。异常/中断即使在所有
  // 调试功能关闭时也需要精确的最近架构边界。
  val WBUCommit = wbu.io.in.fire && wbu.io.in.bits.Retire
  if (config.EnableDynamicGPRDebug) {
    // DiffTest 在上升沿前采样实际物理写口，沿后只回读被写寄存器。
    io.debug_gpr_wen := wbu.io.WriteEN && (wbu.io.WriteSELECT =/= 0.U)
    io.debug_gpr_waddr := wbu.io.WriteSELECT
  } else {
    io.debug_gpr_wen := false.B
    io.debug_gpr_waddr := 0.U
  }
  if (config.EnableRetireTrace) {
    // C++ 在上升沿后看到 commit，此时 GPR 已是该指令提交后的状态。
    io.debug_commit := RegNext(WBUCommit, false.B)
    io.debug_pc := RegEnable(
      wbu.io.in.bits.pc,
      config.ResetAddr.U(32.W),
      WBUCommit
    )
    io.debug_next_pc := RegEnable(
      wbu.io.in.bits.NextPC,
      config.ResetAddr.U(32.W),
      WBUCommit
    )
    io.debug_instructions := RegEnable(
      wbu.io.in.bits.Instruction,
      "h00000013".U(32.W),
      WBUCommit
    )
  } else {
    io.debug_commit := false.B
    io.debug_pc := 0.U
    io.debug_next_pc := 0.U
    io.debug_instructions := 0.U
  }

  // 调试器的 $pc 不能复用 retire trace PC。正常退休时前进到
  // 该指令的实际后继；异常/中断提交时直接记录 trap 目标。
  // 若同拍既有更老的 WBU 退休又有年轻 trap，trap 必须优先。
  val ArchitecturalPC = RegInit(config.ResetAddr.U(32.W))
  // 空流水中断没有随指令携带的 PC；同拍若有更老退休，则以该指令后继
  // 为边界，否则使用最近架构 PC。此路径是架构状态，不能随调试功能裁剪。
  exu.io.IdleIrqPC := Mux(WBUCommit, wbu.io.in.bits.NextPC, ArchitecturalPC)
  when(WBUCommit) {
    ArchitecturalPC := wbu.io.in.bits.NextPC
  }
  when(exu.io.TrapCommit) {
    ArchitecturalPC := exu.io.TrapTarget
  }
  io.debug_arch_pc := ArchitecturalPC

  if (config.EnableTrapTrace) {
    io.debug_trap_valid := RegNext(exu.io.TrapCommit, false.B)
    io.debug_trap_pc := RegEnable(
      exu.io.TrapPC,
      config.ResetAddr.U(32.W),
      exu.io.TrapCommit
    )
    io.debug_trap_target := RegEnable(
      exu.io.TrapTarget,
      config.ResetAddr.U(32.W),
      exu.io.TrapCommit
    )
    io.debug_trap_cause := RegEnable(
      exu.io.TrapCause,
      0.U(32.W),
      exu.io.TrapCommit
    )
  } else {
    io.debug_trap_valid := false.B
    io.debug_trap_pc := 0.U
    io.debug_trap_target := 0.U
    io.debug_trap_cause := 0.U
  }
  if (config.EnableCSRTrace) {
    io.debug_csr_mstatus := RegEnable(
      wbu.io.in.bits.CSRStateMstatus,
      ysyx_26030103_mstatus.resetValue,
      WBUCommit
    )
    io.debug_csr_mtvec := RegEnable(
      wbu.io.in.bits.CSRStateMtvec,
      0.U(32.W),
      WBUCommit
    )
    io.debug_csr_mepc := RegEnable(
      wbu.io.in.bits.CSRStateMepc,
      0.U(32.W),
      WBUCommit
    )
    io.debug_csr_mcause := RegEnable(
      wbu.io.in.bits.CSRStateMcause,
      0.U(32.W),
      WBUCommit
    )
    io.debug_trap_mstatus := RegEnable(
      exu.io.CSRStateMstatus,
      ysyx_26030103_mstatus.resetValue,
      exu.io.TrapCommit
    )
    io.debug_trap_mtvec := RegEnable(
      exu.io.CSRStateMtvec,
      0.U(32.W),
      exu.io.TrapCommit
    )
    io.debug_trap_mepc := RegEnable(
      exu.io.CSRStateMepc,
      0.U(32.W),
      exu.io.TrapCommit
    )
    io.debug_trap_mcause := RegEnable(
      exu.io.CSRStateMcause,
      0.U(32.W),
      exu.io.TrapCommit
    )
  } else {
    io.debug_csr_mstatus := 0.U
    io.debug_csr_mtvec := 0.U
    io.debug_csr_mepc := 0.U
    io.debug_csr_mcause := 0.U
    io.debug_trap_mstatus := 0.U
    io.debug_trap_mtvec := 0.U
    io.debug_trap_mepc := 0.U
    io.debug_trap_mcause := 0.U
  }

  // mtrace 必须与 WBU retire 记录对齐，而不是早一拍的 LSU out.fire。
  // DiffTest 在 debug_commit 拍用它判断 MMIO；错一拍会让 mtime 在 REF 真正
  // 执行并产生伪差异。错误响应/非对齐访问不 Retire，不会到达这里。
  if (config.EnableMemoryTrace) {
    val MtraceCommit = WBUCommit && wbu.io.in.bits.MemoryValid
    io.debug_mtrace_valid := RegNext(MtraceCommit, false.B)
    io.debug_mtrace_pc := RegEnable(wbu.io.in.bits.pc, 0.U(32.W), MtraceCommit)
    io.debug_mtrace_wen := RegEnable(
      wbu.io.in.bits.MemoryWrite,
      false.B,
      MtraceCommit
    )
    io.debug_mtrace_addr := RegEnable(
      wbu.io.in.bits.ALUResult,
      0.U(32.W),
      MtraceCommit
    )
    io.debug_mtrace_wdata := RegEnable(
      wbu.io.in.bits.StoreData,
      0.U(32.W),
      MtraceCommit
    )
    io.debug_mtrace_rdata := RegEnable(
      wbu.io.in.bits.LoadData,
      0.U(32.W),
      MtraceCommit
    )
    io.debug_mtrace_width := RegEnable(
      wbu.io.in.bits.WidthSelect,
      0.U(2.W),
      MtraceCommit
    )
  } else {
    io.debug_mtrace_valid := false.B
    io.debug_mtrace_pc := 0.U
    io.debug_mtrace_wen := false.B
    io.debug_mtrace_addr := 0.U
    io.debug_mtrace_wdata := 0.U
    io.debug_mtrace_rdata := 0.U
    io.debug_mtrace_width := 0.U
  }

  // 调试 Access Fault 只认精确提交事件。取指错误必须随指令
  // 通过流水线到 EXU，错路响应在此前被 flush 就不得打印。
  // LSU 仍以精确 MemTrap 提交为界；cause=4/6 非对齐不是 AXI fault。
  if (config.EnableAnyObservability) {
    val LSUAccessFaultCommit = lsu.io.AccessFault
    val ICacheAccessFaultCommit = exu.io.FetchAccessFaultCommit
    val AccessFaultCommit = LSUAccessFaultCommit || ICacheAccessFaultCommit
    io.debug_access_fault := RegNext(AccessFaultCommit, false.B)
    io.debug_access_fault_pc := RegEnable(
      Mux(LSUAccessFaultCommit, lsu.io.MemTrapPC, exu.io.FetchAccessFaultPC),
      0.U(32.W),
      AccessFaultCommit
    )
    io.debug_access_fault_resp := RegEnable(
      Mux(
        LSUAccessFaultCommit,
        lsu.io.AccessFaultResp,
        exu.io.FetchAccessFaultResp
      ),
      0.U(2.W),
      AccessFaultCommit
    )
  } else {
    io.debug_access_fault := false.B
    io.debug_access_fault_pc := 0.U
    io.debug_access_fault_resp := 0.U
  }
  // 性能计数器：按Kconfig单计数器开关逐个生成。关闭的计数器在顶层输出
  // 常量，未被读取的叶模块信号会被综合展平/常量传播与Verilator死代码
  // 消除一并裁掉；顶层端口ABI保持不变，未启用的计数器对应CSV列恒为0。
  private def PerfBool(Key: String)(Signal: => Bool): Bool =
    if (config.EnablePerfCounter(Key)) { Signal }
    else { false.B }
  private def PerfUInt(Key: String, Width: Int)(Signal: => UInt): UInt =
    if (config.EnablePerfCounter(Key)) { Signal }
    else { 0.U(Width.W) }
  idu.io.ex_memop := exu.io.HazardMemOp
  // 基础吞吐
  // 冲刷拍（pipe_flush）的响应被 StageConnect 消费后直接丢弃，不算交付。
  io.perf_ifu_fetch := PerfBool("ifu_fetch")(icache.io.resp_valid &&
    icache.io.resp_ready && !pipe_flush)
  io.perf_exu_done := PerfBool("exu_done")(exu.io.out.fire)
  io.perf_lsu_load := PerfBool("lsu_load")(lsu.io.Complete &&
    !lsu.io.DebugMemoryWrite)
  io.perf_lsu_store := PerfBool("lsu_store")(lsu.io.Complete &&
    lsu.io.DebugMemoryWrite)
  // 指令类型与执行活跃
  io.perf_alu_op := PerfBool("alu_op")(exu.io.PerfALUOp)
  io.perf_mem_op := PerfBool("mem_op")(exu.io.PerfMemOp)
  io.perf_csr_op := PerfBool("csr_op")(exu.io.PerfCSROp)
  io.perf_branch_op := PerfBool("branch_op")(exu.io.PerfBranchOp)
  io.perf_jal_op := PerfBool("jal_op")(exu.io.PerfJalOp)
  io.perf_jalr_op := PerfBool("jalr_op")(exu.io.PerfJalrOp)
  // 乘除法
  io.perf_mdu_req := PerfBool("mdu_req")(exu.io.PerfMDUReq)
  io.perf_mdu_done := PerfBool("mdu_done")(exu.io.PerfMDUDone)
  io.perf_mdu_op := PerfUInt("mdu_op", 3)(exu.io.PerfMDUOp)
  io.perf_mdu_active := PerfBool("mdu_active")(exu.io.PerfMDUActive)
  io.perf_mdu_wait := PerfBool("mdu_wait")(exu.io.PerfMDUWait)
  // 取指停顿
  io.perf_ifu_stall_pipeline := PerfBool("ifu_stall_pipeline")(
    icache.io.resp_valid && !icache.io.resp_ready
  )
  // AXI等待只统计真实refill的AR/R占用。FetchReady为低还可能是
  // 响应槽或下游反压，不能全部归因于AXI。
  io.perf_ifu_stall_axi := PerfBool("ifu_stall_axi")(
    icache.io.perf_refill_req || icache.io.perf_refill_resp
  )
  io.perf_ifu_stall_ar := PerfBool("ifu_stall_ar")(icache.io.perf_refill_req)
  io.perf_ifu_stall_r := PerfBool("ifu_stall_r")(icache.io.perf_refill_resp)
  // 重定向事件含异常/mret（ExceptionTaken）：它们与分支误预测一样让 IFU
  // 停发一拍；二者同拍时用或避免重复计数。
  io.perf_ifu_stall_redirect := PerfBool("ifu_stall_redirect")(
    exu.io.Redirect || exu.io.ExceptionTaken
  )
  io.perf_ifu_stall_idle := PerfBool("ifu_stall_idle")(ifu.io.StallIdle)
  // 指令缓存
  io.perf_icache_hit := PerfBool("icache_hit")(icache.io.perf_hit)
  io.perf_icache_miss := PerfBool("icache_miss")(icache.io.perf_miss)
  // 数据缓存
  io.perf_dcache_hit := PerfBool("dcache_hit")(lsu.io.DCachePerfHit)
  io.perf_dcache_miss := PerfBool("dcache_miss")(lsu.io.DCachePerfMiss)
  io.perf_dcache_refill_req :=
    PerfBool("dcache_refill_req")(lsu.io.DCachePerfRefillReq)
  io.perf_dcache_refill_resp :=
    PerfBool("dcache_refill_resp")(lsu.io.DCachePerfRefillResp)
  // 执行级停顿
  io.perf_exu_stall_lsu := PerfBool("exu_stall_lsu")(
    exu.io.PerfExecutionActive && !exu.io.out.fire && !exu.io.PerfMDUWait
  )
  io.perf_exu_idle_noinput := PerfBool("exu_idle_noinput")(exu.io.PerfIdleNoInput)
  io.perf_mem_waitslot := PerfBool("mem_waitslot")(lsu.io.Hazard2Valid)
  // 访存级
  io.perf_lsu_active := PerfBool("lsu_active")(lsu.io.Active)
  io.perf_lsu_load_active := PerfBool("lsu_load_active")(lsu.io.Active &&
    !lsu.io.IsStore)
  io.perf_lsu_store_active := PerfBool("lsu_store_active")(lsu.io.Active &&
    lsu.io.IsStore)
  io.perf_lsu_stall_read_ar := PerfBool("lsu_stall_read_ar")(lsu.io.StallReadAR)
  io.perf_lsu_stall_read_r := PerfBool("lsu_stall_read_r")(lsu.io.StallReadR)
  io.perf_lsu_stall_write_req :=
    PerfBool("lsu_stall_write_req")(lsu.io.StallWriteReq)
  io.perf_lsu_stall_write_b := PerfBool("lsu_stall_write_b")(lsu.io.StallWriteB)
  // 译码级RAW停顿
  io.perf_idu_stall_raw := PerfBool("idu_stall_raw")(idu.io.perf_stall_raw)
  io.perf_idu_stall_raw_loaduse :=
    PerfBool("idu_stall_raw_loaduse")(idu.io.perf_stall_raw_loaduse)
  io.perf_idu_stall_raw_alu :=
    PerfBool("idu_stall_raw_alu")(idu.io.perf_stall_raw_alu)
  // 异常
  io.perf_trap_count := PerfBool("trap_count")(exu.io.PerfTrap)
  // 执行活跃电平与事件分类码是多个单计数器的公共信号：消费方任一开启
  // 才生成，否则绑常量。
  io.perf_execution_active :=
    (if (config.EnablePerfExecutionActive) exu.io.PerfExecutionActive
     else false.B)
  io.perf_exu_event_kind :=
    (if (config.EnablePerfEventKind) exu.io.PerfEventKind else 0.U(3.W))

  // AXI响应协议检查: B响应的错误码不再在这里断言——DECERR/SLVERR会由LSU按
  // store访问故障精确提交(cause=7), 顶层断言会抢先把仿真停掉。
  // 这里保留BID/RID协议检查, 并消费这些顶层AXI响应信号。
  // CPU自身使用读ID 0/1和写ID 0；入站DMA proxy保留下游ID 2。
  val b_handshake = io.master_bvalid && io.master_bready
  assert(
    !b_handshake || io.master_bid === 0.U || io.master_bid === 2.U,
    "AXI write: bid=%d",
    io.master_bid
  )
  assert(
    !(io.master_rvalid && io.master_rready) ||
      io.master_rid === 0.U || io.master_rid === 1.U || io.master_rid === 2.U,
    "AXI read: rid=%d",
    io.master_rid
  )

}
