`include "VerilogHead.vh"
module rom #(
    parameter DATA_WIDTH = 32,
    parameter ADDR_WIDTH = 32,
    parameter DEPTH = 1024
)(
    input wire[ADDR_WIDTH-1:0] addr,
    output reg [DATA_WIDTH-1:0] data
);
    reg [DATA_WIDTH-1:0] rom_mem[0:DEPTH-1];
    initial begin
        $readmemh("rom_data.hex", rom_mem);
    end
endmodule