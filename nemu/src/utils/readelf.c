// 自己设计的文件，目前先只支持ftrace功能
// 受不了了，注释让ai写的
#define _XOPEN_SOURCE 700
#include <readelf.h>
#include <stdarg.h>
#include <wchar.h>
#include <wctype.h>

#ifdef CONFIG_ReadELF
typedef MUXDEF(CONFIG_ISA64, Elf64_Half, Elf32_Half) ELF_Half;
typedef MUXDEF(CONFIG_ISA64, Elf64_Word, Elf32_Word) ELF_Word;
typedef MUXDEF(CONFIG_ISA64, Elf64_Xword, Elf32_Word) ELF_Xword;
#define GetElfSymbolBind(info) MUXDEF(CONFIG_ISA64, ELF64_ST_BIND(info), ELF32_ST_BIND(info))
#define GetElfSymbolType(info) MUXDEF(CONFIG_ISA64, ELF64_ST_TYPE(info), ELF32_ST_TYPE(info))
#define GetElfSymbolVisibility(other) MUXDEF(CONFIG_ISA64, ELF64_ST_VISIBILITY(other), ELF32_ST_VISIBILITY(other))
void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
// 标记ReadELF模块当前是否已经完成初始化。
// 只有初始化成功后，后续按地址查函数名这类操作才是安全的。
static bool GlobalReadelfReady = false;
// 保存读取出来的ELF头。
// 这里存的是当前ELF文件最顶层的整体描述信息，例如节区头表偏移、程序头表偏移、表项数量等。
static ELF_Ehdr GlobalElfHeader;
// 保存整个节区头表(section header table)的首地址。
// 初始化时会从ELF文件里把整张节区头表读进内存，后续查找.symtab/.strtab等节区时会用到。
static ELF_Shdr *GlobalSectionHeaderTable = NULL;
// 记录当前节区头表一共有多少项。
// 需要和GlobalSectionHeaderTable配套使用，用于遍历整张节区头表。
static size_t GlobalSectionHeaderCount = 0;
// 保存整个符号表(symbol table)的首地址。
// 初始化时会从.symtab对应节区中读出所有符号表项，后续过滤函数符号时会用到。
static ELF_Sym *GlobalSymbolTable = NULL;
// 记录符号表中的表项数量。
// 需要和GlobalSymbolTable配套使用，用于遍历所有符号。
static size_t GlobalSymbolCount = 0;
// 保存字符串表(string table)的首地址。
// 符号表中的st_name不是字符串本身，而是字符串表中的偏移，所以后续需要通过这张表取出真正的名字。
static char *GlobalStringTable = NULL;
// 记录字符串表的总大小。
// 用来检查符号名偏移是否越界，也方便后续调试输出。
static size_t GlobalStringTableSize = 0;
// 保存节区名字字符串表(.shstrtab)的首地址。
// readelf -S 打印节区名时需要通过 sh_name 偏移在这张表中取出真实名字。
static char *GlobalSectionHeaderStringTable = NULL;
// 记录节区名字字符串表的总大小。
static size_t GlobalSectionHeaderStringTableSize = 0;
// 保存过滤后的函数符号表。
// 这里不是原始ELF符号，而是专门为ftrace整理出的函数信息表，包含函数名、起始地址、结束地址和大小。
static ElfFunctionSymbol *GlobalFunctionSymbolTable = NULL;
// 记录过滤后函数符号的数量。
// 后续ReadelfFindFunction()会遍历这张表，根据地址判断落在哪个函数范围内。
static size_t GlobalFunctionCount = 0;

// 控制是否输出ReadELF的内部调试信息
static bool GlobalReadelfVerbose = false;

void ReadelfSetVerbose(bool Enabled)
{
    GlobalReadelfVerbose = Enabled;
}

static void ReadelfDebugPrint(const char *Format, ...)
{
    if (!GlobalReadelfVerbose)
    {
        return;
    }
    va_list Args;
    va_start(Args, Format);
    vprintf(Format, Args);
    va_end(Args);
}

// 让本文件中的printf按需输出
#define printf ReadelfDebugPrint

