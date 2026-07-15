// SPDX-License-Identifier: GPL-2.0
// C++23 ncurses menuconfig UI — faithful port of mconf.c + lxdialog
#include <ncurses.h>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <clocale>
#include <string>
#include <string_view>
#include <vector>
#include <print>
#include <algorithm>

import npc.kconfig;

static const char *menu_instructions =
    "Arrow keys navigate the menu.  <Enter> selects submenus ---> (or empty "
    "submenus ----).  Highlighted letters are hotkeys.  Pressing <Y> "
    "includes, <N> excludes, <M> modularizes features.  Press "
    "<Esc><Esc> to exit, <?> for Help, </> for Search.  "
    "Legend: [*] built-in  [ ] excluded  <M> module  < > module capable";

static const char mconf_readme[] =
    "Overview\n"
    "--------\n"
    "This interface lets you select features and parameters for the build.\n"
    "Features can either be built-in, modularized, or ignored. Parameters\n"
    "must be entered in as decimal or hexadecimal numbers or text.\n\n"
    "Menu items beginning with following braces represent features that\n"
    "  [ ] can be built in or removed\n"
    "  < > can be built in, modularized or removed\n"
    "  { } can be built in or modularized (selected by other feature)\n"
    "  - - are selected by other feature,\n"
    "while *, M or whitespace inside braces means to build in, build as\n"
    "a module or to exclude the feature respectively.\n\n"
    "To change any of these features, highlight it with the cursor\n"
    "keys and press <Y> to build it in, <M> to make it a module or\n"
    "<N> to remove it.  You may also press the <Space Bar> to cycle\n"
    "through the available options (i.e. Y->N->M->Y).\n\n"
    "Some additional keyboard hints:\n\n"
    "Menus\n"
    "----------\n"
    "o  Use the Up/Down arrow keys (cursor keys) to highlight the item you\n"
    "   wish to change or the submenu you wish to select and press <Enter>.\n"
    "   Submenus are designated by \"--->\", empty ones by \"----\".\n\n"
    "   Shortcut: Press the option's highlighted letter (hotkey).\n"
    "o  To exit a menu use the cursor keys to highlight the <Exit> button\n"
    "   and press <ENTER>.\n"
    "   Shortcut: Press <ESC><ESC> or <E> or <X>.\n"
    "o  To get help with an item, press <H> or <?>.\n"
    "o  To toggle the display of hidden options, press <Z>.\n\n"
    "Radiolists (Choice lists)\n"
    "-----------\n"
    "o  Use the cursor keys to select the option you wish to set and press\n"
    "   <S> or the <SPACE BAR> to select it.\n\n"
    "Searching\n"
    "---------\n"
    "o  Press </> to search forward for a string.\n";

// ============================================================
// Data structures
// ============================================================
struct MenuItem {
    std::string text;
    char tag = 0;           // 't'=toggle, 's'=string, 'm'=menu, ':'=separator
    menu *data = nullptr;   // associated menu
    int hotkey = -1;        // hotkey character position
    bool visible = true;
};

struct SearchResult {
    menu *target;
    int key;
};

static std::vector<MenuItem> items;
static int item_idx = 0;        // currently selected item index
static int vscroll = 0;          // vscroll offset
static int max_visible = 1;
static bool show_all = false;
static bool single_menu_mode = false;
static int saved_scroll = 0;
static menu *saved_menu = nullptr;
static std::string search_str;
static std::vector<SearchResult> jump_table;
static int saved_x = 0, saved_y = 0;
static bool need_resize = false;
static WINDOW *main_win = nullptr;
static WINDOW *menu_win = nullptr;
static WINDOW *help_win = nullptr;
static menu *current = nullptr;
static std::vector<const char *> trail;
static std::string config_filename = ".config";

// ============================================================
// Color scheme (classic blue theme matching original)
// ============================================================
enum ColorPair {
    COL_SCREEN = 1, SHADOW, DIALOG, TITLE, BORDER,
    BUTTON_ACTIVE, BUTTON_INACTIVE,
    BUTTON_KEY_ACTIVE, BUTTON_KEY_INACTIVE,
    BUTTON_LABEL_ACTIVE, BUTTON_LABEL_INACTIVE,
    MENUBOX, MENUBOX_BORDER,
    ITEM, ITEM_SELECTED,
    TAG, TAG_SELECTED, TAG_KEY, TAG_KEY_SELECTED,
    CHECK, CHECK_SELECTED,
    UARROW, DARROW,
    SEARCHBOX, SEARCHBOX_TITLE, SEARCHBOX_BORDER,
    POSITION, INPUTBOX, INPUTBOX_BORDER,
};

