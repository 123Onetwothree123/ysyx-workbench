`include "VerilogHead.vh"
//==============================================================================
// 模块名称: scan_code_parser
// 功能描述: PS/2键盘扫描码解析器
//           解析PS/2协议中的通码(Make Code)和断码(Break Code)
//           输出按键按下状态、当前扫描码和按下脉冲
// PS/2协议说明:
//   - 按键按下: 发送通码 (如'A'键通码为0x1C)
//   - 按键释放: 先发送0xF0(断码前缀),再发送通码 (0xF0, 0x1C)
//==============================================================================

module scan_code_parser(
    input clk,                  // 系统时钟
    input rst_n,                // 低电平复位
    input [7:0] ps2_data,       // PS/2扫描码输入(来自ps2_keyboard模块)
    input ps2_ready,            // 数据有效标志(高电平脉冲,表示ps2_data有效)
    output reg [7:0] scan_code, // 当前按键的扫描码(通码)
    output reg key_pressed,     // 按键按下标志(持续高电平直到释放)
    output key_pressed_pulse    // 按键按下瞬间单周期脉冲(用于计数器触发)
);

//==============================================================================
// 状态机参数定义
//==============================================================================
localparam IDLE      = 2'b00;  // 空闲状态: 等待按键按下
localparam KEY_DOWN  = 2'b01;  // 按键按下状态: 已收到通码,等待断码前缀F0
localparam WAIT_F0   = 2'b10;  // 等待断码状态: 已收到F0,等待断码字节

// 状态寄存器定义
reg [1:0] state;               // 当前状态
reg [1:0] next_state;          // 次态(组合逻辑输出)

// 内部寄存器定义
reg key_pressed_d;             // key_pressed的延迟,用于检测上升沿产生脉冲

//==============================================================================
// 状态机组合逻辑: 根据当前状态和输入决定次态
// 状态转移条件:
//   IDLE -> KEY_DOWN: 收到ps2_ready且数据不是F0(断码前缀),表示新按键按下
//   KEY_DOWN -> WAIT_F0: 收到ps2_ready且数据是F0,表示按键开始释放
//   WAIT_F0 -> IDLE: 收到ps2_ready且断码匹配当前scan_code,按键完全释放
//   WAIT_F0 -> KEY_DOWN: 断码不匹配(异常情况),回到按下状态
//==============================================================================
always @(*) begin
    next_state = state;  // 默认保持当前状态
    case (state)
        IDLE: begin
            // 空闲状态: 当ps2_ready有效且收到非F0数据时,表示按键按下
            if (ps2_ready && ps2_data != 8'hF0)
                next_state = KEY_DOWN;  // 收到通码,进入按下状态
        end
        
        KEY_DOWN: begin
            // 按键按下状态: 等待断码前缀F0
            if (ps2_ready && ps2_data == 8'hF0)
                next_state = WAIT_F0;   // 收到F0断码前缀,等待断码字节
        end
        
        WAIT_F0: begin
            // 等待断码状态: 下一个字节应该是通码(断码)
            if (ps2_ready) begin
                if (ps2_data == scan_code)
                    next_state = IDLE;      // 断码与当前扫描码匹配,按键释放
                else
                    next_state = KEY_DOWN;  // 断码不匹配(异常情况),保持按下
            end
        end
        
        default: next_state = IDLE;  // 异常状态,回到空闲
    endcase
end

//==============================================================================
// 状态寄存器: 时序逻辑,在ps2_ready有效时更新状态
//==============================================================================
always @(posedge clk or negedge rst_n) begin
    if (!rst_n)
        state <= IDLE;               // 复位时回到空闲状态
    else if (ps2_ready)              // 只在ps2_ready脉冲有效时更新状态
        state <= next_state;         // 转移到次态
end

//==============================================================================
// 输出逻辑: 产生scan_code和key_pressed信号
// scan_code: 在IDLE状态收到通码时锁存,一直保持到下一次按键
// key_pressed: 在IDLE状态置1,在WAIT_F0状态匹配断码后置0
//==============================================================================
always @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
        scan_code <= 8'h00;          // 复位时清空扫描码
        key_pressed <= 1'b0;         // 复位时标记为未按下
    end else if (ps2_ready) begin    // 只在ps2_ready有效时更新输出
        case (state)
            IDLE: begin
                // 空闲状态收到通码: 锁存扫描码,标记按键按下
                if (ps2_data != 8'hF0) begin
                    scan_code <= ps2_data;  // 保存当前按键的扫描码
                    key_pressed <= 1'b1;    // 置起按下标志
                end
            end
            
            WAIT_F0: begin
                // 等待断码状态: 检查断码是否匹配
                if (ps2_data == scan_code)
                    key_pressed <= 1'b0;    // 断码匹配,清除按下标志
                // 断码不匹配时不处理(异常情况)
            end
            
            // KEY_DOWN状态: 保持当前输出,等待F0
            default: ;
        endcase
    end
end

//==============================================================================
// 脉冲产生逻辑: 检测key_pressed的上升沿,产生单周期脉冲
// key_pressed_pulse用于触发key_counter计数,确保按住不放只算一次
//==============================================================================
always @(posedge clk or negedge rst_n) begin
    if (!rst_n)
        key_pressed_d <= 1'b0;       // 复位时清零
    else
        key_pressed_d <= key_pressed; // 延迟一拍,用于边沿检测
end

// 上升沿检测: 当前为1且上一拍为0时,产生单周期脉冲
assign key_pressed_pulse = key_pressed && !key_pressed_d;

endmodule