static size_t ReadelfDisplayWidth(const char *S)
{
    if (S == NULL)
    {
        return 0;
    }
    size_t Width = 0;
    mbstate_t State;
    memset(&State, 0, sizeof(State));
    const char *P = S;
    while (*P != '\0')
    {
        wchar_t Wc;
        size_t Consumed = mbrtowc(&Wc, P, MB_CUR_MAX, &State);
        if (Consumed == (size_t)-1 || Consumed == (size_t)-2 || Consumed == 0)
        {
            // Fallback to single-byte width on invalid sequences
            memset(&State, 0, sizeof(State));
            Width += 1;
            P += 1;
            continue;
        }
        int W = wcwidth(Wc);
        if (W < 0)
        {
            W = 1;
        }
        Width += (size_t)W;
        P += Consumed;
    }
    return Width;
}
static const char *GetSectionNameByOffset(ELF_Word NameOffset);
static const char *GetSymbolNameByOffset(ELF_Word NameOffset);
static void GetSymbolSectionIndexString(ELF_Half SectionIndex, char *Buffer, size_t BufferSize);
static int CompareElfFunctionSymbol(const void *Left, const void *Right)
{
    const ElfFunctionSymbol *A = (const ElfFunctionSymbol *)Left;
    const ElfFunctionSymbol *B = (const ElfFunctionSymbol *)Right;

    if (A->start < B->start)
    {
        return -1;
    }
    if (A->start > B->start)
    {
        return 1;
    }
    return 0;
}
bool ReadelfInitialization(const char *ElfFile)
{
    FILE *FilePointer = NULL;
    int SymbolTableSectionIndex = -1;
    int StringTableSectionIndex = -1;
    printf("ReadelfInitialization开始执行\n");
    printf("输入参数 ElfFile = %s\n", ElfFile == NULL ? "(null)" : ElfFile);
    if (ElfFile == NULL)
    {
        printf("ReadelfInitialization失败：ElfFile是空指针\n");
        return false;
    }
    ReadelfFinalize();
    FilePointer = fopen(ElfFile, "rb");
    if (FilePointer == NULL)
    {
        printf("ReadelfInitialization失败：无法打开ELF文件 %s\n", ElfFile);
        return false;
    }
    if (!GetElfHeader(FilePointer, &GlobalElfHeader))
    {
        printf("ReadelfInitialization失败：GetElfHeader失败\n");
        goto fail;
    }
    if (!GetElfSectionHeaderTable(FilePointer, &GlobalElfHeader, &GlobalSectionHeaderTable, &GlobalSectionHeaderCount))
    {
        printf("ReadelfInitialization失败：GetElfSectionHeaderTable失败\n");
        goto fail;
    }
    if ((size_t)GlobalElfHeader.e_shstrndx < GlobalSectionHeaderCount &&
        GlobalSectionHeaderTable[GlobalElfHeader.e_shstrndx].sh_type == SHT_STRTAB)
    {
        if (!GetElfStringTable(FilePointer,
                               &GlobalSectionHeaderTable[GlobalElfHeader.e_shstrndx],
                               &GlobalSectionHeaderStringTable,
                               &GlobalSectionHeaderStringTableSize))
        {
            printf("ReadelfInitialization警告：节区名字字符串表读取失败，后续-S输出可能无法显示节区名\n");
        }
    }
    for (size_t i = 0; i < GlobalSectionHeaderCount; i++)
    {
        if (GlobalSectionHeaderTable[i].sh_type == SHT_SYMTAB)
        {
            SymbolTableSectionIndex = (int)i;
            break;
        }
    }
    if (SymbolTableSectionIndex < 0)
    {
        printf("ReadelfInitialization失败：没有找到SHT_SYMTAB\n");
        goto fail;
    }
    printf("ReadelfInitialization调试：找到符号表节区索引 = %d\n", SymbolTableSectionIndex);
    StringTableSectionIndex = (int)GlobalSectionHeaderTable[SymbolTableSectionIndex].sh_link;
    if (StringTableSectionIndex < 0 || (size_t)StringTableSectionIndex >= GlobalSectionHeaderCount)
    {
        printf("ReadelfInitialization失败：符号表关联的字符串表索引非法\n");
        printf("StringTableSectionIndex = %d\n", StringTableSectionIndex);
        goto fail;
    }
    if (GlobalSectionHeaderTable[StringTableSectionIndex].sh_type != SHT_STRTAB)
    {
        printf("ReadelfInitialization失败：symtab关联的节区不是字符串表\n");
        printf("关联节区类型 = %u\n", GlobalSectionHeaderTable[StringTableSectionIndex].sh_type);
        goto fail;
    }
    printf("ReadelfInitialization调试：找到字符串表节区索引 = %d\n", StringTableSectionIndex);
    if (!GetElfSymbolTable(FilePointer, &GlobalSectionHeaderTable[SymbolTableSectionIndex], &GlobalSymbolTable, &GlobalSymbolCount))
    {
        printf("ReadelfInitialization失败：GetElfSymbolTable失败\n");
        goto fail;
    }
    if (!GetElfStringTable(FilePointer, &GlobalSectionHeaderTable[StringTableSectionIndex], &GlobalStringTable, &GlobalStringTableSize))
    {
        printf("ReadelfInitialization失败：GetElfStringTable失败\n");
        goto fail;
    }
    if (!FilterElfFunctionSymbolTable(GlobalSymbolTable, GlobalSymbolCount, GlobalStringTable, GlobalStringTableSize, &GlobalFunctionSymbolTable, &GlobalFunctionCount))
    {
        printf("ReadelfInitialization失败：FilterElfFunctionSymbolTable失败\n");
        goto fail;
    }
    GlobalReadelfReady = true;
    printf("ReadelfInitialization成功\n");
    printf("GlobalSectionHeaderCount = %zu\n", GlobalSectionHeaderCount);
    printf("GlobalSymbolCount = %zu\n", GlobalSymbolCount);
    printf("GlobalStringTableSize = %zu\n", GlobalStringTableSize);
    printf("GlobalFunctionCount = %zu\n", GlobalFunctionCount);
    fclose(FilePointer);
    return true;
fail:
    if (FilePointer != NULL)
    {
        fclose(FilePointer);
    }
    ReadelfFinalize();
    return false;
}
void ReadelfFinalize(void)
{
    free(GlobalSectionHeaderTable);
    GlobalSectionHeaderTable = NULL;
    GlobalSectionHeaderCount = 0;
    free(GlobalSymbolTable);
    GlobalSymbolTable = NULL;
    GlobalSymbolCount = 0;
    free(GlobalStringTable);
    GlobalStringTable = NULL;
    GlobalStringTableSize = 0;
    free(GlobalSectionHeaderStringTable);
    GlobalSectionHeaderStringTable = NULL;
    GlobalSectionHeaderStringTableSize = 0;
    free(GlobalFunctionSymbolTable);
    GlobalFunctionSymbolTable = NULL;
    GlobalFunctionCount = 0;
    memset(&GlobalElfHeader, 0, sizeof(GlobalElfHeader));
    GlobalReadelfReady = false;
}
bool ReadelfFindFunction(vaddr_t address, ElfFunctionSymbol *out)
{
    if (GlobalReadelfReady == false)
    {
        printf("ReadelfFindFunction失败：ReadELF模块还没有初始化完成\n");
        return false;
    }
    if (out == NULL)
    {
        printf("ReadelfFindFunction失败：out是空指针\n");
        return false;
    }
    for (size_t i = 0; i < GlobalFunctionCount; i++)
    {
        if (GlobalFunctionSymbolTable[i].start <= address &&
            address < GlobalFunctionSymbolTable[i].end)
        {
            *out = GlobalFunctionSymbolTable[i];
            printf("ReadelfFindFunction成功：找到地址对应的函数\n");
            printf("address = " FMT_WORD "\n", address);
            printf("name = %s\n", out->name);
            printf("start = " FMT_WORD "\n", out->start);
            printf("end = " FMT_WORD "\n", out->end);
            printf("size = " FMT_WORD "\n", out->size);
            return true;
        }
    }
    printf("ReadelfFindFunction失败：没有找到地址对应的函数\n");
    printf("address = " FMT_WORD "\n", address);
    return false;
}
const char *ReadelfFindFunctionName(vaddr_t address)
{
    ElfFunctionSymbol FunctionSymbol;
    printf("ReadelfFindFunctionName开始执行\n");
    printf("address = " FMT_WORD "\n", address);
    if (ReadelfFindFunction(address, &FunctionSymbol))
    {
        printf("ReadelfFindFunctionName成功：找到对应函数名\n");
        printf("name = %s\n", FunctionSymbol.name);
        return FunctionSymbol.name;
    }
    printf("ReadelfFindFunctionName失败：没有找到对应函数名\n");
    return "哦豁，我也不知道什么情况，反正ReadelfFindFunctionName函数寄了";
}
bool GetElfHeader(FILE *FilePointer, ELF_Ehdr *ElfHeader)
{
    if (FilePointer == NULL || ElfHeader == NULL)
    {
        printf("GetElfHeader失败：FilePointer=%p, ElfHeader=%p\n", (void *)FilePointer, (void *)ElfHeader);
        return false;
    }
    if (fseek(FilePointer, 0, SEEK_SET) != 0)
    {
        printf("GetElfHeader失败：fseek无法定位到ELF文件开头\n");
        return false;
    }
    if (fread(ElfHeader, sizeof(ELF_Ehdr), 1, FilePointer) != 1)
    {
        printf("GetElfHeader失败：读取ELF头失败，期望读取大小=%zu\n", sizeof(ELF_Ehdr));
        return false;
    }
    printf("GetElfHeader调试：成功读取ELF头\n");
    printf("e_ident magic(ELF魔数) = %02x %02x %02x %02x\n", ElfHeader->e_ident[EI_MAG0], ElfHeader->e_ident[EI_MAG1], ElfHeader->e_ident[EI_MAG2], ElfHeader->e_ident[EI_MAG3]);
    printf("e_ident class(ELF位数) = %u\n", ElfHeader->e_ident[EI_CLASS]);
    printf("e_ident data(数据编码格式) = %u\n", ElfHeader->e_ident[EI_DATA]);
    printf("e_ident version(ELF标识版本) = %u\n", ElfHeader->e_ident[EI_VERSION]);
    printf("e_type(ELF文件类型) = %u\n", ElfHeader->e_type);
    printf("e_machine(目标架构) = %u\n", ElfHeader->e_machine);
    printf("e_version(ELF头版本) = %u\n", ElfHeader->e_version);
    printf("e_entry(程序入口地址) = 0x%lx\n", (unsigned long)ElfHeader->e_entry);
    printf("e_phoff(程序头表文件偏移) = %lu\n", (unsigned long)ElfHeader->e_phoff);
    printf("e_shoff(节区头表文件偏移) = %lu\n", (unsigned long)ElfHeader->e_shoff);
    printf("e_flags(处理器相关标志) = 0x%x\n", ElfHeader->e_flags);
    printf("e_ehsize(ELF头大小) = %u\n", ElfHeader->e_ehsize);
    printf("e_phentsize(程序头表项大小) = %u\n", ElfHeader->e_phentsize);
    printf("e_phnum(程序头表项数量) = %u\n", ElfHeader->e_phnum);
    printf("e_shentsize(节区头表项大小) = %u\n", ElfHeader->e_shentsize);
    printf("e_shnum(节区头表项数量) = %u\n", ElfHeader->e_shnum);
    printf("e_shstrndx(节区名字字符串表索引) = %u\n", ElfHeader->e_shstrndx);
    if (!CheckElfHeader(ElfHeader))
    {
        printf("GetElfHeader失败：ELF头校验没有通过\n");
        return false;
    }
    printf("GetElfHeader成功：已经读出并校验ELF头\n");
    return true;
}
bool GetElfSectionHeaderTable(FILE *FilePointer, const ELF_Ehdr *ElfHeader, ELF_Shdr **ElfSHT, size_t *Count)
{
    size_t SectionHeaderCount = 0;
    size_t SectionHeaderTableSize = 0;
    ELF_Shdr *SectionHeaderTable = NULL;
    printf("GetElfSectionHeaderTable开始执行\n");
    printf("输入参数 FilePointer = %p\n", (void *)FilePointer);
    printf("输入参数 ElfHeader = %p\n", (void *)ElfHeader);
    printf("输入参数 ElfSHT = %p\n", (void *)ElfSHT);
    printf("输入参数 Count = %p\n", (void *)Count);
    if (FilePointer == NULL || ElfHeader == NULL || ElfSHT == NULL || Count == NULL)
    {
        printf("GetElfSectionHeaderTable失败：参数存在空指针\n");
        return false;
    }
    *ElfSHT = NULL;
    *Count = 0;
    printf("GetElfSectionHeaderTable调试：准备检查ELF头中的节区头表信息\n");
    printf("e_shoff(节区头表文件偏移) = %lu\n", (unsigned long)ElfHeader->e_shoff);
    printf("e_shentsize(节区头表项大小) = %u\n", ElfHeader->e_shentsize);
    printf("e_shnum(节区头表项数量) = %u\n", ElfHeader->e_shnum);
    printf("e_shstrndx(节区名字字符串表索引) = %u\n", ElfHeader->e_shstrndx);
    if (ElfHeader->e_shoff == 0)
    {
        printf("GetElfSectionHeaderTable失败：e_shoff为0，说明没有节区头表\n");
        return false;
    }
    if (ElfHeader->e_shnum == 0)
    {
        printf("GetElfSectionHeaderTable失败：e_shnum为0，当前实现暂不支持扩展节区数量\n");
        return false;
    }
    if (ElfHeader->e_shentsize != sizeof(ELF_Shdr))
    {
        printf("GetElfSectionHeaderTable失败：节区头表项大小不匹配\n");
        printf("实际 e_shentsize = %u\n", ElfHeader->e_shentsize);
        printf("期望 sizeof(ELF_Shdr) = %zu\n", sizeof(ELF_Shdr));
        return false;
    }
    SectionHeaderCount = (size_t)ElfHeader->e_shnum;
    SectionHeaderTableSize = SectionHeaderCount * sizeof(ELF_Shdr);
    printf("GetElfSectionHeaderTable调试：计算节区头表大小完成\n");
    printf("SectionHeaderCount = %zu\n", SectionHeaderCount);
    printf("SectionHeaderTableSize = %zu 字节\n", SectionHeaderTableSize);
    if (fseek(FilePointer, (long)ElfHeader->e_shoff, SEEK_SET) != 0)
    {
        printf("GetElfSectionHeaderTable失败：fseek无法定位到节区头表\n");
        printf("目标偏移 = %lu\n", (unsigned long)ElfHeader->e_shoff);
        return false;
    }
    printf("GetElfSectionHeaderTable调试：已经定位到节区头表起始位置\n");
    SectionHeaderTable = (ELF_Shdr *)malloc(SectionHeaderTableSize);
    if (SectionHeaderTable == NULL)
    {
        printf("GetElfSectionHeaderTable失败：malloc分配节区头表内存失败\n");
        printf("申请大小 = %zu 字节\n", SectionHeaderTableSize);
        return false;
    }
    printf("GetElfSectionHeaderTable调试：malloc成功\n");
    printf("SectionHeaderTable = %p\n", (void *)SectionHeaderTable);
    if (fread(SectionHeaderTable, sizeof(ELF_Shdr), SectionHeaderCount, FilePointer) != SectionHeaderCount)
    {
        printf("GetElfSectionHeaderTable失败：读取节区头表失败\n");
        printf("期望读取表项数量 = %zu\n", SectionHeaderCount);
        free(SectionHeaderTable);
        return false;
    }
    printf("GetElfSectionHeaderTable调试：节区头表读取完成\n");
    *ElfSHT = SectionHeaderTable;
    *Count = SectionHeaderCount;
    printf("GetElfSectionHeaderTable成功：已经返回节区头表\n");
    printf("*ElfSHT = %p\n", (void *)*ElfSHT);
    printf("*Count = %zu\n", *Count);
    printf("\n");
    printf("Section Header Table 列表如下：\n");
    printf("[Nr] sh_name(名字偏移) sh_type(节区类型) sh_flags(节区标志)   sh_addr(装载地址)   sh_offset(文件偏移) sh_size(节区大小)\n");
    printf("     sh_link(关联索引) sh_info(附加信息) sh_addralign(地址对齐) sh_entsize(表项大小)\n");
    for (size_t i = 0; i < *Count; i++)
    {
        printf("[%2zu] %-16u %-16u 0x%-16lx 0x%-16lx %-16lu %-16lu\n",
               i,
               (*ElfSHT)[i].sh_name,
               (*ElfSHT)[i].sh_type,
               (unsigned long)(*ElfSHT)[i].sh_flags,
               (unsigned long)(*ElfSHT)[i].sh_addr,
               (unsigned long)(*ElfSHT)[i].sh_offset,
               (unsigned long)(*ElfSHT)[i].sh_size);
        printf("     %-16u %-16u %-16lu %-16lu\n",
               (*ElfSHT)[i].sh_link,
               (*ElfSHT)[i].sh_info,
               (unsigned long)(*ElfSHT)[i].sh_addralign,
               (unsigned long)(*ElfSHT)[i].sh_entsize);
    }
    return true;
}
bool GetElfSymbolTable(FILE *FilePointer, const ELF_Shdr *SymbolTableSection, ELF_Sym **ElfSymbolTable, size_t *Count)
{
    size_t SymbolCount = 0;
    size_t SymbolTableSize = 0;
    ELF_Sym *SymbolTable = NULL;
    printf("GetElfSymbolTable开始执行\n");
    printf("输入参数 FilePointer = %p\n", (void *)FilePointer);
    printf("输入参数 SymbolTableSection = %p\n", (void *)SymbolTableSection);
    printf("输入参数 ElfSymbolTable = %p\n", (void *)ElfSymbolTable);
    printf("输入参数 Count = %p\n", (void *)Count);
    if (FilePointer == NULL || SymbolTableSection == NULL || ElfSymbolTable == NULL || Count == NULL)
    {
        printf("GetElfSymbolTable失败：参数存在空指针\n");
        return false;
    }
    *ElfSymbolTable = NULL;
    *Count = 0;
    printf("GetElfSymbolTable调试：准备检查符号表节区信息\n");
    printf("sh_type(节区类型) = %u\n", SymbolTableSection->sh_type);
    printf("sh_offset(符号表文件偏移) = %lu\n", (unsigned long)SymbolTableSection->sh_offset);
    printf("sh_size(符号表总大小) = %lu\n", (unsigned long)SymbolTableSection->sh_size);
    printf("sh_link(关联字符串表索引) = %u\n", SymbolTableSection->sh_link);
    printf("sh_info(附加信息) = %u\n", SymbolTableSection->sh_info);
    printf("sh_addralign(地址对齐) = %lu\n", (unsigned long)SymbolTableSection->sh_addralign);
    printf("sh_entsize(单个符号表项大小) = %lu\n", (unsigned long)SymbolTableSection->sh_entsize);
    if (SymbolTableSection->sh_type != SHT_SYMTAB)
    {
        printf("GetElfSymbolTable失败：当前节区不是SHT_SYMTAB\n");
        return false;
    }
    if (SymbolTableSection->sh_offset == 0)
    {
        printf("GetElfSymbolTable失败：sh_offset为0，无法定位符号表\n");
        return false;
    }
    if (SymbolTableSection->sh_size == 0)
    {
        printf("GetElfSymbolTable失败：sh_size为0，符号表为空\n");
        return false;
    }
    if (SymbolTableSection->sh_entsize != sizeof(ELF_Sym))
    {
        printf("GetElfSymbolTable失败：符号表项大小不匹配\n");
        printf("实际 sh_entsize = %lu\n", (unsigned long)SymbolTableSection->sh_entsize);
        printf("期望 sizeof(ELF_Sym) = %zu\n", sizeof(ELF_Sym));
        return false;
    }
    SymbolCount = (size_t)(SymbolTableSection->sh_size / SymbolTableSection->sh_entsize);
    SymbolTableSize = SymbolCount * sizeof(ELF_Sym);
    printf("GetElfSymbolTable调试：符号表大小计算完成\n");
    printf("SymbolCount = %zu\n", SymbolCount);
    printf("SymbolTableSize = %zu 字节\n", SymbolTableSize);
    if (fseek(FilePointer, (long)SymbolTableSection->sh_offset, SEEK_SET) != 0)
    {
        printf("GetElfSymbolTable失败：fseek无法定位到符号表\n");
        printf("目标偏移 = %lu\n", (unsigned long)SymbolTableSection->sh_offset);
        return false;
    }
    printf("GetElfSymbolTable调试：已经定位到符号表起始位置\n");
    SymbolTable = (ELF_Sym *)malloc(SymbolTableSize);
    if (SymbolTable == NULL)
    {
        printf("GetElfSymbolTable失败：malloc分配符号表内存失败\n");
        printf("申请大小 = %zu 字节\n", SymbolTableSize);
        return false;
    }
    printf("GetElfSymbolTable调试：malloc成功\n");
    printf("SymbolTable = %p\n", (void *)SymbolTable);
    if (fread(SymbolTable, sizeof(ELF_Sym), SymbolCount, FilePointer) != SymbolCount)
    {
        printf("GetElfSymbolTable失败：读取符号表失败\n");
        printf("期望读取符号表项数量 = %zu\n", SymbolCount);
        free(SymbolTable);
        return false;
    }
    printf("GetElfSymbolTable调试：符号表读取完成\n");
    *ElfSymbolTable = SymbolTable;
    *Count = SymbolCount;
    printf("GetElfSymbolTable成功：已经返回符号表\n");
    printf("*ElfSymbolTable = %p\n", (void *)*ElfSymbolTable);
    printf("*Count = %zu\n", *Count);
    printf("\n");
    printf("Symbol Table 列表如下：\n");
    printf("[Nr] st_name(名字偏移) st_value(符号值/地址) st_size(大小) st_info(类型和绑定) st_other(可见性) st_shndx(节区索引)\n");
    for (size_t i = 0; i < *Count; i++)
    {
        printf("[%2zu] %-17u 0x%-18lx %-12lu %-18u %-14u %-14u\n",
               i,
               (*ElfSymbolTable)[i].st_name,
               (unsigned long)(*ElfSymbolTable)[i].st_value,
               (unsigned long)(*ElfSymbolTable)[i].st_size,
               (unsigned int)(*ElfSymbolTable)[i].st_info,
               (unsigned int)(*ElfSymbolTable)[i].st_other,
               (unsigned int)(*ElfSymbolTable)[i].st_shndx);
    }
    return true;
}
bool GetElfStringTable(FILE *FilePointer, const ELF_Shdr *StringTableSection, char **StringTable, size_t *Size)
{
    size_t StringTableSize = 0;
    char *StringTableBuffer = NULL;
    printf("GetElfStringTable开始执行\n");
    printf("输入参数 FilePointer = %p\n", (void *)FilePointer);
    printf("输入参数 StringTableSection = %p\n", (void *)StringTableSection);
    printf("输入参数 StringTable = %p\n", (void *)StringTable);
    printf("输入参数 Size = %p\n", (void *)Size);
    if (FilePointer == NULL || StringTableSection == NULL || StringTable == NULL || Size == NULL)
    {
        printf("GetElfStringTable失败：参数存在空指针\n");
        return false;
    }
    *StringTable = NULL;
    *Size = 0;
    printf("GetElfStringTable调试：准备检查字符串表节区信息\n");
    printf("sh_type(节区类型) = %u\n", StringTableSection->sh_type);
    printf("sh_offset(字符串表文件偏移) = %lu\n", (unsigned long)StringTableSection->sh_offset);
    printf("sh_size(字符串表总大小) = %lu\n", (unsigned long)StringTableSection->sh_size);
    printf("sh_link(关联节区索引) = %u\n", StringTableSection->sh_link);
    printf("sh_info(附加信息) = %u\n", StringTableSection->sh_info);
    printf("sh_addralign(地址对齐) = %lu\n", (unsigned long)StringTableSection->sh_addralign);
    printf("sh_entsize(表项大小) = %lu\n", (unsigned long)StringTableSection->sh_entsize);
    if (StringTableSection->sh_type != SHT_STRTAB)
    {
        printf("GetElfStringTable失败：当前节区不是SHT_STRTAB\n");
        return false;
    }
    if (StringTableSection->sh_offset == 0)
    {
        printf("GetElfStringTable失败：sh_offset为0，无法定位字符串表\n");
        return false;
    }
    if (StringTableSection->sh_size == 0)
    {
        printf("GetElfStringTable失败：sh_size为0，字符串表为空\n");
        return false;
    }
    StringTableSize = (size_t)StringTableSection->sh_size;
    printf("GetElfStringTable调试：字符串表大小计算完成\n");
    printf("StringTableSize = %zu 字节\n", StringTableSize);
    if (fseek(FilePointer, (long)StringTableSection->sh_offset, SEEK_SET) != 0)
    {
        printf("GetElfStringTable失败：fseek无法定位到字符串表\n");
        printf("目标偏移 = %lu\n", (unsigned long)StringTableSection->sh_offset);
        return false;
    }
    printf("GetElfStringTable调试：已经定位到字符串表起始位置\n");
    StringTableBuffer = (char *)malloc(StringTableSize);
    if (StringTableBuffer == NULL)
    {
        printf("GetElfStringTable失败：malloc分配字符串表内存失败\n");
        printf("申请大小 = %zu 字节\n", StringTableSize);
        return false;
    }
    printf("GetElfStringTable调试：malloc成功\n");
    printf("StringTableBuffer = %p\n", (void *)StringTableBuffer);
    if (fread(StringTableBuffer, 1, StringTableSize, FilePointer) != StringTableSize)
    {
        printf("GetElfStringTable失败：读取字符串表失败\n");
        printf("期望读取字节数 = %zu\n", StringTableSize);
        free(StringTableBuffer);
        return false;
    }
    printf("GetElfStringTable调试：字符串表读取完成\n");
    *StringTable = StringTableBuffer;
    *Size = StringTableSize;
    printf("GetElfStringTable成功：已经返回字符串表\n");
    printf("*StringTable = %p\n", (void *)*StringTable);
    printf("*Size = %zu\n", *Size);
    printf("\n");
    printf("String Table 列表如下：\n");
    printf("[Off] String\n");
    for (size_t i = 0; i < *Size;)
    {
        const char *CurrentString = (*StringTable) + i;
        size_t CurrentLength = strlen(CurrentString);

        printf("[%3zu] %s\n", i, CurrentString);

        i += CurrentLength + 1;
    }
    return true;
}
bool GetElfProgramHeaderTable(FILE *FilePointer, const ELF_Ehdr *ElfHeader, ELF_Phdr **ElfPHT, size_t *Count)
{
    size_t ProgramHeaderCount = 0;
    size_t ProgramHeaderTableSize = 0;
    ELF_Phdr *ProgramHeaderTable = NULL;
    printf("GetElfProgramHeaderTable开始执行\n");
    printf("输入参数 FilePointer = %p\n", (void *)FilePointer);
    printf("输入参数 ElfHeader = %p\n", (void *)ElfHeader);
    printf("输入参数 ElfPHT = %p\n", (void *)ElfPHT);
    printf("输入参数 Count = %p\n", (void *)Count);
    if (FilePointer == NULL || ElfHeader == NULL || ElfPHT == NULL || Count == NULL)
    {
        printf("GetElfProgramHeaderTable失败：参数存在空指针\n");
        return false;
    }
    *ElfPHT = NULL;
    *Count = 0;
    printf("GetElfProgramHeaderTable调试：准备检查ELF头中的程序头表信息\n");
    printf("e_phoff(程序头表文件偏移) = %lu\n", (unsigned long)ElfHeader->e_phoff);
    printf("e_phentsize(程序头表项大小) = %u\n", ElfHeader->e_phentsize);
    printf("e_phnum(程序头表项数量) = %u\n", ElfHeader->e_phnum);
    if (ElfHeader->e_phoff == 0)
    {
        printf("GetElfProgramHeaderTable失败：e_phoff为0，说明没有程序头表\n");
        return false;
    }
    if (ElfHeader->e_phnum == 0)
    {
        printf("GetElfProgramHeaderTable失败：e_phnum为0，程序头表为空\n");
        return false;
    }
    if (ElfHeader->e_phentsize != sizeof(ELF_Phdr))
    {
        printf("GetElfProgramHeaderTable失败：程序头表项大小不匹配\n");
        printf("实际 e_phentsize = %u\n", ElfHeader->e_phentsize);
        printf("期望 sizeof(ELF_Phdr) = %zu\n", sizeof(ELF_Phdr));
        return false;
    }
    ProgramHeaderCount = (size_t)ElfHeader->e_phnum;
    ProgramHeaderTableSize = ProgramHeaderCount * sizeof(ELF_Phdr);
    printf("GetElfProgramHeaderTable调试：程序头表大小计算完成\n");
    printf("ProgramHeaderCount = %zu\n", ProgramHeaderCount);
    printf("ProgramHeaderTableSize = %zu 字节\n", ProgramHeaderTableSize);
    if (fseek(FilePointer, (long)ElfHeader->e_phoff, SEEK_SET) != 0)
    {
        printf("GetElfProgramHeaderTable失败：fseek无法定位到程序头表\n");
        printf("目标偏移 = %lu\n", (unsigned long)ElfHeader->e_phoff);
        return false;
    }
    printf("GetElfProgramHeaderTable调试：已经定位到程序头表起始位置\n");
    ProgramHeaderTable = (ELF_Phdr *)malloc(ProgramHeaderTableSize);
    if (ProgramHeaderTable == NULL)
    {
        printf("GetElfProgramHeaderTable失败：malloc分配程序头表内存失败\n");
        printf("申请大小 = %zu 字节\n", ProgramHeaderTableSize);
        return false;
    }
    printf("GetElfProgramHeaderTable调试：malloc成功\n");
    printf("ProgramHeaderTable = %p\n", (void *)ProgramHeaderTable);
    if (fread(ProgramHeaderTable, sizeof(ELF_Phdr), ProgramHeaderCount, FilePointer) != ProgramHeaderCount)
    {
        printf("GetElfProgramHeaderTable失败：读取程序头表失败\n");
        printf("期望读取表项数量 = %zu\n", ProgramHeaderCount);
        free(ProgramHeaderTable);
        return false;
    }
    printf("GetElfProgramHeaderTable调试：程序头表读取完成\n");
    *ElfPHT = ProgramHeaderTable;
    *Count = ProgramHeaderCount;
    printf("GetElfProgramHeaderTable成功：已经返回程序头表\n");
    printf("*ElfPHT = %p\n", (void *)*ElfPHT);
    printf("*Count = %zu\n", *Count);
    printf("\n");
    printf("Program Header Table 列表如下：\n");
    printf("[Nr] p_type(段类型) p_flags(段标志) p_offset(文件偏移) p_vaddr(虚拟地址) p_paddr(物理地址)\n");
    printf("     p_filesz(文件大小) p_memsz(内存大小) p_align(对齐大小)\n");
    for (size_t i = 0; i < *Count; i++)
    {
        printf("[%2zu] %-14u 0x%-12x %-16lu 0x%-16lx 0x%-16lx\n",
               i,
               (unsigned int)(*ElfPHT)[i].p_type,
               (unsigned int)(*ElfPHT)[i].p_flags,
               (unsigned long)(*ElfPHT)[i].p_offset,
               (unsigned long)(*ElfPHT)[i].p_vaddr,
               (unsigned long)(*ElfPHT)[i].p_paddr);
        printf("     %-16lu %-16lu %-16lu\n",
               (unsigned long)(*ElfPHT)[i].p_filesz,
               (unsigned long)(*ElfPHT)[i].p_memsz,
               (unsigned long)(*ElfPHT)[i].p_align);
    }
    return true;
}
bool CheckElfHeader(const ELF_Ehdr *ElfHeader)
{
    if (ElfHeader == NULL)
    {
        printf("CheckElfHeader失败：ElfHeader是空指针\n");
        return false;
    }
    if (ElfHeader->e_ident[EI_MAG0] != ELFMAG0 ||
        ElfHeader->e_ident[EI_MAG1] != ELFMAG1 ||
        ElfHeader->e_ident[EI_MAG2] != ELFMAG2 ||
        ElfHeader->e_ident[EI_MAG3] != ELFMAG3)
    {
        printf("CheckElfHeader失败：不是合法的ELF文件\n");
        return false;
    }
    if (ElfHeader->e_ident[EI_CLASS] != MUXDEF(CONFIG_ISA64, ELFCLASS64, ELFCLASS32))
    {
        printf("CheckElfHeader失败：ELF位数不匹配，当前期望=%s，实际=%d\n", MUXDEF(CONFIG_ISA64, "ELF64", "ELF32"), ElfHeader->e_ident[EI_CLASS]);
        return false;
    }
    if (ElfHeader->e_ident[EI_VERSION] != EV_CURRENT)
    {
        printf("CheckElfHeader失败：e_ident中的版本非法，实际=%d\n", ElfHeader->e_ident[EI_VERSION]);
        return false;
    }
    if (ElfHeader->e_version != EV_CURRENT)
    {
        printf("CheckElfHeader失败：ELF头版本非法，实际=%u\n", ElfHeader->e_version);
        return false;
    }
    if (ElfHeader->e_ehsize != sizeof(ELF_Ehdr))
    {
        printf("CheckElfHeader失败：ELF头大小不匹配，实际=%u，期望=%zu\n", ElfHeader->e_ehsize, sizeof(ELF_Ehdr));
        return false;
    }
    if (ElfHeader->e_shentsize != sizeof(ELF_Shdr))
    {
        printf("CheckElfHeader失败：节区头表项大小不匹配，实际=%u，期望=%zu\n", ElfHeader->e_shentsize, sizeof(ELF_Shdr));
        return false;
    }
    if (ElfHeader->e_phentsize != sizeof(ELF_Phdr))
    {
        printf("CheckElfHeader失败：程序头表项大小不匹配，实际=%u，期望=%zu\n", ElfHeader->e_phentsize, sizeof(ELF_Phdr));
        return false;
    }
    printf("CheckElfHeader成功：ELF头检查通过\n");
    return true;
}
bool FilterElfFunctionSymbolTable(const ELF_Sym *ElfSymbolTable, size_t SymbolCount, const char *StringTable, size_t StringTableSize, ElfFunctionSymbol **FunctionSymbolTable, size_t *FunctionCount)
{
    size_t CountOfFunction = 0;
    size_t IndexOfFunction = 0;
    ElfFunctionSymbol *FunctionTable = NULL;
    printf("FilterElfFunctionSymbolTable开始执行\n");
    if (ElfSymbolTable == NULL || StringTable == NULL || FunctionSymbolTable == NULL || FunctionCount == NULL)
    {
        printf("FilterElfFunctionSymbolTable失败：参数存在空指针\n");
        return false;
    }
    *FunctionSymbolTable = NULL;
    *FunctionCount = 0;
    for (size_t i = 0; i < SymbolCount; i++)
    {
        if (GetElfSymbolType(ElfSymbolTable[i].st_info) != STT_FUNC)
        {
            continue;
        }
        if (ElfSymbolTable[i].st_shndx == SHN_UNDEF)
        {
            continue;
        }
        if ((size_t)ElfSymbolTable[i].st_name >= StringTableSize)
        {
            continue;
        }
        if (ElfSymbolTable[i].st_size == 0)
        {
            continue;
        }
        CountOfFunction++;
    }
    printf("过滤后函数符号数量 = %zu\n", CountOfFunction);
    if (CountOfFunction == 0)
    {
        printf("FilterElfFunctionSymbolTable失败：没有找到可用的函数符号\n");
        return false;
    }
    FunctionTable = (ElfFunctionSymbol *)malloc(sizeof(ElfFunctionSymbol) * CountOfFunction);
    if (FunctionTable == NULL)
    {
        printf("FilterElfFunctionSymbolTable失败：malloc分配函数符号表失败\n");
        return false;
    }
    for (size_t i = 0; i < SymbolCount; i++)
    {
        if (GetElfSymbolType(ElfSymbolTable[i].st_info) != STT_FUNC)
        {
            continue;
        }
        if (ElfSymbolTable[i].st_shndx == SHN_UNDEF)
        {
            continue;
        }
        if ((size_t)ElfSymbolTable[i].st_name >= StringTableSize)
        {
            continue;
        }
        if (ElfSymbolTable[i].st_size == 0)
        {
            continue;
        }
        FunctionTable[IndexOfFunction].name = StringTable + ElfSymbolTable[i].st_name;
        FunctionTable[IndexOfFunction].start = (vaddr_t)ElfSymbolTable[i].st_value;
        FunctionTable[IndexOfFunction].size = (word_t)ElfSymbolTable[i].st_size;
        FunctionTable[IndexOfFunction].end = (vaddr_t)(ElfSymbolTable[i].st_value + ElfSymbolTable[i].st_size);
        IndexOfFunction++;
    }
    qsort(FunctionTable, CountOfFunction, sizeof(ElfFunctionSymbol), CompareElfFunctionSymbol);
    *FunctionSymbolTable = FunctionTable;
    *FunctionCount = CountOfFunction;
    printf("Function Symbol Table 列表如下：\n");
    printf("[Nr] name(函数名)                     start(起始地址)        end(结束地址)          size(函数大小)\n");
    for (size_t i = 0; i < *FunctionCount; i++)
    {
        printf("[%2zu] %-30s 0x%-16lx 0x%-16lx %-16lu\n",
               i,
               (*FunctionSymbolTable)[i].name,
               (unsigned long)(*FunctionSymbolTable)[i].start,
               (unsigned long)(*FunctionSymbolTable)[i].end,
               (unsigned long)(*FunctionSymbolTable)[i].size);
    }
    return true;
}
static const char *GetElfClassString(unsigned char Class)
{
    switch (Class)
    {
    case ELFCLASSNONE:
        return "none";
    case ELFCLASS32:
        return "ELF32";
    case ELFCLASS64:
        return "ELF64";
    default:
        return "unknown";
    }
}
static const char *GetElfDataString(unsigned char Data)
{
    switch (Data)
    {
    case ELFDATANONE:
        return "none";
    case ELFDATA2LSB:
        return "2's complement, little endian";
    case ELFDATA2MSB:
        return "2's complement, big endian";
    default:
        return "unknown";
    }
}
static const char *GetElfTypeString(ELF_Half Type)
{
    switch (Type)
    {
    case ET_NONE:
        return "NONE (None)";
    case ET_REL:
        return "REL (Relocatable file)";
    case ET_EXEC:
        return "EXEC (Executable file)";
    case ET_DYN:
        return "DYN (Shared object file)";
    case ET_CORE:
        return "CORE (Core file)";
    default:
        return "UNKNOWN";
    }
}
static const char *GetSectionTypeString(ELF_Word Type)
{
    switch (Type)
    {
    case SHT_NULL:
        return "NULL";
    case SHT_PROGBITS:
        return "PROGBITS";
    case SHT_SYMTAB:
        return "SYMTAB";
    case SHT_STRTAB:
        return "STRTAB";
    case SHT_RELA:
        return "RELA";
    case SHT_HASH:
        return "HASH";
    case SHT_DYNAMIC:
        return "DYNAMIC";
    case SHT_NOTE:
        return "NOTE";
    case SHT_NOBITS:
        return "NOBITS";
    case SHT_REL:
        return "REL";
    case SHT_SHLIB:
        return "SHLIB";
    case SHT_DYNSYM:
        return "DYNSYM";
    case SHT_INIT_ARRAY:
        return "INIT_ARRAY";
    case SHT_FINI_ARRAY:
        return "FINI_ARRAY";
    case SHT_PREINIT_ARRAY:
        return "PREINIT_ARRAY";
    case SHT_GROUP:
        return "GROUP";
    case SHT_SYMTAB_SHNDX:
        return "SYMTAB SECTION INDICES";
#ifdef SHT_GNU_ATTRIBUTES
    case SHT_GNU_ATTRIBUTES:
        return "GNU_ATTRIBUTES";
#endif
#ifdef SHT_GNU_HASH
    case SHT_GNU_HASH:
        return "GNU_HASH";
#endif
#ifdef SHT_GNU_verdef
    case SHT_GNU_verdef:
        return "VERDEF";
#endif
#ifdef SHT_GNU_verneed
    case SHT_GNU_verneed:
        return "VERNEED";
#endif
#ifdef SHT_GNU_versym
    case SHT_GNU_versym:
        return "VERSYM";
#endif
    default:
        return "UNKNOWN";
    }
}
static void GetSectionFlagsString(ELF_Xword Flags, char *Buffer, size_t BufferSize)
{
    size_t Index = 0;

    if (Buffer == NULL || BufferSize == 0)
    {
        return;
    }

    Buffer[0] = '\0';
#define APPEND_FLAG(ch)             \
    do                              \
    {                               \
        if (Index + 1 < BufferSize) \
        {                           \
            Buffer[Index++] = (ch); \
            Buffer[Index] = '\0';   \
        }                           \
    } while (0)

    if (Flags & SHF_WRITE)
    {
        APPEND_FLAG('W');
    }
    if (Flags & SHF_ALLOC)
    {
        APPEND_FLAG('A');
    }
    if (Flags & SHF_EXECINSTR)
    {
        APPEND_FLAG('X');
    }
    if (Flags & SHF_MERGE)
    {
        APPEND_FLAG('M');
    }
    if (Flags & SHF_STRINGS)
    {
        APPEND_FLAG('S');
    }
    if (Flags & SHF_INFO_LINK)
    {
        APPEND_FLAG('I');
    }
    if (Flags & SHF_LINK_ORDER)
    {
        APPEND_FLAG('L');
    }
    if (Flags & SHF_OS_NONCONFORMING)
    {
        APPEND_FLAG('O');
    }
    if (Flags & SHF_GROUP)
    {
        APPEND_FLAG('G');
    }
    if (Flags & SHF_TLS)
    {
        APPEND_FLAG('T');
    }
#ifdef SHF_COMPRESSED
    if (Flags & SHF_COMPRESSED)
    {
        APPEND_FLAG('C');
    }
#endif
#ifdef SHF_EXCLUDE
    if (Flags & SHF_EXCLUDE)
    {
        APPEND_FLAG('E');
    }
#endif
#undef APPEND_FLAG
}
static const char *GetSymbolBindString(unsigned char Info)
{
    switch (GetElfSymbolBind(Info))
    {
    case STB_LOCAL:
        return "LOCAL";
    case STB_GLOBAL:
        return "GLOBAL";
    case STB_WEAK:
        return "WEAK";
#ifdef STB_GNU_UNIQUE
    case STB_GNU_UNIQUE:
        return "UNIQUE";
#endif
    default:
        return "UNKNOWN";
    }
}
static const char *GetSymbolTypeString(unsigned char Info)
{
    switch (GetElfSymbolType(Info))
    {
    case STT_NOTYPE:
        return "NOTYPE";
    case STT_OBJECT:
        return "OBJECT";
    case STT_FUNC:
        return "FUNC";
    case STT_SECTION:
        return "SECTION";
    case STT_FILE:
        return "FILE";
    case STT_COMMON:
        return "COMMON";
    case STT_TLS:
        return "TLS";
#ifdef STT_GNU_IFUNC
    case STT_GNU_IFUNC:
        return "IFUNC";
#endif
    default:
        return "UNKNOWN";
    }
}
static const char *GetSymbolVisibilityString(unsigned char Other)
{
    switch (GetElfSymbolVisibility(Other))
    {
    case STV_DEFAULT:
        return "DEFAULT";
    case STV_INTERNAL:
        return "INTERNAL";
    case STV_HIDDEN:
        return "HIDDEN";
    case STV_PROTECTED:
        return "PROTECTED";
    default:
        return "UNKNOWN";
    }
}
// 关闭对printf的重定义，确保显式readelf输出不受控制
#undef printf

