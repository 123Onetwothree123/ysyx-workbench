import std;
using namespace std;

// SPDX-License-Identifier: GPL-2.0+
/*
 *  menubox.c -- implements the menu box
 *
 *  ORIGINAL AUTHOR: Savio Lam (lam836@cs.cuhk.hk)
 *  MODIFIED FOR LINUX KERNEL CONFIG BY: William Roadcap (roadcapw@cfw.com)
 */

/*
 *  Changes by Clifford Wolf (god@clifford.at)
 *
 *  [ 1998-06-13 ]
 *
 *    *)  A bugfix for the Page-Down problem
 *
 *    *)  Formerly when I used Page Down and Page Up, the cursor would be set
 *        to the first position in the menu box.  Now lxdialog is a bit
 *        smarter and works more like other menu systems (just have a look at
 *        it).
 *
 *    *)  Formerly if I selected something my scrolling would be broken because
 *        lxdialog is re-invoked by the Menuconfig shell script, can't
 *        remember the last scrolling position, and just sets it so that the
 *        cursor is at the bottom of the box.  Now it writes the temporary file
 *        lxdialog.scrltmp which contains this information. The file is
 *        deleted by lxdialog if the user leaves a submenu or enters a new
 *        one, but it would be nice if Menuconfig could make another "rm -f"
 *        just to be sure.  Just try it out - you will recognise a difference!
 *
 *  [ 1998-06-14 ]
 *
 *    *)  Now lxdialog is crash-safe against broken "lxdialog.scrltmp" files
 *        and menus change their size on the fly.
 *
 *    *)  If for some reason the last scrolling position is not saved by
 *        lxdialog, it sets the scrolling so that the selected item is in the
 *        middle of the menu box, not at the bottom.
 *
 * 02 January 1999, Michael Elizabeth Chastain (mec@shout.net)
 * Reset 'scroll' to 0 if the value from lxdialog.scrltmp is bogus.
 * This fixes a bug in Menuconfig where using ' ' to descend into menus
 * would leave mis-synchronized lxdialog.scrltmp files lying around,
 * fscanf would read in 'scroll', and eventually that value would get used.
 */

#include "dialog.hpp"

static int menu_width, item_x;

/*
 * Print menu item
 */
static void do_print_item(WINDOW * win, const char *item, int line_y,
			  int selected, int hotkey)
{
	int j;
	char *menu_item = static_cast<char*>(malloc(menu_width + 1));

	strncpy(menu_item, item, menu_width - item_x);
	menu_item[menu_width - item_x] = '\0';
	j = first_alpha(menu_item, "YyNnMmHh");

	/* Clear 'residue' of last item */
	wattrset(win, dlg.menubox.atr);
	wmove(win, line_y, 0);
#if OLD_NCURSES
	{
		int i;
		for (i = 0; i < menu_width; i++)
			waddch(win, ' ');
	}
