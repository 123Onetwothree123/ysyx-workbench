//此文件代码由GPT5.5 codex编写的，注释是我自己慢慢理解，写的
package ysyx_26030103.ysyx_26030103_AXI5
import chisel3._
import chisel3.util._
class ysyx_26030103_AXI5Arbiter extends Module {
  val io = IO(new Bundle {
    val ifu = Flipped(new ysyx_26030103_AXI5IO(32))
    val lsu = Flipped(new ysyx_26030103_AXI5IO(32))
    val memory = new ysyx_26030103_AXI5IO(32)
  })
//日常状态机
  val states = Enum(5)
  val StatesIdle = states(0)
  val StatesReadRequest = states(1)
  val StatesReadResponse = states(2)
  val StatesWriteRequest = states(3)
  val StatesWriteResponse = states(4)
  val state = RegInit(StatesIdle)
//grant是用来记住谁在用总线的
  val GrantIFU = 0.U(1.W) // 0就是授权给ysyx_26030103_IFU使用
  val GrantLSU = 1.U(1.W) // 1就是授权给ysyx_26030103_LSU使用
  val grant = RegInit(GrantIFU)
//和之前一样也是要两个独立的判断，因为AW和W不一定要一个周期同时实现握手，所以两个是独立的
  val AWDone = RegInit(false.B)
  val WDone = RegInit(false.B)
//R通道skid buffer: 切断 xbar状态机→R路由→arbiter透传→master 的全组合链(时序关键路径)
//代价是R返回延迟+1拍; 前向valid/data全部寄存化, 反向ready = !valid || 当拍被消费
  val rSkidValid = RegInit(false.B)
  val rSkidID    = Reg(UInt(4.W))
  val rSkidData  = Reg(UInt(32.W))
  val rSkidResp  = Reg(UInt(2.W))
  val rSkidLast  = Reg(Bool())
//只有在读响应阶段, 被授权方才会看到skid的内容并消费它
  val rConsume = rSkidValid && (state === StatesReadResponse) &&
    Mux(grant === GrantLSU, io.lsu.R.RREADY, io.ifu.R.RREADY)
//都是初始化
  io.ifu.AW.AWREADY := false.B
  io.ifu.W.WREADY := false.B
  io.ifu.B.BID := 0.U
  io.ifu.B.BRESP := 0.U
  io.ifu.B.BVALID := false.B
  io.ifu.AR.ARREADY := false.B
  io.ifu.R.RID := 0.U
  io.ifu.R.RDATA := 0.U
  io.ifu.R.RRESP := 0.U
  io.ifu.R.RLAST := false.B
  io.ifu.R.RVALID := false.B

  io.lsu.AW.AWREADY := false.B
  io.lsu.W.WREADY := false.B
  io.lsu.B.BID := 0.U
  io.lsu.B.BRESP := 0.U
  io.lsu.B.BVALID := false.B
  io.lsu.AR.ARREADY := false.B
  io.lsu.R.RID := 0.U
  io.lsu.R.RDATA := 0.U
  io.lsu.R.RRESP := 0.U
  io.lsu.R.RLAST := false.B
  io.lsu.R.RVALID := false.B

  io.memory.AW.AWVALID := false.B
  io.memory.AW.AWID := 0.U
  io.memory.AW.AWADDR := 0.U
  io.memory.AW.AWLEN := 0.U
  io.memory.AW.AWSIZE := 2.U
  io.memory.AW.AWBURST := 0.U
  io.memory.AW.AWPROT := 0.U
  io.memory.W.WVALID := false.B
  io.memory.W.WDATA := 0.U
  io.memory.W.WSTRB := 0.U
  io.memory.W.WLAST := false.B
  io.memory.B.BREADY := false.B
  io.memory.AR.ARVALID := false.B
  io.memory.AR.ARID := 0.U
  io.memory.AR.ARADDR := 0.U
  io.memory.AR.ARLEN := 0.U
  io.memory.AR.ARSIZE := 2.U
  io.memory.AR.ARBURST := 0.U
  io.memory.AR.ARPROT := 0.U
  io.memory.R.RREADY := !rSkidValid || rConsume
//skid装载: 同拍被消费又装新拍时保持valid, 只消费不装时清空
  when(io.memory.R.RVALID && io.memory.R.RREADY) {
    rSkidValid := true.B
    rSkidID    := io.memory.R.RID
    rSkidData  := io.memory.R.RDATA
    rSkidResp  := io.memory.R.RRESP
    rSkidLast  := io.memory.R.RLAST
  }.elsewhen(rConsume) {
    rSkidValid := false.B
  }
//ysyx_26030103_IFU可以只读，ysyx_26030103_LSU可以又读又写
  val LSUWriteRequest = io.lsu.AW.AWVALID || io.lsu.W.WVALID
  val LSUReadRequest = io.lsu.AR.ARVALID
  val IFUReadRequest = io.ifu.AR.ARVALID

