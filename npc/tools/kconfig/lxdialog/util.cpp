// SPDX-License-Identifier: GPL-2.0+
/*
 *  util.c -> util.cpp
 *
 *  ORIGINAL AUTHOR: Savio Lam (lam836@cs.cuhk.hk)
 *  MODIFIED FOR LINUX KERNEL CONFIG BY: William Roadcap (roadcap@cfw.com)
 */

#include <cstdarg>
#include <cstdlib>
#include <cstring>
#include "dialog.hpp"

int saved_x, saved_y;
dialog_info dlg;

char dialog_input_result[MAX_LEN + 1];

static void set_mono_theme()
{
	dlg.screen.atr = A_NORMAL;
	dlg.shadow.atr = A_NORMAL;
	dlg.dialog.atr = A_NORMAL;
	dlg.title.atr = A_BOLD;
	dlg.border.atr = A_NORMAL;
	dlg.button_active.atr = A_REVERSE;
	dlg.button_inactive.atr = A_DIM;
	dlg.button_key_active.atr = A_REVERSE;
	dlg.button_key_inactive.atr = A_BOLD;
	dlg.button_label_active.atr = A_REVERSE;
	dlg.button_label_inactive.atr = A_NORMAL;
	dlg.inputbox.atr = A_NORMAL;
	dlg.inputbox_border.atr = A_NORMAL;
	dlg.searchbox.atr = A_NORMAL;
	dlg.searchbox_title.atr = A_BOLD;
	dlg.searchbox_border.atr = A_NORMAL;
	dlg.position_indicator.atr = A_BOLD;
	dlg.menubox.atr = A_NORMAL;
	dlg.menubox_border.atr = A_NORMAL;
	dlg.item.atr = A_NORMAL;
	dlg.item_selected.atr = A_REVERSE;
	dlg.tag.atr = A_BOLD;
	dlg.tag_selected.atr = A_REVERSE;
	dlg.tag_key.atr = A_BOLD;
	dlg.tag_key_selected.atr = A_REVERSE;
	dlg.check.atr = A_BOLD;
	dlg.check_selected.atr = A_REVERSE;
	dlg.uarrow.atr = A_BOLD;
	dlg.darrow.atr = A_BOLD;
}

#define DLG_COLOR(dialog, f, b, h) \
do {                               \
	dlg.dialog.fg = (f);       \
	dlg.dialog.bg = (b);       \
	dlg.dialog.hl = (h);       \
} while (0)

