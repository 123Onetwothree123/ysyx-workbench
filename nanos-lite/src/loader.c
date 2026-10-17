#include <proc.h>
#include <elf.h>

#ifdef __LP64__
#define Elf_Ehdr Elf64_Ehdr
#define Elf_Phdr Elf64_Phdr
#else
#define Elf_Ehdr Elf32_Ehdr
#define Elf_Phdr Elf32_Phdr
#endif

size_t ramdisk_read(void *buf, size_t offset, size_t len);
static uintptr_t loader(PCB *pcb, const char *filename)
{
  // TODO();
  Elf_Ehdr ehdr;
  ramdisk_read(&ehdr, 0, sizeof(Elf_Ehdr));
  assert(*(uint32_t *)ehdr.e_ident == 0x464c457f); // 检查是不是ELF
  assert(ehdr.e_machine == EM_RISCV);              // 然后再看看ISA是不是RV
  for (int i = 0; i < ehdr.e_phnum; i++)
  {
    Elf_Phdr phdr;
    ramdisk_read(&phdr, ehdr.e_phoff + i * ehdr.e_phentsize, sizeof(Elf_Phdr));
    if (phdr.p_type == PT_LOAD)
    {
      ramdisk_read((void *)phdr.p_vaddr, phdr.p_offset, phdr.p_filesz);                // 直接把代码跟数据拉过去
      memset((void *)(phdr.p_vaddr + phdr.p_filesz), 0, phdr.p_memsz - phdr.p_filesz); // 剩下的bss清零
    }
  }
  // return 0;
  return ehdr.e_entry; // 直接就返回开始地址
}

void naive_uload(PCB *pcb, const char *filename)
{
  uintptr_t entry = loader(pcb, filename);
  Log("Jump to entry = %p", entry);
  ((void (*)())entry)();
}
