module sdram(
  input        clk,
  input        cke,
  input        cs,
  input        ras,
  input        cas,
  input        we,
  input [12:0] a,
  input [ 1:0] ba,
  input [ 1:0] dqm,
  inout [15:0] dq
);

  assign dq = 16'bz;

  always @(negedge clk)
    if(~cs)
      $display("SDRAM-V: cs=%b ras=%b cas=%b we=%b ba=%d a=%d dqm=%b",
               cs, ras, cas, we, ba, a, dqm);

endmodule
