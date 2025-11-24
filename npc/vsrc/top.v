module top(
  input sw1,
  input sw2,
  output light
);
  assign light = sw1 ^ sw2;
endmodule