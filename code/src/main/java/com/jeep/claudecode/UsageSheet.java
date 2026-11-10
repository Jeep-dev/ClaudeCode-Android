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
 * session and the week, as its /usage shows them (the bridge's /usage), and the limit resets the account
 * was given (claude.ai's Settings → Usage → Limit resets), each with a button that spends one (/reset).
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

    /** A reset being spent: one at a time, so a second tap (the asking sheet is still sliding away) spends none. */
    private boolean resetting;

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
        LinearLayout resets = Ui.column(app); // 额度重置: shown only when the account has some
        box.addView(resets, Ui.fill());

        sheet.show();

        Chat chat = app.current;
        bridge.post("/context", obj("chat", chat.key, "sessionId", chat.sessionId), (status, data) -> {
            context.removeAllViews();
            JSONObject c = status == 200 && data != null ? data.optJSONObject("context") : null;
            if (c == null) context.addView(waitingLine("这个对话的 Claude Code 还没在运行：发一条消息后就能看到"));
            else fillContext(context, c);
        });
        loadPlan(plan, resets);
    }

    private void loadPlan(LinearLayout plan, LinearLayout resets) {
        bridge.get("/usage", (status, data) -> {
            plan.removeAllViews();
            resets.removeAllViews();
            resets.setPadding(0, 0, 0, 0);
            if (status != 200 || data == null) { plan.addView(waitingLine("读取失败：Claude Code 还没连上")); return; }
            String error = data.optString("error");
            if (error.equals("login")) plan.addView(waitingLine("Claude Code 没有用 Claude.ai 账号登录（用 API Key 时没有这些额度）"));
            else if (error.equals("expired")) plan.addView(waitingLine("Claude Code 的登录已过期：发一条消息让它刷新，再打开"));
            else if (!error.isEmpty()) plan.addView(waitingLine("读取失败（" + error + "）"));
            else {
                fillPlan(plan, data.optJSONArray("limits"));
                fillResets(resets, data.optJSONArray("resets"), () -> loadPlan(plan, resets));
            }
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
            TextView name = Ui.text(app, limitName(l), 15, R.color.text);
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
            if (!l.isNull("shareOfWeekly")) { // a model's own limit: at most this share of the whole week
                String share = "最多占每周额度的 " + Math.round(l.optDouble("shareOfWeekly")) + "%";
                resets = resets.isEmpty() ? share : resets + " · " + share;
            }
            if (!resets.isEmpty()) card.addView(Ui.text(app, resets, 12.5f, R.color.text3));
        }
    }

    private static String limitName(JSONObject l) {
        String kind = l.optString("kind"), group = l.optString("group");
        // A row for one model (weekly_scoped): the bridge names it from its scope.
        String only = l.isNull("model") ? "" : l.optString("model");
        if (!only.isEmpty()) {
            if ("session".equals(group)) return "5 小时额度 · " + only;
            return "每周额度 · " + only;
        }
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

    // ---- 额度重置 ----

    /**
     * The limit resets the account holds (claude.ai's 「Reset for free」): what each gives back, how many
     * are left and until when, and a button spending one, asked first (it cannot be undone).
     */
    private void fillResets(LinearLayout box, JSONArray grants, Runnable reload) {
        List<JSONObject> held = new ArrayList<>();
        for (int i = 0; grants != null && i < grants.length(); i++) {
            JSONObject g = grants.optJSONObject(i);
            if (g != null && g.optInt("left") > 0) held.add(g);
        }
        if (held.isEmpty()) return;
        box.setPadding(0, dp(14), 0, 0); // label() sees an empty box: the gap above the section is set here
        label(box, "额度重置");
        LinearLayout card = card(box);
        for (int i = 0; i < held.size(); i++) {
            JSONObject g = held.get(i);
            if (i > 0) card.addView(new View(app), new LinearLayout.LayoutParams(1, dp(12)));
            String title = models(g).isEmpty() ? "额度重置" : models(g) + " 重置";

            LinearLayout row = Ui.row(app);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout words = Ui.column(app);
            TextView name = Ui.text(app, title, 15, R.color.text);
            name.setTypeface(Ui.MEDIUM);
            words.addView(name);
            String left = "剩 " + g.optInt("left") + " 次";
            String ends = expires(g.optString("endsAt"));
            TextView sub = Ui.text(app, ends.isEmpty() ? left : left + " · " + ends, 12.5f, R.color.text3);
            sub.setPadding(0, dp(3), 0, 0);
            words.addView(sub);
            row.addView(words, Ui.weight());

            boolean usable = g.optBoolean("usableNow");
            TextView use = sheets.button(usable ? "免费重置" : "暂不可用", usable, usable ? v -> { if (!resetting) askReset(g, title, reload); } : null);
            use.setEnabled(usable);
            if (!usable) use.setAlpha(0.5f);
            row.addView(use);
            card.addView(row, Ui.fill());

            TextView what = Ui.text(app, gives(g), 12.5f, R.color.text2);
            what.setLineSpacing(0, 1.2f);
            what.setPadding(0, dp(8), 0, 0);
            card.addView(what);
        }
    }

    /** The models a reset gives back the week of (the bridge's, from the limits' scopes), joined; "" for all. */
    private static String models(JSONObject g) {
        JSONArray m = g.optJSONArray("models");
        List<String> names = new ArrayList<>();
        for (int i = 0; m != null && i < m.length(); i++) if (!m.optString(i).isEmpty()) names.add(m.optString(i));
        return TextUtils.join(" / ", names);
    }

    /** What a reset gives back, as the bridge read it from what the server says it clears. */
    private static String gives(JSONObject g) {
        String week;
        switch (g.optString("week")) {
            case "all": week = "每周额度回满"; break;
            case "models": week = "退回本周花在 " + models(g) + " 上的额度"; break;
            case "part": week = "退回部分每周额度"; break;
            default: week = "";
        }
        String five = g.optBoolean("session") ? "5 小时额度回满" : "";
        if (week.isEmpty()) return five.isEmpty() ? "" : five + "。";
        return week + (five.isEmpty() ? "" : "，" + five) + "。不会提高上限，用了不能撤销。";
    }

    private void askReset(JSONObject g, String title, Runnable reload) {
        Dialog sheet = sheets.sheet("使用一次" + title + "？", gives(g)
                + "\n\n剩 " + g.optInt("left") + " 次，用后剩 " + (g.optInt("left") - 1) + " 次。");
        TextView cancel = sheets.button("取消", false, v -> sheet.dismiss());
        TextView ok = sheets.button("免费重置", true, v -> {
            if (resetting) return;
            resetting = true;
            v.setEnabled(false);
            sheet.dismiss();
            bridge.post("/reset", obj("grant", g.optString("id")), (status, data) -> {
                resetting = false;
                String result = status == 200 && data != null ? data.optString("result") : "";
                String error = data != null ? data.optString("error") : "";
                switch (result) {
                    case "reset": toast("已重置"); break;
                    case "already_used": toast("这次重置已经用过了"); break;
                    case "not_limited": toast("现在还没用到额度，不需要重置"); break;
                    case "cooldown": toast("刚重置过，稍后再试"); break;
                    case "ineligible": case "unavailable": toast("这次重置现在用不了"); break;
                    default:
                        toast(error.equals("expired") ? "Claude Code 的登录已过期：发一条消息让它刷新，再试"
                                : "重置失败：" + (error.isEmpty() ? "HTTP " + status : error));
                }
                reload.run();
            });
        });
        sheets.actions(sheet, cancel, ok);
        sheet.show();
    }

    /** When an offer ends: the day and time it lapses. */
    private static String expires(String iso) {
        if (iso == null || iso.isEmpty() || iso.equals("null")) return "";
        try {
            java.time.ZonedDateTime local = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault());
            return String.format(Locale.ROOT, "%d月%d日 %02d:%02d 过期", local.getMonthValue(), local.getDayOfMonth(),
                    local.getHour(), local.getMinute());
        } catch (RuntimeException e) {
            return "";
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