static void init_colors() {
    if (!has_colors()) return;
    start_color();
    #define PC(p, f, b) init_pair(p, f, b)
    PC(COL_SCREEN,              COLOR_CYAN,   COLOR_BLUE);
    PC(SHADOW,              COLOR_BLACK,  COLOR_BLACK);
    PC(DIALOG,              COLOR_BLACK,  COLOR_WHITE);
    PC(TITLE,               COLOR_YELLOW, COLOR_WHITE);
    PC(BORDER,              COLOR_WHITE,  COLOR_WHITE);
    PC(BUTTON_ACTIVE,       COLOR_WHITE,  COLOR_BLUE);
    PC(BUTTON_INACTIVE,     COLOR_BLACK,  COLOR_WHITE);
    PC(BUTTON_KEY_ACTIVE,   COLOR_WHITE,  COLOR_BLUE);
    PC(BUTTON_KEY_INACTIVE, COLOR_RED,    COLOR_WHITE);
    PC(BUTTON_LABEL_ACTIVE, COLOR_YELLOW, COLOR_BLUE);
    PC(BUTTON_LABEL_INACTIVE,COLOR_BLACK,  COLOR_WHITE);
    PC(MENUBOX,             COLOR_BLACK,  COLOR_WHITE);
    PC(MENUBOX_BORDER,      COLOR_WHITE,  COLOR_WHITE);
    PC(ITEM,                COLOR_BLACK,  COLOR_WHITE);
    PC(ITEM_SELECTED,       COLOR_WHITE,  COLOR_BLUE);
    PC(TAG,                 COLOR_YELLOW, COLOR_WHITE);
    PC(TAG_SELECTED,        COLOR_YELLOW, COLOR_BLUE);
    PC(TAG_KEY,             COLOR_YELLOW, COLOR_WHITE);
    PC(TAG_KEY_SELECTED,    COLOR_YELLOW, COLOR_BLUE);
    PC(CHECK,               COLOR_BLACK,  COLOR_WHITE);
    PC(CHECK_SELECTED,      COLOR_WHITE,  COLOR_BLUE);
    PC(UARROW,              COLOR_GREEN,  COLOR_WHITE);
    PC(DARROW,              COLOR_GREEN,  COLOR_WHITE);
    PC(SEARCHBOX,           COLOR_BLACK,  COLOR_WHITE);
    PC(SEARCHBOX_TITLE,     COLOR_YELLOW, COLOR_WHITE);
    PC(SEARCHBOX_BORDER,    COLOR_WHITE,  COLOR_WHITE);
    PC(POSITION,            COLOR_YELLOW, COLOR_WHITE);
    PC(INPUTBOX,            COLOR_BLACK,  COLOR_WHITE);
    PC(INPUTBOX_BORDER,     COLOR_BLACK,  COLOR_WHITE);
    #undef PC
}
static int attr(int pair, bool bold = false) {
    if (!has_colors()) return bold ? A_BOLD : A_NORMAL;
    return bold ? (COLOR_PAIR(pair) | A_BOLD) : COLOR_PAIR(pair);
}

// ============================================================
// Drawing helpers
// ============================================================
static void draw_box(WINDOW *win, int y, int x, int h, int w, int border_attr) {
    wattron(win, border_attr);
    for (int i = 0; i < h; i++) {
        mvwaddch(win, y + i, x, ACS_VLINE);
        mvwaddch(win, y + i, x + w - 1, ACS_VLINE);
    }
    for (int i = 0; i < w; i++) {
        mvwaddch(win, y, x + i, ACS_HLINE);
        mvwaddch(win, y + h - 1, x + i, ACS_HLINE);
    }
    mvwaddch(win, y, x, ACS_ULCORNER);
    mvwaddch(win, y, x + w - 1, ACS_URCORNER);
    mvwaddch(win, y + h - 1, x, ACS_LLCORNER);
    mvwaddch(win, y + h - 1, x + w - 1, ACS_LRCORNER);
    wattroff(win, border_attr);
}

