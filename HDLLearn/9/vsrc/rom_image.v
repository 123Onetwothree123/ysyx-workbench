module vga_rom (
    input clk,
    input [15:0] addr,
    output reg [23:0] dout
);
    reg [23:0] mem [0:65535];
    initial begin
        $readmemh("resource/picture.hex", mem);
    end
    always @(posedge clk) begin
        dout <= mem[addr];
    end
endmodule