package ysyx_26030103
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.ysyx_26030103_AXI5._
class ysyx_26030103_ICache(
    BlockSizeLog2: Int = 4,
    IndexBits:     Int = 5,
    AddressWidth:  Int = 32,
    CacheableBase: Long = 0x00000000L,
    CacheableMask: Long = 0x00000000L  // 0=全缓存，由上层传入覆盖
) extends Module {
  val TagBits = AddressWidth - IndexBits - BlockSizeLog2
  val NumBlocks = 1 << IndexBits
  val WordsPerBlock = 1 << (BlockSizeLog2 - 2) // 块大小 ÷ 4字节
  val WordCntBits = log2Ceil(WordsPerBlock)     // 块内字数计数器位宽
  val io = IO(new Bundle {
    val fetch_addr = Input(UInt(AddressWidth.W))
    val fetch_valid = Input(Bool())
    val fetch_ready = Output(Bool())
    val resp_data = Output(UInt(32.W))
    val resp_addr = Output(UInt(AddressWidth.W)) // 响应对应的取指地址(即那条指令的PC)
    val resp_fault = Output(Bool())              // 该响应是否是取指访问错误
    val resp_valid = Output(Bool())
    val resp_ready = Input(Bool())
    val axi = new ysyx_26030103_AXI5IO(AddressWidth)
    val perf_hit = Output(Bool())
    val perf_miss = Output(Bool())
    val perf_refill_req = Output(Bool())
    val perf_refill_resp = Output(Bool())
    val access_fault = Output(Bool())
    val access_fault_resp = Output(UInt(2.W))
    val flush = Input(Bool()) // fence.i: 清cache行并丢弃在飞refill
    val kill = Input(Bool())  // 重定向/异常: 杀掉在飞取指请求,不动cache行
  })
  // 直接映射cache存储阵列：valid+tag+data
  val valid = RegInit(VecInit(Seq.fill(NumBlocks)(false.B)))
  val tag = Reg(Vec(NumBlocks, UInt(TagBits.W)))
  val data = Reg(Vec(NumBlocks, Vec(WordsPerBlock, UInt(32.W))))
  val index = io.fetch_addr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val blockOffset =
    if (WordsPerBlock > 1) io.fetch_addr(BlockSizeLog2 - 1, 2)
    else 0.U
  val reqTag = io.fetch_addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val cacheable = (io.fetch_addr & CacheableMask.U) === CacheableBase.U
  // 两级流水: s1受理级(寄存请求,命中数据当拍锁存) -> 响应级(命中直接响应,缺失走refill)
  val s1_valid = RegInit(false.B)
  val s1_hit = Reg(Bool())            // 受理时判定: 可缓存且命中
  val s1_ready = RegInit(false.B)     // 缺失的关键词已返回(early restart)或refill已完成
  val fetch_addr_reg = Reg(UInt(AddressWidth.W))
  val fetch_index_reg = Reg(UInt(IndexBits.W))
  val fetch_offset_reg = Reg(UInt((BlockSizeLog2 - 2).W)) // 请求时块内word偏移
  val resp_data_reg = Reg(UInt(32.W))
  val cacheable_reg = RegInit(false.B)
  val access_fault_reg = RegInit(false.B)
  val access_fault_resp_reg = RegInit(0.U(2.W))
  // refill状态机(结构互斥: 同一时刻只处理一个缺失)
  val rfstates = Enum(3)
  val rf_idle = rfstates(0)
  val rf_req = rfstates(1)
  val rf_resp = rfstates(2)
  val rfstate = RegInit(rf_idle)
  // refill专属寄存器: refill期间s1可继续受理新请求(命中直下/缺失排队),
  // 因此阵列写口、AR地址、early restart判定都必须用refill自己的地址, 不能碰s1的
  val ref_addr = Reg(UInt(AddressWidth.W))
  val ref_index = ref_addr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val ref_tag = ref_addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val ref_offset =
    if (WordsPerBlock > 1) ref_addr(BlockSizeLog2 - 1, 2)
    else 0.U
  val ref_cacheable = Reg(Bool())
  val refill_cnt = RegInit(0.U(WordCntBits.W))  // 当前正在填充第几个word
  val burst_mode = RegInit(false.B)             // 突发传输模式标志
  // 在飞refill是否仍属于当前s1里的请求: 原请求被消费/冲刷/替换后,
  // 后续R拍只写阵列, 绝不再写s1的响应寄存器(那会污染新请求)
  val s1_waits = RegInit(false.B)
  // flush(fence.i)/kill不能丢弃已被从机接受的AXI事务: 置位后继续把剩余拍排空,
  // 否则残留的R拍会被后续突发误收, 造成块内数据错位
  val discard = RegInit(false.B)
  // 正在refill的索引: 同索引新请求可能读到半填的行(tag已换/数据填了一半),
  // 一律强制判缺失排队, 等refill完成后重查命中
  val refill_busy = rfstate =/= rf_idle
  val under_refill = refill_busy && ref_cacheable && (index === ref_index)
  val hit = valid(index) && tag(index) === reqTag && !under_refill
  io.access_fault := access_fault_reg
  io.access_fault_resp := access_fault_resp_reg
  io.axi.AW.AWVALID := false.B
  io.axi.AW.AWID := 0.U
  io.axi.AW.AWADDR := 0.U
  io.axi.AW.AWLEN := 0.U
  io.axi.AW.AWSIZE := 2.U
  io.axi.AW.AWBURST := 1.U
  io.axi.AW.AWPROT := 0.U
  io.axi.W.WVALID := false.B
  io.axi.W.WDATA := 0.U
  io.axi.W.WSTRB := 0.U
  io.axi.W.WLAST := false.B
  io.axi.B.BREADY := false.B
  io.axi.AR.ARVALID := false.B
  io.axi.AR.ARID := 0.U
  io.axi.AR.ARADDR := 0.U
  io.axi.AR.ARLEN := 0.U
  io.axi.AR.ARSIZE := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT := 0.U
  io.axi.R.RREADY := true.B  // always drain AXI responses
  // 响应级: 命中(s1_hit)用受理时锁存的数据; 关键词返回/refill完成(s1_ready)后,
  // 可缓存的从阵列读(关键词所在拍已写入), 不可缓存的用R拍锁存的数据; 错误一律给NOP
  val responding = s1_valid && (s1_hit || s1_ready)
  io.resp_valid := responding
  io.resp_addr := fetch_addr_reg
  io.resp_fault := access_fault_reg
  io.resp_data := Mux(access_fault_reg, "h00000013".U,
    Mux(s1_hit, resp_data_reg,
      Mux(cacheable_reg, data(fetch_index_reg)(fetch_offset_reg), resp_data_reg)))
  // 受理级: 响应槽为空,或本拍响应正被接收; refill期间也可受理(缺失则排队)
  io.fetch_ready := !s1_valid || (responding && io.resp_ready)
  val accept = io.fetch_valid && io.fetch_ready
  io.perf_hit := accept && cacheable && hit
  io.perf_miss := accept && !(cacheable && hit)
  io.perf_refill_req := rfstate === rf_req
  io.perf_refill_resp := rfstate === rf_resp
  when(responding && io.resp_ready) {
    s1_valid := false.B
    s1_waits := false.B
  }
  when(accept) {
    fetch_addr_reg := io.fetch_addr
    fetch_index_reg := index
    fetch_offset_reg := blockOffset
    resp_data_reg := data(index)(blockOffset) // 命中时这就是响应数据
    cacheable_reg := cacheable
    s1_hit := cacheable && hit
    s1_ready := false.B
    access_fault_reg := false.B
    access_fault_resp_reg := 0.U
    s1_valid := true.B
    s1_waits := false.B
  }
  // 排队缺失: refill空闲时启动; 启动前重查命中(在飞refill可能刚把这行填好)
  val queued_miss = s1_valid && !s1_hit && !s1_ready
  val recheck_hit = cacheable_reg &&
    valid(fetch_index_reg) &&
    tag(fetch_index_reg) === fetch_addr_reg(AddressWidth - 1, IndexBits + BlockSizeLog2)
  when(queued_miss && rfstate === rf_idle && !io.kill && !io.flush) {
    when(recheck_hit) {
      s1_hit := true.B
      resp_data_reg := data(fetch_index_reg)(fetch_offset_reg)
    }.otherwise {
      rfstate := rf_req
      ref_addr := fetch_addr_reg
      ref_cacheable := cacheable_reg
      refill_cnt := 0.U
      burst_mode := cacheable_reg
      s1_waits := true.B
    }
  }
  // 注意: refill启动刻意不早于s1_valid寄存后一拍——启动条件里的!io.kill
  // 能抑制错误路径取指的refill(误预测时kill比受理晚一拍到)。曾经试过受理即启动,
  // 结果误预测窗口内的错误路径取指把无用refill发上总线并驱逐有用行, microbench
  // 缺失+70%, 总周期+10.6%, 已回退。
  switch(rfstate) {
    is(rf_req) {
      io.axi.AR.ARVALID := true.B
      io.axi.AR.ARLEN := Mux(ref_cacheable && burst_mode, (WordsPerBlock - 1).U, 0.U)
      io.axi.AR.ARADDR := Mux(ref_cacheable,
        Mux(burst_mode,
          Cat(ref_addr(AddressWidth - 1, BlockSizeLog2), 0.U(BlockSizeLog2.W)),
          Cat(ref_addr(AddressWidth - 1, BlockSizeLog2), refill_cnt, 0.U(2.W))
        ),
        ref_addr
      )
      when(io.axi.AR.ARREADY) {
        rfstate := rf_resp
      }
    }
    is(rf_resp) {
      io.axi.R.RREADY := true.B
      when(io.axi.R.RVALID && io.axi.R.RREADY) {
        when(!discard) {
        when(io.axi.R.RRESP =/= 0.U) {
          when(s1_waits) {  // 错误属于原请求, 只有它还住在s1里才上报
            access_fault_reg := true.B
            access_fault_resp_reg := io.axi.R.RRESP
            resp_data_reg := "h00000013".U  // NOP, 不让下游拿到非法指令
          }
        }.otherwise {
          when(s1_waits) {
            resp_data_reg := io.axi.R.RDATA
          }
          when(ref_cacheable) {  // 阵列填充无条件进行, 数据是真实内存内容
            tag(ref_index) := ref_tag
            data(ref_index)(refill_cnt) := io.axi.R.RDATA
            when(refill_cnt === (WordsPerBlock - 1).U) {
              valid(ref_index) := true.B
            }
          }
        }
        }
        // early restart: 关键词所在拍到手即可响应, 无需等整行填完
        when(!discard && s1_waits && refill_cnt === ref_offset) {
          s1_ready := true.B
        }
        when(ref_cacheable && burst_mode) {
          refill_cnt := refill_cnt + 1.U
          when(io.axi.R.RLAST || refill_cnt === (WordsPerBlock - 1).U) {
            // 突发结束(正常结束或被从机提前截断)
            when(discard) {
              discard := false.B
              rfstate := rf_idle
            }.elsewhen(refill_cnt === (WordsPerBlock - 1).U) {
              when(s1_waits) {
                s1_ready := true.B
              }
              rfstate := rf_idle
            }.otherwise {
              burst_mode := false.B  // 提前RLAST: 剩余的词改单拍补取
              rfstate := rf_req
            }
          }
        }.elsewhen(ref_cacheable) {
          // 单拍填充: 每词一个独立AR; 排空时当前拍即结束, 剩余的词放弃(独立事务无残留)
          when(discard) {
            discard := false.B
            rfstate := rf_idle
          }.elsewhen(refill_cnt === (WordsPerBlock - 1).U) {
            when(s1_waits) {
              s1_ready := true.B
            }
            rfstate := rf_idle
          }.otherwise {
            refill_cnt := refill_cnt + 1.U
            rfstate := rf_req
          }
        }.otherwise {
          // 非缓存区: 一词一拍, 当前拍即结束
          when(discard) {
            discard := false.B
            rfstate := rf_idle
          }.otherwise {
            when(s1_waits) {
              s1_ready := true.B
            }
            rfstate := rf_idle
          }
        }
      }
    }
  }
  // kill(重定向/异常): 只杀响应槽(s1),在飞的refill照常完成并填充阵列
  // (数据是真实内存内容,下次循环到同一行就能命中;否则每次跳转都浪费一次refill)
  when(io.kill) {
    s1_valid := false.B
    s1_waits := false.B
  }
  // flush(fence.i): 代码可能已被改写,清cache行,在飞事务排空丢弃
  // 注意: 已发出的ARVALID绝不能在握手前撤回(否则仲裁器授权后等不到fire会死锁),
  // 必须保持请求直到AR完成,再转入排空丢弃数据
  when(io.flush) {
    s1_valid := false.B
    s1_waits := false.B
    valid.foreach(_ := false.B)
    when(rfstate === rf_req) {
      discard := true.B
    }.elsewhen(rfstate === rf_resp && !discard) {
      discard := true.B
    }
  }
}
