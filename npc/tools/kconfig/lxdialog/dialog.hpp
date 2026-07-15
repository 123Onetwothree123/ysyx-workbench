// SPDX-License-Identifier: GPL-2.0+
/*
 *  dialog.hpp -- common declarations for all dialog modules
 *
 *  AUTHOR: Savio Lam (lam836@cs.cuhk.hk)
 */

#pragma once

#include <sys/types.h>
#include <fcntl.h>
#include <unistd.h>
#include <cctype>
#include <cstdlib>
#include <cstring>

#ifdef __sun__
#define CURS_MACROS
#endif
#include <ncurses.h>

#if defined(NCURSES_VERSION) && defined(_NEED_WRAP) && !defined(GCC_PRINTFLIKE)
#define OLD_NCURSES 1
#undef  wbkgdset
#define wbkgdset(w,p)
#else
#define OLD_NCURSES 0
#endif

#define TR(params) _tracef params

constexpr int KEY_ESC = 27;
constexpr int TAB = 9;
constexpr int MAX_LEN = 2048;
constexpr int BUF_SIZE = (10 * 1024);

#ifndef ACS_ULCORNER
#define ACS_ULCORNER '+'
#endif
#ifndef ACS_LLCORNER
#define ACS_LLCORNER '+'
#endif
#ifndef ACS_URCORNER
#define ACS_URCORNER '+'
#endif
#ifndef ACS_LRCORNER
#define ACS_LRCORNER '+'
#endif
#ifndef ACS_HLINE
#define ACS_HLINE '-'
#endif
#ifndef ACS_VLINE
#define ACS_VLINE '|'
#endif
#ifndef ACS_LTEE
#define ACS_LTEE '+'
#endif
#ifndef ACS_RTEE
#define ACS_RTEE '+'
#endif
#ifndef ACS_UARROW
#define ACS_UARROW '^'
#endif
#ifndef ACS_DARROW
#define ACS_DARROW 'v'
#endif

constexpr int ERRDISPLAYTOOSMALL = (KEY_MAX + 1);

struct dialog_color {
	chtype atr{};
	int fg{};
	int bg{};
	int hl{};
};

struct subtitle_list {
	subtitle_list *next{};
	const char *text{};
};

struct dialog_info {
	const char *backtitle{};
	subtitle_list *subtitles{};
	dialog_color screen{};
	dialog_color shadow{};
	dialog_color dialog{};
	dialog_color title{};
	dialog_color border{};
	dialog_color button_active{};
	dialog_color button_inactive{};
	dialog_color button_key_active{};
	dialog_color button_key_inactive{};
	dialog_color button_label_active{};
	dialog_color button_label_inactive{};
	dialog_color inputbox{};
	dialog_color inputbox_border{};
	dialog_color searchbox{};
	dialog_color searchbox_title{};
	dialog_color searchbox_border{};
	dialog_color position_indicator{};
	dialog_color menubox{};
	dialog_color menubox_border{};
	dialog_color item{};
	dialog_color item_selected{};
	dialog_color tag{};
	dialog_color tag_selected{};
	dialog_color tag_key{};
	dialog_color tag_key_selected{};
	dialog_color check{};
	dialog_color check_selected{};
	dialog_color uarrow{};
	dialog_color darrow{};
};

extern dialog_info dlg;
extern char dialog_input_result[];
extern int saved_x, saved_y;

void item_reset();
void item_make(const char *fmt, ...);
void item_add_str(const char *fmt, ...);
void item_set_tag(char tag);
void item_set_data(void *p);
void item_set_selected(int val);
int item_activate_selected();
void *item_data();
char item_tag();

constexpr int MAXITEMSTR = 200;
struct dialog_item {
	char str[MAXITEMSTR]{};
	char tag{};
	void *data{};
	int selected{};
};

struct dialog_list {
	dialog_item node{};
	dialog_list *next{};
};

extern dialog_list *item_cur;
extern dialog_list item_nil;
extern dialog_list *item_head;

int item_count();
void item_set(int n);
int item_n();
const char *item_str();
int item_is_selected();
int item_is_tag(char tag);

#define item_foreach() \
	for (item_cur = item_head ? item_head : item_cur; \
	     item_cur && (item_cur != &item_nil); item_cur = item_cur->next)

int on_key_esc(WINDOW *win);
int on_key_resize();

constexpr int CHECKLIST_HEIGTH_MIN = 6;
constexpr int CHECKLIST_WIDTH_MIN = 6;
constexpr int INPUTBOX_HEIGTH_MIN = 2;
constexpr int INPUTBOX_WIDTH_MIN = 2;
constexpr int MENUBOX_HEIGTH_MIN = 15;
constexpr int MENUBOX_WIDTH_MIN = 65;
constexpr int TEXTBOX_HEIGTH_MIN = 8;
constexpr int TEXTBOX_WIDTH_MIN = 8;
constexpr int YESNO_HEIGTH_MIN = 4;
constexpr int YESNO_WIDTH_MIN = 4;
constexpr int WINDOW_HEIGTH_MIN = 19;
constexpr int WINDOW_WIDTH_MIN = 80;

int init_dialog(const char *backtitle);
void set_dialog_backtitle(const char *backtitle);
void set_dialog_subtitles(subtitle_list *subtitles);
void end_dialog(int x, int y);
void attr_clear(WINDOW *win, int height, int width, chtype attr);
void dialog_clear();
void print_autowrap(WINDOW *win, const char *prompt, int width, int y, int x);
void print_button(WINDOW *win, const char *label, int y, int x, int selected);
void print_title(WINDOW *dialog, const char *title, int width);
void draw_box(WINDOW *win, int y, int x, int height, int width, chtype box, chtype border);
void draw_shadow(WINDOW *win, int y, int x, int height, int width);

int first_alpha(const char *string, const char *exempt);
int dialog_yesno(const char *title, const char *prompt, int height, int width);
int dialog_msgbox(const char *title, const char *prompt, int height, int width, int pause);

using update_text_fn = void (*)(char *buf, size_t start, size_t end, void *_data);

int dialog_textbox(const char *title, char *tbuf, int initial_height,
		   int initial_width, int *keys, int *_vscroll, int *_hscroll,
		   update_text_fn update_text, void *data);
int dialog_menu(const char *title, const char *prompt,
		const void *selected, int *s_scroll);
int dialog_checklist(const char *title, const char *prompt, int height,
		     int width, int list_height);
int dialog_inputbox(const char *title, const char *prompt, int height,
		    int width, const char *init);

constexpr int M_EVENT = (KEY_MAX + 1);
