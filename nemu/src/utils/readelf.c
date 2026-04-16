// 自己设计的文件，目前先只支持ftrace功能
// 受不了了，doxygen注释让ai写的
/**
 * @file readelf.c
 * @brief 读取并缓存 ELF 元数据，为 `ftrace` 和 `readelf` 命令提供基础能力。
 *
 * @details
 * 本文件主要承担两类职责：
 * 1. 在初始化阶段读取 ELF 文件头、节区头表、符号表以及相关字符串表，并把
 *    `STT_FUNC` 函数符号整理成便于按地址查询的内部缓存。
 * 2. 在运行阶段提供两组接口：一组服务于 `ftrace`，负责把 PC/目标地址映射回
 *    函数名；另一组服务于调试命令，按近似 GNU `readelf` 的风格打印 ELF 结构。
 *
 * 当前实现强调“足够支持教学实验里的函数追踪和结构观察”，并没有覆盖完整 ELF
 * 规范。例如它依赖常规 `SHT_SYMTAB`，不会回退到 `SHT_DYNSYM`，也不处理扩展
 * 节区数量编码等较少出现在课程场景中的特殊情况。
 */
#define _XOPEN_SOURCE 700
#include <readelf.h>
#include <stdarg.h>

#ifdef CONFIG_ReadELF
typedef MUXDEF(CONFIG_ISA64, Elf64_Half, Elf32_Half) ELF_Half;
typedef MUXDEF(CONFIG_ISA64, Elf64_Word, Elf32_Word) ELF_Word;
typedef MUXDEF(CONFIG_ISA64, Elf64_Xword, Elf32_Word) ELF_Xword;
#define GetElfSymbolBind(info) MUXDEF(CONFIG_ISA64, ELF64_ST_BIND(info), ELF32_ST_BIND(info))
#define GetElfSymbolType(info) MUXDEF(CONFIG_ISA64, ELF64_ST_TYPE(info), ELF32_ST_TYPE(info))
#define GetElfSymbolVisibility(other) MUXDEF(CONFIG_ISA64, ELF64_ST_VISIBILITY(other), ELF32_ST_VISIBILITY(other))
/**
 * @brief 调用外部反汇编器将机器码格式化为可读指令文本。
 *
 * @details
 * 本文件只声明该接口，不负责实现。之所以在这里保留声明，是为了让 ELF 符号
 * 信息和指令文本在后续调试输出中能够组合使用；当前 `readelf` 主流程本身并不
 * 直接依赖它完成初始化。
 *
 * @param[out] str   用于接收反汇编结果的输出缓冲区。
 * @param[in]  size  输出缓冲区大小。
 * @param[in]  pc    当前指令对应的程序计数器。
 * @param[in]  code  指向待反汇编机器码的起始地址。
 * @param[in]  nbyte 待反汇编的字节数。
 */
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

/**
 * @brief 设置 ReadELF 模块内部调试输出的开关状态。
 *
 * @details
 * 本文件前半段把 `printf` 重定向成 `ReadelfDebugPrint()`，因此该开关只影响
 * 内部调试日志，不影响后面显式导出的 `PrintElfFileHeader()`、
 * `PrintElfSectionHeaders()` 和 `PrintElfSymbols()` 这类面向用户的输出接口。
 *
 * @param[in] Enabled 为 true 时开启调试输出，否则关闭。
 */
void ReadelfSetVerbose(bool Enabled)
{
    GlobalReadelfVerbose = Enabled;
}

/**
 * @brief 按需输出 ReadELF 的调试日志。
 *
 * @details
 * 该函数是文件内“可静默的 `printf`”实现。只有 `GlobalReadelfVerbose` 为 true
 * 时才会真正调用 `vprintf()` 输出内容；否则直接返回。这样可以保留大量内部
 * 诊断信息，而不影响默认运行时的终端输出整洁度。
 *
 * @param[in] Format printf 风格的格式字符串。
 * @param ... 与格式字符串匹配的可变参数列表。
 */
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

