// 自己添加的文件
#include <iringbuf.h>
typedef struct RecordInstruction
{
    vaddr_t pc;
    uint32_t instruction;
    int len;
} RecordInstruction;
typedef struct iringbuf
{
    RecordInstruction buffer[CONFIG_IRINGBUF_SIZE];
    int head;  // 下一次写入的位置
    int count; // 当前有效条目数，也就是目前存了多少条
} iringbuf;
// 真正的缓冲区实例
static iringbuf GlobalIringbuf;
void IringbufInitialization(void)
{
    memset(&GlobalIringbuf, 0, sizeof(GlobalIringbuf));
}
void RecordAInstruction(vaddr_t pc, uint32_t instruction, int len)
{
    GlobalIringbuf.buffer[GlobalIringbuf.head].pc = pc;
    GlobalIringbuf.buffer[GlobalIringbuf.head].instruction = instruction;
    GlobalIringbuf.buffer[GlobalIringbuf.head].len = len;
    // 标记一下数学算法，让head移动后取模，如果取模为0的话那就是到了数组末尾，然后就返回到0
    GlobalIringbuf.head = (GlobalIringbuf.head + 1) % CONFIG_IRINGBUF_SIZE;
    if (GlobalIringbuf.count < CONFIG_IRINGBUF_SIZE)
    {
        GlobalIringbuf.count++;
    }
}
void PrintIringbuf(vaddr_t err_pc)
{
    if (GlobalIringbuf.count == 0)
    {
        printf("iringbuf是空的\n");
        return;
    }
    void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
    // 先标记一下，本质上是start=head-count，因为最新的写入的是head的前一个位置，最老的那一步是head往前退count步
    int start = (GlobalIringbuf.head - GlobalIringbuf.count + CONFIG_IRINGBUF_SIZE) % CONFIG_IRINGBUF_SIZE;
    printf("打印iringbuf\n");
    for (size_t i = 0; i < GlobalIringbuf.count; i++)
    {
        int index = (start + i) % CONFIG_IRINGBUF_SIZE;
        // 取出这一条指令记录的指针，主要是为了的后面访问可以更方便一些
        RecordInstruction *entry = &GlobalIringbuf.buffer[index];
        const char *marker = (entry->pc == err_pc) ? "-->" : "   ";
        char AsmBuf[128] = {};    // 用来保存capstone反汇编后的字符串
        char BytesBuf[32] = {};   // 拿来保存机器码按字节展开后的文本的
        char *pointer = BytesBuf; // 始终指向BytesBuf当前已经写到的位置
        // 地址强转成字节指针的主要目的：后面既可以拿给disassemble，也可以按字节逐个打印
        uint8_t *code = (uint8_t *)&entry->instruction;
        disassemble(AsmBuf, sizeof(AsmBuf), entry->pc, code, entry->len);
        for (int j = entry->len - 1; j >= 0; j--)
        { /*
            这里按高字节到低字节的显示顺序输出是因为是RISC-V，sizeof(bytes_buf) - (p - bytes_buf)表示当前缓冲区还剩多少空间，
            p+=是把写指针往后推
            */
            pointer += snprintf(pointer, sizeof(BytesBuf) - (pointer - BytesBuf), " %02x", code[j]);
        }
        printf("%s " FMT_WORD ": %-24s%s\n",
               marker,
               entry->pc,
               AsmBuf,
               BytesBuf);
    }
}