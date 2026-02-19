`include "VerilogHead.vh"
module gpr(
    input clk,
    input[31:0] wdata,
    input[4:0] WriteSELECT,
    input[4:0] Read1SELECT,
    input[4:0] Read2SELECT,
    input WriteEN,
    output[31:0] ReadDATA1,
    output[31:0] ReadDATA2
);
    wire [31:0] reg_dout0;
    wire [31:0] reg_dout1;
    wire [31:0] reg_dout2;
    wire [31:0] reg_dout3;
    wire [31:0] reg_dout4;
    wire [31:0] reg_dout5;
    wire [31:0] reg_dout6;
    wire [31:0] reg_dout7;
    wire [31:0] reg_dout8;
    wire [31:0] reg_dout9;
    wire [31:0] reg_dout10;
    wire [31:0] reg_dout11;
    wire [31:0] reg_dout12;
    wire [31:0] reg_dout13;
    wire [31:0] reg_dout14;
    wire [31:0] reg_dout15;
    wire [31:0] reg_dout16;
    wire [31:0] reg_dout17;
    wire [31:0] reg_dout18;
    wire [31:0] reg_dout19;
    wire [31:0] reg_dout20;
    wire [31:0] reg_dout21;
    wire [31:0] reg_dout22;
    wire [31:0] reg_dout23;
    wire [31:0] reg_dout24;
    wire [31:0] reg_dout25;
    wire [31:0] reg_dout26;
    wire [31:0] reg_dout27;
    wire [31:0] reg_dout28;
    wire [31:0] reg_dout29;
    wire [31:0] reg_dout30;
    wire [31:0] reg_dout31;
    assign reg_dout0 = 0;
    assign reg_dout1 = 0;
    assign reg_dout2 = 0;
    assign reg_dout3 = 0;
    assign reg_dout4 = 0;
    assign reg_dout5 = 0;
    assign reg_dout6 = 0;
    assign reg_dout7 = 0;
    assign reg_dout8 = 0;
    assign reg_dout9 = 0;
    assign reg_dout10 = 0;
    assign reg_dout11 = 0;
    assign reg_dout12 = 0;
    assign reg_dout13 = 0;
    assign reg_dout14 = 0;
    assign reg_dout15 = 0;
    assign reg_dout16 = 0;
    assign reg_dout17 = 0;
    assign reg_dout18 = 0;
    assign reg_dout19 = 0;
    assign reg_dout20 = 0;
    assign reg_dout21 = 0;
    assign reg_dout22 = 0;
    assign reg_dout23 = 0;
    assign reg_dout24 = 0;
    assign reg_dout25 = 0;
    assign reg_dout26 = 0;
    assign reg_dout27 = 0;
    assign reg_dout28 = 0;
    assign reg_dout29 = 0;
    assign reg_dout30 = 0;
    assign reg_dout31 = 0;
    Reg #(32, 0) gpr_reg0 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout0),  .wen(0 && (WriteSELECT == 5'd0)));
    Reg #(32, 0) gpr_reg1 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout1),  .wen(WriteEN && (WriteSELECT == 5'd1)));
    Reg #(32, 0) gpr_reg2 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout2),  .wen(WriteEN && (WriteSELECT == 5'd2)));
    Reg #(32, 0) gpr_reg3 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout3),  .wen(WriteEN && (WriteSELECT == 5'd3)));
    Reg #(32, 0) gpr_reg4 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout4),  .wen(WriteEN && (WriteSELECT == 5'd4)));
    Reg #(32, 0) gpr_reg5 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout5),  .wen(WriteEN && (WriteSELECT == 5'd5)));
    Reg #(32, 0) gpr_reg6 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout6),  .wen(WriteEN && (WriteSELECT == 5'd6)));
    Reg #(32, 0) gpr_reg7 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout7),  .wen(WriteEN && (WriteSELECT == 5'd7)));
    Reg #(32, 0) gpr_reg8 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout8),  .wen(WriteEN && (WriteSELECT == 5'd8)));
    Reg #(32, 0) gpr_reg9 (.clk(clk), .rst(0), .din(wdata), .dout(reg_dout9),  .wen(WriteEN && (WriteSELECT == 5'd9)));
    Reg #(32, 0) gpr_reg10(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout10), .wen(WriteEN && (WriteSELECT == 5'd10)));
    Reg #(32, 0) gpr_reg11(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout11), .wen(WriteEN && (WriteSELECT == 5'd11)));
    Reg #(32, 0) gpr_reg12(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout12), .wen(WriteEN && (WriteSELECT == 5'd12)));
    Reg #(32, 0) gpr_reg13(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout13), .wen(WriteEN && (WriteSELECT == 5'd13)));
    Reg #(32, 0) gpr_reg14(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout14), .wen(WriteEN && (WriteSELECT == 5'd14)));
    Reg #(32, 0) gpr_reg15(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout15), .wen(WriteEN && (WriteSELECT == 5'd15)));
    Reg #(32, 0) gpr_reg16(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout16), .wen(WriteEN && (WriteSELECT == 5'd16)));
    Reg #(32, 0) gpr_reg17(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout17), .wen(WriteEN && (WriteSELECT == 5'd17)));
    Reg #(32, 0) gpr_reg18(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout18), .wen(WriteEN && (WriteSELECT == 5'd18)));
    Reg #(32, 0) gpr_reg19(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout19), .wen(WriteEN && (WriteSELECT == 5'd19)));
    Reg #(32, 0) gpr_reg20(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout20), .wen(WriteEN && (WriteSELECT == 5'd20)));
    Reg #(32, 0) gpr_reg21(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout21), .wen(WriteEN && (WriteSELECT == 5'd21)));
    Reg #(32, 0) gpr_reg22(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout22), .wen(WriteEN && (WriteSELECT == 5'd22)));
    Reg #(32, 0) gpr_reg23(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout23), .wen(WriteEN && (WriteSELECT == 5'd23)));
    Reg #(32, 0) gpr_reg24(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout24), .wen(WriteEN && (WriteSELECT == 5'd24)));
    Reg #(32, 0) gpr_reg25(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout25), .wen(WriteEN && (WriteSELECT == 5'd25)));
    Reg #(32, 0) gpr_reg26(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout26), .wen(WriteEN && (WriteSELECT == 5'd26)));
    Reg #(32, 0) gpr_reg27(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout27), .wen(WriteEN && (WriteSELECT == 5'd27)));
    Reg #(32, 0) gpr_reg28(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout28), .wen(WriteEN && (WriteSELECT == 5'd28)));
    Reg #(32, 0) gpr_reg29(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout29), .wen(WriteEN && (WriteSELECT == 5'd29)));
    Reg #(32, 0) gpr_reg30(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout30), .wen(WriteEN && (WriteSELECT == 5'd30)));
    Reg #(32, 0) gpr_reg31(.clk(clk), .rst(0), .din(wdata), .dout(reg_dout31), .wen(WriteEN && (WriteSELECT == 5'd31)));
endmodule
