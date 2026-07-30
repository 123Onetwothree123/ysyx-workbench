#include <nvboard.h>
#include "Valu.h"

void nvboard_bind_all_pins(Valu* top) {
	nvboard_bind_pin( &top->A, 4, SW3, SW2, SW1, SW0);
	nvboard_bind_pin( &top->B, 4, SW7, SW6, SW5, SW4);
	nvboard_bind_pin( &top->ALU_Sel, 3, SW10, SW9, SW8);
	nvboard_bind_pin( &top->Result, 4, LD3, LD2, LD1, LD0);
	nvboard_bind_pin( &top->Zero, 1, LD4);
	nvboard_bind_pin( &top->Carry, 1, LD5);
	nvboard_bind_pin( &top->Overflow, 1, LD6);
}