static void draw_shadow(WINDOW *win, int y, int x, int h, int w) {
    if (!has_colors()) return;
    wattron(win, attr(SHADOW));
    for (int i = 0; i < h; i++)
        mvwaddch(win, y + i + 1, x + w, ' ');
    for (int i = 0; i < w; i++)
        mvwaddch(win, y + h, x + i + 1, ' ');
    wattroff(win, attr(SHADOW));
}

static void print_button(WINDOW *win, const char *label, int y, int x, bool active) {
    int key_attr, label_attr, btn_attr;
    if (active) {
        key_attr = attr(BUTTON_KEY_ACTIVE, true);
        label_attr = attr(BUTTON_LABEL_ACTIVE, true);
        btn_attr = attr(BUTTON_ACTIVE, true);
    } else {
        key_attr = attr(BUTTON_KEY_INACTIVE, true);
        label_attr = attr(BUTTON_LABEL_INACTIVE);
        btn_attr = attr(BUTTON_INACTIVE);
    }
    wattron(win, btn_attr);
    mvwaddstr(win, y, x, " < ");
    wattroff(win, btn_attr);
    wattron(win, key_attr);
    waddch(win, (unsigned char)label[0]);
    wattroff(win, key_attr);
    wattron(win, label_attr);
    waddstr(win, label + 1);
    wattroff(win, label_attr);
    wattron(win, btn_attr);
    waddstr(win, "> ");
    wattroff(win, btn_attr);
}

// ============================================================
// Item formatting (matching original build_conf)
// ============================================================
static int hotkey_for(const char *s) {
    const char *hot = strpbrk(s, "YyNnMmHh");
    return hot ? (int)(hot - s) : -1;
}

static void build_menu_items(menu *m, int level = 0) {
    if (!m) return;
    if (!show_all && !menu_is_visible(m)) {
        if (m->sym && m->sym->type != S_UNKNOWN &&
            m->sym->name.empty() && m->list) {
            for (menu *ch = m->list; ch; ch = ch->next)
                build_menu_items(ch, level);
        }
        return;
    }

    symbol *sym = m->sym;
    bool has_children = m->list != nullptr;

    if (sym && !sym_is_choice(sym) && m->prompt && m->prompt->type != P_MENU
        && m->prompt->type != P_COMMENT) {

        symbol_type type = sym_get_type(sym);
        tristate val = sym_get_tristate_value(sym);
        const char *prompt = menu_get_prompt(m);
        int hot = hotkey_for(prompt);

        if (sym_is_changeable(sym)) {
            MenuItem it;
            if (type == S_BOOLEAN)
                it.text = std::format("[{}]", val == no ? ' ' : '*');
            else if (type == S_TRISTATE) {
                char ch = (val == yes ? '*' : (val == mod ? 'M' : ' '));
                it.text = std::format("<{}>", ch);
            } else {
                it.text = std::format("({})", sym_get_string_value(sym));
                it.tag = 's';
            }
            if (type == S_BOOLEAN || type == S_TRISTATE)
                it.tag = 't';

            it.text += std::format("{:{}} {}{}", "", level + 1, prompt,
                                   sym_has_value(sym) ? "" : " (NEW)");
            it.data = m;
            it.hotkey = hot >= 0 ? hot + (int)it.text.size() - (int)strlen(prompt) : -1;
            items.push_back(it);

            if (val == yes && has_children) {
                for (menu *ch = m->list; ch; ch = ch->next)
                    build_menu_items(ch, level + 2);
            }
            return;
        } else {
            MenuItem it;
            if (sym_is_choice_value(sym) && val == yes)
                it.text = "   ";
            else if (type == S_BOOLEAN) {
                char c = val == no ? ' ' : '*';
                it.text = (sym_is_changeable(sym) ? std::format("[{}]", c) : std::format("-{}-", c));
                it.tag = 't';
            } else if (type == S_TRISTATE) {
                char c = (val == yes ? '*' : (val == mod ? 'M' : ' '));
                if (sym_is_changeable(sym))
                    it.text = std::format("<{}>", c);
                else
                    it.text = std::format("-{}-", c);
                it.tag = 't';
            } else {
                it.text = std::format("({})", sym_get_string_value(sym));
                it.tag = 's';
            }
            it.text += std::format("{:{}} {}", "", level + 1, prompt);
            it.data = m;
            it.hotkey = hot >= 0 ? hot + (int)it.text.size() - (int)strlen(prompt) : -1;
            items.push_back(it);
            return;
        }
    }

    if (m == current) {
        MenuItem it;
        it.text = std::format("---{:{}} {}", "", level + 1, menu_get_prompt(m));
        it.tag = ':';
        it.data = m;
        items.push_back(it);
        if (has_children)
            for (menu *ch = m->list; ch; ch = ch->next)
                build_menu_items(ch, level + 2);
        return;
    }

    if (m->prompt && m->prompt->type == P_MENU) {
        if (sym && sym_is_choice(sym)) {
            if (sym_get_tristate_value(sym) == yes && has_children) {
                for (menu *ch = m->list; ch; ch = ch->next)
                    build_menu_items(ch, level);
            }
            return;
        }
        MenuItem it;
        it.text = std::format("{:{}} {}  {}", "", level + 1, "",
                              menu_get_prompt(m));
        it.text += menu_is_empty(m) ? "----" : "--->";
        it.tag = 'm';
        it.data = m;
        items.push_back(it);
        return;
    }

    if (has_children)
        for (menu *ch = m->list; ch; ch = ch->next)
            build_menu_items(ch, level);
}

