/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#ifndef __DEVICE_ALARM_H__
#define __DEVICE_ALARM_H__

/*
 * 时钟中断频率：100Hz，即每个时间片约 10ms。
 * PA4.4 要求以约 10ms 的周期产生时钟中断，用于抢占式分时多任务。
 * alarm.c 用 1000000/TIMER_HZ 微秒设置 setitimer 的周期。
 */
#define TIMER_HZ 100

typedef void (*alarm_handler_t) ();
void add_alarm_handle(alarm_handler_t h);

#endif
