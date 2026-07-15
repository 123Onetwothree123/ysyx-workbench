// SPDX-License-Identifier: GPL-2.0+
/*
 *  textbox.c -> textbox.cpp
 *
 *  ORIGINAL AUTHOR: Savio Lam (lam836@cs.cuhk.hk)
 *  MODIFIED FOR LINUX KERNEL CONFIG BY: William Roadcap (roadcap@cfw.com)
 */

#include <cstdlib>
#include <cstring>
#include <algorithm>
#include "dialog.hpp"

static void back_lines(int n);
static void print_page(WINDOW *win, int height, int width, update_text_fn
		       update_text, void *data);
static void print_line(WINDOW *win, int row, int width);
static char *get_line();
static void print_position(WINDOW *win);

static int hscroll;
static int begin_reached, end_reached, page_length;
static char *buf;
static char *page;

static void refresh_text_box(WINDOW *dialog, WINDOW *box, int boxh, int boxw,
			     int cur_y, int cur_x, update_text_fn update_text,
			     void *data)
{
	print_page(box, boxh, boxw, update_text, data);
	print_position(dialog);
	wmove(dialog, cur_y, cur_x);
	wrefresh(dialog);
}

int dialog_textbox(const char *title, char *tbuf, int initial_height,
		   int initial_width, int *keys, int *_vscroll, int *_hscroll,
		   update_text_fn update_text, void *data)
{
	int key = 0;
	WINDOW *dialog, *box;
	bool done = false;

	begin_reached = 1;
	end_reached = 0;
	page_length = 0;
	hscroll = 0;
	buf = tbuf;
	page = buf;

	if (_vscroll && *_vscroll) {
		begin_reached = 0;

		for (int i = 0; i < *_vscroll; i++)
			get_line();
	}
	if (_hscroll)
		hscroll = *_hscroll;

do_resize:
	int height, width;
	getmaxyx(stdscr, height, width);
	if (height < TEXTBOX_HEIGTH_MIN || width < TEXTBOX_WIDTH_MIN)
		return -ERRDISPLAYTOOSMALL;
	if (initial_height != 0)
		height = initial_height;
	else
		if (height > 4)
			height -= 4;
		else
			height = 0;
	if (initial_width != 0)
		width = initial_width;
	else
		if (width > 5)
			width -= 5;
		else
			width = 0;

	int x = (getmaxx(stdscr) - width) / 2;
	int y = (getmaxy(stdscr) - height) / 2;

	draw_shadow(stdscr, y, x, height, width);

	dialog = newwin(height, width, y, x);
	keypad(dialog, TRUE);

	int boxh = height - 4;
	int boxw = width - 2;
	box = subwin(dialog, boxh, boxw, y + 1, x + 1);
	wattrset(box, dlg.dialog.atr);
	wbkgdset(box, dlg.dialog.atr & A_COLOR);

	keypad(box, TRUE);

	draw_box(dialog, 0, 0, height, width,
		 dlg.dialog.atr, dlg.border.atr);

	wattrset(dialog, dlg.border.atr);
	mvwaddch(dialog, height - 3, 0, static_cast<chtype>(ACS_LTEE));
	for (int i = 0; i < width - 2; i++)
		waddch(dialog, static_cast<chtype>(ACS_HLINE));
	wattrset(dialog, dlg.dialog.atr);
	wbkgdset(dialog, dlg.dialog.atr & A_COLOR);
	waddch(dialog, static_cast<chtype>(ACS_RTEE));

	print_title(dialog, title, width);

	print_button(dialog, " Exit ", height - 2, width / 2 - 4, TRUE);
	wnoutrefresh(dialog);
	int cur_y, cur_x;
	getyx(dialog, cur_y, cur_x);

	attr_clear(box, boxh, boxw, dlg.dialog.atr);
	refresh_text_box(dialog, box, boxh, boxw, cur_y, cur_x, update_text, data);

	while (!done) {
		key = wgetch(dialog);
		switch (key) {
		case 'E':
		case 'e':
		case 'X':
		case 'x':
		case 'q':
		case '\n':
			done = true;
			break;
		case 'g':
		case KEY_HOME:
			if (!begin_reached) {
				begin_reached = 1;
				page = buf;
				refresh_text_box(dialog, box, boxh, boxw,
						 cur_y, cur_x, update_text, data);
			}
			break;
		case 'G':
		case KEY_END:
			end_reached = 1;
			page = buf + strlen(buf);
			back_lines(boxh);
			refresh_text_box(dialog, box, boxh, boxw, cur_y,
					 cur_x, update_text, data);
			break;
		case 'K':
		case 'k':
		case KEY_UP:
			if (begin_reached)
				break;

			back_lines(page_length + 1);
			refresh_text_box(dialog, box, boxh, boxw, cur_y,
					 cur_x, update_text, data);
			break;
		case 'B':
		case 'b':
		case 'u':
		case KEY_PPAGE:
			if (begin_reached)
				break;
			back_lines(page_length + boxh);
			refresh_text_box(dialog, box, boxh, boxw, cur_y,
					 cur_x, update_text, data);
			break;
		case 'J':
		case 'j':
		case KEY_DOWN:
			if (end_reached)
				break;

			back_lines(page_length - 1);
			refresh_text_box(dialog, box, boxh, boxw, cur_y,
					 cur_x, update_text, data);
			break;
		case KEY_NPAGE:
		case ' ':
		case 'd':
			if (end_reached)
				break;

			begin_reached = 0;
			refresh_text_box(dialog, box, boxh, boxw, cur_y,
					 cur_x, update_text, data);
			break;
		case '0':
		case 'H':
		case 'h':
		case KEY_LEFT:
			if (hscroll <= 0)
				break;

			if (key == '0')
				hscroll = 0;
			else
				hscroll--;
			back_lines(page_length);
			refresh_text_box(dialog, box, boxh, boxw, cur_y,
					 cur_x, update_text, data);
			break;
		case 'L':
		case 'l':
		case KEY_RIGHT:
			if (hscroll >= MAX_LEN)
				break;
			hscroll++;
			back_lines(page_length);
			refresh_text_box(dialog, box, boxh, boxw, cur_y,
					 cur_x, update_text, data);
			break;
		case KEY_ESC:
			if (on_key_esc(dialog) == KEY_ESC)
				done = true;
			break;
		case KEY_RESIZE:
			back_lines(height);
			delwin(box);
			delwin(dialog);
			on_key_resize();
			goto do_resize;
		default:
			for (int i = 0; keys[i]; i++) {
				if (key == keys[i]) {
					done = true;
					break;
				}
			}
		}
	}
	delwin(box);
	delwin(dialog);
	if (_vscroll) {
		const char *s = buf;
		*_vscroll = 0;
		back_lines(page_length);
		while (s < page && (s = strchr(s, '\n'))) {
			(*_vscroll)++;
			s++;
		}
	}
	if (_hscroll)
		*_hscroll = hscroll;
	return key;
}

