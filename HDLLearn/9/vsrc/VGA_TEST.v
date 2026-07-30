module VGA_TEST (
    input clk,    // 系统时钟 50MHz
    input rst_n,  // 复位 (SW0)
    output [7:0] VGA_R,
    output [7:0] VGA_G,
    output [7:0] VGA_B,
    output VGA_HSYNC,
    output VGA_VSYNC,
    output VGA_BLANK_N,
    output LED
);

    wire [9:0] h_addr;
    wire [9:0] v_addr;
    wire [23:0] vga_data;
    wire [15:0] rom_addr;
    wire [23:0] rom_dout;

    vga_ctrl my_vga_ctrl(
        .pclk(clk),
        .reset(!rst_n),
        .vga_data(vga_data),
        .h_addr(h_addr),
        .v_addr(v_addr),
        .hsync(VGA_HSYNC),
        .vsync(VGA_VSYNC),
        .valid(VGA_BLANK_N),
        .vga_r(VGA_R),
        .vga_g(VGA_G),
        .vga_b(VGA_B)
    );

    // ROM 图片 (256x256)
    assign rom_addr = {v_addr[7:0], h_addr[7:0]};
    vga_rom my_rom (
        .clk(clk),
        .addr(rom_addr),
        .dout(rom_dout)
    );

    // 在左上角 256x256 区域显示图片，其他区域显示彩条背景
    wire is_in_image = (h_addr < 10'd256) && (v_addr < 10'd256);
    wire [23:0] back_color = (h_addr < 10'd320) ? 24'h1E1E1E : 24'h000000;
    
    assign vga_data = is_in_image ? rom_dout : back_color;

    // LED 显示 vsync 状态
    assign LED = VGA_VSYNC;

endmodule
