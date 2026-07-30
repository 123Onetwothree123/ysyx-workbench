#include <nvboard.h>
#include "Vtop.h"

void nvboard_bind_all_pins(Vtop* top) {
	nvboard_bind_pin( &top->led, 8, LD0, LD1, LD2, LD3, LD4, LD5, LD6, LD7);
	nvboard_bind_pin( &top->rst, 1, SW0);
}
