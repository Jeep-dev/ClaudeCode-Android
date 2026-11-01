package com.jeep.claudecode;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Settings, a full-screen page of pages: the list of them and how one moves to the next, and the pages 对话 and 模型. */
final class Settings extends Part {
    /** Settings, while it is open. */
    Dialog settingsPage;
    /** What the page in front holds, and its title when it is the list of pages. */
    private LinearLayout settingsBox;
    private TextView settingsTitle;
    /** The page of Settings shown: null for the list of pages, else one of them. */
    String settingsAt;
    /** The launch options last read from the bridge, shown while they are read again. */
    JSONObject launchCache;
    /** The pages: id, name, what is in it. */
    private static final String[][] PAGES = {
            {"look", "外观", "主题、显示和字体"}, {"talk", "对话", "工作目录、权限、系统提示和 CLAUDE.md"},
            {"model", "模型", "API Key"},
            {"claude", "Claude Code", "settings.json 和环境变量"}, {"ext", "扩展", "技能、插件、MCP、子代理和 Hooks"},
            {"storage", "存储", "占用的空间和清理"}, {"keep", "后台", "电池优化和保活"},
            {"about", "关于", "版本、日志和终端"},
    };
    /** Its pages, one over another while they move. */
    private FrameLayout settingsStage;
    /** Reopened at once (the colours or text size changed), not moved in. */
    boolean settingsInstant;
    /** Until when a move is under way (settingsMoving). */
    private long settingsStill;
    /** The tag of a card whose rows become tiles once the page is filled (tile). */
    private static final String TILES = "tiles";

    Settings(MainActivity app) { super(app); }

    /** Opens Settings, a full-screen page (or refreshes it when open). */
    void settingsSheet() {
        if (settingsPage == null) {
            Dialog d = new Dialog(app, R.style.Page);
            // Its pages, one over another while they move.
            settingsStage = new FrameLayout(app);
            settingsStage.addView(settingsView());
            d.setContentView(settingsStage);
            Themes.window(d.getWindow(), app);
            View under = app.findViewById(android.R.id.content);
            if (d.getWindow() != null) {
                d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                d.getWindow().setStatusBarColor(col(R.color.sidebar));
                d.getWindow().setNavigationBarColor(col(R.color.sidebar));
                // It moves itself in (Motion), over the conversation seen through it meanwhile.
                d.getWindow().setWindowAnimations(0);
                d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            }
            d.setOnKeyListener((x, code, e) -> {
                if (code != android.view.KeyEvent.KEYCODE_BACK) return false;
                if (e.getAction() == android.view.KeyEvent.ACTION_UP && !e.isCanceled()) settingsBack();
                return true;
            });
            d.setOnDismissListener(x -> {
                // Told after restyle has opened the next one already: that one stays as it is.
                if (settingsPage != d) return;
                settingsPage = null;
                settingsAt = null;
                Motion.reset(under);
            });
            settingsPage = d;
            fillSettings(app.link.linked ? launchCache : null);
            d.show();
            if (!settingsInstant) {
                settingsMoving();
                Motion.push(settingsStage, under, null);
            }
        }
        refreshSettings();
    }

    /** Opens Settings on one of its pages (back goes to the list of pages). */
    void openAt(String page) {
        if (settingsPage != null) return;
        settingsAt = page;
        settingsSheet();
        if (page.equals("claude")) app.claudePage.loadSchema(() -> { });
        if (page.equals("storage")) app.appPage.measureStorage();
    }

