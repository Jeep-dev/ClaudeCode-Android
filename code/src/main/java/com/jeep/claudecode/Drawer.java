package com.jeep.claudecode;

import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The drawer: the list of conversations, found by words or by folder, pinned, renamed or deleted, and the state of the link at its foot. */
final class Drawer extends Part {
    /** The conversations as the bridge last listed them (or as they were kept on the last start). */
    JSONArray sessions = new JSONArray();
    /** The words in the drawer's search box. */
    private String search = "";
    /** For the search in the drawer: sessions whose conversation has the words, with that text. */
    private final Map<String, String> hits = new HashMap<>();
    private final Runnable searchText = this::searchText;
    /** The list of conversations, and the link's state at the drawer's foot. */
    private LinearLayout sessionsBox;
    private TextView linkText;
    private View linkDot;
    /** The drawer as a whole: the immersive layout keeps it clear of the system bars. */
    LinearLayout drawerPanel;
    /** The list was read from the bridge (or changed here) in this run: the kept one is older. */
    boolean sessionsRead;
    /** What the drawer's list was last built with of the live conversations (see drawerState). */
    String drawnState = "";
    /** What the foot last showed: drawn again only when that changes. */
    String shownLink;
    /** What the list shows: all of them, those running, those waiting for an answer, those changed since they were last seen. */
    private int filter;
    private static final String[] FILTERS = {"全部", "运行中", "待确认", "未读"};
    /** The row of filters above the list. */
    private LinearLayout chips;
    /** For 未读: each conversation's last change as it was last seen (id -> mtime), kept in the preferences. */
    private Map<String, Double> seen;
    private boolean seenChanged;

    Drawer(MainActivity app) { super(app); }

    /** The screen is built again: the drawer comes new, and its state is shown again. */
    void reset() {
        shownLink = null;
        drawerPanel = null;
    }