static void back_lines(int n)
{
	begin_reached = 0;
	for (int i = 0; i < n; i++) {
		if (*page == '\0') {
			if (end_reached) {
				end_reached = 0;
				continue;
			}
		}
		if (page == buf) {
			begin_reached = 1;
			return;
		}
		page--;
		do {
			if (page == buf) {
				begin_reached = 1;
				return;
			}
			page--;
		} while (*page != '\n');
		page++;
	}
}

static void print_page(WINDOW *win, int height, int width, update_text_fn
		       update_text, void *data)
{
	if (update_text) {
		for (int i = 0; i < height; i++)
			get_line();
		char *end = page;
		back_lines(height);
		update_text(buf, page - buf, end - buf, data);
	}

	page_length = 0;
	int passed_end = 0;
	for (int i = 0; i < height; i++) {
		print_line(win, i, width);
		if (!passed_end)
			page_length++;
		if (end_reached && !passed_end)
			passed_end = 1;
	}
	wnoutrefresh(win);
}

static void print_line(WINDOW *win, int row, int width)
{
	char *line = get_line();
	line += std::min((int)strlen(line), hscroll);
	wmove(win, row, 0);
	waddch(win, ' ');
	waddnstr(win, line, std::min((int)strlen(line), width - 2));

#if OLD_NCURSES
	int x = getcurx(win);
	for (int i = 0; i < width - x; i++)
		waddch(win, ' ');
#else
	wclrtoeol(win);
#endif
}

static char *get_line()
{
	int i = 0;
	static char line[MAX_LEN + 1];

	end_reached = 0;
	while (*page != '\n') {
		if (*page == '\0') {
			end_reached = 1;
			break;
		} else if (i < MAX_LEN)
			line[i++] = *(page++);
		else {
			if (i == MAX_LEN)
				line[i++] = '\0';
			page++;
		}
	}
	if (i <= MAX_LEN)
		line[i] = '\0';
	if (!end_reached)
		page++;

	return line;
}

static void print_position(WINDOW *win)
{
	wattrset(win, dlg.position_indicator.atr);
	wbkgdset(win, dlg.position_indicator.atr & A_COLOR);
	int percent = (page - buf) * 100 / strlen(buf);
	wmove(win, getmaxy(win) - 3, getmaxx(win) - 9);
	wprintw(win, "(%3d%%)", percent);
}
