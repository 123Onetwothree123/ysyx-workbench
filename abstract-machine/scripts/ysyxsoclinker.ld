/*我六天前写的，今天突然又跑不了了，我百思不得其解为什么跑不起来，问了ai后才发现，注释居然还得单行，否则语法解析器也会解析错误，然后他错了他还不会停止，他还会继续发送给链接器，然后一直到链接器错误*/
/*日你妈，理查德·斯托曼*/
/*此文件的SECTIONS部分开始都是AI写的，本人编写部分大量参考linker.ld*/
ENTRY(_start)
MEMORY {
    /*我也不知道为什么OSOC的SOC是要分出两套来，而且容量都那么小*/
  flash_xip (rx)  : ORIGIN = 0x30000000, LENGTH = 256M
  sram (rw)  : ORIGIN = 0x0f000000, LENGTH = 32K
   PSRAM (rw) : ORIGIN = 0x80000000, LENGTH = 4M
}
SECTIONS {
    . = ORIGIN(flash_xip);
     .text : {
       /*纯粹的链接器脑子有坑，他妈的居然还得给空格，不然区分不了，这哪个天才写的语法解析器，在没有格式化程序的情况下，还要我手写空格*/
     *(entry)
     *(.text*)
      } > sram AT> flash_xip
       /*适配bootloader*/
       .rodata : {
       *(.rodata*)
       *(.srodata*)
       . = ALIGN(4);
       } > sram AT> flash_xip
       /*同上脑子有坑*/
       /*记录代码和只读数据结束的位置*/
       _text_lma = LOADADDR(.text);
       _text_start = ADDR(.text);
       _text_end = _text_start + SIZEOF(.text);
       _rodata_lma = LOADADDR(.rodata);
       _rodata_start = ADDR(.rodata);
       _rodata_end = _rodata_start + SIZEOF(.rodata);
       etext = .;
       _etext = .;
       /*新加的，为了支持实现bootloader*/
       .data : {
      *(.data*)
      *(.sdata*)
      } > sram AT> flash_xip
     _data_lma = LOADADDR(.data);
     _data_start = ADDR(.data);
     _edata = _data_start + SIZEOF(.data);
     .bss (NOLOAD) : {
      /*NOLOAD 表示不把它写进.bin镜像*/
    _bss_start = .;
    *(.bss*)
    *(.sbss*)
    *(.scommon)
    _bss_end = .;
  } > sram
  /*他妈的几百年没更新过吗？C和C++都能做好，为什么这个做不好空格问题，一定要写空格吗？没有格式化文档，我还得自己手改*/
    /*堆从.bss之后开始，到栈底结束*/
  _heap_start = ALIGN(0x10);
  _heap_end = _stack_pointer;
   _stack_top = _heap_end;
   /*初始栈指针放在SRAM顶部，栈向低地址增长*/
   _stack_pointer = ORIGIN(sram) + LENGTH(sram) - 0x800;
  end = .;
  _end = .;
}