    View buildDrawer() {
        LinearLayout d = Ui.column(app);
        drawerPanel = d;
        d.setBackgroundColor(col(R.color.bg)); // full screen: one colour with the status bar
        d.setClickable(true);

        // One line down the side: the title and the conversations' words from 24dp; icons centred
        // in a column 28dp wide from 24dp, their words from 64dp. What can be tapped is round, as elsewhere.
        LinearLayout head = Ui.row(app);
        head.setPadding(dp(24), dp(14), dp(24), dp(14));
        TextView brand = Ui.text(app, "Claude Code", 26, R.color.text);
        brand.setTypeface(Ui.SERIF); // as Settings' titles
        head.addView(brand);
        d.addView(head, Ui.fill());

        LinearLayout newChat = Ui.row(app);
        newChat.setPadding(dp(12), 0, dp(12), 0);
        newChat.setMinimumHeight(dp(48));
        newChat.setBackground(Ui.press(app, null, dp(24)));
        // As Settings' icons: the accent on a pale round of it.
        FrameLayout plus = drawerSlot(Ui.icon(app, R.drawable.ic_plus, 18, R.color.accent), 18, 18);
        plus.setBackground(Ui.round(col(R.color.accentSoft), dp(14)));
        newChat.addView(plus);
        TextView nl = Ui.text(app, "新对话", 16, R.color.text);
        nl.setTypeface(Ui.MEDIUM);
        nl.setPadding(dp(12), 0, 0, 0);
        newChat.addView(nl);
        newChat.setOnClickListener(v -> { app.frame.close(); app.switchTo(app.newChat()); });
        d.addView(newChat, Ui.fill());
        Ui.margins(newChat, app, 12, 0, 12, 8);

        LinearLayout searchBox = Ui.row(app);
        searchBox.setPadding(dp(12), 0, dp(16), 0);
        // White with a hairline, round at its ends: as the message box.
        searchBox.setBackground(Ui.round(col(R.color.surface), dp(22), dp(1), col(R.color.border)));
        searchBox.addView(drawerSlot(Ui.icon(app, R.drawable.ic_search, 18, R.color.text3), 18, 18));
        EditText searchInput = new EditText(app);
        searchInput.setBackground(null);
        searchInput.setHint("搜索对话");
        searchInput.setHintTextColor(col(R.color.text3));
        searchInput.setTextColor(col(R.color.text));
        searchInput.setTextSize(15.5f);
        searchInput.setSingleLine(true);
        searchInput.setPadding(dp(12), 0, 0, 0);
        // Built again after a restyle: what was being looked for is still in the box.
        searchInput.setText(search);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                search = s.toString();
                hits.clear();
                renderSessions();
                ui.removeCallbacks(searchText);
                if (!search.trim().isEmpty()) ui.postDelayed(searchText, 250);
            }
        });
        searchBox.addView(searchInput, Ui.weight());
        d.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(44)));
        Ui.margins(searchBox, app, 12, 0, 12, 4);

        // The filters: a pale round for the one chosen, a hairline round for the others.
        HorizontalScrollView chipScroll = new HorizontalScrollView(app);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = Ui.row(app);
        chips.setPadding(dp(12), dp(6), dp(12), dp(4));
        chipScroll.addView(chips);
        d.addView(chipScroll, Ui.fill());
        renderChips(new int[FILTERS.length]);

        // The list fades out at its ends, as the conversation does under its bar, instead of
        // being cut by a line.
        FrameLayout listArea = new FrameLayout(app);
        ScrollView scroll = new ScrollView(app);
        sessionsBox = Ui.column(app);
        sessionsBox.setPadding(dp(12), dp(8), dp(12), dp(24));
        scroll.addView(sessionsBox);
        listArea.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        int bg = col(R.color.bg);
        View fadeIn = new View(app);
        fadeIn.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg, Glass.alpha(bg, 0f)}));
        listArea.addView(fadeIn, new FrameLayout.LayoutParams(-1, dp(12), Gravity.TOP));
        View fadeOut = new View(app);
        fadeOut.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{bg, Glass.alpha(bg, 0f)}));
        listArea.addView(fadeOut, new FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM));
        d.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));

        // The whole line is Settings' button: the words (from the conversations' line) and the gear.
        LinearLayout foot = Ui.row(app);
        foot.setPadding(dp(12), 0, 0, 0);
        foot.setMinimumHeight(dp(44));
        foot.setBackground(Ui.press(app, null, dp(22)));
        foot.setOnClickListener(v -> { if (app.link.linked) app.settings.settingsSheet(); else app.link.connect(true); });
        LinearLayout link = Ui.row(app);
        linkDot = new View(app);
        link.addView(linkDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        linkText = Ui.text(app, "正在连接…", 15, R.color.text);
        linkText.setTypeface(Ui.MEDIUM);
        linkText.setSingleLine(true);
        linkText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        linkText.setPadding(dp(9), 0, 0, 0);
        link.addView(linkText);
        foot.addView(link, Ui.weight());
        // 功能, beside Settings: its own button, round as the gear.
        ImageView features = Ui.iconButton(app, R.drawable.ic_grid, 44, 20, R.color.text2);
        features.setBackground(Ui.press(app, Ui.round(col(R.color.surface), dp(22), dp(1), col(R.color.border)), dp(22)));
        features.setContentDescription("全部功能");
        features.setOnClickListener(v -> app.features.open());
        foot.addView(features, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Ui.margins(features, app, 0, 0, 8, 0);
        // Round, white with a hairline: as the conversation's own buttons; a tap on it is the line's.
        ImageView gear = Ui.iconButton(app, R.drawable.ic_gear, 44, 20, R.color.text2);
        gear.setBackground(Ui.round(col(R.color.surface), dp(22), dp(1), col(R.color.border)));
        gear.setClickable(false);
        gear.setFocusable(false);
        foot.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        d.addView(foot, Ui.fill());
        Ui.margins(foot, app, 12, 4, 12, 12);
        return d;
    }

    /** The drawer's column of icons: v (w×h dp) in the middle of a 28dp square. */
    private FrameLayout drawerSlot(View v, int w, int h) {
        FrameLayout slot = new FrameLayout(app);
        slot.addView(v, new FrameLayout.LayoutParams(dp(w), dp(h), Gravity.CENTER));
        slot.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        return slot;
    }

    void loadSessions() {
        if (!app.link.linked) { renderSessions(); return; }
        bridge.get("/sessions", (status, data) -> {
            if (status == 200 && data.optJSONArray("sessions") != null) keepSessions(data.optJSONArray("sessions"));
            renderSessions();
        });
    }

    /** The conversation list as last read, kept so it shows at once on the next start. */
    private java.io.File sessionsFile() { return new java.io.File(app.getFilesDir(), "sessions.json"); }

    JSONArray keptSessions() {
        try {
            JSONArray kept = new JSONArray(Userland.read(new java.io.FileInputStream(sessionsFile())));
            for (int i = 0; i < kept.length(); i++) {
                JSONObject s = kept.optJSONObject(i);
                if (s != null) s.remove("running"); // a new start: nothing runs yet
            }
            return kept;
        } catch (java.io.IOException | JSONException e) {
            return new JSONArray();
        }
    }

    void keepSessions(JSONArray next) {
        sessions = next;
        sessionsRead = true;
        forgetSeen(next);
        String text = next.toString();
        try {
            work.execute(() -> {
                // Written aside, then moved in: a write cut short leaves the last list whole.
                java.io.File f = sessionsFile(), part = new java.io.File(f.getPath() + ".tmp");
                try {
                    Userland.write(part, text);
                    if (!part.renameTo(f)) part.delete();
                } catch (java.io.IOException ignored) {
                    // read from the bridge again next time
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closing
        }
    }

    /** Asks the bridge which conversations contain the words being searched for. */
    private void searchText() {
        String q = search.trim();
        if (q.isEmpty() || !app.link.linked) return;
        bridge.get("/search?q=" + Uri.encode(q), (status, data) -> {
            if (!q.equals(search.trim()) || status != 200) return;
            hits.clear();
            JSONArray a = data.optJSONArray("results");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null) hits.put(r.optString("id"), r.optString("snippet"));
            }
            renderSessions();
        });
    }

    /** How long ago a conversation last changed (its last message), short: now, 5m, 1h, 3d, 2w,
     *  4mo, 1y. Read again each time the drawer opens. */
    private static String ago(double mtime) {
        if (Double.isNaN(mtime)) return "";
        long s = Math.max(0, System.currentTimeMillis() / 1000 - (long) mtime), day = 86_400;
        if (s < 60) return "now";
        if (s < 3600) return s / 60 + "m";
        if (s < day) return s / 3600 + "h";
        if (s < 7 * day) return s / day + "d";
        if (s < 30 * day) return s / (7 * day) + "w";
        if (s < 365 * day) return s / (30 * day) + "mo";
        return s / (365 * day) + "y";
    }

    private static String group(double mtime) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        long t = (long) (mtime * 1000), s = start.getTimeInMillis(), day = 86_400_000L;
        if (t >= s) return "今天";
        if (t >= s - day) return "昨天";
        if (t >= s - 7 * day) return "过去 7 天";
        if (t >= s - 30 * day) return "过去 30 天";
        return "更早";
    }

    void renderSessions() {
        if (sessionsBox == null) return;
        drawnState = drawerState();
        sessionsBox.removeAllViews();
        String q = search.trim().toLowerCase(Locale.ROOT);
        String group = "";
        int shown = 0;
        int[] counts = new int[FILTERS.length];
        // Only the working folder's (Settings → 外观 → 只显示当前目录): new conversations' folder, not its subfolders.
        String here = onlyHere() ? claude.workFolder().replaceAll("/+$", "") : "";
        // Pinned ones first, under their own heading.
        java.util.Set<String> pins = pinned();
        List<JSONObject> ordered = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.optJSONObject(i);
                if (s != null && pins.contains(s.optString("id")) == (pass == 0)) ordered.add(s);
            }
        }
        for (JSONObject s : ordered) {
            String titleText = s.optString("title").isEmpty() ? "新对话" : s.optString("title"), folder = s.optString("cwd"), id = s.optString("id");
            if (!here.isEmpty() && !folder.replaceAll("/+$", "").equals(here)) continue;
            Chat chat = app.chats.get(app.bySession.containsKey(id) ? app.bySession.get(id) : id);
            boolean live = chat != null ? chat.running : s.optBoolean("running");
            boolean pending = chat != null && chat.pending();
            boolean unread = unread(s, id);
            if (live) counts[1]++;
            if (pending) counts[2]++;
            if (unread) counts[3]++;
            boolean byName = titleText.toLowerCase(Locale.ROOT).contains(q) || folder.toLowerCase(Locale.ROOT).contains(q);
            if (!q.isEmpty() && !byName && !hits.containsKey(id)) continue;
            if ((filter == 1 && !live) || (filter == 2 && !pending) || (filter == 3 && !unread)) continue;
            String g = pins.contains(id) ? "置顶" : group(s.optDouble("mtime"));
            if (!g.equals(group)) {
                group = g;
                // As Settings' headings: the accent, close under the search, apart from a group above.
                TextView label = Ui.text(app, g, 14, R.color.accent);
                label.setTypeface(Ui.MEDIUM);
                label.setPadding(dp(12), dp(sessionsBox.getChildCount() == 0 ? 8 : 20), dp(12), dp(8));
                sessionsBox.addView(label);
            }
            LinearLayout item = Ui.column(app);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            boolean active = id.equals(app.current.sessionId);
            // The open one on a pale round of the accent, as what is chosen in Settings.
            item.setBackground(Ui.press(app, active ? Ui.round(col(R.color.accentSoft), dp(20)) : null, dp(20)));
            LinearLayout line = Ui.row(app);
            if (live) {
                View dot = new View(app);
                dot.setBackground(Ui.round(col(R.color.accent), dp(4)));
                line.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
                Ui.margins(dot, app, 0, 0, 6, 0);
            }
            TextView t = Ui.text(app, titleText, 15, R.color.text);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(t, Ui.weight());
            if (pending) {
                TextView tag = Ui.text(app, "待确认", 11.5f, R.color.accent);
                tag.setTypeface(Ui.MEDIUM);
                tag.setPadding(dp(8), dp(3), dp(8), dp(3));
                tag.setBackground(Ui.round(col(R.color.accentSoft), dp(10)));
                line.addView(tag);
                Ui.margins(tag, app, 8, 0, 0, 0);
            }
            if (unread) {
                View mark = new View(app);
                mark.setBackground(Ui.round(col(R.color.accent), dp(4)));
                line.addView(mark, new LinearLayout.LayoutParams(dp(8), dp(8)));
                Ui.margins(mark, app, 8, 0, 0, 0);
            }
            item.addView(line, Ui.fill());
            TextView meta = Ui.text(app, Ui.base(folder), 12.5f, R.color.text3);
            meta.setPadding(0, dp(3), 0, 0);
            meta.setSingleLine(true);
            if (!q.isEmpty() && !byName) { // found in what was said: show where
                String snip = hits.get(id);
                SpannableStringBuilder sb = new SpannableStringBuilder(snip);
                int at = snip.toLowerCase(Locale.ROOT).indexOf(q);
                if (at >= 0) sb.setSpan(new ForegroundColorSpan(col(R.color.text)), at, at + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                meta.setText(sb);
                meta.setEllipsize(TextUtils.TruncateAt.END);
            }
            // How long ago, in a column of one width at the end of the folder's line, its text from the
            // column's start: 55m, 1h and 3d begin at one place.
            // Shown unless turned off (Settings → 外观 → 对话时间).
            LinearLayout under = Ui.row(app);
            under.addView(meta, Ui.weight());
            if (prefs.getBoolean("drawerTimes", true)) {
                TextView when = Ui.text(app, ago(s.optDouble("mtime")), 11.5f, R.color.text3);
                when.setFontFeatureSettings("tnum");
                when.setSingleLine(true);
                when.setPadding(0, dp(3), 0, 0);
                int column = (int) Math.ceil(when.getPaint().measureText("88mo"));
                under.addView(when, new LinearLayout.LayoutParams(column + dp(2), -2));
                Ui.margins(when, app, 10, 0, 0, 0);
            }
            item.addView(under, Ui.fill());
            item.setOnClickListener(v -> openSession(id, titleText, folder));
            item.setOnLongClickListener(v -> { sessionActions(id, titleText, folder); return true; });
            sessionsBox.addView(item, Ui.fill());
            shown++;
        }
        if (seenChanged) saveSeen();
        renderChips(counts);
        if (shown == 0) {
            String none = !q.isEmpty() ? "没有匹配的对话" : filter > 0 ? "没有" + FILTERS[filter] + "的对话" : here.isEmpty() ? "还没有对话" : Ui.base(here) + " 里还没有对话";
            LinearLayout empty = Ui.column(app);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(16), dp(48), dp(16), dp(28));
            TextView words = Ui.text(app, app.link.linked ? none : "连接后显示历史对话", 14, R.color.text3);
            words.setGravity(Gravity.CENTER);
            empty.addView(words);
            sessionsBox.addView(empty, Ui.fill());
        }
    }

    /** The row of filters, each with how many it would show (but 全部). */
    private void renderChips(int[] counts) {
        if (chips == null) return;
        chips.removeAllViews();
        for (int i = 0; i < FILTERS.length; i++) {
            int f = i;
            boolean on = filter == i;
            TextView c = Ui.text(app, FILTERS[i] + (i > 0 && counts[i] > 0 ? " " + counts[i] : ""), 13.5f, on ? R.color.accent : R.color.text2);
            if (on) c.setTypeface(Ui.MEDIUM);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(14), 0, dp(14), 0);
            c.setBackground(Ui.press(app, on ? Ui.round(col(R.color.accentSoft), dp(16))
                    : Ui.round(col(R.color.surface), dp(16), dp(1), col(R.color.border)), dp(16)));
            c.setOnClickListener(v -> {
                if (filter == f) return;
                filter = f;
                renderSessions();
            });
            chips.addView(c, new LinearLayout.LayoutParams(-2, dp(32)));
            if (i > 0) Ui.margins(c, app, 8, 0, 0, 0);
        }
    }

    /** Changed since it was last seen. The open one is seen; one met for the first time counts as seen. */
    private boolean unread(JSONObject s, String id) {
        double mtime = s.optDouble("mtime");
        if (Double.isNaN(mtime)) return false;
        if (seen == null) loadSeen();
        Double was = seen.get(id);
        if (was == null || id.equals(app.current.sessionId)) {
            if (was == null || was != mtime) { seen.put(id, mtime); seenChanged = true; }
            return false;
        }
        return mtime > was + 0.5;
    }

    private void loadSeen() {
        seen = new HashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("seen", "{}"));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                seen.put(k, o.optDouble(k));
            }
        } catch (JSONException ignored) {
            // everything counts as seen again
        }
    }

    private void saveSeen() {
        seenChanged = false;
        prefs.edit().putString("seen", new JSONObject(seen).toString()).apply();
    }

    /** What the bridge no longer lists is forgotten. */
    private void forgetSeen(JSONArray listed) {
        if (listed.length() == 0) return;
        if (seen == null) loadSeen();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < listed.length(); i++) {
            JSONObject s = listed.optJSONObject(i);
            if (s != null) ids.add(s.optString("id"));
        }
        if (seen.keySet().retainAll(ids)) seenChanged = true;
    }

    private void openSession(String id, String titleText, String folder) {
        app.frame.close();
        String key = app.bySession.containsKey(id) ? app.bySession.get(id) : id;
        Chat chat = app.chats.get(key);
        if (chat == null) {
            chat = new Chat(key);
            chat.sessionId = id;
            chat.cwd = folder;
            chat.loaded = false;
            app.chats.put(key, chat);
            app.bySession.put(id, key);
        }
        if (chat.title.isEmpty()) chat.title = titleText;
        app.switchTo(chat);
        app.loadHistory(chat);
        if (app.link.linked) app.sender.opened(chat);
    }

    /** The drawer lists only the working folder's conversations (off by default; Settings → 外观). */
    boolean onlyHere() {
        return prefs.getBoolean("drawerOnlyHere", false);
    }

    private java.util.Set<String> pinned() {
        java.util.Set<String> out = new java.util.HashSet<>();
        JSONArray a = savedArray("pinned");
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private void sessionActions(String id, String titleText, String folder) {
        sessionsBox.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        Dialog sheet = sheets.sheet(titleText, null);
        LinearLayout card = sheets.card(sheet);
        boolean pin = pinned().contains(id);
        sheets.option(card, R.drawable.ic_pin, pin ? "取消置顶" : "置顶", null, false, () -> {
            sheet.dismiss();
            java.util.Set<String> pins = pinned();
            if (pin) pins.remove(id); else pins.add(id);
            prefs.edit().putString("pinned", new JSONArray(pins).toString()).apply();
            renderSessions();
        });
        sheets.option(card, R.drawable.ic_pencil, "重命名", null, false, () -> {
            sheet.dismiss();
            sheets.textSheet("重命名", titleText, "", false, (t, done) -> {
                String name = t.trim();
                if (name.isEmpty()) return;
                bridge.post("/title", obj("id", id, "title", name), (status, data) -> {
                    if (status != 200) { toast("重命名失败"); return; }
                    Chat ch = app.chats.get(app.bySession.containsKey(id) ? app.bySession.get(id) : id);
                    if (ch != null) ch.title = name;
                    done.run();
                    app.screen.updateChrome();
                    loadSessions();
                });
            }, null);
        });
        sheets.option(card, R.drawable.ic_trash, "删除对话", null, true, () -> {
            sheet.dismiss();
            bridge.post("/delete", obj("id", id), (status, data) -> {
                if (status != 200) { toast("删除失败"); return; }
                JSONArray next = new JSONArray();
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject s = sessions.optJSONObject(i);
                    if (s != null && !id.equals(s.optString("id"))) next.put(s);
                }
                keepSessions(next);
                String key = app.bySession.containsKey(id) ? app.bySession.get(id) : id;
                if (app.current.key.equals(key)) app.switchTo(app.newChat());
                app.drop(key, id);
                renderSessions();
                toast("已删除");
            });
        });
        sheet.show();
    }

    /** The open conversation and those running: all the list shows that events change. */
    String drawerState() {
        StringBuilder s = new StringBuilder(app.current == null ? "" : String.valueOf(app.current.sessionId));
        for (Chat ch : app.chats.values()) if (ch.running && ch.sessionId != null) s.append(' ').append(ch.sessionId);
        return s.toString();
    }

    void updateLink() {
        if (linkDot == null) return;
        // Linked: the user's own words (a setting), nothing when they wrote none.
        String label = app.link.linked ? prefs.getString("footer", "") : app.link.installing ? "正在准备…"
                : app.link.connecting ? "正在启动…" : "未运行 · 点此重试";
        int bannerShown = !app.link.linked && !app.link.connecting && app.link.setup == null && app.link.everLinked ? View.VISIBLE : View.GONE;
        if (app.screen.banner.getVisibility() != bannerShown) app.screen.banner.setVisibility(bannerShown);
        if (label.equals(shownLink)) return;
        shownLink = label;
        int dot = app.link.linked ? R.color.ok : app.link.connecting ? R.color.text3 : R.color.danger;
        linkDot.setBackground(Ui.round(col(dot), dp(4)));
        linkDot.setVisibility(app.link.linked ? View.GONE : View.VISIBLE);
        linkText.setPadding(app.link.linked ? 0 : dp(9), 0, 0, 0);
        linkText.setText(label);
        ((View) linkText.getParent()).setVisibility(label.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }
}
