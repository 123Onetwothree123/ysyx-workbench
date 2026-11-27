package ysyx_26030103.ifu
import _root_.ysyx_26030103.common.ysyx_26030103_KconfigDefaults
import chisel3._
import chisel3.util._

// 分支目标缓冲(Branch Target Buffer)
// 用PC索引,命中返回跳转目标,供IFU做BTFN预测
// 组织方式: 组相联,组数=2^BTBBits,相联度=BTBWays(1=直接映射)
// 表项: valid + tag + target (+ 可选kind, KindBits>0时启用); 组满按每组repl_ptr轮转替换(FIFO)
// 顶层例化两张: 分支BTB(KindBits=0, BTFN方向预测) + jal BTB(KindBits=2, 区分Jal/Call/Ret)
class ysyx_26030103_BTB(
    BTBBits: Int = ysyx_26030103_KconfigDefaults.NpcBTBBits,
    BTBWays: Int = ysyx_26030103_KconfigDefaults.NpcBTBWays,
    AddressWidth: Int = ysyx_26030103_KconfigDefaults.NpcAddressWidth,
    KindBits: Int = 0,
    // 启用 C 扩展时 IALIGN=16，PC 可能落在 2 字节边界。索引/标签必须
    // 保留 bit1，避免同一条 32 位指令的两个半字或相邻 16 位指令互相
    // 混叠预测目标。
    UseC: Boolean = false
) extends Module {
  require(BTBBits >= 0, "BTBBits must be non-negative")
  require(BTBWays >= 1, "BTB must contain at least one way")
  require(AddressWidth >= 3, "BTB address width must include PC alignment and tag bits")
  private val PCShift = if (UseC) 1 else 2
  require(
    BTBBits < AddressWidth - PCShift,
    "BTB geometry must leave at least one tag bit after removing PC alignment bits"
  )
  require(KindBits >= 0, "BTB kind width must be non-negative")
  val NumberSets = 1 << BTBBits
  val tagBits = AddressWidth - BTBBits - PCShift // PC>>PcShift 后去掉 index 位
  val addressShiftedWidth = AddressWidth - PCShift // PC[31:1] 或 PC[31:2]
  val hasKind = KindBits > 0
  val io = IO(new Bundle {
    // 查询端口(组合逻辑,IFU每拍用当前PC[31:2]查,决定下一PC)
    val LookupPC = Input(UInt(addressShiftedWidth.W))
    val hit = Output(Bool())
    val target = Output(UInt(AddressWidth.W))
    // 更新端口(EXU提交分支/jal时写,写PC对应的真实target)
    val UpdateValid = Input(Bool())
    val UpdatePC = Input(UInt(addressShiftedWidth.W))
    val UpdateTarget = Input(UInt(AddressWidth.W))
    // fence.i 后代码含义可能改变，旧控制流表项必须失效。
    val flush = Input(Bool())
    // 可选kind端口(仅KindBits>0时存在)
    val HitKind = if (hasKind) Some(Output(UInt(KindBits.W))) else None
    val UpdateKind = if (hasKind) Some(Input(UInt(KindBits.W))) else None
  })

  // 阵列: 每组ways项
  val valid = RegInit(
    VecInit(Seq.fill(NumberSets)(VecInit(Seq.fill(BTBWays)(false.B))))
  )
  val tag = Reg(Vec(NumberSets, Vec(BTBWays, UInt(tagBits.W))))
  val target = Reg(Vec(NumberSets, Vec(BTBWays, UInt(AddressWidth.W))))
  val kind =
    if (hasKind) Some(Reg(Vec(NumberSets, Vec(BTBWays, UInt(KindBits.W)))))
    else None

  private def IndexOf(pc: UInt): UInt =
    if (BTBBits == 0) 0.U(1.W) else pc(BTBBits - 1, 0)
  private def TagOf(pc: UInt): UInt =
    pc(addressShiftedWidth - 1, BTBBits)
  private def WayHit(set: UInt, way: UInt, tagValue: UInt): Bool =
    valid(set)(way) && tag(set)(way) === tagValue

  // 查询: input已是PC[31:2], 低BTBBits位做index, 高位做tag
  val lookupIndex = IndexOf(io.LookupPC)
  val lookupTag = TagOf(io.LookupPC)
  val hitVec = Wire(Vec(BTBWays, Bool()))
  for (w <- 0 until BTBWays) {
    hitVec(w) := WayHit(lookupIndex, w.U, lookupTag)
  }
  val hitWay = hitVec.indexWhere((h: Bool) => h)
  io.hit := hitVec.reduceTree(_ || _)
  io.target := target(lookupIndex)(hitWay)
  if (hasKind) {
    io.HitKind.get := kind.get(lookupIndex)(hitWay)
  }

  // 更新: 写入对应组,命中同tag则更新target,否则找第一个空槽,组满按轮转指针替换(FIFO)
  val updIndex = IndexOf(io.UpdatePC)
  val updTag = TagOf(io.UpdatePC)
  // 每组一个轮转指针, 组满替换时指向受害者way, 替换后自增回绕
  // (ways=1时恒为0, 退化为直接映射的正常覆盖行为)
  val replPointerWidth = log2Ceil(BTBWays).max(1)
  val replPointer = RegInit(VecInit(Seq.fill(NumberSets)(0.U(replPointerWidth.W))))
  when(io.flush) {
    for (set <- 0 until NumberSets; way <- 0 until BTBWays) {
      valid(set)(way) := false.B
    }
    for (set <- 0 until NumberSets) {
      replPointer(set) := 0.U
    }
  }.elsewhen(io.UpdateValid) {
    // 先组合判断: 是否有同tag命中, 各way之前(不含自己)是否全占用
    val tagMatch = Wire(Vec(BTBWays, Bool()))
    for (w <- 0 until BTBWays) {
      tagMatch(w) := WayHit(updIndex, w.U, updTag)
    }
    val anyMatch = tagMatch.reduceTree(_ || _)
    // prefix_full(w) = way 0..w-1 全部占用(AND语义, way0的前置恒为"已满")
    val prefixFull = Wire(Vec(BTBWays, Bool()))
    prefixFull(0) := true.B
    for (w <- 1 until BTBWays) {
      prefixFull(w) := prefixFull(w - 1) && valid(updIndex)(w - 1)
    }
    val allFull = prefixFull(BTBWays - 1) && valid(updIndex)(BTBWays - 1)
    // 每个way的写入: 命中则刷target; 否则当无命中且(该way是第一个空槽 或 组满且轮到该way)时建新项
    for (w <- 0 until BTBWays) {
      when(tagMatch(w)) {
        target(updIndex)(w) := io.UpdateTarget
        if (hasKind) {
          kind.get(updIndex)(w) := io.UpdateKind.get
        }
      }.elsewhen(
        !anyMatch && (!valid(updIndex)(w) && prefixFull(
          w
        ) || (allFull && w.U === replPointer(updIndex)))
      ) {
        valid(updIndex)(w) := true.B
        tag(updIndex)(w) := updTag
        target(updIndex)(w) := io.UpdateTarget
        if (hasKind) {
          kind.get(updIndex)(w) := io.UpdateKind.get
        }
      }
    }
    // 组满发生替换后轮转指针自增(回绕到0)
    when(!anyMatch && allFull) {
      replPointer(updIndex) := Mux(
        replPointer(updIndex) === (BTBWays - 1).U,
        0.U,
        replPointer(updIndex) + 1.U
      )
    }
  }
}
