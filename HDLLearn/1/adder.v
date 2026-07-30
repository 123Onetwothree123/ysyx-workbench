// 简单的2输入加法器模块
module adder(
    input wire a,
    input wire b,
    output wire sum,
    output wire cout
);

    // 1位全加器逻辑
    assign sum = a ^ b;
    assign cout = a & b;

endmodule
