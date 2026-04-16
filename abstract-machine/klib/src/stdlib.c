#include <am.h>
#include <klib.h>
#include <klib-macros.h>

#include <stdint.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
static unsigned long int next = 1;

/*
他妈的代码作废
// 自己写的
struct MemoryPool // 总内存
{
  struct MemoryPool *next;
};
struct Memory64Byte_Block
{
  struct Memory64Byte_Block *next;
};
struct Memory1KiB_Block
{
  struct Memory1KiB_Block *next;
};
struct Memory16KiB_Block
{
  struct Memory16KiB_Block *next;
};
struct Memory128KiB_Block
{
  struct Memory128KiB_Block *next;
};
struct Memory1MiB_Block
{
  struct Memory1MiB_Block *next;
};
struct Memory2MiB_Block
{
  struct Memory2MiB_Block *next;
};
struct Memory4MiB_Block
{
};
struct Memory8MiB_Block
{
};
struct allocator // 拿来控制的
{

};
*/
// 也是自己写的
enum
{
  MinimumBlock = 6,
  MaximumBlock = 27,                                // 先整个128MiB内存再说，不够再加
  BlockSizeGrades = MaximumBlock - MinimumBlock + 1 // 块大小等级，看有多少种块
};
// 几年没写了，都忘记C++语法了，先标记一下这是前向声明，不然等下FreeBlock包含BH的指针的时候直接报错
typedef struct BlockHeader BlockHeader;
typedef struct FreeBlock
{
  BlockHeader *previous; // previous翻译：上一个。指向同一等级的空闲的链表中的前一个块
  BlockHeader *next;     // 下一个块
} FreeBlock;
// 块头
typedef struct BlockHeader
{
  uint8_t Grades;     // 拿来看块大小的，就是看等级的，看2的多少次方，反正现在先定最小是64Byte，MinimumBlock = 6，内存最大就128MiB
  bool used;          // 标记一下0是空闲，1是分配了
  FreeBlock FreeLink; // 拿来给空闲块之间互相串联的
} BlockHeader;
// 分配器
typedef struct Allocator
{
  uintptr_t start; // 内存开始
  uintptr_t end;   // 结束
  /*
  先写一下原理，不然后面几天就是真的看不懂了
  就是中括号里面的数字代表了多少等级的空闲块，然后统一一个接口管理了，就是0就代表最小等级，1就是最小+1，
  */
  BlockHeader *FreeArea[BlockSizeGrades];
  // 就反正第一次malloc和free的时候，heap要切分还有挂入FA[BSG]，所以给个提示避免重复初始化
  bool initialized;
} Allocator;
static Allocator allocator; // 先补一个实例
// 把等级转成块的大小
size_t Grades_to_Size(uint8_t Grades);
// 把等级转成FreeArea的下标
uint8_t Grades_to_index(uint8_t Grades);
// 根据申请大小来计算最小可容纳的块是什么等级
uint8_t Size_to_Grades(size_t size);
// 指针和块互相转化
void *Block_to_Payload(BlockHeader *block);
BlockHeader *Payload_to_Block(void *pointer);
// 把空闲块根据等级挂到空闲链表里面
void PushFreeBlock(BlockHeader *block);
// 把空闲块从链表里面拉出来
void RemoveFreeBlock(BlockHeader *block);
// 初始化
void AllocatorInit(void);
// 找人的
BlockHeader *FindFriend(BlockHeader *block);
// split分裂
BlockHeader *SplitBlock(BlockHeader *block);
// 合并的
BlockHeader *merge(BlockHeader *block);
// align是对齐的意思
// 向上对齐，比如AlignUp(100,64)就是128，就是
uintptr_t AlignUp(uintptr_t value, size_t align);
// 向下对齐，比如AlignDown(100,64)就是64
uintptr_t AlignDown(uintptr_t value, size_t align);
// 卧槽，漏了一个，取块函数
BlockHeader *GetBlock(uint8_t TargetGrades);

int rand(void)
{
  // RAND_MAX assumed to be 32767
  next = next * 1103515245 + 12345;
  return (unsigned int)(next / 65536) % 32768;
}

void srand(unsigned int seed)
{
  next = seed;
}

int abs(int x)
{
  return (x < 0 ? -x : x);
}

int atoi(const char *nptr)
{
  int x = 0;
  while (*nptr == ' ')
  {
    nptr++;
  }
  while (*nptr >= '0' && *nptr <= '9')
  {
    x = x * 10 + *nptr - '0';
    nptr++;
  }
  return x;
}

