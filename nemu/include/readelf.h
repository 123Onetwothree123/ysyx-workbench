// 自己设计的文件，目前先只支持ftrace功能
#ifndef READDELF_H
#define READDELF_H
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <common.h>
#include <elf.h>
#include <stdlib.h>
typedef struct
{
  const char *name; // 函数名，指向内部字符串表
  vaddr_t start;    // 函数起始地址
  vaddr_t end;      // 函数结束地址，目前的设想是打算用[start, end)
  word_t size;      // 函数大小
} ElfFunctionSymbol;
typedef MUXDEF(CONFIG_ISA64, Elf64_Ehdr, Elf32_Ehdr) ELF_Ehdr;
typedef MUXDEF(CONFIG_ISA64, Elf64_Shdr, Elf32_Shdr) ELF_Shdr;
typedef MUXDEF(CONFIG_ISA64, Elf64_Sym, Elf32_Sym) ELF_Sym;
typedef MUXDEF(CONFIG_ISA64, Elf64_Phdr, Elf32_Phdr) ELF_Phdr;
#define GetElfSymbolType(info) MUXDEF(CONFIG_ISA64, ELF64_ST_TYPE(info), ELF32_ST_TYPE(info))
#ifdef CONFIG_ReadELF
// 设置ReadELF模块的输出开关，只有打开时才会打印内部信息
void ReadelfSetVerbose(bool Enabled);
// 读取ELF，解析符号表和字符串表，并且把FUNC符号缓存起来
bool ReadelfInitialization(const char *ElfFile);
// 释放内部申请的内存
void ReadelfFinalize(void);
// 给一个地址，然后查他落在哪个函数里面，然后成功就把完整符号信息写到out
bool ReadelfFindFunction(vaddr_t address, ElfFunctionSymbol *out);
// 如果只想拿名字
const char *ReadelfFindFunctionName(vaddr_t address);
// 获取ELF的头
bool GetElfHeader(FILE *FilePointer, ELF_Ehdr *ElfHeader);
// 获取ELF的节区头表
bool GetElfSectionHeaderTable(FILE *FilePointer, const ELF_Ehdr *ElfHeader, ELF_Shdr **ElfSHT, size_t *Count);
// 获取ELF的符号
bool GetElfSymbolTable(FILE *FilePointer, const ELF_Shdr *SymbolTableSection, ELF_Sym **ElfSymbolTable, size_t *Count);
bool GetElfStringTable(FILE *FilePointer, const ELF_Shdr *StringTableSection, char **StringTable, size_t *Size);
// 获取ELF的程序头表
bool GetElfProgramHeaderTable(FILE *FilePointer, const ELF_Ehdr *ElfHeader, ELF_Phdr **ElfPHT, size_t *Count);
// 打印ELF头
void PrintElfFileHeader(void);
// 打印节区头表
void PrintElfSectionHeaders(void);
// 打印符号表
void PrintElfSymbols(void);
// 辅助的
// 检查ELF的文件头
bool CheckElfHeader(const ELF_Ehdr *ElfHeader);
// 过滤出函数符号表
bool FilterElfFunctionSymbolTable(const ELF_Sym *ElfSymbolTable, size_t SymbolCount, const char *StringTable, size_t StringTableSize, ElfFunctionSymbol **FunctionSymbolTable, size_t *FunctionCount);
#else
static inline bool ReadelfInitialization(const char *ElfFile)
{
  (void)ElfFile;
  return true;
}
static inline void ReadelfFinalize(void) {}
static inline bool ReadelfFindFunction(vaddr_t address, ElfFunctionSymbol *out)
{
  (void)address;
  (void)out;
  return false;
}
static inline const char *ReadelfFindFunctionName(vaddr_t address)
{
  (void)address;
  return "CONFIG_FTRACE都关掉了，用不了了这个函数";
}
static inline bool GetElfHeader(FILE *FilePointer, ELF_Ehdr *ElfHeader)
{
  (void)FilePointer;
  (void)ElfHeader;
  return false;
}
static inline bool GetElfSectionHeaderTable(FILE *FilePointer, const ELF_Ehdr *ElfHeader, ELF_Shdr **ElfSHT, size_t *Count)
{
  (void)FilePointer;
  (void)ElfHeader;
  (void)ElfSHT;
  (void)Count;
  return false;
}
static inline bool GetElfSymbolTable(FILE *FilePointer, const ELF_Shdr *SymbolTableSection, ELF_Sym **ElfSymbolTable, size_t *Count)
{
  (void)FilePointer;
  (void)SymbolTableSection;
  (void)ElfSymbolTable;
  (void)Count;
  return false;
}
static inline bool GetElfStringTable(FILE *FilePointer, const ELF_Shdr *StringTableSection, char **StringTable, size_t *Size)
{
  (void)FilePointer;
  (void)StringTableSection;
  (void)StringTable;
  (void)Size;
  return false;
}
static inline bool GetElfProgramHeaderTable(FILE *FilePointer, const ELF_Ehdr *ElfHeader, ELF_Phdr **ElfPHT, size_t *Count)
{
  (void)FilePointer;
  (void)ElfHeader;
  (void)ElfPHT;
  (void)Count;
  return false;
}
static inline void PrintElfFileHeader(void) {}
static inline void PrintElfSectionHeaders(void) {}
static inline void PrintElfSymbols(void) {}
static inline bool CheckElfHeader(const ELF_Ehdr *ElfHeader)
{
  (void)ElfHeader;
  return false;
}
static inline bool FilterElfFunctionSymbolTable(const ELF_Sym *ElfSymbolTable, size_t SymbolCount, const char *StringTable, size_t StringTableSize, ElfFunctionSymbol **FunctionSymbolTable, size_t *FunctionCount)
{
  (void)ElfSymbolTable;
  (void)SymbolCount;
  (void)StringTable;
  (void)StringTableSize;
  (void)FunctionSymbolTable;
  (void)FunctionCount;
  return false;
}
#endif
#endif
