#include <nvboard.h>
#include "Vdecode24.h"

void nvboard_bind_all_pins(Vdecode24* top) {
	nvboard_bind_pin( &top->x, 2, SW0, SW1);
	nvboard_bind_pin( &top->en, 1, SW2);
	nvboard_bind_pin( &top->y, 4, LD0, LD1, LD2, LD3);
}