void *malloc(size_t size)
{
  // On native, malloc() will be called during initializaion of C runtime.
  // Therefore do not call panic() here, else it will yield a dead recursion:
  //   panic() -> putchar() -> (glibc) -> malloc() -> panic()
#if !(defined(__ISA_NATIVE__) && defined(__NATIVE_USE_KLIB__))
  // panic("Not implemented");
  if (size == 0)
  {
    return NULL;
  }
  AllocatorInit();
  size_t TotalSize = size + sizeof(BlockHeader);
  uint8_t Grades = Size_to_Grades(TotalSize);
  if (Grades == 0)
  {
    return NULL;
  }
  BlockHeader *block = GetBlock(Grades);
  if (block == NULL)
  {
    return NULL;
  }
  block->used = true;
  return Block_to_Payload(block);
#endif
  return NULL;
}

void free(void *ptr)
{
  if (ptr == NULL)
  {
    return;
  }
  BlockHeader *block = Payload_to_Block(ptr); // 先还原成块的头指针
  block->used = false;
  // 尝试不断向上合并，合并后就挂到对应等级的链表里面去
  block = merge(block);
  PushFreeBlock(block);
}

size_t Grades_to_Size(uint8_t Grades)
{
  if (Grades < MinimumBlock || Grades > MaximumBlock)
  {
    printf("他妈的内存分配等级超范围了\n");
    return 0;
  }
  return (size_t)1ULL << Grades; // 怎么裸机连数学库都用不了？连数学库都不提供吗？搞到现在我都只能用移位来设计了
}
uint8_t Grades_to_index(uint8_t Grades)
{
  if (Grades < MinimumBlock || Grades > MaximumBlock)
  {
    printf("内存分配等级超过了范围限制了\n");
    return 0;
  }
  return Grades - MinimumBlock;
}
uint8_t Size_to_Grades(size_t size)
{
  // 从头开始匹配，他妈的看都看不懂对数的数学公式，不管了，一个个配对
  for (uint8_t Grades = MinimumBlock; Grades <= MaximumBlock; Grades++)
  {
    size_t BlockSize = Grades_to_Size(Grades);
    // 找到能装下的就直接返回
    if (size <= BlockSize)
    {
      return Grades;
    }
  }
  printf("现在找不到空间分配了\n");
  return 0;
}
void *Block_to_Payload(BlockHeader *block)
{
  if (block == NULL)
  {
    return NULL;
  }
  // 找到一个更简单的方法，直接跳过现在的BH，然后就是真正的payload用的那个内存，就不需要额外的其他位移操作了
  // return (void *)(block + 1);
  // 唉算了，还是规范点写吧
  return (void *)((char *)block + sizeof(BlockHeader));
}
BlockHeader *Payload_to_Block(void *pointer)
{
  if (pointer == NULL)
  {
    return NULL;
  }
  return (BlockHeader *)((char *)pointer - sizeof(BlockHeader));
}
void PushFreeBlock(BlockHeader *block)
{
  if (block == NULL)
  {
    return;
  }
  uint8_t index = Grades_to_index(block->Grades);
  // 现在空闲链表的位置
  BlockHeader *NowHead = allocator.FreeArea[index];
  // 块初始化，先当新的链表的开头部分也就是表头
  block->used = false;
  block->FreeLink.previous = NULL;
  block->FreeLink.next = NowHead;
  // 如果原本的链表里面已经有了块，那就把指针指回来
  if (NowHead != NULL)
  {
    NowHead->FreeLink.previous = block;
  }
  // 更新这个等级的空闲的链表的表头
  allocator.FreeArea[index] = block;
}
void RemoveFreeBlock(BlockHeader *block)
{
  if (block == NULL)
  {
    return;
  }
  uint8_t index = Grades_to_index(block->Grades);
  BlockHeader *previous = block->FreeLink.previous;
  BlockHeader *next = block->FreeLink.next;
  if (previous != NULL)
  {
    // 链表设计的真的吐血
    // 假设allocator.FreeArea[index]-A-B-C三个块连着，这个时候previous是A，next是C
    // 如果block不是表头，那么block就是B，那么A.next就是C了
    previous->FreeLink.next = next;
  }
  else
  {
    // 这个时候就是block是表头，就是allocator.FreeArea[index]-B-C连着，然后B的previous是空的，删掉了B，那C就是头了
    allocator.FreeArea[index] = next;
  }
  // 如果后面还有块，就把后一个块的previous改好
  if (next != NULL)
  {
    // 就假设A-B-C连着，然后B善后，A.next就是连着C了，然后把C的previous改成A
    next->FreeLink.previous = previous;
  }
  // 断开，指针全部关掉
  block->FreeLink.previous = NULL;
  block->FreeLink.next = NULL;
}
void AllocatorInit(void)
{
  if (allocator.initialized)
  {
    return;
  }
  size_t MinimumSize = Grades_to_Size(MinimumBlock);
  // AM提供的内存边界
  uintptr_t RawStart = (uintptr_t)heap.start;
  uintptr_t RawEnd = (uintptr_t)heap.end;
  uintptr_t current;                                // 从allocator.start一直往后走，扫描整个内存堆，然后每次走过一个已经切好的块
  allocator.start = AlignUp(RawStart, MinimumSize); // 把起始地址往上对齐到最小块边界
  allocator.end = AlignDown(RawEnd, MinimumSize);   // 向下对齐
  // 先把所有等级的空闲链表头清空再初始化
  for (int i = 0; i < BlockSizeGrades; i++)
  {
    allocator.FreeArea[i] = NULL;
  }
  // 如果对齐后起点没有小于终点，就是没有对空间了，没有堆空间，理论上来讲应该是初始化完毕了，直接返回
  if (allocator.start >= allocator.end)
  {
    allocator.initialized = true;
    return;
  }
  current = allocator.start;                     // 开始调到开头，准备烧苗整段的内存堆了
  while (current + MinimumSize <= allocator.end) // 只要current后面还至少放得下一个最小块，就继续分割
  {
    size_t remaining = (size_t)(allocator.end - current); // 还有多少字节可用
    /*
    current相较于a.s的偏移量
    因为一个块要大小合法，还得起始位置也必须按自己的块大小对齐
    到时候条件判断就可以用offset%block_size==0来看整个地址能不能作为整个块的大小的七点
    唉，Linux的页分配器一个极度简化的模仿都这么难设计，伙伴系统很多东西看都看不懂，Linux那个内存架构和内存
    模型我都不敢想了，C++那个内存模型我都现在都没搞明白，唉
    */
    uintptr_t offset = current - allocator.start;
    /*
    本来打算直接就是从小到大不浪费内存，然后一步一步设置的，然后通过修改的方式做的
    然后又去问了下ai伙伴系统初始化要怎么做，最后ai给的多个建议中其中一个建议是从最
    大等级开始测试，然后在current这个位置尽量放一个最大的内存块，这样就可以实现初
    始空闲块更少，更规整
    */
    uint8_t Grades = MaximumBlock;
    while (Grades > MinimumBlock)
    {
      size_t BlockSize = Grades_to_Size(Grades);
      if (BlockSize <= remaining && (offset % BlockSize) == 0)
      {
        break;
      }
      Grades--;
    }
    if (Grades == MinimumBlock) // 确认一下是否真的是降低到最低等级已经到了降无可降的程度了
    {
      // 在理论上来说star和end已经按最小对齐的话，这个条件应该是总能成立的
      size_t BlockSize = Grades_to_Size(Grades);
      if (BlockSize > remaining || (offset % BlockSize) != 0) // 如果连最小块都放不下，就说明扫描该结束了
      {
        break;
      }
    }
    // 现在，current这个位置上可以放一个Grades等级的块了，直接把current给看成一个BlockHeader
    BlockHeader *block = (BlockHeader *)current;
    block->Grades = Grades;
    block->used = false;
    block->FreeLink.previous = NULL;
    block->FreeLink.next = NULL;
    // 把这个空闲块挂到对应等级的空闲链表里
    PushFreeBlock(block);
    // 然后current向后移动一个块，继续处理下一个还没有切分的空间
    current += Grades_to_Size(Grades);
  }
  allocator.initialized = true;
}
BlockHeader *FindFriend(BlockHeader *block)
{
  if (block == NULL)
  {
    return NULL;
  }
  size_t BlockSize = Grades_to_Size(block->Grades); // 显得出这个块实际大小
  uintptr_t BlockAddress = (uintptr_t)block;        // 用uintptr是为了后面做地址减法还有位运算
  /*
  一种可能性的设想是设计一个全场的内存地址，通过绝对地址，链接wsl Linux，通过核心的函数精确定位，然后进行操作，但是
  我能力有限做不出来
  然后这里也询问了ai，给的一个建议是设计一个相对地址而不是硬地址更好，因为伙伴关系应该建立在当前分配器管理的这段堆空
  间内部，而且而且而且最重要的一点是现在核心目标是以allocato.start作为统一基准点，我看了下，应该也能达到同样的效果的
  */
  uintptr_t offset = BlockAddress - allocator.start;
  /*
  图省事，直接用异或设计了本来想要循环加选择语句判断来实现的，结果发现越来越冗杂，所以直接异或操作
  就假设当前块的大小是256B对吧
  然后BlockSize=256=0x100
  如果offset=0x00
  那么FriendOffset = offset ^ BlockSize=0x100
  如果offset=0x100
  那么FriendOffset = offset ^ BlockSize=0x000
  就反正两个互相映射就正好是一对
  */
  uintptr_t FriendOffset = offset ^ BlockSize;
  /*
  但凡设计一个全局绝对地址，也不需要搞这玩意了，只能用这种方法切换到在AM中的硬地址
  */
  uintptr_t FriendAdress = allocator.start + FriendOffset;
  if (FriendAdress < allocator.start || FriendAdress + BlockSize > allocator.end)
  {
    return NULL;
  }
  // 目前返回的地址上对应的块
  return (BlockHeader *)FriendAdress;
}
BlockHeader *SplitBlock(BlockHeader *block)
{
  if (block == NULL)
  {
    return NULL;
  }
  // 如果已经是最小等级了，就不能再拆了，这时直接返回自己。
  if (block->Grades <= MinimumBlock)
  {
    return block;
  }
  // 原来的等级，例如 8 表示 256B。
  uint8_t OldGrades = block->Grades;
  // 拆分后两个新块的等级。
  uint8_t NewGrades = OldGrades - 1;
  // 新块的大小
  size_t NewBlockSize = Grades_to_Size(NewGrades);
  /*
  这个就是另一半的块头地址
  位置就是原来的block地址+半块大小
  */
  BlockHeader *friend = (BlockHeader *)((uintptr_t)block + NewBlockSize);
  // 原本的block现在降级，新的也初始化同一个等级
  block->Grades = NewGrades;
  block->used = false;
  block->FreeLink.previous = NULL;
  block->FreeLink.next = NULL;
  friend->Grades = NewGrades;
  friend->used = false;
  friend->FreeLink.previous = NULL;
  friend->FreeLink.next = NULL;
  // 新拆出来的friend放回空闲链表取
  PushFreeBlock(friend);
  return block;
}
BlockHeader *merge(BlockHeader *block)
{
  if (block == NULL)
  {
    return NULL;
  }
  // 只要现在的块还没打到最大等级就继续向上合并
  while (block->Grades < MaximumBlock)
  {
    BlockHeader *friend = FindFriend(block);
    // 从理论上来说，我是从理论上来说的啊，如果连friend都找不到，那就说明没办法合并了，前提是代码确实这么设计没有问题的情况下
    if (friend == NULL)
    {
      break;
    }
    // 才几个小时没看就看不懂了，先标记一下，如果friend还在被使用就肯定不能合并，并且得是同一个等级，不然也不能合并
    if (friend->used || friend->Grades != block->Grades)
    {
      break;
    }
    RemoveFreeBlock(friend); // 反正如果合并的时候就直接移除出链表
    // 再记录一下，假设两个块合并，然后[block][buddy]合并成更大的块之后，就新块从前面那个地址开始
    if ((uintptr_t)friend < (uintptr_t)block)
    {
      block = friend;
    }
    block->Grades++;
    block->used = false;
    block->FreeLink.previous = NULL;
    block->FreeLink.next = NULL;
  }
  return block;
}
// 向上对齐到align的整数倍，就比如100按64对齐，结果就是128
uintptr_t AlignUp(uintptr_t value, size_t align)
{
  uintptr_t remainder = value % align;
  if (remainder == 0)
  {
    return value;
  }
  // 有余数就代表没有对齐，然后就是当前的数值加上原本要对齐的数据减掉多余的余数
  return value + (align - remainder);
}
// 向下对齐到align的整数倍，比如100按64对齐，结果就是64
uintptr_t AlignDown(uintptr_t value, size_t align)
{
  uintptr_t remainder = value % align;
  // 把余数减掉就可以到最近的下边界
  return value - remainder;
}
BlockHeader *GetBlock(uint8_t TargetGrades)
{
  if (TargetGrades < MinimumBlock || TargetGrades > MaximumBlock)
  {
    return NULL;
  }
  for (uint8_t Grades = TargetGrades; Grades < MaximumBlock; Grades++)
  {
    uint8_t index = Grades_to_index(Grades);
    if (allocator.FreeArea[index] == NULL) // 没有空闲的就直接循环往上拉等级
    {
      continue;
    }
    // 找到了空闲的就从链表里面取出来
    BlockHeader *block = allocator.FreeArea[index];
    RemoveFreeBlock(block);
    while (block->Grades > TargetGrades) // 等级太大就往下拆分
    {
      block = SplitBlock(block);
    }
    return block;
  }
  // 就是如果从目标等级一直找到最大等级都没找到可用块，那理论上来说的话，那么就应该说明当前没有可分配空间了
  return NULL;
}

#endif