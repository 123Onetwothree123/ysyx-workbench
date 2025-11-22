#include <nvboard.h>
#include "Vlfsr_random_generator_top.h"

void nvboard_bind_all_pins(Vlfsr_random_generator_top* top) {
	nvboard_bind_pin( &top->KEY, 2, BTNC, SW0);
	nvboard_bind_pin( &top->HEX0, 7, SEG0G, SEG0F, SEG0E, SEG0D, SEG0C, SEG0B, SEG0A);
	nvboard_bind_pin( &top->HEX1, 7, SEG1G, SEG1F, SEG1E, SEG1D, SEG1C, SEG1B, SEG1A);
}
