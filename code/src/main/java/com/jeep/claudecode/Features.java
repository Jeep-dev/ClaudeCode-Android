package com.jeep.claudecode;

import android.app.Dialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 功能: what is done with the conversation open (its details, the files it changed, saving it as text,
 * finding in it) and the tools the app has, on a page of its own beside Settings, which holds only
 * what is set. Each is a card; one that needs a conversation with something in it says so while there is none.
 */
final class Features extends Part {
    /** The page, while it is open, and the column its cards go in. */
    private Dialog page;
    private FrameLayout stage;
    private LinearLayout box;
    /** Until when a move is under way: a second tap meanwhile waits. */
    private long still;
    /** A file is shown up to this much of it. */
    private static final int PREVIEW = 64 * 1024;
    /** A text is handed to another app up to this much of it (the system passes it in one piece). */
    private static final int SHARE = 150_000;

    Features(MainActivity app) { super(app); }

    boolean isOpen() { return page != null; }

    void open() {
        if (page != null) return;
        Dialog d = new Dialog(app, R.style.Page);
        stage = new FrameLayout(app);
        stage.addView(view());
        d.setContentView(stage);
        Themes.window(d.getWindow(), app);
        View under = app.findViewById(android.R.id.content);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            d.getWindow().setStatusBarColor(col(R.color.sidebar));
            d.getWindow().setNavigationBarColor(col(R.color.sidebar));
            // It moves itself in (Motion), over what was seen through it meanwhile.
            d.getWindow().setWindowAnimations(0);
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
        d.setOnKeyListener((x, code, e) -> {
            if (code != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() == android.view.KeyEvent.ACTION_UP && !e.isCanceled()) back();
            return true;
        });
        d.setOnDismissListener(x -> {
            if (page != d) return;
            page = null;
            Motion.reset(under);
        });
        page = d;
        fill();
        d.show();
        moving();
        Motion.push(stage, under, null);
    }

