`include "VerilogHead.vh"

module MuxKeyInternal #(
    parameter NR_KEY = 16,      // 键值对数量
    parameter KEY_LEN = 4,      // 键的位宽
    parameter DATA_LEN = 7,     // 数据的位宽
    parameter HAS_DEFAULT = 0   // 是否有默认输出
)(
    input wire [KEY_LEN-1:0] key,          // 输入键
    input wire [(NR_KEY*(KEY_LEN+DATA_LEN))-1:0] lut,  // 查找表
    input wire [DATA_LEN-1:0] default_out, // 默认输出
    output reg [DATA_LEN-1:0] out          // 输出数据
);

    integer i;
    reg found;

    always @(*) begin
        found = 0;
        out = HAS_DEFAULT ? default_out : {DATA_LEN{1'b0}};
        
        for (i = 0; i < NR_KEY; i = i + 1) begin
            if (lut[i*(KEY_LEN+DATA_LEN) +: KEY_LEN] == key) begin
                out = lut[i*(KEY_LEN+DATA_LEN) + KEY_LEN +: DATA_LEN];
                found = 1;
            end
        end
    end

endmodule