// ============================================================
// Rendering
// ============================================================
static void render_screen() {
    int h, w;
    getmaxyx(main_win, h, w);
    // Calculate layout
    int menu_h = h - 10; // 10 for title+border+instruction+buttons
    if (menu_h < 6) menu_h = 6;
    int menu_w = w - 4;
    max_visible = menu_h;

    int visible_count = 0;
    for (auto &it : items) if (it.visible) visible_count++;

    // Clamp vscroll and item_idx
    if (item_idx < 0) item_idx = 0;
    if (item_idx >= visible_count) item_idx = visible_count - 1;
    if (item_idx < vscroll) vscroll = item_idx;
    if (item_idx >= vscroll + max_visible) vscroll = item_idx - max_visible + 1;
    if (vscroll < 0) vscroll = 0;
    if (visible_count <= max_visible) vscroll = 0;

    werase(main_win);

    // Title bar
    wattron(main_win, attr(TITLE, true));
    std::string title = " Linux Kernel Configuration ";
    if (!trail.empty()) title = std::string(trail.back()) + " - Linux Kernel Configuration";
    mvwprintw(main_win, 0, (w - (int)title.size()) / 2, "%s", title.c_str());
    wattroff(main_win, attr(TITLE, true));

    // Breadcrumb
    std::string crumb;
    for (size_t i = 0; i < trail.size(); i++) {
        if (i) crumb += " > ";
        crumb += trail[i];
    }
    if (!crumb.empty()) {
        wattron(main_win, attr(COL_SCREEN));
        mvwprintw(main_win, 1, 2, "%s", crumb.c_str());
        wattroff(main_win, attr(COL_SCREEN));
    }

    // Menu box
    int box_y = 2;
    int box_x = 1;
    int box_w = menu_w;
    int box_h = menu_h + 2;
    draw_shadow(main_win, box_y, box_x, box_h, box_w);
    draw_box(main_win, box_y, box_x, box_h, box_w, attr(MENUBOX_BORDER));

    // Menu items
    int disp_row = 0;
    int skipped = 0;
    for (size_t i = 0; i < items.size(); i++) {
        if (!items[i].visible) continue;
        if (skipped < vscroll) { skipped++; continue; }
        if (disp_row >= max_visible) break;

        int y = box_y + 1 + disp_row;
        bool sel = (skipped + disp_row == item_idx);

        if (items[i].tag == ':' && !sel) {
            wattron(main_win, attr(MENUBOX));
            mvwprintw(main_win, y, box_x + 2, "%s", items[i].text.c_str());
            wattroff(main_win, attr(MENUBOX));
        } else {
            int item_attr = sel ? ITEM_SELECTED : ITEM;
            wattron(main_win, attr(item_attr));
            // Print text with hotkey highlight
            int hk = items[i].hotkey;
            if (hk >= 0 && hk < (int)items[i].text.size()) {
                mvwaddnstr(main_win, y, box_x + 2, items[i].text.c_str(), hk);
                wattron(main_win, sel ? attr(TAG_KEY_SELECTED, true) : attr(TAG_KEY, true));
                waddch(main_win, (unsigned char)items[i].text[hk]);
                wattroff(main_win, sel ? attr(TAG_KEY_SELECTED, true) : attr(TAG_KEY, true));
                wattron(main_win, attr(item_attr));
                waddstr(main_win, items[i].text.c_str() + hk + 1);
            } else {
                mvwaddstr(main_win, y, box_x + 2, items[i].text.c_str());
            }
            wattroff(main_win, attr(item_attr));
        }
        disp_row++;
    }

    // Scroll arrows
    if (vscroll > 0) {
        wattron(main_win, attr(UARROW, true));
        mvwprintw(main_win, box_y, box_x + box_w - 6, "(+)");
        wattroff(main_win, attr(UARROW, true));
    }
    if (vscroll + max_visible < visible_count) {
        wattron(main_win, attr(DARROW, true));
        mvwprintw(main_win, box_y + box_h - 1, box_x + box_w - 6, "(+)");
        wattroff(main_win, attr(DARROW, true));
    }

    // Instructions
    wattron(main_win, attr(COL_SCREEN));
    mvwprintw(main_win, h - 6, 2, "%s", menu_instructions);
    wattroff(main_win, attr(COL_SCREEN));

    // Search bar
    if (!search_str.empty()) {
        wattron(main_win, attr(SEARCHBOX));
        mvwprintw(main_win, h - 5, 2, "Search: %s", search_str.c_str());
        wattroff(main_win, attr(SEARCHBOX));
    }

    // Buttons
    int btn_y = h - 3, btn_x = (w - 60) / 2;
    wattron(main_win, attr(BORDER));
    mvwhline(main_win, h - 4, 1, ACS_HLINE, w - 2);
    mvwaddch(main_win, h - 4, 1, ACS_LTEE);
    mvwaddch(main_win, h - 4, w - 2, ACS_RTEE);
    wattroff(main_win, attr(BORDER));

    // Select/Exit/Help/Save/Load buttons (simplified to Select/Exit/Help)
    print_button(main_win, "Select", btn_y, btn_x, false);
    print_button(main_win, " Exit ", btn_y, btn_x + 14, false);
    print_button(main_win, " Help ", btn_y, btn_x + 28, false);

    wnoutrefresh(main_win);
    doupdate();
}