/**
 * @brief 判断一个字节是否是 UTF-8 延续字节。
 *
 * @details
 * UTF-8 多字节序列中，除首字节外的后续字节都必须满足二进制模式 `10xxxxxx`。
 * 这里单独拆成辅助函数，是为了让解码逻辑更直观，也避免在多个分支里重复位运算。
 *
 * @param[in] Byte 待判断的原始字节。
 * @return bool 是合法延续字节返回 true，否则返回 false。
 */
static bool ReadelfIsUtf8ContinuationByte(unsigned char Byte)
{
    return (Byte & 0xc0u) == 0x80u;
}

/**
 * @brief 从 UTF-8 字节流开头解出一个 Unicode 码点。
 *
 * @details
 * 该函数用于终端对齐计算，不追求完整文本库的复杂特性，但会做几项关键合法性检查：
 * 例如拒绝不完整序列、拒绝过长编码、拒绝 UTF-16 代理区以及超出 Unicode 上界
 * 的码点。这样可以在中英文混排场景下得到比按字节计数更可靠的显示宽度。
 *
 * @param[in]  S         指向待解析字符串当前位置的指针。
 * @param[out] CodePoint 成功时写出解析到的码点值。
 * @return size_t 成功时返回消耗的字节数；若参数为空、遇到字符串结尾或字节序列非法，
 *                则返回 0。
 */
static size_t ReadelfDecodeUtf8CodePoint(const char *S, uint32_t *CodePoint)
{
    if (S == NULL || CodePoint == NULL)
    {
        return 0;
    }

    const unsigned char *Bytes = (const unsigned char *)S;
    unsigned char B0 = Bytes[0];
    if (B0 == '\0')
    {
        return 0;
    }
    if (B0 < 0x80u)
    {
        *CodePoint = (uint32_t)B0;
        return 1;
    }

    unsigned char B1 = Bytes[1];
    if (B1 == '\0')
    {
        return 0;
    }

    if (B0 >= 0xc2u && B0 <= 0xdfu && ReadelfIsUtf8ContinuationByte(B1))
    {
        *CodePoint = ((uint32_t)(B0 & 0x1fu) << 6) | (uint32_t)(B1 & 0x3fu);
        return 2;
    }

    unsigned char B2 = Bytes[2];
    if (B2 == '\0')
    {
        return 0;
    }

    if (B0 == 0xe0u && B1 >= 0xa0u && B1 <= 0xbfu && ReadelfIsUtf8ContinuationByte(B2))
    {
        *CodePoint = ((uint32_t)(B0 & 0x0fu) << 12) | ((uint32_t)(B1 & 0x3fu) << 6) | (uint32_t)(B2 & 0x3fu);
        return 3;
    }
    if (((B0 >= 0xe1u && B0 <= 0xecu) || (B0 >= 0xeeu && B0 <= 0xefu)) &&
        ReadelfIsUtf8ContinuationByte(B1) && ReadelfIsUtf8ContinuationByte(B2))
    {
        *CodePoint = ((uint32_t)(B0 & 0x0fu) << 12) | ((uint32_t)(B1 & 0x3fu) << 6) | (uint32_t)(B2 & 0x3fu);
        return 3;
    }
    if (B0 == 0xedu && B1 >= 0x80u && B1 <= 0x9fu && ReadelfIsUtf8ContinuationByte(B2))
    {
        *CodePoint = ((uint32_t)(B0 & 0x0fu) << 12) | ((uint32_t)(B1 & 0x3fu) << 6) | (uint32_t)(B2 & 0x3fu);
        return 3;
    }

    unsigned char B3 = Bytes[3];
    if (B3 == '\0')
    {
        return 0;
    }

    if (B0 == 0xf0u && B1 >= 0x90u && B1 <= 0xbfu &&
        ReadelfIsUtf8ContinuationByte(B2) && ReadelfIsUtf8ContinuationByte(B3))
    {
        *CodePoint = ((uint32_t)(B0 & 0x07u) << 18) | ((uint32_t)(B1 & 0x3fu) << 12) |
                     ((uint32_t)(B2 & 0x3fu) << 6) | (uint32_t)(B3 & 0x3fu);
        return 4;
    }
    if (B0 >= 0xf1u && B0 <= 0xf3u &&
        ReadelfIsUtf8ContinuationByte(B1) && ReadelfIsUtf8ContinuationByte(B2) && ReadelfIsUtf8ContinuationByte(B3))
    {
        *CodePoint = ((uint32_t)(B0 & 0x07u) << 18) | ((uint32_t)(B1 & 0x3fu) << 12) |
                     ((uint32_t)(B2 & 0x3fu) << 6) | (uint32_t)(B3 & 0x3fu);
        return 4;
    }
    if (B0 == 0xf4u && B1 >= 0x80u && B1 <= 0x8fu &&
        ReadelfIsUtf8ContinuationByte(B2) && ReadelfIsUtf8ContinuationByte(B3))
    {
        *CodePoint = ((uint32_t)(B0 & 0x07u) << 18) | ((uint32_t)(B1 & 0x3fu) << 12) |
                     ((uint32_t)(B2 & 0x3fu) << 6) | (uint32_t)(B3 & 0x3fu);
        return 4;
    }

    return 0;
}

