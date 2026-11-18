// Test-only bind: the production RTL currently has no $finish path.  A magic
// first image word lets the regression exercise Verilator's real gotFinish
// state without adding a simulator-only hook to production C++ or RTL.
module sdb_finish_injector (
  input wire        clock,
  input wire        ram_init_valid,
  input wire [15:0] ram_init_index,
  input wire [31:0] ram_init_data
);
  always @(posedge clock) begin
    if (ram_init_valid && ram_init_index == 16'd0 &&
        ram_init_data == 32'hc0de_f17e) begin
      $finish;
    end
  end
endmodule

bind riscv32e_npc_SimTop sdb_finish_injector injected_sdb_finish (
  .clock(clock),
  .ram_init_valid(ram_init_valid),
  .ram_init_index(ram_init_index),
  .ram_init_data(ram_init_data)
);
