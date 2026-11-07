package com.jeep.claudecode;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 用量 (the top bar's menu): what fills this conversation's context window, as Claude Code's /context
 * counts it (the bridge's /context: get_context_usage), and the Claude.ai plan's limits, the five-hour
 * session and the week, as its /usage shows them (the bridge's /usage).
 *
 * The context is one segmented bar of the window, a part per kind (system prompt, tools, messages, ...),
 * each kind always in its own colour, with a legend naming them; a limit is a meter whose fill turns to
 * warning, then danger, as it runs out, said in words too.
 */
final class UsageSheet extends Part {
    /** The categorical palette (validated, light and dark), slots in a fixed order. */
    private static final int[] LIGHT = {0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948};
    private static final int[] DARK = {0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767};
    /** Claude Code's kinds, each its own slot whatever else is there: a colour follows its kind. */
    private static final String[][] KINDS = {
            {"System prompt", "系统提示"}, {"System tools", "系统工具"}, {"MCP tools", "MCP 工具"},
            {"Custom agents", "自定义代理"}, {"Memory files", "记忆文件"}, {"Skills", "技能"},
            {"Messages", "消息"}, {"MCP server instructions", "MCP 服务说明"}};

    UsageSheet(MainActivity app) { super(app); }

    void show() {
        Dialog sheet = sheets.sheet("用量", null);
        LinearLayout box = sheets.content(sheet);

        label(box, "上下文");
        LinearLayout context = card(box);
        context.addView(waitingLine("正在读取…"));

        label(box, "订阅额度");
        LinearLayout plan = card(box);
        plan.addView(waitingLine("正在读取…"));

        sheet.show();

        Chat chat = app.current;
        bridge.post("/context", obj("chat", chat.key, "sessionId", chat.sessionId), (status, data) -> {
            context.removeAllViews();
            JSONObject c = status == 200 && data != null ? data.optJSONObject("context") : null;
            if (c == null) context.addView(waitingLine("这个对话的 Claude Code 还没在运行：发一条消息后就能看到"));
            else fillContext(context, c);
        });
        bridge.get("/usage", (status, data) -> {
            plan.removeAllViews();
            if (status != 200 || data == null) { plan.addView(waitingLine("读取失败：Claude Code 还没连上")); return; }
            String error = data.optString("error");
            if (error.equals("login")) plan.addView(waitingLine("Claude Code 没有用 Claude.ai 账号登录（用 API Key 时没有这些额度）"));
            else if (error.equals("expired")) plan.addView(waitingLine("Claude Code 的登录已过期：发一条消息让它刷新，再打开"));
            else if (!error.isEmpty()) plan.addView(waitingLine("读取失败（" + error + "）"));
            else fillPlan(plan, data.optJSONArray("limits"));
        });
    }

    // ---- 上下文 ----

    private void fillContext(LinearLayout card, JSONObject c) {
        long total = c.optLong("totalTokens"), max = Math.max(1, c.optLong("maxTokens"));
        int[] palette = Themes.dark(app) ? DARK : LIGHT;

        // The headline: the share of the window in use, and how much of how much.
        LinearLayout head = Ui.row(app);
        head.setGravity(Gravity.BOTTOM);
        TextView pct = Ui.text(app, Math.round(100.0 * total / max) + "%", 30, R.color.text);
        pct.setTypeface(Ui.MEDIUM);
        head.addView(pct);
        head.addView(new View(app), new LinearLayout.LayoutParams(0, 1, 1));
        TextView of = Ui.text(app, tokens(total) + " / " + tokens(max), 14, R.color.text2);
        of.setPadding(0, 0, 0, dp(5));
        head.addView(of);
        card.addView(head, Ui.fill());

        // The parts in use, each in its kind's colour; the buffer kept for compacting in grey; the rest free.
        List<long[]> parts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        long buffer = 0;
        JSONArray cats = c.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject k = cats.optJSONObject(i);
            if (k == null || k.optBoolean("deferred")) continue; // not loaded: in no part of the window
            String name = k.optString("name");
            long t = k.optLong("tokens");
            if (name.equals("Free space")) continue;
            if (name.equals("Autocompact buffer")) { buffer = t; continue; }
            if (t <= 0) continue;
            parts.add(new long[]{t, slot(name)});
            names.add(name);
        }
        int bufferColor = Ui.color(app, R.color.borderStrong);
        Segments bar = new Segments(app, max, Ui.color(app, R.color.surface2));
        for (long[] p : parts) bar.add(p[0], palette[(int) p[1]]);
        if (buffer > 0) bar.add(buffer, bufferColor);
        bar.setContentDescription("上下文已用 " + tokens(total) + "，共 " + tokens(max));
        card.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
        Ui.margins(bar, app, 0, 10, 0, 6);

        // The legend, in two columns (all of the sheet on one screen): each part's name and amount, in the bar's order.
        List<Object[]> items = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) items.add(new Object[]{palette[(int) parts.get(i)[1]], chinese(names.get(i)), parts.get(i)[0]});
        if (buffer > 0) items.add(new Object[]{bufferColor, "自动压缩预留", buffer});
        items.add(new Object[]{Ui.color(app, R.color.surface2), "空闲", Math.max(0, max - total - buffer)});
        int rows = (items.size() + 1) / 2;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = Ui.row(app);
            for (int col = 0; col < 2; col++) {
                int i = col * rows + r; // down the first column, then the second: the bar's order
                LinearLayout cell = i < items.size()
                        ? legend((int) items.get(i)[0], (String) items.get(i)[1], (long) items.get(i)[2]) : Ui.row(app);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (col == 0) lp.setMargins(0, 0, dp(14), 0);
                line.addView(cell, lp);
            }
            card.addView(line, Ui.fill());
        }
    }

    private static int slot(String name) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i][0].equals(name)) return i;
        return 7; // anything else Claude Code adds: the last slot, shared
    }

    private static String chinese(String name) {
        for (String[] k : KINDS) if (k[0].equals(name)) return k[1];
        return name;
    }

    private LinearLayout legend(int color, String name, long tokens) {
        LinearLayout row = Ui.row(app);
        row.setPadding(0, dp(4), 0, dp(4));
        View dot = new View(app);
        dot.setBackground(Ui.round(color, dp(3), dp(1), Ui.color(app, R.color.border)));
        row.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView n = Ui.text(app, name, 13.5f, R.color.text);
        n.setPadding(dp(8), 0, dp(4), 0);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(n, Ui.weight());
        row.addView(Ui.text(app, tokens(tokens), 13.5f, R.color.text2));
        return row;
    }

    // ---- 订阅额度 ----

    private void fillPlan(LinearLayout card, JSONArray limits) {
        if (limits == null || limits.length() == 0) { card.addView(waitingLine("这个账号没有额度上限")); return; }
        for (int i = 0; i < limits.length(); i++) {
            JSONObject l = limits.optJSONObject(i);
            if (l == null) continue;
            if (i > 0) {
                View gap = new View(app);
                card.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
            }
            int pct = (int) Math.max(0, Math.min(100, Math.round(l.optDouble("percent", 0))));
            String severity = l.optString("severity");
            // The fill carries how near it is to running out; words say it too.
            int fill, state = 0;
            if (pct >= 100 || severity.equals("critical") || severity.equals("exceeded")) { fill = R.color.danger; state = 2; }
            else if (pct >= 80 || severity.equals("warning")) { fill = R.color.warn; state = 1; }
            else fill = R.color.accent;

            LinearLayout head = Ui.row(app);
            TextView name = Ui.text(app, limitName(l.optString("kind"), l.optString("group")), 15, R.color.text);
            name.setTypeface(Ui.MEDIUM);
            head.addView(name, Ui.weight());
            if (state > 0) {
                TextView s = Ui.text(app, state == 2 ? "已用完" : "快用完了", 12.5f, fill);
                s.setPadding(0, 0, dp(8), 0);
                head.addView(s);
            }
            TextView p = Ui.text(app, pct + "%", 15, R.color.text);
            p.setTypeface(Ui.MEDIUM);
            head.addView(p);
            card.addView(head, Ui.fill());

            int fillColor = Ui.color(app, fill);
            Segments meter = new Segments(app, 100, Glass.alpha(fillColor, 0.18f));
            meter.add(pct, fillColor);
            meter.setContentDescription(name.getText() + " 已用 " + pct + "%");
            card.addView(meter, new LinearLayout.LayoutParams(-1, dp(8)));
            Ui.margins(meter, app, 0, 7, 0, 5);

            String resets = resets(l.optString("resetsAt"));
            if (!resets.isEmpty()) card.addView(Ui.text(app, resets, 12.5f, R.color.text3));
        }
    }

    private static String limitName(String kind, String group) {
        switch (kind) {
            case "session": return "5 小时额度";
            case "weekly_all": return "每周额度 · 全部模型";
            case "weekly_opus": return "每周额度 · Opus";
            case "weekly_sonnet": return "每周额度 · Sonnet";
            default:
                if ("weekly".equals(group)) return "每周额度 · " + kind.replace("weekly_", "");
                if ("session".equals(group)) return "5 小时额度 · " + kind;
                return kind;
        }
    }

    /** When it starts again: in how long, if within a day; else the day and time. */
    private static String resets(String iso) {
        if (iso == null || iso.isEmpty() || iso.equals("null")) return "";
        try {
            java.time.OffsetDateTime at = java.time.OffsetDateTime.parse(iso);
            long mins = java.time.Duration.between(java.time.OffsetDateTime.now(), at).toMinutes();
            if (mins <= 0) return "即将重置";
            if (mins < 60) return mins + " 分钟后重置";
            if (mins < 24 * 60) return (mins / 60) + " 小时 " + (mins % 60) + " 分钟后重置";
            java.time.ZonedDateTime local = at.atZoneSameInstant(java.time.ZoneId.systemDefault());
            String[] week = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            return String.format(Locale.ROOT, "%d月%d日 %s %02d:%02d 重置", local.getMonthValue(), local.getDayOfMonth(),
                    week[local.getDayOfWeek().getValue() - 1], local.getHour(), local.getMinute());
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---- 共用 ----

    private void label(LinearLayout box, String text) {
        TextView t = Ui.text(app, text, 14, R.color.text2);
        t.setTypeface(Ui.MEDIUM);
        t.setPadding(dp(6), dp(box.getChildCount() > 0 ? 14 : 0), 0, dp(6));
        box.addView(t);
    }

    private LinearLayout card(LinearLayout box) {
        LinearLayout card = Ui.column(app);
        card.setPadding(dp(16), dp(12), dp(16), dp(13));
        card.setBackground(Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.border)));
        box.addView(card, Ui.fill());
        return card;
    }

    private TextView waitingLine(String text) {
        TextView t = Ui.text(app, text, 14, R.color.text2);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    /** 1,234 → 1.2k, 1,000,000 → 1m, as Claude Code writes them. */
    static String tokens(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return trim(n / 1000.0) + "k";
        return trim(n / 1_000_000.0) + "m";
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * One whole split into parts, left to right: each part as wide as its share, a 2 dp gap of the surface
     * between parts, the ends round; what is left is the track. A part at zero draws nothing.
     */
    private static final class Segments extends View {
        private final long whole;
        private final int track, surface;
        private final List<long[]> parts = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();

        Segments(Context c, long whole, int track) {
            super(c);
            this.whole = Math.max(1, whole);
            this.track = track;
            this.surface = Ui.color(c, R.color.surface);
        }

        void add(long amount, int color) {
            if (amount > 0) parts.add(new long[]{amount, color});
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), gap = 2 * getResources().getDisplayMetrics().density;
            clip.reset();
            r.set(0, 0, w, h);
            clip.addRoundRect(r, h / 2, h / 2, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setColor(track);
            canvas.drawRect(r, paint);
            float x = 0, least = Math.max(gap * 1.5f, h / 3);
            for (int i = 0; i < parts.size(); i++) {
                float width = Math.max(least, w * parts.get(i)[0] / whole);
                float end = Math.min(w, x + width);
                paint.setColor((int) parts.get(i)[1]);
                canvas.drawRect(x, 0, Math.max(x, end - (end < w ? gap : 0)), h, paint);
                // The gap after each part shows the card's surface.
                if (end < w) { paint.setColor(surface); canvas.drawRect(end - gap, 0, end, h, paint); }
                x = end;
                if (x >= w) break;
            }
            canvas.restore();
        }
    }
}
