#为了保证PPT演示文档一致性，然后节省空间位置，就把讲解写到注释里面
.section entry, "ax"#这个先指定段先把allocatable声明下来再用x标记是可执行机器指令
.globl _start#之前确实不明白为什么要求写这个，后面询问AI，AI说这是因为需要对链接器可见，查了下
#好像是linker.ld的第一行写了ENTRY(_start)
.type _start, @function#不懂什么意思，function应该是函数标记，可能是类似于C++那种类型设定的，然后写一个函数类型标记
#应该是给ELF的，到时候ReadELF会显示function类型，应该就是函数类型

_start:
  mv s0, zero#将帧指针寄存器手动清零，应该是为了保险起见，查手册这好像伪指令，mv rd rs等于addi rd rs 0
  #我不清楚为什么要强制手动清零，询问过AI，AI说是因为这是调用链的起始点，到时候调试可以拿这个为0作为判断是
  #不是程序的栈底
  la sp, _stack_pointer#la好像也是伪指令，好像是auipc那个什么的，非PIC的话是auipc+addi加载32bit地址，这个项目
  #组自带的汇编看起来很艰难
  call _trm_init#跳转进入C的运行时

.size _start, . - _start