  switch(state) {
    is(StatesIdle) {
      AWDone := false.B
      WDone := false.B
      when(LSUWriteRequest) {
        grant := GrantLSU
        state := StatesWriteRequest
      }.elsewhen(LSUReadRequest) {
        grant := GrantLSU
        state := StatesReadRequest
      }.elsewhen(IFUReadRequest) {
        grant := GrantIFU
        state := StatesReadRequest
      }
    }
    is(StatesReadRequest) {
      when(grant === GrantLSU) {
        io.memory.AR.ARVALID := io.lsu.AR.ARVALID
        io.memory.AR.ARID := io.lsu.AR.ARID
        io.memory.AR.ARADDR := io.lsu.AR.ARADDR
        io.memory.AR.ARLEN := io.lsu.AR.ARLEN
        io.memory.AR.ARSIZE := io.lsu.AR.ARSIZE
        io.memory.AR.ARBURST := io.lsu.AR.ARBURST
        io.memory.AR.ARPROT := io.lsu.AR.ARPROT
        io.lsu.AR.ARREADY := io.memory.AR.ARREADY
      }.otherwise { // ysyx_26030103_IFU
        io.memory.AR.ARVALID := io.ifu.AR.ARVALID
        io.memory.AR.ARID := io.ifu.AR.ARID
        io.memory.AR.ARADDR := io.ifu.AR.ARADDR
        io.memory.AR.ARLEN := io.ifu.AR.ARLEN
        io.memory.AR.ARSIZE := io.ifu.AR.ARSIZE
        io.memory.AR.ARBURST := io.ifu.AR.ARBURST
        io.memory.AR.ARPROT := io.ifu.AR.ARPROT
        io.ifu.AR.ARREADY := io.memory.AR.ARREADY
      }
      when(io.memory.AR.ARVALID && io.memory.AR.ARREADY) { // fire了
        state := StatesReadResponse // 开始到回复阶段
      }
    }
    is(StatesReadResponse) {
      when(grant === GrantLSU) {
        io.lsu.R.RID := rSkidID
        io.lsu.R.RDATA := rSkidData
        io.lsu.R.RRESP := rSkidResp
        io.lsu.R.RLAST := rSkidLast
        io.lsu.R.RVALID := rSkidValid
      }.otherwise {
        io.ifu.R.RID := rSkidID
        io.ifu.R.RDATA := rSkidData
        io.ifu.R.RRESP := rSkidResp
        io.ifu.R.RLAST := rSkidLast
        io.ifu.R.RVALID := rSkidValid
      }
      when(rConsume && rSkidLast) { // 最后一拍从skid被消费才算读事务结束
        state := StatesIdle
      }
    }
    is(StatesWriteRequest) {
      // 看AW和W通道之前有没有已经完成握手
      val AWAlreadyDone = AWDone
      val WAlreadyDone = WDone
      // 必须得没有完成握手应该是怕不会重复握手，后面两个条件就是对标fire
      val AWFire = !AWAlreadyDone && io.lsu.AW.AWVALID && io.memory.AW.AWREADY
      val WFire = !WAlreadyDone && io.lsu.W.WVALID && io.memory.W.WREADY
//只有AW还没完成时才把ysyx_26030103_LSU的AWVALID传递给Memory
      io.memory.AW.AWVALID := io.lsu.AW.AWVALID && !AWAlreadyDone
      io.memory.AW.AWID := io.lsu.AW.AWID
      io.memory.AW.AWADDR := io.lsu.AW.AWADDR
      io.memory.AW.AWLEN := io.lsu.AW.AWLEN
      io.memory.AW.AWSIZE := io.lsu.AW.AWSIZE
      io.memory.AW.AWBURST := io.lsu.AW.AWBURST
      io.memory.AW.AWPROT := io.lsu.AW.AWPROT
      io.lsu.AW.AWREADY := io.memory.AW.AWREADY && !AWAlreadyDone // 也是得AW还没完成
      // 因为如果AW完成了握手，那么valid和ready都得改为false，防止出现重复握手的情况

//一样，得W没完成
      io.memory.W.WVALID := io.lsu.W.WVALID && !WAlreadyDone
      io.memory.W.WDATA := io.lsu.W.WDATA
      io.memory.W.WSTRB := io.lsu.W.WSTRB
      io.memory.W.WLAST := io.lsu.W.WLAST
      io.lsu.W.WREADY := io.memory.W.WREADY && !WAlreadyDone

      when(AWFire) {
        AWDone := true.B
      }
      when(WFire) {
        WDone := true.B
      }
      // 都完成了就直接开始回复
      when((AWAlreadyDone || AWFire) && (WAlreadyDone || WFire)) {
        state := StatesWriteResponse
      }
    }
    is(StatesWriteResponse) {
      io.lsu.B.BID := io.memory.B.BID
      io.lsu.B.BRESP := io.memory.B.BRESP
      io.lsu.B.BVALID := io.memory.B.BVALID
      io.memory.B.BREADY := io.lsu.B.BREADY
      when(io.memory.B.BVALID && io.memory.B.BREADY) { // fire了就开始新轮回
        state := StatesIdle
      }
    }
  }
}
