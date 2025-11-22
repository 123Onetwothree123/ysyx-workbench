`include "VerilogHead.vh"
module rom8bit #(
    parameter DATA_WIDTH = 8,
    parameter ADDR_WIDTH = 4,
    parameter DEPTH = 16
)(
    input wire [ADDR_WIDTH-1:0] addr,
    output reg [DATA_WIDTH-1:0] data
);
    reg [DATA_WIDTH-1:0] rom_mem [0:DEPTH-1];
    
    initial begin
        $readmemh("rom_data.hex", rom_mem);
    end
    
    always @(*) begin
        data = rom_mem[addr];
    end
endmodule