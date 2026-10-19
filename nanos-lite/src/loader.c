#include <proc.h>
#include <fs.h>
#include <elf.h>

#ifdef __LP64__
#define Elf_Ehdr Elf64_Ehdr
#define Elf_Phdr Elf64_Phdr
#else
#define Elf_Ehdr Elf32_Ehdr
#define Elf_Phdr Elf32_Phdr
#endif

uintptr_t loader(PCB *pcb, const char *filename)
{
  assert(pcb != NULL);
  assert(pcb->as.ptr != NULL);
  assert(filename != NULL);

  int fd = fs_open(filename, 0, 0);

  Elf_Ehdr ehdr;
  size_t rd = fs_read(fd, &ehdr, sizeof(Elf_Ehdr));
  assert(rd == sizeof(Elf_Ehdr));

  assert(*(uint32_t *)ehdr.e_ident == 0x464c457f); // ELF 魔数
  assert(ehdr.e_machine == EM_RISCV);
  assert(ehdr.e_type == ET_EXEC);
  assert(ehdr.e_phentsize == sizeof(Elf_Phdr));
  assert(ehdr.e_phnum > 0);

  uintptr_t user_start = (uintptr_t)pcb->as.area.start;
  uintptr_t stack_low = (uintptr_t)pcb->as.area.end - STACK_SIZE;
  uintptr_t max_brk = 0;

  for (int i = 0; i < ehdr.e_phnum; i++)
  {
    Elf_Phdr phdr;
    assert(fs_lseek(fd, ehdr.e_phoff + i * ehdr.e_phentsize, SEEK_SET) >= 0);
    rd = fs_read(fd, &phdr, sizeof(Elf_Phdr));
    assert(rd == sizeof(Elf_Phdr));

    if (phdr.p_type != PT_LOAD)
    {
      continue;
    }

    assert(phdr.p_filesz <= phdr.p_memsz);

    uintptr_t seg_start = phdr.p_vaddr;
    uintptr_t seg_end = phdr.p_vaddr + phdr.p_memsz;
    assert(seg_start >= user_start);
    assert(seg_end <= stack_low);

    uintptr_t page_start = ROUNDDOWN(seg_start, PGSIZE);
    uintptr_t page_end = ROUNDUP(seg_end, PGSIZE);

    for (uintptr_t page_va = page_start; page_va < page_end; page_va += PGSIZE)
    {
      void *pa = new_page(1);
      assert(pa != NULL);
      memset(pa, 0, PGSIZE);
      map(&pcb->as, (void *)page_va, pa, 0);

      uintptr_t file_start = page_va > seg_start ? page_va : seg_start;
      uintptr_t file_end = page_va + PGSIZE < seg_start + phdr.p_filesz
                               ? page_va + PGSIZE
                               : seg_start + phdr.p_filesz;

      if (file_start < file_end)
      {
        size_t len = file_end - file_start;
        assert(fs_lseek(fd, phdr.p_offset + (file_start - seg_start), SEEK_SET) >= 0);
        rd = fs_read(fd, (char *)pa + (file_start - page_va), len);
        assert(rd == len);
      }
    }

    if (seg_end > max_brk)
    {
      max_brk = seg_end;
    }
  }

  fs_close(fd);

  pcb->max_brk = max_brk;
  pcb->brk = max_brk;
  pcb->brk_min = max_brk;
  assert(pcb->max_brk <= stack_low);

  return ehdr.e_entry;
}

void naive_uload(PCB *pcb, const char *filename)
{
  uintptr_t entry = loader(pcb, filename);
  ((void (*)())entry)();
}
