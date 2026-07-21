module sdram_top_axi(
  input         clock,
  input         reset,
  output        in_awready,
  input         in_awvalid,
  input  [31:0] in_awaddr,  input [3:0] in_awid,    input [7:0] in_awlen,
  input  [2:0]  in_awsize,  input [1:0] in_awburst,
  input         in_awlock,  input [3:0] in_awcache, input [2:0] in_awprot, input [3:0] in_awqos,
  output        in_wready,
  input         in_wvalid,  input [31:0] in_wdata,  input [3:0] in_wstrb, input in_wlast,
  input         in_bready,
  output        in_bvalid,  output [1:0] in_bresp,  output [3:0] in_bid,
  output        in_arready,
  input         in_arvalid, input [31:0] in_araddr, input [3:0] in_arid,
  input  [7:0]  in_arlen,   input [2:0] in_arsize,  input [1:0] in_arburst,
  input         in_arlock,  input [3:0] in_arcache, input [2:0] in_arprot, input [3:0] in_arqos,
  input         in_rready,
  output        in_rvalid,  output [1:0] in_rresp,  output [31:0] in_rdata,
  output        in_rlast,   output [3:0] in_rid,
  output        sdram_clk,  output sdram_cke, output sdram_cs, output sdram_ras, output sdram_cas, output sdram_we,
  output [12:0] sdram_a,    output [1:0] sdram_ba,  output [3:0] sdram_dqm,
  inout  [15:0] sdram_dq_0, inout [15:0] sdram_dq_1
);

  localparam [2:0] S_IDLE=0, S_W_REQ=1, S_W_ACK=2, S_W_RESP=3, S_R_REQ=4, S_R_ACK=5, S_R_RESP=6;
  reg [2:0] state;
  reg [31:0] awaddr_r;  reg [3:0] awid_r;
  reg [31:0] wdata_r;   reg [3:0] wstrb_r;
  reg [3:0] arid_r;     reg [31:0] araddr_r;
  reg [31:0] rdata_r;   reg        rerror_r;

  wire core_accept, core_ack, core_error;
  wire [31:0] core_rdata;

  wire w_go = (state == S_IDLE) && in_awvalid && in_wvalid;
  wire r_go = (state == S_IDLE) && in_arvalid && !w_go;

  assign in_awready = w_go;
  assign in_wready  = w_go;
  assign in_arready = r_go;
  assign in_bvalid  = (state == S_W_RESP);
  assign in_bresp   = rerror_r ? 2'h2 : 2'h0;
  assign in_bid     = awid_r;
  assign in_rvalid  = (state == S_R_RESP);
  assign in_rresp   = rerror_r ? 2'h2 : 2'h0;
  assign in_rdata   = rdata_r;
  assign in_rid     = arid_r;
  assign in_rlast   = 1'b1;

  always @(posedge clock) begin
    if (reset) state <= S_IDLE;
    else case (state)
      S_IDLE: if (w_go) begin
          awaddr_r <= in_awaddr; awid_r <= in_awid;
          wdata_r  <= in_wdata;  wstrb_r <= in_wstrb; rerror_r <= 0;
          state <= S_W_REQ;
        end else if (r_go) begin
          araddr_r <= in_araddr; arid_r <= in_arid; rerror_r <= 0;
          state <= S_R_REQ;
        end
      S_W_REQ: if (core_accept) state <= S_W_ACK;
      S_W_ACK: if (core_ack)    state <= S_W_RESP;
      S_W_RESP: if (in_bready)  state <= S_IDLE;
      S_R_REQ: if (core_accept) state <= S_R_ACK;
      S_R_ACK: if (core_ack) begin
          rdata_r <= core_rdata; state <= S_R_RESP;
        end
      S_R_RESP: if (in_rready) state <= S_IDLE;
      default: state <= S_IDLE;
    endcase
  end

  wire sdram_dout_en;
  wire [31:0] sdram_dout;
  assign sdram_dq_0 = sdram_dout_en ? sdram_dout[15:0]  : 16'bz;
  assign sdram_dq_1 = sdram_dout_en ? sdram_dout[31:16] : 16'bz;

  sdram_axi_core #(.SDRAM_MHZ(100),.SDRAM_ADDR_W(24),.SDRAM_COL_W(9),.SDRAM_READ_LATENCY(2))
  u_core(.clk_i(clock),.rst_i(reset),
    .inport_wr_i((state==S_W_REQ)?wstrb_r:4'b0), .inport_rd_i(state==S_R_REQ),
    .inport_len_i(0), .inport_addr_i((state==S_W_REQ)?awaddr_r:(state==S_R_REQ)?araddr_r:32'h0),
    .inport_write_data_i(wdata_r), .inport_accept_o(core_accept), .inport_ack_o(core_ack),
    .inport_error_o(core_error), .inport_read_data_o(core_rdata),
    .sdram_clk_o(sdram_clk),.sdram_cke_o(sdram_cke),.sdram_cs_o(sdram_cs),.sdram_ras_o(sdram_ras),
    .sdram_cas_o(sdram_cas),.sdram_we_o(sdram_we),.sdram_dqm_o(sdram_dqm),.sdram_addr_o(sdram_a),
    .sdram_ba_o(sdram_ba), .sdram_data_input_i({sdram_dq_1,sdram_dq_0}),
    .sdram_data_output_o(sdram_dout), .sdram_data_out_en_o(sdram_dout_en)
  );
endmodule
