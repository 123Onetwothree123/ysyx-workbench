#include <nvboard.h>
#include "Vtop.h"

void nvboard_bind_all_pins(Vtop* top) {
	nvboard_bind_pin( &top->clk, 1, BTNC);
	nvboard_bind_pin( &top->rst_n, 1, SW15);
	nvboard_bind_pin( &top->WE, 1, SW14);
	nvboard_bind_pin( &top->DATAIn, 8, SW7, SW6, SW5, SW4, SW3, SW2, SW1, SW0);
	nvboard_bind_pin( &top->scpuResult, 8, LD7, LD6, LD5, LD4, LD3, LD2, LD1, LD0);
	nvboard_bind_pin( &top->seg0, 8, DEC0P, SEG0G, SEG0F, SEG0E, SEG0D, SEG0C, SEG0B, SEG0A);
	nvboard_bind_pin( &top->seg1, 8, DEC1P, SEG1G, SEG1F, SEG1E, SEG1D, SEG1C, SEG1B, SEG1A);
	nvboard_bind_pin( &top->seg2, 8, DEC2P, SEG2G, SEG2F, SEG2E, SEG2D, SEG2C, SEG2B, SEG2A);
	nvboard_bind_pin( &top->seg3, 8, DEC3P, SEG3G, SEG3F, SEG3E, SEG3D, SEG3C, SEG3B, SEG3A);
	nvboard_bind_pin( &top->seg4, 8, DEC4P, SEG4G, SEG4F, SEG4E, SEG4D, SEG4C, SEG4B, SEG4A);
	nvboard_bind_pin( &top->seg5, 8, DEC5P, SEG5G, SEG5F, SEG5E, SEG5D, SEG5C, SEG5B, SEG5A);
	nvboard_bind_pin( &top->seg6, 8, DEC6P, SEG6G, SEG6F, SEG6E, SEG6D, SEG6C, SEG6B, SEG6A);
	nvboard_bind_pin( &top->seg7, 8, DEC7P, SEG7G, SEG7F, SEG7E, SEG7D, SEG7C, SEG7B, SEG7A);
}
