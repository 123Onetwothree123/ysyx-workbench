module ROM_DPI_C (
    input  [31:0] Address,
    output [31:0] ReadDATA
);
import "DPI-C" function int pmem_read(input int raddr);
reg [31:0] rdata;
assign ReadDATA=rdata;
always@(*)begin
    rdata=pmem_read(Address);
end
endmodule