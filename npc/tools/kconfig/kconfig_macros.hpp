/* SPDX-License-Identifier: GPL-2.0 */
/*
 * 必须跨 TU 使用的宏：C++ 模块无法导出宏，这些定义保留在头文件中。
 * 使用前需要 import kconfig.core;（展开依赖模块里的函数/类型，
 * offsetof/std::remove_pointer_t 等由使用者的 std 提供）。
 */
#ifndef KCONFIG_MACROS_H
#define KCONFIG_MACROS_H

/* ---- 原 lkc.hpp ---- */
#define SRCTREE "srctree"

#ifndef PACKAGE
#define PACKAGE "linux"
#endif

/*
 * CONFIG_ 宏 trick 的用户侧：宏展开为模块里的 CONFIG_prefix() 调用，
 * 默认值（-DCONFIG_=...）与环境变量覆盖逻辑见 kconfig.ixx。
 */
#undef CONFIG_
#define CONFIG_ CONFIG_prefix()

/* ---- 原 list.hpp：侵入式链表宏 ---- */
/**
 * container_of - cast a member of a structure out to the containing structure
 * @ptr:	the pointer to the member.
 * @type:	the type of the container struct this is embedded in.
 * @member:	the name of the member within the struct.
 */
#define container_of(ptr, type, member) \
	list_container_of<type>((ptr), offsetof(type, member))
#define LIST_HEAD_INIT(name) { &(name), &(name) }

#define LIST_HEAD(name) \
	struct list_head name = LIST_HEAD_INIT(name)

/**
 * list_entry - get the struct for this entry
 * @ptr:	the &struct list_head pointer.
 * @type:	the type of the struct this is embedded in.
 * @member:	the name of the list_head within the struct.
 */
#define list_entry(ptr, type, member) \
	container_of(ptr, type, member)

/**
 * list_for_each_entry	-	iterate over list of given type
 * @pos:	the type * to use as a loop cursor.
 * @head:	the head for your list.
 * @member:	the name of the list_head within the struct.
 */
#define list_for_each_entry(pos, head, member)				\
	for (pos = list_entry((head)->next,				\
			      std::remove_pointer_t<decltype(pos)>, member); \
	     &pos->member != (head);					\
	     pos = list_entry(pos->member.next,				\
			      std::remove_pointer_t<decltype(pos)>, member))

/**
 * list_for_each_entry_safe - iterate over list of given type safe against removal of list entry
 * @pos:	the type * to use as a loop cursor.
 * @n:		another type * to use as temporary storage
 * @head:	the head for your list.
 * @member:	the name of the list_head within the struct.
 */
#define list_for_each_entry_safe(pos, n, head, member)			\
	for (pos = list_entry((head)->next,				\
			      std::remove_pointer_t<decltype(pos)>, member), \
		n = list_entry(pos->member.next,			\
			      std::remove_pointer_t<decltype(n)>, member); \
	     &pos->member != (head);					\
	     pos = n, n = list_entry(n->member.next,			\
			      std::remove_pointer_t<decltype(n)>, member))

/* ---- 原 expr.hpp ---- */
#define expr_list_for_each_sym(l, e, s) \
	for (e = (l); e && (s = e->right.sym); e = e->left.expr)

#endif /* KCONFIG_MACROS_H */
