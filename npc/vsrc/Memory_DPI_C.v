module Memory_DPI_C(
    input clk,
    //当前周期是否真的发起了一次数据存储器访问请求，1是本周起LSU进行load或者store，0是本周起没有数据访存需求，就忽略
    //掉其他访存输入信号
    input valid,
    input wen,//0是读，1是写
    /*
    目前的设计思路是当执行 load 指令时，用这个地址去调用 pmem_read函数，然后按课程讲的，C那边通常会自动按4字节对齐处理:
    所以实际读取的是raddr & ~32'h3所在的那个32位字
    讲义：我们在这两个内存读写函数中模拟了32位总线的行为: 它们只支持地址按4字节对齐的读写, 其中
    读操作总是返回按4字节对齐读出的数据, 需要由RTL代码根据读地址选择出需要的部分. 这样是为了将
    来在实现总线的时候不必改动太多的代码. 你需要在Verilog代码中为这两个函数的调用传入正确的参
    数, 并在C++代码中实现这两个函数的功能. 对于取指, 你需要删除之前把信号拉到顶层的实现, 然后额
    外调用一次pmem_read()来实现它.
    */
    input  [31:0] raddr,
    input  [31:0] waddr,
    input  [31:0] wdata,
    input  [3:0]  wmask,//字节写掩码，先标记，这是字节
    output reg [31:0] rdata
);
import "DPI-C" function int pmem_read(input int raddr);
import "DPI-C" function void pmem_write(
  input int waddr, input int wdata, input byte wmask);
always @(*) begin
  if (valid) begin // 有读写请求时
    rdata = pmem_read(raddr);
  end
  else begin
    rdata = 0;
  end
end

always @(posedge clk) begin
  if (valid && wen) begin
    pmem_write(waddr, wdata, {4'b0, wmask});
  end
end
endmodule