    private boolean moving() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < still) return false;
        still = now + Motion.PAGE;
        return true;
    }

    private void back() {
        if (page == null || !moving()) return;
        Dialog d = page;
        Motion.pop(app.findViewById(android.R.id.content), stage, d::dismiss);
    }

    /** Closes the page at once, and the drawer under it, to show what a card does in the conversation. */
    private void leave(Runnable then) {
        if (page != null) page.dismiss();
        app.frame.close();
        then.run();
    }

    private View view() {
        LinearLayout root = Ui.column(app);
        root.setBackgroundColor(col(R.color.sidebar));
        root.setClickable(true);
        LinearLayout top = Ui.row(app);
        top.setPadding(dp(14), dp(8), dp(14), dp(4));
        ImageView back = Ui.iconButton(app, R.drawable.ic_back, 48, 22, R.color.text);
        back.setBackground(Ui.press(app, Ui.round(col(R.color.surface), dp(24)), dp(24)));
        back.setOnClickListener(v -> back());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(app, "功能", 26, R.color.text);
        title.setTypeface(Ui.SERIF);
        title.setPadding(dp(14), 0, 0, 0);
        top.addView(title);
        root.addView(top, Ui.fill());
        ScrollView scroll = new ScrollView(app);
        box = Ui.column(app);
        box.setPadding(dp(16), dp(4), dp(16), dp(32));
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    // ---- the cards ----

    private void fill() {
        if (page == null) return;
        box.removeAllViews();
        Chat chat = app.current;
        boolean said = !chat.messages.isEmpty();
        boolean saved = chat.sessionId != null && app.link.linked;
        Transcript.Stats stats = Transcript.stats(chat);
        String none = "先打开有内容的对话";

        box.addView(chatCard(chat));

        section("当前对话");
        grid(
                card(R.drawable.ic_list, "对话详情", said ? stats.users + " 条消息 · " + stats.toolCalls() + " 次工具" : none, said, this::details),
                card(R.drawable.ic_file_plus, "改动的文件", said ? (stats.files.isEmpty() ? "还没有改动" : stats.files.size() + " 个文件") : none, said, this::changedFiles),
                card(R.drawable.ic_arrow_down, "导出对话", said ? "存成 Markdown、复制或分享" : none, said, this::export),
                card(R.drawable.ic_search, "搜索对话", said ? "在这段对话里找词" : none, said, () -> leave(app.screen::findInChat)),
                card(R.drawable.ic_refresh, "回到之前", said ? "回退到某条消息重新编辑" : none, said, () -> leave(app.messages::tree)),
                card(R.drawable.ic_brain, "压缩上下文", saved ? "/compact，腾出上下文空间" : "对话开始后可用", saved, () -> leave(() -> command("/compact"))),
                card(R.drawable.ic_spark, "上下文用量", saved ? "/context，看占用了多少" : "对话开始后可用", saved, () -> leave(() -> command("/context"))));

        section("工具");
        grid(
                card(R.drawable.ic_terminal, "终端", "在手机里的 Linux 里敲命令", true, () -> app.openTerminal()),
                card(R.drawable.ic_book, "技能", "查看、新建和编辑技能", true, () -> app.settings.openAt("ext")),
                card(R.drawable.ic_link, "MCP 服务器", "接入外部工具", true, () -> app.settings.openAt("ext")),
                card(R.drawable.ic_folder, "工作目录", chat.sessionId == null ? "新对话在哪个文件夹里做事" : "已有对话的目录不能更改", true, () -> leave(() -> app.slash.localCommand("/folder"))),
                card(R.drawable.ic_pencil, "模型", "模型和思考强度", true, () -> leave(() -> app.pickers.modelSheet())));

        section("设置");
        grid(
                card(R.drawable.ic_image, "外观", "主题、字体和显示", true, () -> app.settings.openAt("look")),
                card(R.drawable.ic_compose, "对话", "权限、系统提示和 CLAUDE.md", true, () -> app.settings.openAt("talk")),
                card(R.drawable.ic_refresh, "后台保活", "电池优化和保活", true, () -> app.settings.openAt("keep")),
                card(R.drawable.ic_gear, "全部设置", "所有设置页", true, () -> app.settings.settingsSheet()));
    }

    /** The overview: which conversation the cards under it are about; a tap goes back to it. */
    private View chatCard(Chat chat) {
        LinearLayout row = Ui.row(app);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setMinimumHeight(dp(72));
        row.setBackground(Ui.press(app, Ui.round(col(R.color.surface), dp(24)), dp(24)));
        ImageView icon = Ui.icon(app, R.drawable.ic_compose, 44, R.color.accent);
        icon.setPadding(dp(11), dp(11), dp(11), dp(11));
        icon.setBackground(Ui.round(col(R.color.accentSoft), dp(22)));
        row.addView(icon);
        Ui.margins(icon, app, 0, 0, 14, 0);
        LinearLayout words = Ui.column(app);
        String name = chat.title.isEmpty() ? "新对话" : chat.title;
        TextView t = Ui.text(app, name, 16.5f, R.color.text);
        t.setTypeface(Ui.MEDIUM);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(t);
        String where = chat.cwd == null || chat.cwd.isEmpty() ? "当前打开的对话" : claude.tilde(chat.cwd);
        TextView s = Ui.text(app, (chat.running ? "运行中 · " : "") + where, 13, R.color.text2);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        s.setPadding(0, dp(4), 0, 0);
        words.addView(s);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Ui.icon(app, R.drawable.ic_chevron_right, 18, R.color.text3));
        row.setOnClickListener(v -> leave(() -> { }));
        return row;
    }

    private void section(String label) {
        TextView t = Ui.text(app, label, 14, R.color.accent);
        t.setTypeface(Ui.MEDIUM);
        t.setPadding(dp(12), dp(22), 0, dp(10));
        box.addView(t);
    }

    /** Cards two to a row; an odd one out leaves its place empty. */
    private void grid(View... cards) {
        int gap = dp(10);
        for (int i = 0; i < cards.length; i += 2) {
            LinearLayout row = Ui.row(app);
            row.setBaselineAligned(false);
            for (int j = 0; j < 2; j++) {
                View c = i + j < cards.length ? cards[i + j] : new View(app);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
                if (j == 1) p.leftMargin = gap;
                row.addView(c, p);
            }
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (i + 2 < cards.length) Ui.margins(row, app, 0, 0, 0, 10);
        }
    }

    private View card(int iconRes, String title, String about, boolean on, Runnable click) {
        LinearLayout c = Ui.column(app);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setMinimumHeight(dp(124));
        c.setBackground(Ui.press(app, Ui.round(col(R.color.surface), dp(24)), dp(24)));
        ImageView icon = Ui.icon(app, iconRes, 40, R.color.accent);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackground(Ui.round(col(R.color.accentSoft), dp(20)));
        c.addView(icon);
        TextView t = Ui.text(app, title, 16, R.color.text);
        t.setTypeface(Ui.MEDIUM);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(0, dp(12), 0, 0);
        c.addView(t);
        TextView s = Ui.text(app, about, 12.5f, R.color.text3);
        s.setMaxLines(2);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setLineSpacing(0, 1.15f);
        s.setPadding(0, dp(4), 0, 0);
        c.addView(s);
        if (on) {
            c.setOnClickListener(v -> click.run());
        } else {
            c.setAlpha(0.5f);
            c.setOnClickListener(v -> toast(about));
        }
        return c;
    }

    /** A / command sent as a message from the box, as if typed. */
    private void command(String text) {
        app.box.input.setText(text);
        app.box.input.setSelection(app.box.input.length());
        app.sender.send();
    }

    // ---- 对话详情 ----

    private void details() {
        Chat chat = app.current;
        Transcript.Stats stats = Transcript.stats(chat);
        Dialog sheet = sheets.sheet("对话详情", null);
        LinearLayout info = sheets.card(sheet);
        sheets.option(info, 0, "标题", chat.title.isEmpty() ? "新对话" : chat.title, false, null);
        if (chat.cwd != null && !chat.cwd.isEmpty()) sheets.option(info, 0, "目录", claude.tilde(chat.cwd), false, () -> app.copy(chat.cwd));
        String model = chat.model != null && !chat.model.isEmpty() ? chat.model : claude.model;
        if (model != null && !model.isEmpty()) sheets.option(info, 0, "模型", model, false, null);
        if (chat.sessionId != null) sheets.option(info, 0, "会话", chat.sessionId.length() > 8 ? chat.sessionId.substring(0, 8) + "…" : chat.sessionId, false, () -> app.copy(chat.sessionId));
        sheets.option(info, 0, "消息", stats.users + " 条提问 · " + stats.replies + " 次回复", false, null);
        sheets.option(info, 0, "改动的文件", stats.files.size() + " 个", false, stats.files.isEmpty() ? null : () -> { sheet.dismiss(); changedFiles(); });
        if (!stats.tools.isEmpty()) {
            LinearLayout tools = sheets.card(sheet);
            Ui.margins(tools, app, 0, 12, 0, 0);
            int shown = 0;
            java.util.List<Map.Entry<String, Integer>> byUse = new java.util.ArrayList<>(stats.tools.entrySet());
            byUse.sort((a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<String, Integer> e : byUse) {
                if (shown++ == 8) break;
                sheets.option(tools, R.drawable.ic_tool, e.getKey(), e.getValue() + " 次", false, null);
            }
        }
        sheet.show();
    }

    // ---- 改动的文件 ----

    private void changedFiles() {
        Chat chat = app.current;
        Transcript.Stats stats = Transcript.stats(chat);
        Dialog sheet = sheets.sheet("改动的文件", stats.files.isEmpty() ? null : "点一下看现在的内容");
        LinearLayout card = sheets.card(sheet);
        if (stats.files.isEmpty()) sheets.empty(card, "这段对话还没有改过文件");
        java.util.List<Map.Entry<String, Integer>> list = new java.util.ArrayList<>(stats.files.entrySet());
        for (int i = list.size() - 1; i >= 0; i--) { // the latest first
            Map.Entry<String, Integer> e = list.get(i);
            String path = e.getKey();
            sheets.option(card, R.drawable.ic_file, Ui.base(path), e.getValue() > 1 ? e.getValue() + " 次" : null, false, () -> viewFile(path, chat.cwd));
        }
        sheet.show();
    }

    /** A path as Claude Code means it (absolute, ~/…, or from the conversation's folder) as a file here. */
    private File resolve(String path, String cwd) {
        String p = path;
        if (p.startsWith("~/")) p = Userland.HOME + p.substring(1);
        else if (!p.startsWith("/")) p = (cwd == null || cwd.isEmpty() ? claude.defaultCwd : cwd) + "/" + p;
        // The phone's own storage, shared into the userland, is read where Android has it.
        if (p.startsWith("/sdcard/") || p.startsWith("/storage/")) return new File(p);
        return userland.file(p);
    }

    private void viewFile(String path, String cwd) {
        File file = resolve(path, cwd);
        work.execute(() -> {
            String text, note = "";
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[PREVIEW + 1];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                boolean binary = false;
                for (int i = 0; i < Math.min(n, 4096) && !binary; i++) binary = buf[i] == 0;
                if (binary) {
                    text = "";
                    note = "不是文本文件，大小 " + Ui.size(file.length());
                } else {
                    if (n > PREVIEW) { n = PREVIEW; note = "只显示前 " + PREVIEW / 1024 + " KB，全文 " + Ui.size(file.length()); }
                    text = new String(buf, 0, n, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                text = "";
                note = "读不到这个文件：它可能已被删除，或在手机读不到的位置";
            }
            String shown = text, why = note;
            ui.post(() -> {
                Dialog sheet = sheets.sheet(Ui.base(path), claude.tilde(path));
                if (!shown.isEmpty()) {
                    TextView t = Ui.text(app, shown, 12, R.color.text);
                    t.setTypeface(Ui.MONO);
                    t.setTextIsSelectable(true);
                    sheets.content(sheet).addView(t, Ui.fill());
                }
                if (!why.isEmpty()) {
                    TextView n = Ui.text(app, why, 13, R.color.text3);
                    n.setPadding(0, dp(shown.isEmpty() ? 0 : 12), 0, 0);
                    sheets.content(sheet).addView(n, Ui.fill());
                }
                sheets.actions(sheet, sheets.button("复制路径", false, v -> app.copy(path)),
                        sheets.button("复制内容", true, v -> { if (shown.isEmpty()) toast("没有可复制的文字"); else app.copy(shown); }));
                sheet.show();
            });
        });
    }

    // ---- 导出对话 ----

    private void export() {
        Chat chat = app.current;
        String name = Transcript.fileName(chat.title);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
        String text = Transcript.markdown(chat, new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()));
        Dialog sheet = sheets.sheet("导出对话", name + ".md");
        LinearLayout card = sheets.card(sheet);
        sheets.option(card, R.drawable.ic_arrow_down, "保存到“下载”", "Markdown 文件", false, () -> {
            sheet.dismiss();
            saveToDownloads(name + "-" + stamp + ".md", text);
        });
        sheets.option(card, R.drawable.ic_copy, "复制全文", Ui.size(text.length()), false, () -> { sheet.dismiss(); app.copy(text); });
        sheets.option(card, R.drawable.ic_arrow_up, "分享…", null, false, () -> { sheet.dismiss(); share(chat.title, text); });
        sheet.show();
    }

    private void saveToDownloads(String name, String text) {
        if (!app.hasStorage()) {
            app.requestStorage();
            toast("允许访问存储后，再导出一次");
            return;
        }
        work.execute(() -> {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            File out = new File(dir, name);
            try {
                Userland.write(out, text);
                ui.post(() -> toast("已保存到 下载/" + name));
            } catch (IOException e) {
                ui.post(() -> toast("没能保存：" + e.getMessage()));
            }
        });
    }

    private void share(String title, String text) {
        boolean cut = text.length() > SHARE;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title.isEmpty() ? "对话" : title)
                .putExtra(Intent.EXTRA_TEXT, cut ? text.substring(0, SHARE) : text);
        try {
            app.startActivity(Intent.createChooser(send, "分享对话"));
            if (cut) toast("对话太长，只分享了前一部分；完整的请保存为文件");
        } catch (RuntimeException e) {
            toast("没有可以分享的应用");
        }
    }
}
