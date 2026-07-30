package ysyx_26030103
import chisel3._
import chisel3.util._

// jal BTB表项类型(KindBits=2时使用): ret表项只作ret标记, 预测目标由RAS给出
object ysyx_26030103_BTBKind {
  val Jal  = 0.U(2.W)
  val Call = 1.U(2.W)
  val Ret  = 2.U(2.W)
}

// 分支目标缓冲(Branch Target Buffer)
// 用PC索引,命中返回跳转目标,供IFU做BTFN预测
// 组织方式: 组相联,组数=2^BTBBits,相联度=BTBWays(1=直接映射)
// 表项: valid + tag + target (+ 可选kind, KindBits>0时启用); 组满按每组repl_ptr轮转替换(FIFO)
// 顶层例化两张: 分支BTB(KindBits=0, BTFN方向预测) + jal BTB(KindBits=2, 区分Jal/Call/Ret)
class ysyx_26030103_BTB(
  BTBBits: Int = 4,
  BTBWays: Int = 1,
  AddressWidth: Int = 32,
  KindBits: Int = 0
) extends Module {
  val NumSets = 1 << BTBBits
  val TagBits = AddressWidth - BTBBits - 2 // PC>>2后去掉index位
  val AddrShiftedWidth = AddressWidth - 2 // PC[31:2], 低位始终为0
  val HasKind = KindBits > 0
  val io = IO(new Bundle {
    // 查询端口1(组合逻辑,IFU每拍用当前PC[31:2]查,决定下一PC)
    val lookup_pc = Input(UInt(AddrShiftedWidth.W))
    val hit       = Output(Bool())
    val target    = Output(UInt(AddressWidth.W))
    // 查询端口2(原响应级贴标签用; 已改为取指接受时快照标签, 顶层不再使用, 仅保留端口)
    val lookup2_pc = Input(UInt(AddrShiftedWidth.W))
    val hit2       = Output(Bool())
    val target2    = Output(UInt(AddressWidth.W))
    // 更新端口(EXU提交分支/jal时写,写PC对应的真实target)
    val update_valid  = Input(Bool())
    val update_pc     = Input(UInt(AddrShiftedWidth.W))
    val update_target = Input(UInt(AddressWidth.W))
    // 可选kind端口(仅KindBits>0时存在)
    val hit_kind    = if (HasKind) Some(Output(UInt(KindBits.W))) else None
    val hit2_kind   = if (HasKind) Some(Output(UInt(KindBits.W))) else None
    val update_kind = if (HasKind) Some(Input(UInt(KindBits.W))) else None
  })

  // 阵列: 每组ways项
  val valid = RegInit(VecInit(Seq.fill(NumSets)(VecInit(Seq.fill(BTBWays)(false.B)))))
  val tag   = Reg(Vec(NumSets, Vec(BTBWays, UInt(TagBits.W))))
  val target = Reg(Vec(NumSets, Vec(BTBWays, UInt(AddressWidth.W))))
  val kind  = if (HasKind) Some(Reg(Vec(NumSets, Vec(BTBWays, UInt(KindBits.W))))) else None

  // 查询: input已是PC[31:2], 低BTBBits位做index, 高位做tag
  val lookup_idx = io.lookup_pc(BTBBits - 1, 0)
  val lookup_tag = io.lookup_pc(AddrShiftedWidth - 1, BTBBits)
  val hit_vec = Wire(Vec(BTBWays, Bool()))
  for (w <- 0 until BTBWays) {
    hit_vec(w) := valid(lookup_idx)(w) && tag(lookup_idx)(w) === lookup_tag
  }
  val hit_way = hit_vec.indexWhere((h: Bool) => h)
  io.hit := hit_vec.reduceTree(_ || _)
  io.target := target(lookup_idx)(hit_way)
  if (HasKind) {
    io.hit_kind.get := kind.get(lookup_idx)(hit_way)
  }

  // 第二查询端口(响应级用resp_addr查,跟端口1读同一张表)
  val lookup2_idx = io.lookup2_pc(BTBBits - 1, 0)
  val lookup2_tag = io.lookup2_pc(AddrShiftedWidth - 1, BTBBits)
  val hit2_vec = Wire(Vec(BTBWays, Bool()))
  for (w <- 0 until BTBWays) {
    hit2_vec(w) := valid(lookup2_idx)(w) && tag(lookup2_idx)(w) === lookup2_tag
  }
  val hit2_way = hit2_vec.indexWhere((h: Bool) => h)
  io.hit2 := hit2_vec.reduceTree(_ || _)
  io.target2 := target(lookup2_idx)(hit2_way)
  if (HasKind) {
    io.hit2_kind.get := kind.get(lookup2_idx)(hit2_way)
  }

  // 更新: 写入对应组,命中同tag则更新target,否则找第一个空槽,组满按轮转指针替换(FIFO)
  val upd_idx = io.update_pc(BTBBits - 1, 0)
  val upd_tag = io.update_pc(AddrShiftedWidth - 1, BTBBits)
  // 每组一个轮转指针, 组满替换时指向受害者way, 替换后自增回绕
  // (ways=1时恒为0, 退化为直接映射的正常覆盖行为)
  val ReplPtrWidth = log2Ceil(BTBWays).max(1)
  val repl_ptr = RegInit(VecInit(Seq.fill(NumSets)(0.U(ReplPtrWidth.W))))
  when(io.update_valid) {
    // 先组合判断: 是否有同tag命中, 各way之前(不含自己)是否全占用
    val tag_match = Wire(Vec(BTBWays, Bool()))
    for (w <- 0 until BTBWays) {
      tag_match(w) := valid(upd_idx)(w) && tag(upd_idx)(w) === upd_tag
    }
    val any_match = tag_match.reduceTree(_ || _)
    // prefix_full(w) = way 0..w-1 全部占用(AND语义, way0的前置恒为"已满")
    val prefix_full = Wire(Vec(BTBWays, Bool()))
    prefix_full(0) := true.B
    for (w <- 1 until BTBWays) {
      prefix_full(w) := prefix_full(w - 1) && valid(upd_idx)(w - 1)
    }
    val all_full = prefix_full(BTBWays - 1) && valid(upd_idx)(BTBWays - 1)
    // 每个way的写入: 命中则刷target; 否则当无命中且(该way是第一个空槽 或 组满且轮到该way)时建新项
    for (w <- 0 until BTBWays) {
      when(tag_match(w)) {
        target(upd_idx)(w) := io.update_target
        if (HasKind) {
          kind.get(upd_idx)(w) := io.update_kind.get
        }
      }.elsewhen(!any_match && (!valid(upd_idx)(w) && prefix_full(w) || (all_full && w.U === repl_ptr(upd_idx)))) {
        valid(upd_idx)(w) := true.B
        tag(upd_idx)(w) := upd_tag
        target(upd_idx)(w) := io.update_target
        if (HasKind) {
          kind.get(upd_idx)(w) := io.update_kind.get
        }
      }
    }
    // 组满发生替换后轮转指针自增(回绕到0)
    when(!any_match && all_full) {
      repl_ptr(upd_idx) := Mux(repl_ptr(upd_idx) === (BTBWays - 1).U, 0.U, repl_ptr(upd_idx) + 1.U)
    }
  }
}
