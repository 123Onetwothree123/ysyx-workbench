module;
#include <stdio.h>
#include <stdlib.h>
#include <readline/readline.h>
#include <readline/history.h>
export module npc.readline;

export using ::readline;
export using ::add_history;
export using ::history_list;
export using ::free;
export {
    using ::history_length;
    using ::history_base;
    using ::HIST_ENTRY;
}
