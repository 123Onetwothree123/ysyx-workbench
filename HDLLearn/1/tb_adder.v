// 加法器模块的测试台
`timescale 1ns / 1ps

module tb_adder;

    // 声明测试信号
    reg a;
    reg b;
    wire sum;
    wire cout;

    // 实例化被测模块
    adder uut(
        .a(a),
        .b(b),
        .sum(sum),
        .cout(cout)
    );

    // 测试过程
    initial begin
        // 显示测试开始
        $display("========================================");
        $display("加法器测试开始");
        $display("========================================");
        
        // 初始化输入
        a = 0;
        b = 0;
        
        // 测试所有可能的输入组合 (00, 01, 10, 11)
        #10;
        $display("时间 = %0t ns: a=%b, b=%b, sum=%b, cout=%b", $time, a, b, sum, cout);
        
        a = 0; b = 1;
        #10;
        $display("时间 = %0t ns: a=%b, b=%b, sum=%b, cout=%b", $time, a, b, sum, cout);
        
        a = 1; b = 0;
        #10;
        $display("时间 = %0t ns: a=%b, b=%b, sum=%b, cout=%b", $time, a, b, sum, cout);
        
        a = 1; b = 1;
        #10;
        $display("时间 = %0t ns: a=%b, b=%b, sum=%b, cout=%b", $time, a, b, sum, cout);
        
        // 显示测试结束
        $display("========================================");
        $display("加法器测试完成");
        $display("========================================");
        
        // 结束仿真
        $finish;
    end

endmodule
