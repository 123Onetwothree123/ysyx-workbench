OUTPUT_ARCH(riscv)
ENTRY(_start)

SECTIONS
{
	. = PAYLOAD_ADDR;
	PROVIDE(__global_pointer$ = . + 0x800);
	.text : {
		*(.text.entry)
		*(.text .text.*)
	}
	.rodata : {
		*(.rodata .rodata.*)
	}
	.data : {
		*(.data .data.*)
	}
	.bss : {
		PROVIDE(_bss_start = .);
		*(.bss .bss.*)
		*(COMMON)
		PROVIDE(_bss_end = .);
	}
	. = ALIGN(16);
	PROVIDE(_stack_top = . + 0x4000);
}