/**
 * @brief 计算单个 Unicode 码点在终端中的显示宽度。
 *
 * @details
 * 这里没有依赖宿主机 locale 或 `wcwidth()`，而是手动维护一套“足够支持终端表格
 * 对齐”的宽度规则：控制字符宽度视为 0，典型东亚宽字符视为 2，其余大多数可打印
 * 字符视为 1。这样即使运行环境没有正确配置 UTF-8 locale，也能较稳定地对齐
 * `readelf` 输出里的中英文标签。
 *
 * @param[in] CodePoint 待计算显示宽度的 Unicode 码点。
 * @return int 返回估算得到的列宽，通常为 0、1 或 2。
 */
static int ReadelfCodePointDisplayWidth(uint32_t CodePoint)
{
    if (CodePoint == 0)
    {
        return 0;
    }
    if (CodePoint < 0x20u || (CodePoint >= 0x7fu && CodePoint < 0xa0u))
    {
        return 0;
    }
    if (CodePoint == 0x2329u || CodePoint == 0x232au)
    {
        return 2;
    }
    if (CodePoint >= 0x1100u &&
        (CodePoint <= 0x115fu ||
         (CodePoint >= 0x2e80u && CodePoint <= 0xa4cfu && CodePoint != 0x303fu) ||
         (CodePoint >= 0xac00u && CodePoint <= 0xd7a3u) ||
         (CodePoint >= 0xf900u && CodePoint <= 0xfaffu) ||
         (CodePoint >= 0xfe10u && CodePoint <= 0xfe19u) ||
         (CodePoint >= 0xfe30u && CodePoint <= 0xfe6fu) ||
         (CodePoint >= 0xff00u && CodePoint <= 0xff60u) ||
         (CodePoint >= 0xffe0u && CodePoint <= 0xffe6u) ||
         (CodePoint >= 0x20000u && CodePoint <= 0x2fffdu) ||
         (CodePoint >= 0x30000u && CodePoint <= 0x3fffdu)))
    {
        return 2;
    }
    return 1;
}

/**
 * @brief 计算字符串在终端中的显示宽度。
 *
 * @details
 * 该函数逐个解码 UTF-8 码点并累计显示列宽，用于对齐带中文标签的 `readelf`
 * 风格输出。若遇到非法 UTF-8 序列，不会直接报错退出，而是把当前字节按宽度 1
 * 处理后继续向前扫描，尽量保证输出过程具有容错性。
 *
 * @param[in] S 待测字符串，预期为 UTF-8 编码。
 * @return size_t 字符串的显示宽度；若 `S == NULL` 则返回 0。
 */
