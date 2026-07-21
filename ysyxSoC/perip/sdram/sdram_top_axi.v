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

  // === AXI → Core state machine ===
  localparam ST_IDLE       = 3'd0;
  localparam ST_WRITE_REQ  = 3'd1;
  localparam ST_WRITE_ACK  = 3'd2;
  localparam ST_READ_REQ   = 3'd3;
  localparam ST_READ_ACK   = 3'd4;
  reg [2:0] state;

  reg [31:0] awaddr_reg;
  reg [3:0]  awid_reg;
  reg [31:0] wdata_reg;
  reg [3:0]  wstrb_reg;
  reg [3:0]  arid_reg;
  reg [31:0] araddr_reg;

  wire       core_accept;
  wire       core_ack;
  wire       core_error;
  wire [31:0] core_rdata;

  // capture AW
  wire aw_fire = in_awvalid && in_awready;
  wire w_fire  = in_wvalid  && in_wready;
  wire ar_fire = in_arvalid && in_arready;

  wire has_write = (state == ST_IDLE) && in_awvalid && in_wvalid;
  wire has_read  = (state == ST_IDLE) && in_arvalid && !has_write;

  // AXI ready signals
  assign in_awready = (state == ST_IDLE) && in_wvalid;
  assign in_wready  = (state == ST_IDLE) && in_awvalid;
  assign in_arready = (state == ST_IDLE) && !has_write;

  always @(posedge clock) begin
    if (reset) begin
      state <= ST_IDLE;
    end else begin
      case (state)
        ST_IDLE: begin
          if (has_write) begin
            awaddr_reg <= in_awaddr;
            awid_reg   <= in_awid;
            wdata_reg  <= in_wdata;
            wstrb_reg  <= in_wstrb;
            state <= ST_WRITE_REQ;
          end else if (has_read) begin
            araddr_reg <= in_araddr;
            arid_reg   <= in_arid;
            state <= ST_READ_REQ;
          end
        end
        ST_WRITE_REQ: begin
          if (core_accept)
            state <= ST_WRITE_ACK;
        end
        ST_WRITE_ACK: begin
          if (core_ack)
            state <= ST_IDLE;
        end
        ST_READ_REQ: begin
          if (core_accept)
            state <= ST_READ_ACK;
        end
        ST_READ_ACK: begin
          if (core_ack)
            state <= ST_IDLE;
        end
        default: state <= ST_IDLE;
      endcase
    end
  end

  // Core request signals
  wire is_write_req = (state == ST_WRITE_REQ);
  wire is_read_req  = (state == ST_READ_REQ);

  // B response (combinational from state and core_ack)
  assign in_bvalid = (state == ST_WRITE_ACK) && core_ack;
  assign in_bresp  = core_error ? 2'h2 : 2'h0;
  assign in_bid    = awid_reg;

  // R response
  assign in_rvalid = (state == ST_READ_ACK) && core_ack;
  assign in_rresp  = core_error ? 2'h2 : 2'h0;
  assign in_rdata  = core_rdata;
  assign in_rid    = arid_reg;
  assign in_rlast  = 1'b1;

  // SDRAM data bus
  wire sdram_dout_en;
  wire [31:0] sdram_dout;
  assign sdram_dq_0 = sdram_dout_en ? sdram_dout[15:0]  : 16'bz;
  assign sdram_dq_1 = sdram_dout_en ? sdram_dout[31:16] : 16'bz;

  sdram_axi_core #(
    .SDRAM_MHZ(100),
    .SDRAM_ADDR_W(24),
    .SDRAM_COL_W(9),
    .SDRAM_READ_LATENCY(2)
  ) u_core (
    .clk_i(clock),
    .rst_i(reset),

    .inport_wr_i(is_write_req ? wstrb_reg : 4'b0),
    .inport_rd_i(is_read_req),
    .inport_len_i(8'd0),
    .inport_addr_i(is_write_req ? awaddr_reg : araddr_reg),
    .inport_write_data_i(wdata_reg),
    .inport_accept_o(core_accept),
    .inport_ack_o(core_ack),
    .inport_error_o(core_error),
    .inport_read_data_o(core_rdata),

    .sdram_clk_o(sdram_clk),
    .sdram_cke_o(sdram_cke),
    .sdram_cs_o(sdram_cs),
    .sdram_ras_o(sdram_ras),
    .sdram_cas_o(sdram_cas),
    .sdram_we_o(sdram_we),
    .sdram_dqm_o(sdram_dqm),
    .sdram_addr_o(sdram_a),
    .sdram_ba_o(sdram_ba),
    .sdram_data_input_i({sdram_dq_1, sdram_dq_0}),
    .sdram_data_output_o(sdram_dout),
    .sdram_data_out_en_o(sdram_dout_en)
  );

endmodule
