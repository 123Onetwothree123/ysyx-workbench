`include "VerilogHead.vh"
module ps2_keyboard(clk,clrn,ps2_clk,ps2_data,data,
                    ready,nextdata_n,overflow);
    /*
    clk是系统时钟
    clrn是异步复位信号，低有效
    ps2_clk是PS/2接口时钟，来自键盘
    ps2_data是PS/2接口数据，来自键盘
    nextdata_n是读下一个数据请求，低有效
    */
    input clk,clrn,ps2_clk,ps2_data;
    input nextdata_n;
    output [7:0] data;//当前FIFO读指针指向的1字节扫描码
    output reg ready;//FIFO非空标志，1表示有数据可读
    //FIFO溢出标志，写指针追上读指针，满了还写
    output reg overflow;     // fifo overflow
    // internal signal, for test
    /*
    buffer是临时缓存，保存start+8data+parity 共10位（stop位用当前ps2_data判断）
    buffer[0]是start bit，应为0
    buffer[8:1]，8bit数据，因为是LSB先来，所以先到的是bit0，然后放在buffer[1]
    buffer[9]是parity bit奇校验位
    */
    reg [9:0] buffer;        // ps2_data bits
    //8深度FIFO，存放接收到的扫描码
    reg [7:0] fifo[7:0];     // data fifo
    //FIFO的写指针和读指针
    reg [2:0] w_ptr,r_ptr;   // fifo write and read pointers
    //记录当前已经采样了多少位
    reg [3:0] count;  // count ps2_data bits
    // detect falling edge of ps2_clk
    reg [2:0] ps2_clk_sync;//用于消除亚稳态和检测边沿的，PS/2时钟是异步输入

    always @(posedge clk) begin
        //每个clk上升沿采样一次ps2_clk，形成同步后的历史序列，ps2_clk_sync[2]最旧，ps2_clk_sync[0]最新
        ps2_clk_sync <=  {ps2_clk_sync[1:0],ps2_clk};
    end

    wire sampling = ps2_clk_sync[2] & ~ps2_clk_sync[1];//sampling=1表示检测到ps2_clk的下降沿，即上一刻为1，这一刻为0
    //复位、读FIFO、按ps2_clk下降沿采样数据
    always @(posedge clk) begin
        //复位信号被激活，低的
        if (clrn == 0) begin // reset
            count <= 0; w_ptr <= 0; r_ptr <= 0; overflow <= 0; ready<= 0;
        end
        else begin
            //读FIFO
            //ready=1的时候是表示FIFO非空，允许读
            if ( ready ) begin // read to output next data
                //nextdata_n=0的时候则是表示外部请求读下一字节
                if(nextdata_n == 1'b0) //read next data
                begin
                    r_ptr <= r_ptr + 3'b1;//读下一个，指针+1
                    //如果读完之后，读指针追上写指针，表示空了，那么就会将ready拉低
                    //如果读指针移动后等于写指针，说明FIFO变空，因为r_ptr是非阻塞赋值，表达式里用r_ptr+1来预测下一位置
                    if(w_ptr==(r_ptr+1'b1)) //empty
                        ready <= 1'b0;
                end
            end
            //采样PS/2数据
            if (sampling) begin
                //start（1位） + data（8位） + parity（1位） + stop（1位）
                //start/data/parity共10位存入buffer[0..9]
                //stop位不存到buffer，用当前时刻的ps2_data来判断
              if (count == 4'd10) begin

                if ((buffer[0] == 0) &&  // start bit
                    //stop bit=1，
                    (ps2_data)       &&  // stop bit
                    //^buffer[9:1]为奇校验结果为1（即9位数据（8位数据+奇偶位）中1的个数为奇数）
                    (^buffer[9:1])) begin      // odd  parity
                    //把8bit数据写入FIFO
                    //扫描码（8bit=1Byte）
                    fifo[w_ptr] <= buffer[8:1];  // kbd scan code
                    w_ptr <= w_ptr+3'b1;//写入指针+1
                    ready <= 1'b1;//启动ready信号，允许读数据，FIFO非空
                    //如果写指针和读指针匹配了，证明FIFO满了，直接置位overflow
                    overflow <= overflow | (r_ptr == (w_ptr + 3'b1));
                end
                //重置计数器，准备接收下一帧数据
                count <= 0;     // for next
              end else begin
                //如果还没到第10位，那么就继续把当前获取到的ps2_data存入buffer，根据计数器，count从0开始
                buffer[count] <= ps2_data;  // store ps2_data
                count <= count + 3'b1;//计数器加1，正常迭代
              end
            end
        end
    end
    //直接输出FIFO中读指针位置的数据，如果ready=1表示data有效，nextdata_n拉低会让r_ptr前进到下一字节
    assign data = fifo[r_ptr]; //always set output data

endmodule
