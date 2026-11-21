/*
	Copyright 2020 Efabless Corp.

	Author: Mohamed Shalan (mshalan@efabless.com)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at:
	http://www.apache.org/licenses/LICENSE-2.0
	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
*/

`timescale              1ns/1ps
`default_nettype        none

// Using EBH Command
module EF_PSRAM_CTRL_wb (
    // WB bus Interface
    input   wire        clk_i,
    input   wire        rst_i,
    input   wire [31:0] adr_i,
    input   wire [31:0] dat_i,
    output  wire [31:0] dat_o,
    input   wire [3:0]  sel_i,
    input   wire        cyc_i,
    input   wire        stb_i,
    output  wire        ack_o,
    input   wire        we_i,

    // External Interface to Quad I/O
    output  wire            sck,
    output  wire            ce_n,
    input   wire [3:0]      din,
    output  wire [3:0]      dout,
    output  wire [3:0]      douten
);

    localparam  ST_IDLE = 1'b0,
                ST_WAIT = 1'b1;
    reg qpi_mode;
    reg init_active;
    reg init_sck;
    reg [3:0] init_cnt;

    wire        mr_sck;
    wire        mr_ce_n;
    wire [3:0]  mr_din;
    wire [3:0]  mr_dout;
    wire        mr_doe;

    wire        mw_sck;
    wire        mw_ce_n;
    wire [3:0]  mw_din;
    wire [3:0]  mw_dout;
    wire        mw_doe;

    // PSRAM Reader and Writer wires
    wire        mr_rd;
    wire        mr_done;
    wire        mw_wr;
    wire        mw_done;

    //wire        doe;

    // WB Control Signals
    wire        wb_valid        =   cyc_i & stb_i;
    wire        wb_we           =   we_i & wb_valid;
    wire        wb_re           =   ~we_i & wb_valid;
    always @(posedge clk_i or posedge rst_i)
        if(rst_i) begin
            {init_cnt, init_sck, qpi_mode} <= 0;
            init_active <= 1'b1;
        end else if(init_active) begin
            init_sck <= ~init_sck;
            if(init_sck && init_cnt < 8)  init_cnt <= init_cnt + 1;
            if(init_sck && init_cnt == 8) begin
                init_active <= 1'b0;
                qpi_mode <= 1'b1;
            end
        end
    // The FSM
    reg         state, nstate;
    always @ (posedge clk_i or posedge rst_i)
        if(rst_i)
            state <= ST_IDLE;
        else
            state <= nstate;

    always @* begin
        case(state)
            ST_IDLE :
                if(wb_valid)
                    nstate = ST_WAIT;
                else
                    nstate = ST_IDLE;

            ST_WAIT :
                // 引擎完成即回IDLE: delayer会在delay期间保持psel, 不能等wb_we/wb_re撤销
                if(mw_done | mr_done)
                    nstate = ST_IDLE;
                else
                    nstate = ST_WAIT;
        endcase
    end

    // 窄写归一化: 选通位可能落在任意lane, 数据按最低有效字节下移到lane0.
    // 地址保持调用方给出的字节偏移(上游已经把offset加进adr_i)
    wire [1:0]  first_byte = sel_i[0] ? 2'd0 :
                             sel_i[1] ? 2'd1 :
                             sel_i[2] ? 2'd2 : 2'd3;
    wire [3:0]  lane_strb  = sel_i >> first_byte;
    wire [2:0]  size = (lane_strb == 4'b1111) ? 3'd4 :
                       (lane_strb[1:0] == 2'b11) ? 3'd2 : 3'd1;
    wire [31:0] wdata = dat_i >> {first_byte, 3'b0};

    assign mr_rd = !init_active & (state==ST_IDLE) & wb_re;
    assign mw_wr = !init_active & (state==ST_IDLE) & wb_we;

    PSRAM_READER MR (
        .clk(clk_i),
        .rst_n(~rst_i),
        .addr({adr_i[23:2],2'b0}),
        .rd(mr_rd),
        //.size(size), Always read a word
        .size(3'd4),
        .done(mr_done),
        .line(dat_o),
        .sck(mr_sck),
        .ce_n(mr_ce_n),
        .din(mr_din),
        .dout(mr_dout),
        .douten(mr_doe),
        .qpi_mode(qpi_mode)
    );

    PSRAM_WRITER MW (
        .clk(clk_i),
        .rst_n(~rst_i),
        .addr({adr_i[23:0]}),
        .wr(mw_wr),
        .size(size),
        .done(mw_done),
        .line(wdata),
        .sck(mw_sck),
        .ce_n(mw_ce_n),
        .din(mw_din),
        .dout(mw_dout),
        .douten(mw_doe),
        .qpi_mode(qpi_mode)
    );

    assign sck  = init_active ? init_sck : (wb_we ? mw_sck  : mr_sck);
    assign ce_n = init_active ? 1'b0     : (wb_we ? mw_ce_n : mr_ce_n);
    wire [7:0] CMD_35H = 8'h35;
    assign dout = init_active ? {3'b0, CMD_35H[7 - init_cnt]} : (wb_we ? mw_dout : mr_dout);
    assign douten = init_active ? 4'b1111 : (wb_we ? {4{mw_doe}} : {4{mr_doe}});

    assign mw_din = din;
    assign mr_din = din;
    assign ack_o = wb_we ? mw_done :mr_done ;
endmodule