void PrintElfFileHeader(void)
{
    if (GlobalReadelfReady == false)
    {
        printf("PrintElfHeader失败：ReadELF模块还没有初始化完成\n");
        return;
    }
    const char *Labels[] = {
        "Magic (ELF魔数)",
        "Class (ELF位数)",
        "Data (数据编码格式)",
        "Version (ELF标识版本)",
        "OS/ABI (操作系统/ABI标识)",
        "ABI Version (ABI版本)",
        "Type (ELF文件类型)",
        "Machine (目标架构)",
        "Version (ELF头版本)",
        "Entry point address (程序入口地址)",
        "Start of program headers (程序头表文件偏移)",
        "Start of section headers (节区头表文件偏移)",
        "Flags (处理器相关标志)",
        "Size of this header (ELF头大小)",
        "Size of program headers (程序头表项大小)",
        "Number of program headers (程序头表项数量)",
        "Size of section headers (节区头表项大小)",
        "Number of section headers (节区头表项数量)",
        "Section header string table index (节区名字字符串表索引)",
    };
    size_t LabelCount = sizeof(Labels) / sizeof(Labels[0]);
    size_t LabelWidth = 0;
    size_t LabelWidths[sizeof(Labels) / sizeof(Labels[0])] = {0};
    for (size_t i = 0; i < LabelCount; i++)
    {
        size_t Width = ReadelfDisplayWidth(Labels[i]);
        LabelWidths[i] = Width;
        if (Width > LabelWidth)
        {
            LabelWidth = Width;
        }
    }
    int Width = (int)LabelWidth;

    printf("ELF Header:\n");
    printf("  %s%*s %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x\n",
           Labels[0], (int)(Width - LabelWidths[0] + 1), "",
           GlobalElfHeader.e_ident[0],
           GlobalElfHeader.e_ident[1],
           GlobalElfHeader.e_ident[2],
           GlobalElfHeader.e_ident[3],
           GlobalElfHeader.e_ident[4],
           GlobalElfHeader.e_ident[5],
           GlobalElfHeader.e_ident[6],
           GlobalElfHeader.e_ident[7],
           GlobalElfHeader.e_ident[8],
           GlobalElfHeader.e_ident[9],
           GlobalElfHeader.e_ident[10],
           GlobalElfHeader.e_ident[11],
           GlobalElfHeader.e_ident[12],
           GlobalElfHeader.e_ident[13],
           GlobalElfHeader.e_ident[14],
           GlobalElfHeader.e_ident[15]);
    printf("  %s%*s %s\n", Labels[1], (int)(Width - LabelWidths[1] + 1), "", GetElfClassString(GlobalElfHeader.e_ident[EI_CLASS]));
    printf("  %s%*s %s\n", Labels[2], (int)(Width - LabelWidths[2] + 1), "", GetElfDataString(GlobalElfHeader.e_ident[EI_DATA]));
    printf("  %s%*s %u\n", Labels[3], (int)(Width - LabelWidths[3] + 1), "", GlobalElfHeader.e_ident[EI_VERSION]);
    printf("  %s%*s %u\n", Labels[4], (int)(Width - LabelWidths[4] + 1), "", GlobalElfHeader.e_ident[EI_OSABI]);
    printf("  %s%*s %u\n", Labels[5], (int)(Width - LabelWidths[5] + 1), "", GlobalElfHeader.e_ident[EI_ABIVERSION]);
    printf("  %s%*s %s\n", Labels[6], (int)(Width - LabelWidths[6] + 1), "", GetElfTypeString(GlobalElfHeader.e_type));
    printf("  %s%*s %u\n", Labels[7], (int)(Width - LabelWidths[7] + 1), "", GlobalElfHeader.e_machine);
    printf("  %s%*s 0x%x\n", Labels[8], (int)(Width - LabelWidths[8] + 1), "", GlobalElfHeader.e_version);
    printf("  %s%*s 0x%lx\n", Labels[9], (int)(Width - LabelWidths[9] + 1), "", (unsigned long)GlobalElfHeader.e_entry);
    printf("  %s%*s %lu (bytes into file)\n", Labels[10], (int)(Width - LabelWidths[10] + 1), "", (unsigned long)GlobalElfHeader.e_phoff);
    printf("  %s%*s %lu (bytes into file)\n", Labels[11], (int)(Width - LabelWidths[11] + 1), "", (unsigned long)GlobalElfHeader.e_shoff);
    printf("  %s%*s 0x%x\n", Labels[12], (int)(Width - LabelWidths[12] + 1), "", GlobalElfHeader.e_flags);
    printf("  %s%*s %u (bytes)\n", Labels[13], (int)(Width - LabelWidths[13] + 1), "", GlobalElfHeader.e_ehsize);
    printf("  %s%*s %u (bytes)\n", Labels[14], (int)(Width - LabelWidths[14] + 1), "", GlobalElfHeader.e_phentsize);
    printf("  %s%*s %u\n", Labels[15], (int)(Width - LabelWidths[15] + 1), "", GlobalElfHeader.e_phnum);
    printf("  %s%*s %u (bytes)\n", Labels[16], (int)(Width - LabelWidths[16] + 1), "", GlobalElfHeader.e_shentsize);
    printf("  %s%*s %u\n", Labels[17], (int)(Width - LabelWidths[17] + 1), "", GlobalElfHeader.e_shnum);
    printf("  %s%*s %u\n", Labels[18], (int)(Width - LabelWidths[18] + 1), "", GlobalElfHeader.e_shstrndx);
}

