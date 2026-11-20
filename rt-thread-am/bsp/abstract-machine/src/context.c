#include <am.h>
#include <klib.h>
#include <rtthread.h>
/*
static Context *ev_handler(Event e, Context *c)
{
  switch (e.event)
  {
  default:
    printf("Unhandled event ID = %d\n", e.event);
    assert(0);
  }
  return c;
}
*/
static rt_ubase_t context_switch_to;
static rt_ubase_t context_switch_from;
static Context *ev_handler(Event e, Context *c) // e是自陷的事件，c是保存的上下文
{
  switch (e.event)
  {
  case EVENT_YIELD:
  {
    if (context_switch_to)
    {
      // conntxt_switch_to放的是to_thread->sp变量的地址，不是上下文，ICS又是规定rt_ubase_t是u long，拿u long存地址
      // 因为thread是指针，所以to_thread->sp是指针，所以得用指针的指针
      void **to = (void **)context_switch_to;
      Context *next = (Context *)(*to); // 解引用了就是上下文了，先直接把下一个指向上下文，后面的要修改的话就到时候再说
      if (context_switch_from)
      {
        void **from = (void **)context_switch_from;
        *from = c; // 写回，别到时候暂停后跑回来跑不回了
      }
      // 本来是没有这行的，让gpt审查下代码质量的时候，gpt建议用完变量后手动清零，免得又一个event yield的时候全局变量拉出去误报认为没有完成上下文切换
      context_switch_to = 0;
      context_switch_from = 0;
      assert(next != NULL);
      return next;
    }
    break;
  }
  case EVENT_IRQ_TIMER:
  {
    return c;
  }
  default:
    printf("Unhandled event ID = %d\n", e.event);
    assert(0);
  }
  return c;
}

// 给包裹函数用的
struct PackageArg
{
  void (*tentry)(void *);
  void *parameter;
  void (*texit)(void);
};
static void PackageFunctionKcontext(void *arg);

void __am_cte_init()
{
  cte_init(ev_handler);
}

void rt_hw_context_switch_to(rt_ubase_t to)
{
  // assert(0);
  assert(to != 0 && "rt_hw_context_switch_to的to都是0了，跑你妈逼");
  context_switch_from = 0; // 也是ai后来要求的
  context_switch_to = to;
  yield();
}

void rt_hw_context_switch(rt_ubase_t from, rt_ubase_t to)
{
  // assert(0);
  assert(from != 0 && "rt_hw_context_switch的from都是0了");
  assert(to != 0 && "rt_hw_context_switch的to都是0，没法定位");
  context_switch_from = from;
  context_switch_to = to;
  yield();
}

void rt_hw_context_switch_interrupt(void *context, rt_ubase_t from, rt_ubase_t to, struct rt_thread *to_thread)
{
  assert(0);
}

rt_uint8_t *rt_hw_stack_init(void *tentry, void *parameter, rt_uint8_t *stack_addr, void *texit)
{
  // assert(0);
  // return NULL;
  /*
  它的功能是以stack_addr为栈底创建一个入口为tentry, 参数为parameter的上下文, 并返回这个上下文结构的指针. 此外, 若上下文对应的内核线程从tentry返回, 则调用texit, RT-Thread会保证代码不会从texit中返回. 需要注意:

传入的stack_addr可能没有任何对齐限制, 最好将它对齐到sizeof(uintptr_t)再使用.
CTE的kcontext()要求不能从入口返回, 因此需要一种新的方式来支持texit的功能. 一种方式是构造一个包裹函数, 让包裹函数来调用tentry, 并在tentry返回后调用texit, 然后将这个包裹函数作为kcontext()的真正入口, 不过这还要求我们将tentry, parameter和texit这三个参数传给包裹函数, 应该如何解决这个传参问题呢?
  */
  // stack_addr = (rt_uint8_t *)((uintptr_t)stack_addr & ~(sizeof(uintptr_t) - 1));//这是ai写的
  uintptr_t addr = (uintptr_t)stack_addr; // 先存下来
  uintptr_t align = sizeof(uintptr_t);    // 因为要对齐
  addr = addr - addr % align;             // 万一有多出来的零头，就减掉，反正只要对齐就可以
  stack_addr = (rt_uint8_t *)addr;
  stack_addr -= sizeof(struct PackageArg); // 因为是栈向下增长，所以是减法
  struct PackageArg *package = (struct PackageArg *)stack_addr;
  package->tentry = (void (*)(void *))tentry;
  package->parameter = parameter;
  package->texit = (void (*)(void))texit;
  Area kstack = {
      .start = NULL, // 主要是因为这里init函数就只给了栈顶，没有给栈底这个起始的地址，反正只要能给kcontext提供在哪个栈空间建上下文就可以
      .end = stack_addr,
  };
  return (rt_uint8_t *)kcontext(kstack, PackageFunctionKcontext, package);
}
// 包裹函数
static void PackageFunctionKcontext(void *arg)
{
  struct PackageArg *package = (struct PackageArg *)arg;
  package->tentry(package->parameter);
  package->texit();
  // 问过ai，ai说因为入口函数不能返回，正常是跑到texit结束，但是以防万一，让我写一个死循环
  while (1)
  {
  }
}
