module sdram_top_axi(
  input         clock,
  input         reset,
  output        in_awready,
  input         in_awvalid,
  input  [31:0] in_awaddr,
  input  [3:0]  in_awid,
  input  [7:0]  in_awlen,
  input  [2:0]  in_awsize,
  input  [1:0]  in_awburst,
  input         in_awlock,
  input  [3:0]  in_awcache,
  input  [2:0]  in_awprot,
  input  [3:0]  in_awqos,
  output        in_wready,
  input         in_wvalid,
  input  [31:0] in_wdata,
  input  [3:0]  in_wstrb,
  input         in_wlast,
  input         in_bready,
  output        in_bvalid,
  output [1:0]  in_bresp,
  output [3:0]  in_bid,
  output        in_arready,
  input         in_arvalid,
  input  [31:0] in_araddr,
  input  [3:0]  in_arid,
  input  [7:0]  in_arlen,
  input  [2:0]  in_arsize,
  input  [1:0]  in_arburst,
  input         in_arlock,
  input  [3:0]  in_arcache,
  input  [2:0]  in_arprot,
  input  [3:0]  in_arqos,
  input         in_rready,
  output        in_rvalid,
  output [1:0]  in_rresp,
  output [31:0] in_rdata,
  output        in_rlast,
  output [3:0]  in_rid,

  output        sdram_clk,
  output        sdram_cke,
  output        sdram_cs,
  output        sdram_ras,
  output        sdram_cas,
  output        sdram_we,
  output [12:0] sdram_a,
  output [ 1:0] sdram_ba,
  output [ 3:0] sdram_dqm,
  inout  [15:0] sdram_dq_0,
  inout  [15:0] sdram_dq_1
);

  wire sdram_dout_en;
  wire [31:0] sdram_dout;
  assign sdram_dq_0 = sdram_dout_en ? sdram_dout[15:0]  : 16'bz;
  assign sdram_dq_1 = sdram_dout_en ? sdram_dout[31:16] : 16'bz;

  wire [31:0] ram_addr_w;
  wire [ 3:0] ram_wr_w;
  wire        ram_rd_w;
  wire        ram_accept_w;
  wire [31:0] ram_write_data_w;
  wire [31:0] ram_read_data_w;
  wire [ 7:0] ram_len_w;
  wire        ram_ack_w;
  wire        ram_error_w;

  sdram_axi_pmem
  u_axi
  (
      .clk_i(clock),
      .rst_i(reset),

      .axi_awvalid_i(in_awvalid),
      .axi_awaddr_i(in_awaddr),
      .axi_awid_i(in_awid),
      .axi_awlen_i(in_awlen),
      .axi_awburst_i(in_awburst),
      .axi_wvalid_i(in_wvalid),
      .axi_wdata_i(in_wdata),
      .axi_wstrb_i(in_wstrb),
      .axi_wlast_i(in_wlast),
      .axi_bready_i(in_bready),
      .axi_arvalid_i(in_arvalid),
      .axi_araddr_i(in_araddr),
      .axi_arid_i(in_arid),
      .axi_arlen_i(in_arlen),
      .axi_arburst_i(in_arburst),
      .axi_rready_i(in_rready),
      .axi_awready_o(in_awready),
      .axi_wready_o(in_wready),
      .axi_bvalid_o(in_bvalid),
      .axi_bresp_o(in_bresp),
      .axi_bid_o(in_bid),
      .axi_arready_o(in_arready),
      .axi_rvalid_o(in_rvalid),
      .axi_rdata_o(in_rdata),
      .axi_rresp_o(in_rresp),
      .axi_rid_o(in_rid),
      .axi_rlast_o(in_rlast),

      .ram_addr_o(ram_addr_w),
      .ram_accept_i(ram_accept_w),
      .ram_wr_o(ram_wr_w),
      .ram_rd_o(ram_rd_w),
      .ram_len_o(ram_len_w),
      .ram_write_data_o(ram_write_data_w),
      .ram_ack_i(ram_ack_w),
      .ram_error_i(ram_error_w),
      .ram_read_data_i(ram_read_data_w)
  );

  sdram_axi_core
  #(
      .SDRAM_MHZ(100),
      .SDRAM_ADDR_W(24),
      .SDRAM_COL_W(9),
      .SDRAM_READ_LATENCY(3)
  )
  u_core
  (
      .clk_i(clock),
      .rst_i(reset),

      .inport_wr_i(ram_wr_w),
      .inport_rd_i(ram_rd_w),
      .inport_len_i(ram_len_w),
      .inport_addr_i(ram_addr_w),
      .inport_write_data_i(ram_write_data_w),
      .inport_accept_o(ram_accept_w),
      .inport_ack_o(ram_ack_w),
      .inport_error_o(ram_error_w),
      .inport_read_data_o(ram_read_data_w),

      .sdram_clk_o(sdram_clk),
      .sdram_cke_o(sdram_cke),
      .sdram_cs_o(sdram_cs),
      .sdram_ras_o(sdram_ras),
      .sdram_cas_o(sdram_cas),
      .sdram_we_o(sdram_we),
      .sdram_dqm_o(sdram_dqm),
      .sdram_addr_o(sdram_a),
      .sdram_ba_o(sdram_ba),
      .sdram_data_output_o(sdram_dout),
      .sdram_data_out_en_o(sdram_dout_en),
      .sdram_data_input_i({sdram_dq_1, sdram_dq_0})
  );

endmodule
