package com.jeep.claudecode;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/** What can be done with a message: its menu, selecting text, translating; going back to a message (/tree), taking one out. */
final class MessageActions extends Part {
    MessageActions(MainActivity app) { super(app); }

    /**
     * /tree: the user's messages in this conversation, the last first. One picked, the conversation
     * goes back to before it (the bridge's /rewind cuts the transcript there: the model never sees
     * what was after, a message its safeguards refused included) and its text is in the box again.
     */
    void tree() {
        Chat chat = app.current;
        if (chat.sessionId == null) { toast("这个对话还没有可以回退的消息"); return; }
        if (chat.running) { toast("回复结束后才能回退"); return; }
        if (!app.link.linked) { toast("Claude Code 正在启动，稍后再试"); return; }
        // Read again: a message sent from here knows its uuid only from the transcript.
        bridge.get("/history?id=" + Uri.encode(chat.sessionId), (status, data) -> {
            if (status != 200) { toast("读取对话失败：" + data.optString("error", "HTTP " + status)); return; }
            chat.load(data);
            chat.loaded = true;
            if (app.current == chat) { app.screen.adapter.show(chat, true); app.renderNow(); }
            List<Chat.Msg> users = new ArrayList<>();
            for (Chat.Msg m : chat.messages) if (m.kind == Chat.Msg.USER && m.uuid != null) users.add(m);
            if (users.isEmpty()) { toast("没有可以回退的消息"); return; }
            Dialog sheet = sheets.sheet("回到哪条消息之前", "选中的消息和它之后的对话都会删掉，它的文字放回输入框");
            LinearLayout card = sheets.card(sheet);
            for (int i = users.size() - 1; i >= 0; i--) {
                Chat.Msg m = users.get(i);
                sheets.option(card, 0, (i + 1) + ". " + preview(m), null, false, () -> {
                    sheet.dismiss();
                    rewindAsk(chat, m);
                });
            }
            sheet.show();
        });
    }

    private static String preview(Chat.Msg m) {
        String t = m.text.trim().replaceAll("\\s+", " ");
        return !t.isEmpty() ? t : !m.images.isEmpty() ? "[图片]" : "（空）";
    }

    private void rewindAsk(Chat chat, Chat.Msg m) {
        Dialog sheet = sheets.sheet("回到这条消息之前？", preview(m) + "\n\n它和之后的对话会删掉，不能恢复。");
        TextView cancel = sheets.button("取消", false, v -> sheet.dismiss());
        TextView ok = sheets.button("回退", true, v -> { sheet.dismiss(); rewind(chat, m); });
        sheets.actions(sheet, cancel, ok);
        sheet.show();
    }

    private void rewind(Chat chat, Chat.Msg m) {
        String id = chat.sessionId;
        bridge.post("/rewind", obj("chat", chat.key, "sessionId", id, "uuid", m.uuid), (status, data) -> {
            if (status != 200) { toast("回退失败：" + data.optString("error", "HTTP " + status)); return; }
            if (data.optInt("left") == 0) {
                gone(chat, id, m.text); // nothing was before it: a new conversation starts with its text
            } else {
                int at = chat.messages.indexOf(m);
                if (at >= 0) chat.messages.subList(at, chat.messages.size()).clear();
                if (app.current == chat) {
                    app.box.input.setText(m.text);
                    app.box.input.setSelection(app.box.input.length());
                    app.screen.stick = true;
                    app.screen.adapter.show(chat, true);
                    app.renderNow();
                } else {
                    chat.draft = m.text;
                }
            }
            if (!m.images.isEmpty()) toast("已回退，图片要重新添加");
            else toast("已回退");
            app.drawer.loadSessions();
        });
    }

    /** The conversation's transcript is gone (nothing left in it): a new one takes its place, with this draft. */
    private void gone(Chat chat, String id, String draft) {
        JSONArray next = new JSONArray();
        for (int i = 0; i < app.drawer.sessions.length(); i++) {
            JSONObject s = app.drawer.sessions.optJSONObject(i);
            if (s != null && !id.equals(s.optString("id"))) next.put(s);
        }
        app.drawer.keepSessions(next);
        app.drop(chat.key, id);
        Chat fresh = app.newChat();
        fresh.cwd = chat.cwd;
        fresh.draft = draft;
        if (app.current == chat) app.switchTo(fresh);
    }