// ============================================================
// Search
// ============================================================
static void do_search() {
    echo(); curs_set(1);
    int h, w;
    getmaxyx(main_win, h, w);
    WINDOW *search_win = newwin(3, 50, (h - 3) / 2, (w - 50) / 2);
    draw_box(search_win, 0, 0, 3, 50, attr(SEARCHBOX_BORDER));
    wattron(search_win, attr(SEARCHBOX_TITLE, true));
    mvwprintw(search_win, 0, 2, " Search ");
    wattroff(search_win, attr(SEARCHBOX_TITLE, true));
    wattron(search_win, attr(SEARCHBOX));
    mvwprintw(search_win, 1, 2, "Enter search string: ");
    char buf[256] = {};
    echo();
    wmove(search_win, 1, 23);
    wgetnstr(search_win, buf, 100);
    noecho(); curs_set(0);
    delwin(search_win);
    render_screen();

    search_str = buf;
    if (search_str.empty()) {
        for (auto &it : items) it.visible = true;
    } else {
        std::string lower = search_str;
        std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
        for (auto &it : items) {
            std::string tl = it.text;
            std::transform(tl.begin(), tl.end(), tl.begin(), ::tolower);
            it.visible = (tl.find(lower) != std::string::npos);
        }
    }
    item_idx = 0; vscroll = 0;
}

// ============================================================
// Help
// ============================================================
static void show_help(menu *m) {
    const char *help_text = m ? menu_get_help(m) : nullptr;
    if (!help_text)
        help_text = mconf_readme;
    int h, w;
    getmaxyx(main_win, h, w);
    int help_h = h - 6, help_w = w - 6;
    if (help_h < 8) help_h = 8;
    if (help_w < 10) help_w = 10;

    int y = (h - help_h) / 2, x = (w - help_w) / 2;
    WINDOW *help_win = newwin(help_h, help_w, y, x);
    draw_shadow(main_win, y, x, help_h, help_w);
    draw_box(help_win, 0, 0, help_h, help_w, attr(BORDER));
    wattron(help_win, attr(TITLE, true));
    mvwprintw(help_win, 0, 2, " Help ");
    wattroff(help_win, attr(TITLE, true));

    wattron(help_win, attr(DIALOG));
    // Simple text display — show first screenful
    const char *p = help_text;
    for (int i = 0; i < help_h - 3 && *p; i++) {
        char line[256];
        int n = 0;
        while (*p && *p != '\n' && n < help_w - 3) line[n++] = *p++;
        if (*p == '\n') p++;
        line[n] = 0;
        mvwprintw(help_win, i + 1, 2, "%s", line);
    }
    wattroff(help_win, attr(DIALOG));

    print_button(help_win, " Exit ", help_h - 2, (help_w - 10) / 2, true);
    wrefresh(help_win);
    while (wgetch(help_win) != '\n');
    delwin(help_win);
    render_screen();
}

