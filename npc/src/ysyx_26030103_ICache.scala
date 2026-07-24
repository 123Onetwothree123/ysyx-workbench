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
    val resp_valid = Output(Bool())
    val resp_ready = Input(Bool())
    val axi = new ysyx_26030103_AXI5IO(AddressWidth)
    val perf_hit = Output(Bool())
    val perf_miss = Output(Bool())
    val perf_refill_req = Output(Bool())
    val perf_refill_resp = Output(Bool())
    val access_fault = Output(Bool())
    val access_fault_resp = Output(UInt(2.W))
  })
  // 直接映射cache存储阵列：valid+tag+data
  val valid = RegInit(VecInit(Seq.fill(NumBlocks)(false.B)))
  val tag = Reg(Vec(NumBlocks, UInt(TagBits.W)))
  val data = Reg(Vec(NumBlocks, Vec(WordsPerBlock, UInt(32.W))))
  val states = Enum(4)
  val state_idle = states(0)
  val state_refill_req = states(1)
  val state_refill_resp = states(2)
  val state_resp = states(3)
  val state = RegInit(state_idle)
  val index = io.fetch_addr(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val blockOffset =
    if (WordsPerBlock > 1) io.fetch_addr(BlockSizeLog2 - 1, 2)
    else 0.U
  val reqTag = io.fetch_addr(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val hit = valid(index) && tag(index) === reqTag
  val cacheable = (io.fetch_addr & CacheableMask.U) === CacheableBase.U
  val fetch_addr_reg = Reg(UInt(AddressWidth.W))
  val fetch_index_reg = Reg(UInt(IndexBits.W))
  val fetch_tag_reg = Reg(UInt(TagBits.W))
  val fetch_offset_reg = Reg(UInt((BlockSizeLog2 - 2).W)) // 请求时块内word偏移
  val resp_data_reg = Reg(UInt(32.W))
  val cacheable_reg = RegInit(false.B)
  val access_fault_reg = RegInit(false.B)
  val access_fault_resp_reg = RegInit(0.U(2.W))
  val refill_cnt = RegInit(0.U(WordCntBits.W))  // 当前正在填充第几个word
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
  io.axi.AR.ARLEN := Mux(cacheable_reg, (WordsPerBlock - 1).U, 0.U)
  io.axi.AR.ARSIZE := 2.U
  io.axi.AR.ARBURST := 1.U
  io.axi.AR.ARPROT := 0.U
  io.axi.R.RREADY := false.B
  io.fetch_ready := false.B
  io.resp_valid := false.B
  io.resp_data := 0.U
  io.perf_hit := false.B
  io.perf_miss := false.B
  io.perf_refill_req := state === state_refill_req
  io.perf_refill_resp := state === state_refill_resp
  switch(state) {
    is(state_idle) {
      access_fault_reg := false.B
      access_fault_resp_reg := 0.U
      io.fetch_ready := true.B
      io.perf_hit  := io.fetch_valid && io.fetch_ready && cacheable && hit
      io.perf_miss := io.fetch_valid && io.fetch_ready && cacheable && !hit
      when(io.fetch_valid && io.fetch_ready) {
        fetch_addr_reg  := io.fetch_addr
        fetch_index_reg := index
        fetch_tag_reg   := reqTag
        resp_data_reg   := data(index)(blockOffset)
        cacheable_reg   := (io.fetch_addr & CacheableMask.U) === CacheableBase.U
        fetch_offset_reg := blockOffset
        refill_cnt      := 0.U
        when(cacheable && hit) {
          state := state_resp
        }.otherwise {
          state := state_refill_req
        }
      }
    }
    is(state_refill_req) {
      io.axi.AR.ARVALID := true.B
      io.axi.AR.ARADDR := Mux(cacheable_reg,
        Cat(fetch_addr_reg(AddressWidth - 1, BlockSizeLog2), 0.U((BlockSizeLog2).W)),
        fetch_addr_reg
      )
      when(io.axi.AR.ARREADY) {
        state := state_refill_resp
      }
    }
    is(state_refill_resp) {
      io.axi.R.RREADY := true.B
      when(io.axi.R.RVALID && io.axi.R.RREADY) {
        when(io.axi.R.RRESP =/= 0.U) {
          access_fault_reg := true.B
          access_fault_resp_reg := io.axi.R.RRESP
          state := state_resp
        }.otherwise {
          resp_data_reg := io.axi.R.RDATA
          when(cacheable_reg) {
            tag(fetch_index_reg)   := fetch_tag_reg
            data(fetch_index_reg)(refill_cnt) := io.axi.R.RDATA
          }
          when(io.axi.R.RLAST || !cacheable_reg) {
            when(cacheable_reg) {
              valid(fetch_index_reg) := true.B
            }
            state := state_resp
          }.otherwise {
            refill_cnt := refill_cnt + 1.U
          }
        }
      }
    }
    is(state_resp) {
      io.resp_valid := true.B
      io.resp_data := Mux(cacheable_reg && !access_fault_reg,
        data(fetch_index_reg)(fetch_offset_reg), resp_data_reg)
      when(io.resp_ready && fetch_addr_reg === "ha00000ec".U) {
        printf(cf"ICache resp: addr=${fetch_addr_reg}, data=${io.resp_data}\n")
      }
      when(io.resp_ready) {
        state := state_idle
      }
    }
  }
}