    /** A message pressed long: a small card where the finger is, in place of selecting its text. */
    void messageMenu(Chat.Msg m) {
        app.screen.list.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        Chat chat = app.current;
        boolean user = m.kind == Chat.Msg.USER;
        int shade = dp(24); // room for the card's whole shadow
        FrameLayout room = new FrameLayout(app);
        room.setPadding(shade, shade, shade, shade);
        LinearLayout card = sheets.menuCard();
        room.addView(card, new FrameLayout.LayoutParams(-2, -2));
        android.widget.PopupWindow pop = new android.widget.PopupWindow(room, -2, -2, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setOutsideTouchable(true);
        // Selecting is what is done most; it shows the Markdown, so a table's cells can be selected too.
        sheets.menuItem(card, pop, R.drawable.ic_edit_lines, "选择文字", null, () -> selectSheet(user ? m.text : replyText(m, false)));
        if (user) {
            sheets.menuItem(card, pop, R.drawable.ic_copy, "复制", null, () -> app.copy(m.text));
        } else {
            sheets.menuItem(card, pop, R.drawable.ic_file, "复制 Markdown", null, () -> app.copy(replyText(m, false)));
            sheets.menuItem(card, pop, R.drawable.ic_copy, "复制文字", null, () -> app.copy(replyText(m, true)));
        }
        if (user) sheets.menuItem(card, pop, R.drawable.ic_pencil, "编辑重发", null, () -> {
            if (canChange(chat, "回退")) withUuid(chat, m, found -> rewindAsk(chat, found));
        });
        sheets.menuItem(card, pop, R.drawable.ic_trash, "删除", null, () -> {
            if (canChange(chat, "删除")) withUuid(chat, m, found -> deleteAsk(chat, found));
        });
        // Where the finger was, kept on the screen: above it when there is no room below.
        room.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = room.getMeasuredWidth(), h = room.getMeasuredHeight();
        int screenW = app.getResources().getDisplayMetrics().widthPixels, screenH = app.getResources().getDisplayMetrics().heightPixels;
        int x = Math.max(0, Math.min((int) app.screen.touchX - w / 2, screenW - w));
        int y = (int) app.screen.touchY + h > screenH - dp(24) ? (int) app.screen.touchY - h : (int) app.screen.touchY;
        pop.showAtLocation(app.screen.list, Gravity.NO_GRAVITY, x, Math.max(0, y));
        // It grows out of the finger.
        card.setPivotX(Math.max(0, Math.min(w - 2 * shade, app.screen.touchX - x - shade)));
        card.setPivotY(Math.max(0, Math.min(h - 2 * shade, app.screen.touchY - Math.max(0, y) - shade)));
        card.setAlpha(0f);
        card.setScaleX(0.8f);
        card.setScaleY(0.8f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
                .setInterpolator(Motion.EMPHASIZED).start();
    }

    /** A reply's text blocks: as shown (plain) or as written (Markdown). */
    private String replyText(Chat.Msg m, boolean plain) {
        StringBuilder s = new StringBuilder();
        for (Chat.Block b : m.blocks) {
            if (b.kind != Chat.Block.TEXT) continue;
            if (s.length() > 0) s.append("\n\n");
            s.append(plain ? app.md.plain(b.text) : b.text);
        }
        return s.toString();
    }

    /** The text in a sheet, to select a part of it. */
    private void selectSheet(String text) {
        Dialog sheet = sheets.sheet("选择文字", null);
        TextView t = Ui.text(app, text, 15.5f, R.color.text);
        t.setLineSpacing(0, 1.1f);
        t.setTextIsSelectable(true);
        sheets.content(sheet).addView(t, Ui.fill());
        // The sheet reaches the screen's bottom edge (no navigation bar): the text, and the scroll it
        // is in, end above the system's gesture strip, or a swipe there is the system's (one-handed
        // mode), and with room under them.
        View box = ((ViewGroup) sheet.findViewById(android.R.id.content)).getChildAt(0);
        android.view.WindowInsets in = app.getWindow().getDecorView().getRootWindowInsets();
        int gestures = in != null && Build.VERSION.SDK_INT >= 29 ? in.getMandatorySystemGestureInsets().bottom : 0;
        box.setPadding(box.getPaddingLeft(), box.getPaddingTop(), box.getPaddingRight(), box.getPaddingBottom() + gestures);
        sheet.show();
    }

    /**
     * The text in Google Translate, as its 翻译 for selected text does (shown over the app, nothing
     * replaced); without it, the apps that take selected text, to pick one.
     */
    void translate(String text) {
        String s = text.trim();
        if (s.isEmpty()) return;
        // Far more than it translates at once, and within what an intent may carry.
        if (s.length() > 100_000) s = s.substring(0, 100_000);
        Intent i = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, s).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        try {
            app.startActivity(new Intent(i).setPackage("com.google.android.apps.translate"));
        } catch (android.content.ActivityNotFoundException e) {
            try {
                app.startActivity(Intent.createChooser(i, "翻译"));
            } catch (RuntimeException e2) {
                toast("没有可以翻译的应用");
            }
        }
    }

    /** Whether the conversation's transcript may be changed now; says why not. */
    private boolean canChange(Chat chat, String what) {
        if (chat.sessionId == null) { toast("这条消息还没存进对话记录"); return false; }
        if (chat.running) { toast("回复结束后才能" + what); return false; }
        if (!app.link.linked) { toast("Claude Code 正在启动，稍后再试"); return false; }
        return true;
    }

    /**
     * The message with its uuid. One sent or received here knows it only from the transcript: that
     * is read again, and the message found there by its place among those of its kind and its text.
     */
    private void withUuid(Chat chat, Chat.Msg m, java.util.function.Consumer<Chat.Msg> then) {
        if (m.uuid != null) { then.accept(m); return; }
        int nth = 0;
        for (Chat.Msg o : chat.messages) {
            if (o == m) break;
            if (o.kind == m.kind) nth++;
        }
        int place = nth;
        String text = m.kind == Chat.Msg.USER ? m.text : replyText(m, false);
        bridge.get("/history?id=" + Uri.encode(chat.sessionId), (status, data) -> {
            if (status != 200) { toast("读取对话失败：" + data.optString("error", "HTTP " + status)); return; }
            chat.load(data);
            chat.loaded = true;
            if (app.current == chat) { app.screen.adapter.show(chat, true); app.renderNow(); }
            int k = 0;
            for (Chat.Msg o : chat.messages) {
                if (o.kind != m.kind || k++ != place) continue;
                if (o.uuid != null && text.equals(o.kind == Chat.Msg.USER ? o.text : replyText(o, false))) then.accept(o);
                else toast("对话记录里找不到这条消息");
                return;
            }
            toast("对话记录里找不到这条消息");
        });
    }

    private void deleteAsk(Chat chat, Chat.Msg m) {
        String what = m.kind == Chat.Msg.USER ? preview(m) : replyText(m, true).trim().replaceAll("\\s+", " ");
        Dialog sheet = sheets.sheet("删除这条消息？", (what.length() > 80 ? what.substring(0, 80) + "…" : what)
                + "\n\n" + (m.kind == Chat.Msg.USER ? "" : "这条回复里的工具调用也一起删掉。")
                + "Claude Code 的对话记录里也会删掉，不能恢复。");
        TextView cancel = sheets.button("取消", false, v -> sheet.dismiss());
        TextView ok = sheets.button("删除", true, v -> { sheet.dismiss(); deleteMessage(chat, m); });
        sheets.actions(sheet, cancel, ok);
        sheet.show();
    }

    /**
     * Takes the message out of the transcript (the bridge's /delete), then reads it again: what was
     * on its two sides is shown as the transcript now has it, and pictures are named anew.
     */
    private void deleteMessage(Chat chat, Chat.Msg m) {
        String id = chat.sessionId;
        String role = m.kind == Chat.Msg.USER ? "user" : "assistant";
        bridge.post("/delete-message", obj("chat", chat.key, "sessionId", id, "uuid", m.uuid, "role", role), (status, data) -> {
            if (status != 200 || !data.has("left")) { toast("删除失败：" + data.optString("error", "HTTP " + status)); return; }
            toast("已删除");
            app.drawer.loadSessions();
            if (data.optInt("left") == 0) { gone(chat, id, ""); return; }
            Images.forget();
            bridge.get("/history?id=" + Uri.encode(id), (st, history) -> {
                if (st != 200) return;
                chat.load(history);
                chat.loaded = true;
                if (app.current == chat) { app.screen.adapter.show(chat, true); app.renderNow(); }
            });
        });
    }
}
