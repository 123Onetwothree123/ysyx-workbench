#include <nvboard.h>
#include "VVGA_TEST.h"

void nvboard_bind_all_pins(VVGA_TEST* top) {
	nvboard_bind_pin( &top->rst_n, 1, SW0);
	nvboard_bind_pin( &top->VGA_HSYNC, 1, VGA_HSYNC);
	nvboard_bind_pin( &top->VGA_VSYNC, 1, VGA_VSYNC);
	nvboard_bind_pin( &top->VGA_BLANK_N, 1, VGA_BLANK_N);
	nvboard_bind_pin( &top->VGA_R0, 1, VGA_R0);
	nvboard_bind_pin( &top->VGA_R1, 1, VGA_R1);
	nvboard_bind_pin( &top->VGA_R2, 1, VGA_R2);
	nvboard_bind_pin( &top->VGA_R3, 1, VGA_R3);
	nvboard_bind_pin( &top->VGA_R4, 1, VGA_R4);
	nvboard_bind_pin( &top->VGA_R5, 1, VGA_R5);
	nvboard_bind_pin( &top->VGA_R6, 1, VGA_R6);
	nvboard_bind_pin( &top->VGA_R7, 1, VGA_R7);
	nvboard_bind_pin( &top->VGA_G0, 1, VGA_G0);
	nvboard_bind_pin( &top->VGA_G1, 1, VGA_G1);
	nvboard_bind_pin( &top->VGA_G2, 1, VGA_G2);
	nvboard_bind_pin( &top->VGA_G3, 1, VGA_G3);
	nvboard_bind_pin( &top->VGA_G4, 1, VGA_G4);
	nvboard_bind_pin( &top->VGA_G5, 1, VGA_G5);
	nvboard_bind_pin( &top->VGA_G6, 1, VGA_G6);
	nvboard_bind_pin( &top->VGA_G7, 1, VGA_G7);
	nvboard_bind_pin( &top->VGA_B0, 1, VGA_B0);
	nvboard_bind_pin( &top->VGA_B1, 1, VGA_B1);
	nvboard_bind_pin( &top->VGA_B2, 1, VGA_B2);
	nvboard_bind_pin( &top->VGA_B3, 1, VGA_B3);
	nvboard_bind_pin( &top->VGA_B4, 1, VGA_B4);
	nvboard_bind_pin( &top->VGA_B5, 1, VGA_B5);
	nvboard_bind_pin( &top->VGA_B6, 1, VGA_B6);
	nvboard_bind_pin( &top->VGA_B7, 1, VGA_B7);
	nvboard_bind_pin( &top->LED, 1, LD0);
}