// ============================================================
// Input box for string values
// ============================================================
static std::string input_box(const char *title, const char *prompt, const char *init) {
    echo(); curs_set(1);
    int h, w;
    getmaxyx(main_win, h, w);
    int dlg_h = 6, dlg_w = std::min(60, w - 4);
    int y = (h - dlg_h) / 2, x = (w - dlg_w) / 2;
    auto *dlg = newwin(dlg_h, dlg_w, y, x);
    draw_shadow(main_win, y, x, dlg_h, dlg_w);
    draw_box(dlg, 0, 0, dlg_h, dlg_w, attr(INPUTBOX_BORDER));
    wattron(dlg, attr(TITLE, true));
    mvwprintw(dlg, 0, 2, " %s ", title);
    wattroff(dlg, attr(TITLE, true));
    wattron(dlg, attr(DIALOG));
    mvwprintw(dlg, 2, 2, "%s", prompt);
    wattron(dlg, attr(INPUTBOX));
    mvwprintw(dlg, 3, 2, "[%s]", init ? init : "");
    wmove(dlg, 3, 2);
    wclrtoeol(dlg);
    char buf[256] = {};
    if (init) strncpy(buf, init, 255);
    echo();
    wmove(dlg, 3, 2);
    waddch(dlg, '[');
    wgetnstr(dlg, buf, 200);
    noecho(); curs_set(0);
    delwin(dlg);
    render_screen();
    return buf;
}

// ============================================================
// Toggle / change item value
// ============================================================
static void toggle_item(MenuItem &it) {
    if (!it.data || !it.data->sym) return;
    symbol *sym = it.data->sym;
    if (!sym_is_changeable(sym)) return;

    if (sym_is_choice(sym) && sym_get_tristate_value(sym) == yes) {
        // Navigate into choice
        saved_menu = current;
        current = it.data;
        trail.push_back(menu_get_prompt(it.data));
        items.clear(); vscroll = 0; item_idx = 0;
        build_menu_items(current);
        return;
    }

    symbol_type type = sym_get_type(sym);
    if (type == S_BOOLEAN || type == S_TRISTATE) {
        tristate val = sym_get_tristate_value(sym);
        if (val == no) val = yes;
        else if (val == yes && type == S_TRISTATE) val = mod;
        else val = no;
        sym_set_tristate_value(sym, val);
        items.clear();
        build_menu_items(current);
    } else if (type == S_INT || type == S_HEX || type == S_STRING) {
        auto new_val = input_box(menu_get_prompt(it.data), "Enter value:",
                                 sym_get_string_value(sym));
        if (!new_val.empty())
            sym_set_string_value(sym, new_val.c_str());
        items.clear();
        build_menu_items(current);
    }
}