static void set_classic_theme()
{
	DLG_COLOR(screen,                COLOR_CYAN,   COLOR_BLUE,   true);
	DLG_COLOR(shadow,                COLOR_BLACK,  COLOR_BLACK,  true);
	DLG_COLOR(dialog,                COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(title,                 COLOR_YELLOW, COLOR_WHITE,  true);
	DLG_COLOR(border,                COLOR_WHITE,  COLOR_WHITE,  true);
	DLG_COLOR(button_active,         COLOR_WHITE,  COLOR_BLUE,   true);
	DLG_COLOR(button_inactive,       COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(button_key_active,     COLOR_WHITE,  COLOR_BLUE,   true);
	DLG_COLOR(button_key_inactive,   COLOR_RED,    COLOR_WHITE,  false);
	DLG_COLOR(button_label_active,   COLOR_YELLOW, COLOR_BLUE,   true);
	DLG_COLOR(button_label_inactive, COLOR_BLACK,  COLOR_WHITE,  true);
	DLG_COLOR(inputbox,              COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(inputbox_border,       COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(searchbox,             COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(searchbox_title,       COLOR_YELLOW, COLOR_WHITE,  true);
	DLG_COLOR(searchbox_border,      COLOR_WHITE,  COLOR_WHITE,  true);
	DLG_COLOR(position_indicator,    COLOR_YELLOW, COLOR_WHITE,  true);
	DLG_COLOR(menubox,               COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(menubox_border,        COLOR_WHITE,  COLOR_WHITE,  true);
	DLG_COLOR(item,                  COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(item_selected,         COLOR_WHITE,  COLOR_BLUE,   true);
	DLG_COLOR(tag,                   COLOR_YELLOW, COLOR_WHITE,  true);
	DLG_COLOR(tag_selected,          COLOR_YELLOW, COLOR_BLUE,   true);
	DLG_COLOR(tag_key,               COLOR_YELLOW, COLOR_WHITE,  true);
	DLG_COLOR(tag_key_selected,      COLOR_YELLOW, COLOR_BLUE,   true);
	DLG_COLOR(check,                 COLOR_BLACK,  COLOR_WHITE,  false);
	DLG_COLOR(check_selected,        COLOR_WHITE,  COLOR_BLUE,   true);
	DLG_COLOR(uarrow,                COLOR_GREEN,  COLOR_WHITE,  true);
	DLG_COLOR(darrow,                COLOR_GREEN,  COLOR_WHITE,  true);
}

static void set_blackbg_theme()
{
	DLG_COLOR(screen, COLOR_RED,   COLOR_BLACK, true);
	DLG_COLOR(shadow, COLOR_BLACK, COLOR_BLACK, false);
	DLG_COLOR(dialog, COLOR_WHITE, COLOR_BLACK, false);
	DLG_COLOR(title,  COLOR_RED,   COLOR_BLACK, false);
	DLG_COLOR(border, COLOR_BLACK, COLOR_BLACK, true);

	DLG_COLOR(button_active,         COLOR_YELLOW, COLOR_RED,   false);
	DLG_COLOR(button_inactive,       COLOR_YELLOW, COLOR_BLACK, false);
	DLG_COLOR(button_key_active,     COLOR_YELLOW, COLOR_RED,   true);
	DLG_COLOR(button_key_inactive,   COLOR_RED,    COLOR_BLACK, false);
	DLG_COLOR(button_label_active,   COLOR_WHITE,  COLOR_RED,   false);
	DLG_COLOR(button_label_inactive, COLOR_BLACK,  COLOR_BLACK, true);

	DLG_COLOR(inputbox,         COLOR_YELLOW, COLOR_BLACK, false);
	DLG_COLOR(inputbox_border,  COLOR_YELLOW, COLOR_BLACK, false);

	DLG_COLOR(searchbox,        COLOR_YELLOW, COLOR_BLACK, false);
	DLG_COLOR(searchbox_title,  COLOR_YELLOW, COLOR_BLACK, true);
	DLG_COLOR(searchbox_border, COLOR_BLACK,  COLOR_BLACK, true);

	DLG_COLOR(position_indicator, COLOR_RED, COLOR_BLACK,  false);

	DLG_COLOR(menubox,          COLOR_YELLOW, COLOR_BLACK, false);
	DLG_COLOR(menubox_border,   COLOR_BLACK,  COLOR_BLACK, true);

	DLG_COLOR(item,             COLOR_WHITE, COLOR_BLACK, false);
	DLG_COLOR(item_selected,    COLOR_WHITE, COLOR_RED,   false);

	DLG_COLOR(tag,              COLOR_RED,    COLOR_BLACK, false);
	DLG_COLOR(tag_selected,     COLOR_YELLOW, COLOR_RED,   true);
	DLG_COLOR(tag_key,          COLOR_RED,    COLOR_BLACK, false);
	DLG_COLOR(tag_key_selected, COLOR_YELLOW, COLOR_RED,   true);

	DLG_COLOR(check,            COLOR_YELLOW, COLOR_BLACK, false);
	DLG_COLOR(check_selected,   COLOR_YELLOW, COLOR_RED,   true);

	DLG_COLOR(uarrow, COLOR_RED, COLOR_BLACK, false);
	DLG_COLOR(darrow, COLOR_RED, COLOR_BLACK, false);
}

static void set_bluetitle_theme()
{
	set_classic_theme();
	DLG_COLOR(title,               COLOR_BLUE,   COLOR_WHITE, true);
	DLG_COLOR(button_key_active,   COLOR_YELLOW, COLOR_BLUE,  true);
	DLG_COLOR(button_label_active, COLOR_WHITE,  COLOR_BLUE,  true);
	DLG_COLOR(searchbox_title,     COLOR_BLUE,   COLOR_WHITE, true);
	DLG_COLOR(position_indicator,  COLOR_BLUE,   COLOR_WHITE, true);
	DLG_COLOR(tag,                 COLOR_BLUE,   COLOR_WHITE, true);
	DLG_COLOR(tag_key,             COLOR_BLUE,   COLOR_WHITE, true);
}

static int set_theme(const char *theme)
{
	int use_color = 1;
	if (!theme)
		set_bluetitle_theme();
	else if (strcmp(theme, "classic") == 0)
		set_classic_theme();
	else if (strcmp(theme, "bluetitle") == 0)
		set_bluetitle_theme();
	else if (strcmp(theme, "blackbg") == 0)
		set_blackbg_theme();
	else if (strcmp(theme, "mono") == 0)
		use_color = 0;

	return use_color;
}

static void init_one_color(dialog_color *color)
{
	static int pair = 0;

	pair++;
	init_pair(pair, color->fg, color->bg);
	if (color->hl)
		color->atr = A_BOLD | COLOR_PAIR(pair);
	else
		color->atr = COLOR_PAIR(pair);
}

static void init_dialog_colors()
{
	init_one_color(&dlg.screen);
	init_one_color(&dlg.shadow);
	init_one_color(&dlg.dialog);
	init_one_color(&dlg.title);
	init_one_color(&dlg.border);
	init_one_color(&dlg.button_active);
	init_one_color(&dlg.button_inactive);
	init_one_color(&dlg.button_key_active);
	init_one_color(&dlg.button_key_inactive);
	init_one_color(&dlg.button_label_active);
	init_one_color(&dlg.button_label_inactive);
	init_one_color(&dlg.inputbox);
	init_one_color(&dlg.inputbox_border);
	init_one_color(&dlg.searchbox);
	init_one_color(&dlg.searchbox_title);
	init_one_color(&dlg.searchbox_border);
	init_one_color(&dlg.position_indicator);
	init_one_color(&dlg.menubox);
	init_one_color(&dlg.menubox_border);
	init_one_color(&dlg.item);
	init_one_color(&dlg.item_selected);
	init_one_color(&dlg.tag);
	init_one_color(&dlg.tag_selected);
	init_one_color(&dlg.tag_key);
	init_one_color(&dlg.tag_key_selected);
	init_one_color(&dlg.check);
	init_one_color(&dlg.check_selected);
	init_one_color(&dlg.uarrow);
	init_one_color(&dlg.darrow);
}

static void color_setup(const char *theme)
{
	int use_color = set_theme(theme);
	if (use_color && has_colors()) {
		start_color();
		init_dialog_colors();
	} else
		set_mono_theme();
}

void attr_clear(WINDOW *win, int height, int width, chtype attr)
{
	wattrset(win, attr);
	for (int i = 0; i < height; i++) {
		wmove(win, i, 0);
		for (int j = 0; j < width; j++)
			waddch(win, ' ');
	}
	touchwin(win);
}

void dialog_clear()
{
	int lines = getmaxy(stdscr);
	int columns = getmaxx(stdscr);

	attr_clear(stdscr, lines, columns, dlg.screen.atr);
	if (dlg.backtitle != nullptr) {
		int len = 0, skip = 0;

		wattrset(stdscr, dlg.screen.atr);
		mvwaddstr(stdscr, 0, 1, (char *)dlg.backtitle);

		for (auto *pos = dlg.subtitles; pos != nullptr; pos = pos->next) {
			len += strlen(pos->text) + 3;
		}

		wmove(stdscr, 1, 1);
		if (len > columns - 2) {
			const char *ellipsis = "[...] ";
			waddstr(stdscr, ellipsis);
			skip = len - (columns - 2 - strlen(ellipsis));
		}

		for (auto *pos = dlg.subtitles; pos != nullptr; pos = pos->next) {
			if (skip == 0)
				waddch(stdscr, ACS_RARROW);
			else
				skip--;

			if (skip == 0)
				waddch(stdscr, ' ');
			else
				skip--;

			if (skip < (int)strlen(pos->text)) {
				waddstr(stdscr, pos->text + skip);
				skip = 0;
			} else
				skip -= strlen(pos->text);

			if (skip == 0)
				waddch(stdscr, ' ');
			else
				skip--;
		}

		for (int i = len + 1; i < columns - 1; i++)
			waddch(stdscr, ACS_HLINE);
	}
	wnoutrefresh(stdscr);
}

int init_dialog(const char *backtitle)
{
	initscr();

	getyx(stdscr, saved_y, saved_x);

	int height, width;
	getmaxyx(stdscr, height, width);
	if (height < WINDOW_HEIGTH_MIN || width < WINDOW_WIDTH_MIN) {
		endwin();
		return -ERRDISPLAYTOOSMALL;
	}

	dlg.backtitle = backtitle;
	color_setup(getenv("MENUCONFIG_COLOR"));

	keypad(stdscr, TRUE);
	cbreak();
	noecho();
	dialog_clear();

	return 0;
}

void set_dialog_backtitle(const char *backtitle)
{
	dlg.backtitle = backtitle;
}

void set_dialog_subtitles(subtitle_list *subtitles)
{
	dlg.subtitles = subtitles;
}

void end_dialog(int x, int y)
{
	move(y, x);
	refresh();
	endwin();
}

void print_title(WINDOW *dialog, const char *title, int width)
{
	if (title) {
		int tlen = std::min(width - 2, (int)strlen(title));
		wattrset(dialog, dlg.title.atr);
		mvwaddch(dialog, 0, (width - tlen) / 2 - 1, ' ');
		mvwaddnstr(dialog, 0, (width - tlen) / 2, title, tlen);
		waddch(dialog, ' ');
	}
}

void print_autowrap(WINDOW *win, const char *prompt, int width, int y, int x)
{
	int cur_x, cur_y;
	char tempstr[MAX_LEN + 1];
	const char *newline_separator = nullptr;

	strcpy(tempstr, prompt);

	int prompt_len = strlen(tempstr);

	if (prompt_len <= width - x * 2) {
		wmove(win, y, (width - prompt_len) / 2);
		waddstr(win, tempstr);
	} else {
		cur_x = x;
		cur_y = y;
		int newl = 1;
		char *word = tempstr;
		while (word && *word) {
			char *sp = strpbrk(word, "\n ");
			if (sp && *sp == '\n')
				newline_separator = sp;

			if (sp)
				*sp++ = 0;

			int room = width - cur_x;
			int wlen = strlen(word);
			if (wlen > room ||
			    (newl && wlen < 4 && sp
			     && wlen + 1 + strlen(sp) > room
			     && (!(sp2 = strpbrk(sp, "\n "))
				 || wlen + 1 + (sp2 - sp) > room))) {
				cur_y++;
				cur_x = x;
			}
			wmove(win, cur_y, cur_x);
			waddstr(win, word);
			getyx(win, cur_y, cur_x);

			if (newline_separator) {
				cur_y++;
				cur_x = x;
				newline_separator = nullptr;
			} else
				cur_x++;

			if (sp && *sp == ' ') {
				cur_x++;
				while (*++sp == ' ') ;
				newl = 1;
			} else
				newl = 0;
			word = sp;
		}
	}
}

void print_button(WINDOW *win, const char *label, int y, int x, int selected)
{
	wmove(win, y, x);
	wattrset(win, selected ? dlg.button_active.atr
		 : dlg.button_inactive.atr);
	waddstr(win, "<");
	int temp = strspn(label, " ");
	label += temp;
	wattrset(win, selected ? dlg.button_label_active.atr
		 : dlg.button_label_inactive.atr);
	for (int i = 0; i < temp; i++)
		waddch(win, ' ');
	wattrset(win, selected ? dlg.button_key_active.atr
		 : dlg.button_key_inactive.atr);
	waddch(win, label[0]);
	wattrset(win, selected ? dlg.button_label_active.atr
		 : dlg.button_label_inactive.atr);
	waddstr(win, (char *)label + 1);
	wattrset(win, selected ? dlg.button_active.atr
		 : dlg.button_inactive.atr);
	waddstr(win, ">");
	wmove(win, y, x + temp + 1);
}

void draw_box(WINDOW *win, int y, int x, int height, int width,
	      chtype box, chtype border)
{
	wattrset(win, 0);
	for (int i = 0; i < height; i++) {
		wmove(win, y + i, x);
		for (int j = 0; j < width; j++)
			if (!i && !j)
				waddch(win, border | static_cast<chtype>(ACS_ULCORNER));
			else if (i == height - 1 && !j)
				waddch(win, border | static_cast<chtype>(ACS_LLCORNER));
			else if (!i && j == width - 1)
				waddch(win, box | static_cast<chtype>(ACS_URCORNER));
			else if (i == height - 1 && j == width - 1)
				waddch(win, box | static_cast<chtype>(ACS_LRCORNER));
			else if (!i)
				waddch(win, border | static_cast<chtype>(ACS_HLINE));
			else if (i == height - 1)
				waddch(win, box | static_cast<chtype>(ACS_HLINE));
			else if (!j)
				waddch(win, border | static_cast<chtype>(ACS_VLINE));
			else if (j == width - 1)
				waddch(win, box | static_cast<chtype>(ACS_VLINE));
			else
				waddch(win, box | ' ');
	}
}

void draw_shadow(WINDOW *win, int y, int x, int height, int width)
{
	if (has_colors()) {
		wattrset(win, dlg.shadow.atr);
		wmove(win, y + height, x + 2);
		for (int i = 0; i < width; i++)
			waddch(win, winch(win) & A_CHARTEXT);
		for (int i = y + 1; i < y + height + 1; i++) {
			wmove(win, i, x + width);
			waddch(win, winch(win) & A_CHARTEXT);
			waddch(win, winch(win) & A_CHARTEXT);
		}
		wnoutrefresh(win);
	}
}

int first_alpha(const char *string, const char *exempt)
{
	int in_paren = 0;

	for (int i = 0; string[i]; i++) {
		int c = (unsigned char)string[i];
		if (c >= 0x80)
			continue;
		c = tolower(c);

		if (strchr("<[(", c))
			++in_paren;
		if (strchr(">])", c) && in_paren > 0)
			--in_paren;

		if ((!in_paren) && isalpha(c) && strchr(exempt, c) == 0)
			return i;
	}

	return -1;
}

int on_key_esc(WINDOW *win)
{
	nodelay(win, TRUE);
	keypad(win, FALSE);
	int key = wgetch(win);
	int key2 = wgetch(win);
	int key3;
	do {
		key3 = wgetch(win);
	} while (key3 != ERR);
	nodelay(win, FALSE);
	keypad(win, TRUE);
	if (key == KEY_ESC && key2 == ERR)
		return KEY_ESC;
	else if (key != ERR && key != KEY_ESC && key2 == ERR)
		ungetch(key);

	return -1;
}

int on_key_resize()
{
	dialog_clear();
	return KEY_RESIZE;
}

dialog_list *item_cur;
dialog_list item_nil;
dialog_list *item_head;

void item_reset()
{
	dialog_list *p = item_head;
	while (p) {
		dialog_list *next = p->next;
		delete p;
		p = next;
	}
	item_head = nullptr;
	item_cur = &item_nil;
}

void item_make(const char *fmt, ...)
{
	va_list ap;
	auto *p = new dialog_list{};

	if (item_head)
		item_cur->next = p;
	else
		item_head = p;
	item_cur = p;

	va_start(ap, fmt);
	vsnprintf(item_cur->node.str, sizeof(item_cur->node.str), fmt, ap);
	va_end(ap);
}

void item_add_str(const char *fmt, ...)
{
	va_list ap;
	size_t avail = sizeof(item_cur->node.str) - strlen(item_cur->node.str);

	va_start(ap, fmt);
	vsnprintf(item_cur->node.str + strlen(item_cur->node.str),
		  avail, fmt, ap);
	item_cur->node.str[sizeof(item_cur->node.str) - 1] = '\0';
	va_end(ap);
}

void item_set_tag(char tag)
{
	item_cur->node.tag = tag;
}

void item_set_data(void *ptr)
{
	item_cur->node.data = ptr;
}

void item_set_selected(int val)
{
	item_cur->node.selected = val;
}

int item_activate_selected()
{
	item_foreach()
		if (item_is_selected())
			return 1;
	return 0;
}

void *item_data()
{
	return item_cur->node.data;
}

char item_tag()
{
	return item_cur->node.tag;
}

int item_count()
{
	int n = 0;
	for (auto *p = item_head; p; p = p->next)
		n++;
	return n;
}

void item_set(int n)
{
	int i = 0;
	item_foreach()
		if (i++ == n)
			return;
}

int item_n()
{
	int n = 0;
	for (auto *p = item_head; p; p = p->next) {
		if (p == item_cur)
			return n;
		n++;
	}
	return 0;
}

const char *item_str()
{
	return item_cur->node.str;
}

int item_is_selected()
{
	return (item_cur->node.selected != 0);
}

int item_is_tag(char tag)
{
	return (item_cur->node.tag == tag);
}