static size_t ReadelfDisplayWidth(const char *S)
{
    if (S == NULL)
    {
        return 0;
    }

    size_t Width = 0;
    const char *P = S;
    while (*P != '\0')
    {
        uint32_t CodePoint = 0;
        size_t Consumed = ReadelfDecodeUtf8CodePoint(P, &CodePoint);
        if (Consumed == 0)
        {
            Width += 1;
            P += 1;
            continue;
        }
        Width += (size_t)ReadelfCodePointDisplayWidth(CodePoint);
        P += Consumed;
    }
    return Width;
}
static const char *GetSectionNameByOffset(ELF_Word NameOffset);
static const char *GetSymbolNameByOffset(ELF_Word NameOffset);
static void GetSymbolSectionIndexString(ELF_Half SectionIndex, char *Buffer, size_t BufferSize);
/**
 * @brief 按函数起始地址比较两个函数符号。
 *
 * @details
 * `FilterElfFunctionSymbolTable()` 在筛出函数符号后，会调用 `qsort()` 按起始地址
 * 排序，方便后续按地址做区间判断。这里只比较 `start`，因为当前查找逻辑只关心
 * 地址区间，不需要再按名字或大小做稳定排序。
 *
 * @param[in] Left  指向左侧函数符号的指针。
 * @param[in] Right 指向右侧函数符号的指针。
 * @return int 小于 0 表示 `Left` 排在前面，等于 0 表示两者起始地址相同，
 *             大于 0 表示 `Right` 排在前面。
 */
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
/**
 * @brief 初始化 ReadELF 模块并缓存后续查询所需的 ELF 数据。
 *
 * @details
 * 这是整个模块的主入口。初始化流程大致分为以下几步：
 * 1. 调用 `ReadelfFinalize()` 清理旧状态，保证重复初始化不会泄漏资源。
 * 2. 打开目标 ELF 文件，读取并校验文件头。
 * 3. 读取完整节区头表，并尽量读取节区名字符串表 `.shstrtab`，以支持 `-S` 输出。
 * 4. 在线性扫描中找到常规符号表 `SHT_SYMTAB`，再根据其 `sh_link` 找到关联字符串表。
 * 5. 读取原始符号表和字符串表，并筛选出对 `ftrace` 有意义的函数符号缓存。
 *
 * 只有上述步骤全部成功后，`GlobalReadelfReady` 才会被置为 true。成功后所有缓存的
 * 生命周期都由本模块管理，直到下一次初始化或 `ReadelfFinalize()` 被调用为止。
 *
 * @note 当前实现依赖常规 `SHT_SYMTAB`，不会自动回退到 `SHT_DYNSYM`。
 *
 * @param[in] ElfFile 待解析 ELF 文件的路径。
 * @return bool 初始化成功返回 true；如果文件打不开、ELF 头非法、关键节区缺失，
 *              或任一内存分配/读取步骤失败，则返回 false。
 */
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
/**
 * @brief 释放 ReadELF 模块在初始化阶段申请的全部资源。
 *
 * @details
 * 该函数会释放节区头表、符号表、字符串表、节区名字符串表以及筛选后的函数符号表，
 * 并把相关全局状态恢复到“未初始化”状态。它是幂等的：在未初始化或部分初始化失败
 * 的情况下重复调用也应当是安全的。
 *
 * @warning 任何由 `ReadelfFindFunction()` / `ReadelfFindFunctionName()` 返回、且内部
 *          指向模块缓存的数据指针，在本函数调用后都会失效。
 */
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
/**
 * @brief 在缓存的函数符号表中查找给定地址所属的函数。
 *
 * @details
 * 查找规则基于 `FilterElfFunctionSymbolTable()` 生成的函数区间 `[start, end)`。
 * 当前实现采用线性扫描：对教学实验中的符号表规模已经足够直观，也便于调试验证。
 * 若命中区间，则把对应的 `ElfFunctionSymbol` 拷贝到 `out`；其中 `name` 仍然指向
 * 模块内部的字符串表缓存，而不是新分配的独立副本。
 *
 * @param[in]  address 待查询的虚拟地址。
 * @param[out] out     查找成功时写回匹配到的函数符号信息。
 * @return bool 找到匹配函数返回 true；若模块尚未初始化、`out == NULL`，
 *              或地址不落在任何已知函数区间内，则返回 false。
 */
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
/**
 * @brief 查询给定地址所属函数的名称。
 *
 * @details
 * 这是 `ReadelfFindFunction()` 的轻量封装，适合 `ftrace` 这类只关心名字、不关心
 * 起止地址和大小的调用方。成功时直接返回内部字符串表中的指针，因此不会产生额外
 * 分配；失败时返回固定提示字符串，便于上层在日志里直接打印。
 *
 * @param[in] address 待查询的虚拟地址。
 * @return const char * 成功时返回内部字符串表中的函数名指针；失败时返回固定提示字串。
 */
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
/**
 * @brief 从文件开头读取并校验 ELF 文件头。
 *
 * @details
 * 函数会先把文件位置重置到开头，再读取一个完整的 `ELF_Ehdr` 结构，并调用
 * `CheckElfHeader()` 做与当前构建配置相关的关键字段校验。它不缓存文件指针，
 * 也不接管 `ElfHeader` 的生命周期，只负责把结果写入调用方提供的对象。
 *
 * @param[in]  FilePointer 已打开且可随机访问的 ELF 文件流。
 * @param[out] ElfHeader   用于接收读取结果的 ELF 文件头对象。
 * @return bool 读取并校验成功返回 true；若参数为空、定位失败、读取不足或头部校验
 *              失败，则返回 false。
 */
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
/**
 * @brief 读取 ELF 节区头表并分配对应缓存。
 *
 * @details
 * 节区头表是后续定位 `.symtab`、`.strtab` 和 `.shstrtab` 的基础，因此这里会把整张
 * 表一次性读入内存。成功时由本函数分配一块连续缓冲区并通过 `ElfSHT` 返回，释放
 * 责任由调用方承担。
 *
 * 当前实现只支持常规节区头表编码，不支持 `e_shnum == 0` 所表示的扩展节区数量。
 * 这不是 ELF 格式错误，而是实现范围的有意裁剪。
 *
 * @param[in]  FilePointer 已打开的 ELF 文件流。
 * @param[in]  ElfHeader   已成功读取并校验的 ELF 文件头。
 * @param[out] ElfSHT      用于返回新分配的节区头表缓冲区。
 * @param[out] Count       用于返回节区头表项数量。
 * @return bool 读取成功返回 true；若节区头表不存在、编码超出当前实现支持范围、
 *              文件定位/读取失败或内存分配失败，则返回 false。
 */
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
/**
 * @brief 读取 ELF 符号表节区并分配符号表缓存。
 *
 * @details
 * 本函数要求传入的节区头确实描述一个 `SHT_SYMTAB` 节区，并据此计算表项数量，
 * 再把整张符号表一次性读入内存。读取结果是原始 ELF 符号数组，不做筛选，也不
 * 解引用 `st_name`；这些工作留给后续过滤步骤完成。
 *
 * @param[in]  FilePointer        已打开的 ELF 文件流。
 * @param[in]  SymbolTableSection 指向符号表节区头的指针，必须是 `SHT_SYMTAB`。
 * @param[out] ElfSymbolTable     用于返回新分配的符号表缓冲区。
 * @param[out] Count              用于返回符号表项数量。
 * @return bool 读取成功返回 true；若节区类型不符、表项大小异常、文件定位/读取失败，
 *              或内存分配失败，则返回 false。
 */
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
/**
 * @brief 读取 ELF 字符串表节区并分配字符串缓存。
 *
 * @details
 * 该函数是一个通用字符串表读取器，既可读取符号名字符串表 `.strtab`，也可读取
 * 节区名字符串表 `.shstrtab`。返回值不是“字符串数组”，而是一整块原始字节缓存；
 * 调用方需要再通过偏移量（如 `st_name` / `sh_name`）定位到具体的 NUL 结尾字符串。
 *
 * @param[in]  FilePointer        已打开的 ELF 文件流。
 * @param[in]  StringTableSection 指向字符串表节区头的指针，必须是 `SHT_STRTAB`。
 * @param[out] StringTable        用于返回新分配的字符串表缓冲区。
 * @param[out] Size               用于返回字符串表总字节数。
 * @return bool 读取成功返回 true；若节区类型不符、字符串表为空、文件定位/读取失败，
 *              或内存分配失败，则返回 false。
 */
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
/**
 * @brief 读取 ELF 程序头表并分配对应缓存。
 *
 * @details
 * 程序头表主要描述文件如何装载到内存。当前 `ReadelfInitialization()` 并不依赖它来
 * 支撑 `ftrace`，但保留该辅助函数有两个好处：一是便于后续扩展 `readelf -l` 风格
 * 的输出，二是让模块内部对 ELF 主要表结构的读取接口保持完整。
 *
 * 成功时本函数会分配一块连续缓冲区并返回给调用方，释放责任由调用方承担。
 *
 * @param[in]  FilePointer 已打开的 ELF 文件流。
 * @param[in]  ElfHeader   已成功读取的 ELF 文件头。
 * @param[out] ElfPHT      用于返回新分配的程序头表缓冲区。
 * @param[out] Count       用于返回程序头表项数量。
 * @return bool 读取成功返回 true；若程序头表不存在、表项大小异常、文件定位/读取失败，
 *              或内存分配失败，则返回 false。
 */
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
/**
 * @brief 校验 ELF 文件头中的关键字段是否合法且与当前构建配置匹配。
 *
 * @details
 * 这里检查的是当前实现真正依赖的那部分字段，而不是对整个 ELF 规范做穷尽式验证。
 * 重点包括：
 * 1. ELF 魔数是否正确。
 * 2. 位数是否与当前编译配置（32/64 位 ISA）一致。
 * 3. 版本字段是否为 `EV_CURRENT`。
 * 4. 文件头、节区头表项、程序头表项的结构体大小是否与本地类型定义一致。
 *
 * 如果这些条件不满足，后续按本地结构体直接读取 ELF 数据就可能发生解释错误，因此
 * 函数会立即拒绝继续处理。
 *
 * @param[in] ElfHeader 待校验的 ELF 文件头。
 * @return bool 校验通过返回 true，否则返回 false。
 */
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
/**
 * @brief 从原始符号表中过滤出可用于函数追踪的函数符号。
 *
 * @details
 * 当前筛选规则非常明确，只保留同时满足以下条件的符号：
 * 1. 类型为 `STT_FUNC`。
 * 2. 不是 `SHN_UNDEF`，也就是确实在当前 ELF 中定义。
 * 3. `st_name` 没有越过字符串表边界。
 * 4. `st_size` 非 0，能够形成一个有意义的地址区间。
 *
 * 过滤结果会转换成更适合 `ftrace` 使用的 `ElfFunctionSymbol` 数组，其中：
 * `name` 直接指向字符串表内部，`start`/`end` 表示半开区间 `[start, end)`。
 * 最后还会按起始地址排序，方便后续查找逻辑线性扫描或将来升级到二分查找。
 *
 * @warning 返回的 `name` 指针依赖原始字符串表缓存的生命周期，不能在模块释放后继续使用。
 *
 * @param[in]  ElfSymbolTable     原始 ELF 符号表。
 * @param[in]  SymbolCount        原始符号表项数量。
 * @param[in]  StringTable        与符号表对应的字符串表。
 * @param[in]  StringTableSize    字符串表总字节数。
 * @param[out] FunctionSymbolTable 用于返回筛选并排序后的函数符号表。
 * @param[out] FunctionCount      用于返回筛选后的函数数量。
 * @return bool 过滤成功返回 true；若参数为空、没有任何可用函数符号，
 *              或内存分配失败，则返回 false。
 */
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
/**
 * @brief 将 ELF 类别编码转换为可读字符串。
 *
 * @details 该函数主要服务于 `PrintElfFileHeader()`，把 `e_ident[EI_CLASS]` 的数值
 *          转成更接近 GNU `readelf` 的文本描述。对当前实现不认识的编码统一返回
 *          `"unknown"`，避免在打印阶段传播魔法数字。
 *
 * @param[in] Class ELF 类别字段值。
 * @return const char * 对应的类别描述字符串。
 */
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
/**
 * @brief 将 ELF 数据编码方式转换为可读字符串。
 *
 * @details 该函数只负责把 `EI_DATA` 的枚举值转换成文本，不参与大小端兼容处理；
 *          当前实现默认输入文件已经通过 `CheckElfHeader()` 的基本约束检查。
 *
 * @param[in] Data ELF 数据编码字段值。
 * @return const char * 对应的数据编码描述字符串。
 */
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
/**
 * @brief 将 ELF 文件类型编码转换为可读字符串。
 *
 * @details 该函数用于展示 `e_type`，方便在 `readelf -h` 输出里区分可执行文件、
 *          共享对象和可重定位文件等常见类型。未知类型统一打印 `"UNKNOWN"`。
 *
 * @param[in] Type ELF 文件类型字段值。
 * @return const char * 对应的文件类型描述字符串。
 */
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
/**
 * @brief 将节区类型编码转换为可读字符串。
 *
 * @details
 * 该函数主要服务于 `PrintElfSectionHeaders()`，把 `sh_type` 显示成更易读的短名称。
 * 对 GNU 扩展节区类型使用 `#ifdef` 做条件支持，这样既能在支持的平台上显示更完整，
 * 也不会因为系统头文件缺少相关宏而导致编译失败。
 *
 * @param[in] Type 节区类型字段值。
 * @return const char * 对应的节区类型描述字符串。
 */
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
/**
 * @brief 将节区标志位压缩为 readelf 风格的字符标记串。
 *
 * @details
 * GNU `readelf -S` 通常会把多个布尔标志压缩成一串短字符，例如 `WAX`。本函数沿用
 * 这种展示方式，把当前实现关心的节区标志拼成紧凑文本。若缓冲区过小，会尽量写入
 * 前缀并保持 NUL 结尾，而不是越界写入。
 *
 * @param[in]  Flags      节区标志位集合。
 * @param[out] Buffer     接收标记串的缓冲区。
 * @param[in]  BufferSize 缓冲区大小。
 */
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
/**
 * @brief 将符号绑定属性转换为可读字符串。
 *
 * @details
 * `st_info` 同时编码了绑定属性和符号类型，这里只提取高位中的绑定部分，供
 * `PrintElfSymbols()` 以接近 GNU `readelf` 的格式打印。
 *
 * @param[in] Info 符号表项的 `st_info` 字段。
 * @return const char * 对应的绑定属性描述字符串。
 */
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
/**
 * @brief 将符号类型转换为可读字符串。
 *
 * @details
 * 与 `GetSymbolBindString()` 类似，该函数从 `st_info` 中提取符号类型位段，用于
 * 把 `FUNC`、`OBJECT`、`SECTION` 等类型以文本形式展示给用户。
 *
 * @param[in] Info 符号表项的 `st_info` 字段。
 * @return const char * 对应的符号类型描述字符串。
 */
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
/**
 * @brief 将符号可见性转换为可读字符串。
 *
 * @details
 * `st_other` 中只有一部分位真正表示可见性，本函数通过宏展开屏蔽掉无关位，只把
 * 最终可见性语义映射成 `DEFAULT`、`HIDDEN` 等可读字符串。
 *
 * @param[in] Other 符号表项的 `st_other` 字段。
 * @return const char * 对应的可见性描述字符串。
 */
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