    /** A page of Settings: the round back button (with the name beside it on the list of pages), its contents scrolling under. */
    private View settingsView() {
        LinearLayout root = Ui.column(app);
        // Tiles a shade lighter than the page under them.
        root.setBackgroundColor(col(R.color.sidebar));
        root.setClickable(true); // touches stay on the page in front while two are shown
        LinearLayout top = Ui.row(app);
        top.setPadding(dp(14), dp(8), dp(14), dp(4));
        ImageView back = Ui.iconButton(app, R.drawable.ic_back, 48, 22, R.color.text);
        back.setBackground(Ui.press(app, Ui.round(col(R.color.surface), dp(24)), dp(24)));
        back.setOnClickListener(v -> settingsBack());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        settingsTitle = Ui.text(app, "设置", 26, R.color.text);
        settingsTitle.setTypeface(Ui.SERIF);
        settingsTitle.setPadding(dp(14), 0, 0, 0);
        top.addView(settingsTitle);
        root.addView(top, Ui.fill());
        ScrollView scroll = new ScrollView(app);
        settingsBox = Ui.column(app);
        settingsBox.setPadding(dp(16), dp(4), dp(16), dp(32));
        scroll.addView(settingsBox);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    /** A move has begun: taps meanwhile (a second back, a second page) wait for it to end. */
    private boolean settingsMoving() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < settingsStill) return false;
        settingsStill = now + Motion.PAGE;
        return true;
    }

    /** Back: from a page to the list of pages, from the list out of Settings. */
    private void settingsBack() {
        if (settingsPage == null || !settingsMoving()) return;
        if (settingsAt != null) { settingsGo(null); return; }
        Dialog d = settingsPage;
        Motion.pop(app.findViewById(android.R.id.content), settingsStage, d::dismiss);
    }

    private void settingsOpen(String page) {
        if (settingsPage == null || !settingsMoving()) return;
        settingsGo(page);
    }

    private void settingsGo(String page) {
        View old = settingsStage.getChildAt(settingsStage.getChildCount() - 1);
        settingsAt = page;
        View fresh = settingsView();
        // A page comes in over the list; the list comes back from under the page.
        if (page != null) settingsStage.addView(fresh);
        else settingsStage.addView(fresh, 0);
        fillSettings(app.link.linked ? launchCache : null);
        if (page != null) Motion.push(fresh, old, () -> settingsStage.removeView(old));
        else Motion.pop(fresh, old, () -> settingsStage.removeView(old));
        if (page != null && page.equals("claude")) app.claudePage.loadSchema(() -> { });
        if (page != null && page.equals("storage")) app.appPage.measureStorage();
    }

    /** Reads the options again and redraws Settings, if open. */
    void refreshSettings() {
        if (settingsPage == null) return;
        if (!app.link.linked) { fillSettings(null); return; }
        // Claude Code's version is found a moment after the bridge starts.
        bridge.get("/config", (st, config) -> {
            if (st == 200) claude.applyConfig(config);
            bridge.get("/launch", (status, launch) -> {
                if (status == 200) launchCache = launch;
                fillSettings(status == 200 ? launch : null);
            });
        });
    }

    LinearLayout card(LinearLayout box) {
        LinearLayout card = Ui.column(app);
        if (sheets.tiled) {
            // Its rows become tiles once Settings is filled (tile).
            card.setTag(TILES);
            box.addView(card, Ui.fill());
            return card;
        }
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border)));
        box.addView(card, Ui.fill());
        return card;
    }

    /** Settings' contents; the launch options only with the bridge running (null launch). */
    void fillSettings(JSONObject launch) {
        if (settingsPage == null) return;
        LinearLayout box = settingsBox;
        box.removeAllViews();
        String name = "设置";
        for (String[] p : PAGES) if (p[0].equals(settingsAt)) name = p[1];
        // The list of pages has its name beside the back button; a page, big above its contents.
        settingsTitle.setText(settingsAt == null ? name : "");
        if (settingsAt != null) {
            TextView big = Ui.text(app, name, 34, R.color.text);
            big.setTypeface(Ui.SERIF);
            big.setPadding(dp(6), dp(10), 0, dp(14));
            box.addView(big);
        }
        sheets.tiled = true;
        try {
            if (settingsAt == null) fillPages(box);
            else switch (settingsAt) {
                case "look": app.look.fillLook(box); break;
                case "talk": fillTalk(box, launch); break;
                case "model": fillModel(box); break;
                case "claude": app.claudePage.fillClaude(box); break;
                case "ext": app.ext.fillExt(box, launch); break;
                case "storage": app.appPage.fillStorage(box); break;
                case "keep": app.appPage.fillKeep(box); break;
                default: app.appPage.fillAbout(box); break;
            }
        } finally {
            sheets.tiled = false;
        }
        tile(box);
    }

    /** Each card's rows as tiles one under another: the group's outer corners round, its inner ones nearly square. */
    private void tile(LinearLayout box) {
        float big = dp(24), small = dp(5);
        for (int i = 0; i < box.getChildCount(); i++) {
            View g = box.getChildAt(i);
            if (!TILES.equals(g.getTag())) continue;
            LinearLayout card = (LinearLayout) g;
            int n = card.getChildCount();
            for (int j = 0; j < n; j++) {
                View row = card.getChildAt(j);
                float t = j == 0 ? big : small, b = j == n - 1 ? big : small;
                float[] radii = {t, t, t, t, b, b, b, b};
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(col(R.color.surface));
                shape.setCornerRadii(radii);
                if (row.isClickable()) {
                    GradientDrawable mask = new GradientDrawable();
                    mask.setColor(0xFF000000);
                    mask.setCornerRadii(radii);
                    row.setBackground(new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(col(R.color.active)), shape, mask));
                } else {
                    row.setBackground(shape);
                }
                if (j > 0) Ui.margins(row, app, 0, 2, 0, 0);
            }
        }
    }

    /** The first level: a row for each page. */
    private void fillPages(LinearLayout box) {
        section(box, "通用");
        LinearLayout a = card(box);
        page(a, R.drawable.ic_image, "look");
        page(a, R.drawable.ic_compose, "talk");
        page(a, R.drawable.ic_link, "model");
        section(box, "Claude Code");
        LinearLayout b = card(box);
        page(b, R.drawable.ic_gear, "claude");
        page(b, R.drawable.ic_tool, "ext");
        section(box, "应用");
        LinearLayout c = card(box);
        page(c, R.drawable.ic_folder, "storage");
        page(c, R.drawable.ic_refresh, "keep");
        page(c, R.drawable.ic_book, "about");
    }

    private void page(LinearLayout card, int icon, String id) {
        String label = id, what = null;
        for (String[] p : PAGES) if (p[0].equals(id)) { label = p[1]; what = p[2]; }
        sheets.option(card, icon, label, what, false, () -> settingsOpen(id));
    }

    // ---- 对话 ----
    private void fillTalk(LinearLayout box, JSONObject launch) {
        LinearLayout talk = card(box);
        sheets.choice(talk, "回复中发消息", new String[]{"排队", "插入"}, app.sender.insertWhileRunning() ? 1 : 0, i -> {
            prefs.edit().putBoolean("insertWhileRunning", i == 1).apply();
            fillSettings(launchCache); // shown chosen
        });
        String folderNow = claude.cwd.isEmpty() ? claude.defaultCwd : claude.cwd;
        sheets.option(talk, R.drawable.ic_folder, "工作目录", folderNow.isEmpty() ? "~/work" : claude.tilde(folderNow), false, () -> {
            if (!app.link.linked) { toast("Claude Code 还没启动好"); return; }
            app.pickers.folderSheet(null);
        });
        if (launch == null) return;
        String mode = launch.optString("permissionMode", "bypassPermissions");
        String prompt = launch.optString("appendSystemPrompt");
        String extra = launch.optString("extraArgs");
        sheets.option(talk, R.drawable.ic_tool, "权限", mode, false, () -> app.pickers.permissionSheet(mode));
        sheets.option(talk, R.drawable.ic_spark, "系统提示", oneLine(prompt), false,
                () -> sheets.textSheet("系统提示", prompt, "", true, (t, done) -> claude.saveLaunch("appendSystemPrompt", t, done), this::refreshSettings));
        sheets.option(talk, R.drawable.ic_edit_lines, "启动参数", extra.trim(), false,
                () -> sheets.textSheet("启动参数", extra, "--max-turns 50", false, (t, done) -> claude.saveLaunch("extraArgs", t, done), this::refreshSettings));
        section(box, "CLAUDE.md");
        LinearLayout memory = card(box);
        String folder = app.current.cwd == null || app.current.cwd.isEmpty() ? folderNow : app.current.cwd;
        sheets.option(memory, R.drawable.ic_book, "全局", "~/.claude", false, () -> app.pickers.memorySheet("global", null, "CLAUDE.md"));
        sheets.option(memory, R.drawable.ic_book, Ui.base(folder), claude.tilde(folder), false,
                () -> app.pickers.memorySheet("folder", folder, "CLAUDE.md · " + Ui.base(folder)));
    }

    // ---- 模型 ----
    private void fillModel(LinearLayout box) {
        LinearLayout api = card(box);
        sheets.option(api, R.drawable.ic_link, "API Key", null, false, app.claudePage::apiSheet);
    }

    /** The scrolling of the Settings page in front. */
    ScrollView settingsScroll() {
        return (ScrollView) settingsBox.getParent();
    }

    private static String oneLine(String text) {
        String t = text.trim().replace('\n', ' ');
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    void section(LinearLayout box, String label) {
        TextView t = Ui.text(app, label, 14, R.color.accent);
        t.setTypeface(Ui.MEDIUM);
        // Close under the page's title, apart from a group above.
        boolean afterGroup = box.getChildCount() > 0 && TILES.equals(box.getChildAt(box.getChildCount() - 1).getTag());
        t.setPadding(dp(12), dp(afterGroup ? 22 : 4), 0, dp(10));
        box.addView(t);
    }
}