// ============================================================
// Main loop
// ============================================================
static void conf(menu *m) {
    if (m != &rootmenu)
        trail.push_back(menu_get_prompt(m));
    current = m;

    while (true) {
        items.clear();
        item_idx = 0; vscroll = 0;
        build_menu_items(m);
        if (items.empty()) break;

        while (true) {
            render_screen();
            int key = wgetch(main_win);

            // Count visible items
            int vis = 0; for (auto &it : items) if (it.visible) vis++;

            if (key == KEY_UP || key == 'k') {
                if (item_idx > 0) item_idx--;
            } else if (key == KEY_DOWN || key == 'j') {
                if (item_idx < vis - 1) item_idx++;
            } else if (key == KEY_PPAGE || key == KEY_NPAGE) {
                int pg = max_visible - 1;
                if (key == KEY_PPAGE) item_idx = std::max(0, item_idx - pg);
                else item_idx = std::min(vis - 1, item_idx + pg);
            } else if (key == KEY_HOME) {
                item_idx = 0;
            } else if (key == KEY_END) {
                item_idx = vis - 1;
            } else if (key == ' ' || key == '\n') {
                // Find visible item at item_idx
                int si = 0;
                for (size_t i = 0; i < items.size(); i++) {
                    if (!items[i].visible) continue;
                    if (si == item_idx) {
                        MenuItem it = items[i];
                        if (it.tag == 'm') {
                            if (it.data && !menu_is_empty(it.data)) {
                                // Navigate into submenu
                                saved_scroll = vscroll;
                                conf(it.data);
                                vscroll = saved_scroll;
                                items.clear();
                                build_menu_items(m);
                                break;
                            }
                        } else if (it.tag == 't' || it.tag == 's') {
                            toggle_item(it);
                        }
                        break;
                    }
                    si++;
                }
            } else if (key == 'y' || key == 'Y') {
                int si = 0;
                for (size_t i = 0; i < items.size(); i++) {
                    if (!items[i].visible) continue;
                    if (si == item_idx && items[i].tag == 't') {
                        if (items[i].data && items[i].data->sym)
                            sym_set_tristate_value(items[i].data->sym, yes);
                        items.clear(); build_menu_items(m); break;
                    }
                    si++;
                }
            } else if (key == 'n' || key == 'N') {
                int si = 0;
                for (size_t i = 0; i < items.size(); i++) {
                    if (!items[i].visible) continue;
                    if (si == item_idx && items[i].tag == 't') {
                        if (items[i].data && items[i].data->sym)
                            sym_set_tristate_value(items[i].data->sym, no);
                        items.clear(); build_menu_items(m); break;
                    }
                    si++;
                }
            } else if (key == 'm' || key == 'M') {
                int si = 0;
                for (size_t i = 0; i < items.size(); i++) {
                    if (!items[i].visible) continue;
                    if (si == item_idx && items[i].tag == 't') {
                        if (items[i].data && items[i].data->sym)
                            sym_set_tristate_value(items[i].data->sym, mod);
                        items.clear(); build_menu_items(m); break;
                    }
                    si++;
                }
            } else if (key == '/') {
                do_search();
            } else if (key == '?' || key == 'h' || key == 'H') {
                int si = 0; menu *hm = nullptr;
                for (size_t i = 0; i < items.size(); i++) {
                    if (!items[i].visible) continue;
                    if (si == item_idx) { hm = items[i].data; break; }
                    si++;
                }
                show_help(hm);
            } else if (key == 'z' || key == 'Z') {
                show_all = !show_all;
                items.clear();
                build_menu_items(m);
            } else if (key == 27 || key == 'e' || key == 'E' || key == 'x' || key == 'X') {
                goto exit_menu;
            } else if (key == KEY_RESIZE) {
                items.clear();
                build_menu_items(m);
            } else {
                // Hotkey matching
                int lower_key = tolower(key);
                int si = 0; int best = -1;
                for (size_t i = 0; i < items.size(); i++) {
                    if (!items[i].visible) continue;
                    int hk = items[i].hotkey;
                    if (hk >= 0 && hk < (int)items[i].text.size() &&
                        tolower((unsigned char)items[i].text[hk]) == lower_key) {
                        if (si == item_idx && best < 0) best = (int)i;
                        else if (si > item_idx && best < 0) best = (int)i;
                    }
                    si++;
                }
                if (best >= 0) {
                    // Find index
                    int idx = 0;
                    for (int i = 0; i < best; i++)
                        if (items[i].visible) idx++;
                    item_idx = idx;
                    // Toggle
                    MenuItem it = items[best];
                    if (it.tag == 't' || it.tag == 's') toggle_item(it);
                    else if (it.tag == 'm' && it.data && !menu_is_empty(it.data))
                        conf(it.data);
                }
            }
        }
    }
exit_menu:
    if (!trail.empty()) trail.pop_back();
}

// ============================================================
// Entry point
// ============================================================
int main() {
    setlocale(LC_ALL, "");
    initscr();
    cbreak();
    noecho();
    keypad(stdscr, TRUE);
    curs_set(0);
    init_colors();

    int h, w;
    getmaxyx(stdscr, h, w);
    main_win = newwin(h, w, 0, 0);
    keypad(main_win, TRUE);

    conf_read(nullptr);
    conf(&rootmenu);

    delwin(main_win);
    endwin();

    conf_write(nullptr);
    conf_write_autoconf(1);
    return 0;
}