/**
 * @brief 以 readelf 风格打印当前缓存的 ELF 文件头信息。
 *
 * @details
 * 该函数面向命令行用户，而不是内部调试日志，因此位于 `#undef printf` 之后，
 * 不受 `ReadelfSetVerbose()` 控制。打印时会先根据中英文标签的显示宽度动态计算
 * 对齐列宽，尽量让双语说明在不同终端环境下都保持整齐。
 *
 * @note 调用前要求模块已经成功初始化，否则函数只输出错误提示并返回。
 */
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

/**
 * @brief 通过节区名字偏移解析真实节区名。
 *
 * @details
 * 节区头里的 `sh_name` 本质上是 `.shstrtab` 中的字节偏移，而不是直接存放名字。
 * 本函数负责完成这一步“偏移到字符串”的映射，并在缺失节区名字表或偏移越界时
 * 返回固定占位字符串，避免打印逻辑访问非法地址。
 *
 * @param[in] NameOffset 节区头中的 `sh_name` 偏移值。
 * @return const char * 成功时返回节区名，失败时返回占位字符串。
 */
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
/**
 * @brief 通过符号名字偏移解析真实符号名。
 *
 * @details
 * 与 `GetSectionNameByOffset()` 类似，符号表项中的 `st_name` 也是字符串表偏移。
 * 该函数统一封装越界检查和“无字符串表”处理，让符号打印与函数过滤逻辑都能复用
 * 同一套安全访问语义。
 *
 * @param[in] NameOffset 符号表项中的 `st_name` 偏移值。
 * @return const char * 成功时返回符号名，失败时返回占位字符串。
 */
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
/**
 * @brief 将符号所在节区索引格式化为可打印文本。
 *
 * @details
 * 符号表里的 `st_shndx` 既可能是普通数字索引，也可能是 `SHN_UNDEF`、`SHN_ABS`、
 * `SHN_COMMON` 这类保留值。为了让输出风格更贴近 GNU `readelf`，这里会把常见
 * 特殊值转成 `UND`、`ABS`、`COM` 等短标签，其余值再按十进制索引打印。
 *
 * @param[in]  SectionIndex 符号表项中的 `st_shndx` 字段值。
 * @param[out] Buffer       接收格式化结果的缓冲区。
 * @param[in]  BufferSize   缓冲区大小。
 */
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
/**
 * @brief 以 readelf 风格打印当前缓存的节区头表。
 *
 * @details
 * 输出内容基于初始化阶段缓存的整张节区头表，并结合节区名字字符串表和若干格式化
 * 辅助函数，把 `sh_type`、`sh_flags` 等字段打印成更易读的文本。当前格式重点追求
 * 教学场景下的可读性，与 GNU `readelf` 接近但不保证逐列完全一致。
 *
 * @note 调用前要求模块已经成功初始化，否则函数只输出错误提示并返回。
 */
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
/**
 * @brief 以 readelf 风格打印当前缓存的符号表。
 *
 * @details
 * 该函数会先重新找到 `SHT_SYMTAB` 节区，用它的节区名作为表头，再逐项打印缓存的
 * 原始符号表内容。这里展示的是完整原始符号表，而不是筛选后的函数符号表，因此更
 * 适合排查 ELF 结构问题；`ftrace` 真正使用的是更早构建出来的函数缓存。
 *
 * @note 调用前要求模块已经成功初始化，否则函数只输出错误提示并返回。
 */
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