static const char *GetSectionNameByOffset(ELF_Word NameOffset)
{
    if (GlobalSectionHeaderStringTable == NULL)
    {
        return "<no-shstrtab>";
    }
    if ((size_t)NameOffset >= GlobalSectionHeaderStringTableSize)
    {
        return "<bad-name>";
    }
    return GlobalSectionHeaderStringTable + NameOffset;
}
static const char *GetSymbolNameByOffset(ELF_Word NameOffset)
{
    if (GlobalStringTable == NULL)
    {
        return "<no-strtab>";
    }
    if ((size_t)NameOffset >= GlobalStringTableSize)
    {
        return "<bad-name>";
    }
    return GlobalStringTable + NameOffset;
}
static void GetSymbolSectionIndexString(ELF_Half SectionIndex, char *Buffer, size_t BufferSize)
{
    if (Buffer == NULL || BufferSize == 0)
    {
        return;
    }
    if (SectionIndex == SHN_UNDEF)
    {
        snprintf(Buffer, BufferSize, "UND");
        return;
    }
    if (SectionIndex == SHN_ABS)
    {
        snprintf(Buffer, BufferSize, "ABS");
        return;
    }
    if (SectionIndex == SHN_COMMON)
    {
        snprintf(Buffer, BufferSize, "COM");
        return;
    }
    snprintf(Buffer, BufferSize, "%u", (unsigned int)SectionIndex);
}
void PrintElfSectionHeaders(void)
{
    int AddressWidth = MUXDEF(CONFIG_ISA64, 16, 8);
    char FlagsBuffer[32];
    if (GlobalReadelfReady == false)
    {
        printf("PrintElfSectionHeaders失败：ReadELF模块还没有初始化完成\n");
        return;
    }
    printf("Section Headers:\n");
    printf("  [Nr] Name              Type            Address%*sOff    Size   ES Flg Lk Inf Al\n",
           MUXDEF(CONFIG_ISA64, 9, 1), "");
    for (size_t i = 0; i < GlobalSectionHeaderCount; i++)
    {
        GetSectionFlagsString(GlobalSectionHeaderTable[i].sh_flags, FlagsBuffer, sizeof(FlagsBuffer));
        printf("  [%2zu] %-17.17s %-15.15s %0*lx %06lx %06lx %02lx %-3s %2u %3u %2lu\n",
               i,
               GetSectionNameByOffset(GlobalSectionHeaderTable[i].sh_name),
               GetSectionTypeString(GlobalSectionHeaderTable[i].sh_type),
               AddressWidth,
               (unsigned long)GlobalSectionHeaderTable[i].sh_addr,
               (unsigned long)GlobalSectionHeaderTable[i].sh_offset,
               (unsigned long)GlobalSectionHeaderTable[i].sh_size,
               (unsigned long)GlobalSectionHeaderTable[i].sh_entsize,
               FlagsBuffer,
               (unsigned int)GlobalSectionHeaderTable[i].sh_link,
               (unsigned int)GlobalSectionHeaderTable[i].sh_info,
               (unsigned long)GlobalSectionHeaderTable[i].sh_addralign);
    }
}
void PrintElfSymbols(void)
{
    int ValueWidth = MUXDEF(CONFIG_ISA64, 16, 8);
    int SymbolTableSectionIndex = -1;
    char SectionIndexBuffer[16];
    if (GlobalReadelfReady == false)
    {
        printf("PrintElfSymbols失败：ReadELF模块还没有初始化完成\n");
        return;
    }
    for (size_t i = 0; i < GlobalSectionHeaderCount; i++)
    {
        if (GlobalSectionHeaderTable[i].sh_type == SHT_SYMTAB)
        {
            SymbolTableSectionIndex = (int)i;
            break;
        }
    }
    printf("Symbol table '%s' contains %zu entries:\n",
           (SymbolTableSectionIndex >= 0) ? GetSectionNameByOffset(GlobalSectionHeaderTable[SymbolTableSectionIndex].sh_name) : "<symtab>",
           GlobalSymbolCount);
    printf("   Num: %*s %-5s %-7s %-6s %-8s %3s Name\n",
           ValueWidth,
           "Value",
           "Size",
           "Type",
           "Bind",
           "Vis",
           "Ndx");
    for (size_t i = 0; i < GlobalSymbolCount; i++)
    {
        GetSymbolSectionIndexString(GlobalSymbolTable[i].st_shndx, SectionIndexBuffer, sizeof(SectionIndexBuffer));
        printf("  %4zu: %0*lx %-5lu %-7s %-6s %-8s %3s %s\n",
               i,
               ValueWidth,
               (unsigned long)GlobalSymbolTable[i].st_value,
               (unsigned long)GlobalSymbolTable[i].st_size,
               GetSymbolTypeString(GlobalSymbolTable[i].st_info),
               GetSymbolBindString(GlobalSymbolTable[i].st_info),
               GetSymbolVisibilityString(GlobalSymbolTable[i].st_other),
               SectionIndexBuffer,
               GetSymbolNameByOffset(GlobalSymbolTable[i].st_name));
    }
}

#endif
