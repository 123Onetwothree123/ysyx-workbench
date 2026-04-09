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
void PrintIringbuf(void)
{
    if (GlobalIringbuf.count == 0)
    {
        printf("iringbuf是空的\n");
        return;
    }
    // 先标记一下，本质上是start=head-count，因为最新的写入的是head的前一个位置，最老的那一步是head往前退count步
    int start = (GlobalIringbuf.head - GlobalIringbuf.count + CONFIG_IRINGBUF_SIZE) % CONFIG_IRINGBUF_SIZE;
    printf("打印iringbuf\n");
    for (size_t i = 0; i < GlobalIringbuf.count; i++)
    {
        int index = (start + i) % CONFIG_IRINGBUF_SIZE;
        printf("pc = " FMT_WORD ", inst = 0x%08x, len = %d\n",
               GlobalIringbuf.buffer[index].pc,
               GlobalIringbuf.buffer[index].instruction,
               GlobalIringbuf.buffer[index].len);
    }
}