`include "VerilogHead.vh"

module debounce (
    input clk,//设定开发板上50MHz系统时钟
    input rst_n,//复位信号
    input btn_in,//原始按键输入
    output btn_out//消抖后的稳定脉冲输出
);
    wire sync_d0, sync_d1;  // 同步器输出
    wire sync_d1_d0;        // 检测边缘延迟
    wire btn_neg_edge;      // 下降沿，按键按下
    wire [19:0] cnt_d;       // 计数器输入
    wire [19:0] cnt_q;       // 计数器输出
    wire cnt_done;           // 计数完成信号
    wire rst_sync;           // 同步复位信号，高电平有效
    wire cnt_clr;            // 计数器清零信号
    wire cnt_inc;            // 计数器递增信号
    assign rst_sync = ~rst_n;
    //第一个DFF接收btn_in的信号
    //第二个DFF是再接过一次，确保消抖
    //第三个DFF的目的是做边缘检测，记住上一次的数据，方面后面比较变化情况
    DFF sync_d0 (
        .clk(clk),
        .rst(rst_sync),
        .din(btn_in),
        .dout(sync_d0),
        .wen(1'b1)          // 始终使能
    );
    DFF sync_d1 (
        .clk(clk),
        .rst(rst_sync),
        .din(sync_d0),
        .dout(sync_d1),
        .wen(1'b1)
    );
    DFF sync_d1_delay (
        .clk(clk),
        .rst(rst_sync),
        .din(sync_d1),
        .dout(sync_d1_d0),
        .wen(1'b1)
    );
    assign btn_neg_edge=sync_d1_d0&~sync_d1;
    wire btn_stable_released = sync_d1 & sync_d1_d0;  // 稳定释放：两个周期都是高电平
    // 只有在按键释放后才清零计数器（允许重新触发）
    // triggered 只阻止计数递增，不清零计数器
    assign cnt_clr=rst_sync|sync_d1;// 如果按键是高电平（未按下），就清零计数器
    assign cnt_inc = ~sync_d1 & ~cnt_done & ~triggered;// 如果按键是低电平（按下）且未计满且未触发过，就累加
    assign cnt_d[0]  = cnt_clr ? 1'b0 : (cnt_inc ? ~cnt_q[0]  : cnt_q[0]);
    assign cnt_d[1]  = cnt_clr ? 1'b0 : (cnt_inc ? (cnt_q[0]  ? ~cnt_q[1]  : cnt_q[1])  : cnt_q[1]);
    assign cnt_d[2]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1])  ? ~cnt_q[2]  : cnt_q[2])  : cnt_q[2]);
    assign cnt_d[3]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2])  ? ~cnt_q[3]  : cnt_q[3])  : cnt_q[3]);
    assign cnt_d[4]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3])  ? ~cnt_q[4]  : cnt_q[4])  : cnt_q[4]);
    assign cnt_d[5]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4])  ? ~cnt_q[5]  : cnt_q[5])  : cnt_q[5]);
    assign cnt_d[6]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5])  ? ~cnt_q[6]  : cnt_q[6])  : cnt_q[6]);
    assign cnt_d[7]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6])  ? ~cnt_q[7]  : cnt_q[7])  : cnt_q[7]);
    assign cnt_d[8]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7])  ? ~cnt_q[8]  : cnt_q[8])  : cnt_q[8]);
    assign cnt_d[9]  = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8])  ? ~cnt_q[9]  : cnt_q[9])  : cnt_q[9]);
    assign cnt_d[10] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9])  ? ~cnt_q[10] : cnt_q[10]) : cnt_q[10]);
    assign cnt_d[11] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10]) ? ~cnt_q[11] : cnt_q[11]) : cnt_q[11]);
    assign cnt_d[12] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11]) ? ~cnt_q[12] : cnt_q[12]) : cnt_q[12]);
    assign cnt_d[13] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11] & cnt_q[12]) ? ~cnt_q[13] : cnt_q[13]) : cnt_q[13]);
    assign cnt_d[14] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11] & cnt_q[12] & cnt_q[13]) ? ~cnt_q[14] : cnt_q[14]) : cnt_q[14]);
    assign cnt_d[15] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11] & cnt_q[12] & cnt_q[13] & cnt_q[14]) ? ~cnt_q[15] : cnt_q[15]) : cnt_q[15]);
    assign cnt_d[16] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11] & cnt_q[12] & cnt_q[13] & cnt_q[14] & cnt_q[15]) ? ~cnt_q[16] : cnt_q[16]) : cnt_q[16]);
    assign cnt_d[17] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11] & cnt_q[12] & cnt_q[13] & cnt_q[14] & cnt_q[15] & cnt_q[16]) ? ~cnt_q[17] : cnt_q[17]) : cnt_q[17]);
    assign cnt_d[18] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11] & cnt_q[12] & cnt_q[13] & cnt_q[14] & cnt_q[15] & cnt_q[16] & cnt_q[17]) ? ~cnt_q[18] : cnt_q[18]) : cnt_q[18]);
    assign cnt_d[19] = cnt_clr ? 1'b0 : (cnt_inc ? ((cnt_q[0]  & cnt_q[1]  & cnt_q[2]  & cnt_q[3]  & cnt_q[4]  & cnt_q[5]  & cnt_q[6]  & cnt_q[7]  & cnt_q[8]  & cnt_q[9]  & cnt_q[10] & cnt_q[11] & cnt_q[12] & cnt_q[13] & cnt_q[14] & cnt_q[15] & cnt_q[16] & cnt_q[17] & cnt_q[18]) ? ~cnt_q[19] : cnt_q[19]) : cnt_q[19]);
    DFF u_cnt0  (.clk(clk), .rst(rst_sync), .din(cnt_d[0]),  .dout(cnt_q[0]),  .wen(1'b1));
    DFF u_cnt1  (.clk(clk), .rst(rst_sync), .din(cnt_d[1]),  .dout(cnt_q[1]),  .wen(1'b1));
    DFF u_cnt2  (.clk(clk), .rst(rst_sync), .din(cnt_d[2]),  .dout(cnt_q[2]),  .wen(1'b1));
    DFF u_cnt3  (.clk(clk), .rst(rst_sync), .din(cnt_d[3]),  .dout(cnt_q[3]),  .wen(1'b1));
    DFF u_cnt4  (.clk(clk), .rst(rst_sync), .din(cnt_d[4]),  .dout(cnt_q[4]),  .wen(1'b1));
    DFF u_cnt5  (.clk(clk), .rst(rst_sync), .din(cnt_d[5]),  .dout(cnt_q[5]),  .wen(1'b1));
    DFF u_cnt6  (.clk(clk), .rst(rst_sync), .din(cnt_d[6]),  .dout(cnt_q[6]),  .wen(1'b1));
    DFF u_cnt7  (.clk(clk), .rst(rst_sync), .din(cnt_d[7]),  .dout(cnt_q[7]),  .wen(1'b1));
    DFF u_cnt8  (.clk(clk), .rst(rst_sync), .din(cnt_d[8]),  .dout(cnt_q[8]),  .wen(1'b1));
    DFF u_cnt9  (.clk(clk), .rst(rst_sync), .din(cnt_d[9]),  .dout(cnt_q[9]),  .wen(1'b1));
    DFF u_cnt10 (.clk(clk), .rst(rst_sync), .din(cnt_d[10]), .dout(cnt_q[10]), .wen(1'b1));
    DFF u_cnt11 (.clk(clk), .rst(rst_sync), .din(cnt_d[11]), .dout(cnt_q[11]), .wen(1'b1));
    DFF u_cnt12 (.clk(clk), .rst(rst_sync), .din(cnt_d[12]), .dout(cnt_q[12]), .wen(1'b1));
    DFF u_cnt13 (.clk(clk), .rst(rst_sync), .din(cnt_d[13]), .dout(cnt_q[13]), .wen(1'b1));
    DFF u_cnt14 (.clk(clk), .rst(rst_sync), .din(cnt_d[14]), .dout(cnt_q[14]), .wen(1'b1));
    DFF u_cnt15 (.clk(clk), .rst(rst_sync), .din(cnt_d[15]), .dout(cnt_q[15]), .wen(1'b1));
    DFF u_cnt16 (.clk(clk), .rst(rst_sync), .din(cnt_d[16]), .dout(cnt_q[16]), .wen(1'b1));
    DFF u_cnt17 (.clk(clk), .rst(rst_sync), .din(cnt_d[17]), .dout(cnt_q[17]), .wen(1'b1));
    DFF u_cnt18 (.clk(clk), .rst(rst_sync), .din(cnt_d[18]), .dout(cnt_q[18]), .wen(1'b1));
    DFF u_cnt19 (.clk(clk), .rst(rst_sync), .din(cnt_d[19]), .dout(cnt_q[19]), .wen(1'b1));
    assign cnt_done = (cnt_q == 20'h0F423F);
    // 延迟cnt_done一个周期，用于检测上升沿
    wire cnt_done_d0;
    DFF u_cnt_done_delay (
        .clk(clk),
        .rst(rst_sync),
        .din(cnt_done),
        .dout(cnt_done_d0),
        .wen(1'b1)
    );
    // 触发后锁定机制：产生脉冲后，必须等待按键释放才能再次触发
    // 这样可以防止长按期间重复触发，也确保每次按键只触发一次
    reg triggered;
    always @(posedge clk or posedge rst_sync) begin
        if (rst_sync)
            triggered <= 1'b0;
        else if (cnt_done & ~cnt_done_d0)
            triggered <= 1'b1;
        else if (btn_stable_released)  // 只在稳定释放（连续两个周期高电平）时复位triggered
            triggered <= 1'b0;
    end
    // 只在cnt_done上升沿且按键仍按下时才产生单周期脉冲
    assign btn_out = cnt_done & ~cnt_done_d0 & ~sync_d1;
endmodule