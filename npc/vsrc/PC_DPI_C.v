module PC_DPI_C(
    input [31:0] pc_current
);
export "DPI-C" function NPCGetPC;
function int NPCGetPC;
    NPCGetPC = pc_current;
endfunction
endmodule