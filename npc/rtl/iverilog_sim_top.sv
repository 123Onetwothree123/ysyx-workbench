module iverilog_sim_top;
    reg clock;
    reg reset;

    // AXI master signals between CPU and RAM
    wire        cpu_awvalid, cpu_awready, cpu_wvalid, cpu_wready, cpu_wlast;
    wire [31:0] cpu_awaddr, cpu_wdata;
    wire [ 3:0] cpu_wstrb;
    wire        cpu_bvalid, cpu_bready;
    wire [ 1:0] cpu_bresp;
    wire        cpu_arvalid, cpu_arready, cpu_rvalid, cpu_rready, cpu_rlast;
    wire [31:0] cpu_araddr, cpu_rdata;
    wire [ 1:0] cpu_rresp;

    wire        trap_valid;
    wire [31:0] trap_pc;

    initial begin
        $dumpfile("build/npc_sim.vcd");
        $dumpvars(0, iverilog_sim_top);
    end

    initial begin
        clock = 0;
        forever #10 clock = ~clock;
    end

    initial begin
        reset = 1;
        #50 reset = 0;
    end

    ysyx_26030103 cpu (
        .clock(clock), .reset(reset),
        .io_interrupt(1'b0),
        .io_trap_valid(trap_valid), .io_trap_pc(trap_pc),
        .io_master_awvalid(cpu_awvalid), .io_master_awready(cpu_awready),
        .io_master_awaddr(cpu_awaddr),
        .io_master_awid(), .io_master_awlen(), .io_master_awsize(), .io_master_awburst(),
        .io_master_awlock(), .io_master_awcache(), .io_master_awprot(), .io_master_awqos(),
        .io_master_wvalid(cpu_wvalid), .io_master_wready(cpu_wready),
        .io_master_wdata(cpu_wdata), .io_master_wstrb(cpu_wstrb), .io_master_wlast(cpu_wlast),
        .io_master_bvalid(cpu_bvalid), .io_master_bready(cpu_bready),
        .io_master_bresp(cpu_bresp), .io_master_bid(),
        .io_master_arvalid(cpu_arvalid), .io_master_arready(cpu_arready),
        .io_master_araddr(cpu_araddr),
        .io_master_arid(), .io_master_arlen(), .io_master_arsize(), .io_master_arburst(),
        .io_master_arlock(), .io_master_arcache(), .io_master_arprot(), .io_master_arqos(),
        .io_master_rvalid(cpu_rvalid), .io_master_rready(cpu_rready),
        .io_master_rdata(cpu_rdata), .io_master_rresp(cpu_rresp),
        .io_master_rlast(cpu_rlast), .io_master_rid(),
        .io_slave_awvalid(1'b0), .io_slave_awaddr(32'b0), .io_slave_awid(4'b0),
        .io_slave_awlen(8'b0), .io_slave_awsize(3'b0), .io_slave_awburst(2'b0),
        .io_slave_awlock(1'b0), .io_slave_awcache(4'b0), .io_slave_awprot(3'b0),
        .io_slave_awqos(4'b0),
        .io_slave_wvalid(1'b0), .io_slave_wdata(32'b0), .io_slave_wstrb(4'b0),
        .io_slave_wlast(1'b0),
        .io_slave_bready(1'b0),
        .io_slave_arvalid(1'b0), .io_slave_araddr(32'b0), .io_slave_arid(4'b0),
        .io_slave_arlen(8'b0), .io_slave_arsize(3'b0), .io_slave_arburst(2'b0),
        .io_slave_arlock(1'b0), .io_slave_arcache(4'b0), .io_slave_arprot(3'b0),
        .io_slave_arqos(4'b0),
        .io_slave_rready(1'b0),
        .io_debug_gpr_raddr(5'b0),
        .io_debug_gpr_rdata(), .io_debug_pc(), .io_debug_instructions(),
        .io_debug_mtrace_valid(), .io_debug_mtrace_wen(),
        .io_debug_mtrace_addr(), .io_debug_mtrace_wdata(),
        .io_debug_mtrace_rdata(), .io_debug_mtrace_width(),
        .io_debug_access_fault(), .io_debug_access_fault_resp(),
        .io_debug_commit(),
        .io_perf_ifu_fetch(), .io_perf_exu_done(), .io_perf_lsu_load(), .io_perf_lsu_store(),
        .io_perf_alu_op(), .io_perf_mem_op(), .io_perf_csr_op(), .io_perf_branch_op(),
        .io_perf_jal_op(), .io_perf_jalr_op(),
        .io_perf_ifu_stall_pipeline(), .io_perf_ifu_stall_axi(),
        .io_perf_ifu_stall_ar(), .io_perf_ifu_stall_r(),
        .io_perf_ifu_stall_redirect(), .io_perf_ifu_stall_idle(),
        .io_perf_execution_active(), .io_perf_exu_stall_lsu(), .io_perf_lsu_active(),
        .io_perf_lsu_load_active(), .io_perf_lsu_store_active(),
        .io_perf_lsu_stall_read_ar(), .io_perf_lsu_stall_read_r(),
        .io_perf_lsu_stall_write_req(), .io_perf_lsu_stall_write_b(),
        .io_perf_icache_hit(), .io_perf_icache_miss(),
        .io_perf_mem_waitslot()
    );

    iverilog_axi_ram #(.MEM_FILE("build/program_iverilog.hex")) ram (
        .clock(clock), .reset(reset),
        .awvalid(cpu_awvalid), .awready(cpu_awready), .awaddr(cpu_awaddr),
        .wvalid(cpu_wvalid),  .wready(cpu_wready),
        .wdata(cpu_wdata),    .wstrb(cpu_wstrb),
        .bvalid(cpu_bvalid),  .bready(cpu_bready), .bresp(cpu_bresp),
        .arvalid(cpu_arvalid), .arready(cpu_arready), .araddr(cpu_araddr),
        .rvalid(cpu_rvalid),  .rready(cpu_rready),
        .rdata(cpu_rdata),    .rresp(cpu_rresp),
        .rlast(cpu_rlast)
    );

    integer cycle;
    always @(posedge clock) begin
        if (!reset) cycle <= cycle + 1;
        else cycle <= 0;
    end

    always @(posedge clock) begin
        if (trap_valid) begin
            $display("HIT GOOD TRAP at pc = 0x%08h, cycles = %0d", trap_pc, cycle);
            if (trap_pc == 32'h8000_0000) begin
                $display("trap at reset pc - test FAILED");
            end else begin
                $display("test PASSED");
            end
            #100 $finish;
        end
    end

    initial begin
        #100000000 $display("TIMEOUT: simulation exceeded 100M time units");
        $finish;
    end
endmodule
