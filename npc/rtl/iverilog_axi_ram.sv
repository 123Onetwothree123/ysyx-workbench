module iverilog_axi_ram #(
    parameter MEM_FILE  = "program_iverilog.hex",
    parameter BASE_ADDR = 32'h8000_0000,
    parameter DEPTH     = 262144 // 1MB = 256K words
) (
    input          clock,
    input          reset,
    input          awvalid,
    output         awready,
    input  [31:0]  awaddr,
    input          wvalid,
    output         wready,
    input  [31:0]  wdata,
    input  [ 3:0]  wstrb,
    output         bvalid,
    input          bready,
    output [ 1:0]  bresp,
    input          arvalid,
    output         arready,
    input  [31:0]  araddr,
    output         rvalid,
    input          rready,
    output [31:0]  rdata,
    output [ 1:0]  rresp,
    output         rlast
);
    reg [31:0] mem [0:DEPTH-1];

    reg [31:0] rd_addr;
    reg        rd_active;
    reg        rd_resp;
    reg        wr_aw_active;
    reg        wr_active;
    reg [31:0] wr_addr;
    reg [31:0] wr_data;
    reg [ 3:0] wr_strb;
    reg        wr_bresp;

    initial begin
        $readmemh(MEM_FILE, mem);
    end

    // AR channel
    assign arready = !rd_active && !wr_aw_active && !wr_active && !wr_bresp;
    always @(posedge clock) begin
        if (reset) begin
            rd_active <= 0;
        end else if (arvalid && arready) begin
            rd_addr   <= (araddr - BASE_ADDR) >> 2;
            rd_active <= 1;
            rd_resp   <= 0;
        end else if (rd_active) begin
            rd_resp <= 1;
            if (rd_resp && rready) begin
                rd_active <= 0;
            end
        end
    end

    // R channel
    assign rvalid = rd_resp;
    assign rdata  = (rd_addr < DEPTH) ? mem[rd_addr] : 32'h0000_0013;
    assign rresp  = 2'b00;
    assign rlast  = 1'b1;

    // AW channel
    assign awready = !rd_active && !wr_aw_active && !wr_active && !wr_bresp;
    always @(posedge clock) begin
        if (reset) begin
            wr_aw_active <= 0;
        end else if (awvalid && awready) begin
            wr_addr      <= (awaddr - BASE_ADDR) >> 2;
            wr_aw_active <= 1;
        end else if (wr_aw_active && wvalid) begin
            wr_aw_active <= 0;
            wr_active    <= 1;
        end
    end

    // W channel
    assign wready = wr_aw_active;
    always @(posedge clock) begin
        if (reset) begin
            wr_active <= 0;
        end else if (wr_aw_active && wvalid) begin
            wr_data  <= wdata;
            wr_strb  <= wstrb;
            wr_active  <= 1;
        end else if (wr_active) begin
            if (wr_addr < DEPTH) begin
                if (wr_strb[0]) mem[wr_addr][ 7: 0] <= wr_data[ 7: 0];
                if (wr_strb[1]) mem[wr_addr][15: 8] <= wr_data[15: 8];
                if (wr_strb[2]) mem[wr_addr][23:16] <= wr_data[23:16];
                if (wr_strb[3]) mem[wr_addr][31:24] <= wr_data[31:24];
            end
            wr_active <= 0;
            wr_bresp  <= 1;
        end
    end

    // B channel
    assign bvalid = wr_bresp;
    assign bresp  = 2'b00;
    always @(posedge clock) begin
        if (reset) begin
            wr_bresp <= 0;
        end else if (wr_bresp && bready) begin
            wr_bresp <= 0;
        end
    end
endmodule
