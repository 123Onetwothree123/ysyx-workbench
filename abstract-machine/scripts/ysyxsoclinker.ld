ENTRY(_start)
MEMORY {
  mrom (rx)  : ORIGIN = 0x20000000, LENGTH = 0x1000
  sram (rw)  : ORIGIN = 0x0f000000, LENGTH = 0x2000
}
SECTIONS {
    . = ORIGIN(mrom);
    .text : {
    *(entry)
    *(.text*)
    *(.rodata*)
    *(.srodata*)
    } > mrom
    etext = .;
    _etext = .;
    .bss (NOLOAD) : {
    _bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    _bss_end = .;
  } > sram
  _heap_start = ALIGN(0x1000);
  _heap_end = ORIGIN(sram) + LENGTH(sram) - 0x1000;
   _stack_top = _heap_end;
  _stack_pointer = ORIGIN(sram) + LENGTH(sram);
  end = .;
  _end = .;
}