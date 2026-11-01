package com.jeep.claudecode;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Sending: a message written, queued while a reply runs, inserted, answered to Claude Code; the reply stopped. */
final class Sender extends Part {
    private static final int SHORT_TITLE = 16; // as the bridge's: a first message this short names its conversation until it has a name
    /** When a new conversation was last opened (or written in): see warm. */
    private long warmedAt;
    final ThreadAdapter.Queue queueActions = new ThreadAdapter.Queue() {
        @Override public void edit(Chat.Queued q) {
            Chat chat = app.current;
            takeBack(chat, q, () -> {
                if (!chat.queue.remove(q)) return;
                chat.attachments.addAll(0, q.attachments);
                boolean here = app.current == chat;
                String typed = here ? app.box.input.getText().toString() : chat.draft;
                String text = typed.trim().isEmpty() ? q.text : q.text + "\n" + typed;
                if (!here) { chat.draft = text; return; } // another conversation was opened meanwhile: it waits in this one's draft
                app.box.input.setText(text);
                app.box.input.setSelection(app.box.input.length());
                app.box.input.requestFocus();
                app.box.showKeyboard();
                app.renderNow();
            });
        }

        @Override public void cancel(Chat.Queued q) {
            Chat chat = app.current;
            takeBack(chat, q, () -> {
                chat.queue.remove(q);
                app.renderNow();
            });
        }

        @Override public void sendNow(Chat.Queued q) {
            Chat chat = app.current;
            // With Claude Code already: stopping the reply is enough, it then starts a turn for it.
            if (q.inserted) { if (chat.running) stop(chat); app.renderNow(); return; }
            chat.queue.remove(q);
            chat.queue.add(0, q);
            q.now = true;
            if (chat.running) stop(chat);
            else sendQueued(chat);
            app.renderNow();
        }
    };

    Sender(MainActivity app) { super(app); }

    void send() {
        Chat chat = app.current;
        String text = app.box.input.getText().toString().replaceAll("\\s+$", "");
        if (text.trim().isEmpty() && chat.attachments.isEmpty()) {
            if (chat.running) stop(chat);
            return;
        }
        if (text.matches("^/\\S[^\\n]*$") && app.slash.localCommand(text)) {
            if (!text.startsWith("/find ")) app.box.input.setText(""); // /find's words stay in the box
            app.box.hideMenu();
            app.screen.updateChrome();
            return;
        }
        if (!app.link.linked) {
            toast(app.link.installing ? "运行环境还在准备，稍后再发" : "Claude Code 正在启动，稍后再发");
            if (!app.link.connecting) app.link.connect(false);
            return;
        }
        if (app.finder.finding()) app.finder.findEnd();
        List<Chat.Attachment> attached = new ArrayList<>(chat.attachments);
        chat.attachments.clear();
        app.box.input.setText("");
        app.box.hideMenu();
        if (chat.running) {
            // Written while a reply runs: it waits (edited, taken back or sent now meanwhile), and goes
            // when the reply runs a tool (插入) or when it ends (排队): sendQueued.
            chat.queue.add(new Chat.Queued(text, attached));
            app.screen.stick = true;
            sendQueued(chat);
            app.renderNow();
            return;
        }
        app.box.hideKeyboard();
        submit(chat, text, attached);
    }

    /** What is written while a reply runs: 插入 (sent when the reply runs a tool, the model gets it
     *  with the tool's result; the default) or 排队 (sent when the reply ends). Settings → 对话. */
    boolean insertWhileRunning() {
        return prefs.getBoolean("insertWhileRunning", true);
    }

    /** Hands a message to Claude Code while a reply runs: the model gets it with its next request,
     *  or Claude Code starts a turn for it when this one ends. It waits as it was (edited, taken back
     *  or sent now) until the model has it (Chat.apply, command_lifecycle). */
    private void insert(Chat chat, Chat.Queued q) {
        q.inserted = true;
        q.uuid = java.util.UUID.randomUUID().toString();
        upload(chat, q.attachments, 0, new ArrayList<>(), q.text, q);
    }

    /** An inserted message could not be sent: it waits for the reply to end instead. */
    private void notInserted(Chat chat, Chat.Queued q, String why) {
        if (!q.inserted) return;
        q.inserted = false;
        q.uuid = null;
        q.notInserted = true; // not tried again: it goes when the reply ends
        toast("没能插入，改为回复结束后发送：" + why);
        if (!chat.running) sendQueued(chat);
        app.render();
    }

    /** Sends a message as the chat's next turn. */
    private void submit(Chat chat, String text, List<Chat.Attachment> attached) {
        Chat.Msg user = new Chat.Msg(Chat.Msg.USER);
        user.text = text;
        for (Chat.Attachment a : attached) {
            if (a.image) user.images.add(a.shown);
            else user.files.add(a.name);
        }
        chat.messages.add(user);
        if (chat.title.isEmpty()) {
            // A short message is the name until one is made (Claude Code makes none for those); a long one is not shown.
            String t = text.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty() && t.length() <= SHORT_TITLE) chat.title = t;
        }
        if (chat.cwd == null || chat.cwd.isEmpty()) chat.cwd = claude.cwd.isEmpty() ? claude.defaultCwd : claude.cwd;
        chat.running = true;
        chat.stopped = false;
        chat.startedAt = System.currentTimeMillis();
        chat.verb = chat.randomVerb();
        chat.detail = "";
        app.screen.stick = true;
        app.renderNow();
        upload(chat, attached, 0, new ArrayList<>(), text, null);
    }

    /** The reply ended: the next queued message goes, unless the reply was stopped by hand. */
    void sendQueued(Chat chat) {
        if (chat.queue.isEmpty() || !app.link.linked) return;
        if (chat.running) {
            // 插入: while the reply runs a tool, what waits goes now and the model gets it with the
            // tool's result, without the reply ending (a stop asked for keeps it waiting).
            if (!insertWhileRunning() || chat.stopping || !chat.toolRunning()) return;
            for (Chat.Queued q : new ArrayList<>(chat.queue)) if (!q.inserted && !q.now && !q.notInserted) insert(chat, q);
            return;
        }
        Chat.Queued q = null;
        for (Chat.Queued w : chat.queue) if (!w.inserted) { q = w; break; } // Claude Code has the others
        if (q == null || (chat.stopped && !q.now)) return;
        chat.queue.remove(q);
        submit(chat, q.text, q.attachments);
    }

    /** Takes a waiting message back from Claude Code, if it was handed over, then goes on; when the
     *  model has got it meanwhile, it is a message of the conversation and nothing is done. */
    private void takeBack(Chat chat, Chat.Queued q, Runnable then) {
        if (!q.inserted) { then.run(); return; }
        bridge.post("/cancel", obj("chat", chat.key, "sessionId", chat.sessionId, "uuid", q.uuid), (status, data) -> {
            if (status == 200 && data.optBoolean("cancelled")) {
                q.inserted = false;
                q.uuid = null;
                then.run();
            } else if (status == 200) {
                toast("模型已经读到这条消息了");
            } else {
                toast("没能撤回：" + data.optString("error", "HTTP " + status));
            }
        });
    }

    /** Saves non-picture files into the working folder one by one, then sends. */
    private void upload(Chat chat, List<Chat.Attachment> attached, int from, List<String> saved, String text, Chat.Queued inserted) {
        int i = from;
        while (i < attached.size() && attached.get(i).image) i++;
        if (i >= attached.size()) { deliver(chat, attached, saved, text, inserted); return; }
        Chat.Attachment f = attached.get(i);
        int next = i + 1;
        bridge.upload(chat.cwd, f.name, f.uri, (status, data) -> {
            if (status != 200) {
                String why = f.name + " 没能存到 " + Ui.base(chat.cwd) + "：" + data.optString("error", String.valueOf(status));
                if (inserted != null) notInserted(chat, inserted, why);
                else fail(chat, "发送失败", why);
                return;
            }
            saved.add(data.optString("path"));
            upload(chat, attached, next, saved, text, inserted);
        });
    }

    private void deliver(Chat chat, List<Chat.Attachment> attached, List<String> saved, String text, Chat.Queued inserted) {
        JSONArray content = new JSONArray();
        for (Chat.Attachment a : attached) {
            if (!a.image) continue;
            content.put(obj("type", "image", "source", obj("type", "base64", "media_type", a.type, "data", a.data)));
        }
        StringBuilder full = new StringBuilder(text.trim().isEmpty() ? "" : text);
        if (!saved.isEmpty()) {
            if (full.length() > 0) full.append("\n\n");
            full.append(saved.size() > 1 ? "附件已保存到：" : "附件已保存为：");
            for (String p : saved) full.append("\n- ").append(p);
        }
        if (full.length() > 0) content.put(obj("type", "text", "text", full.toString()));
        long sentAt = System.currentTimeMillis();
        JSONObject body = obj("chat", chat.key, "sessionId", chat.sessionId, "cwd", chat.cwd,
                "model", claude.model.isEmpty() ? null : claude.model, "effort", claude.effort.isEmpty() ? null : claude.effort,
                "content", content, "mid", chat.key + ":" + sentAt,
                "uuid", inserted != null ? inserted.uuid : null);
        sendWithRetry(chat, body, sentAt, 0, inserted);
    }

    /** The answer may be lost with the message delivered: the bridge ignores a repeated mid. */
    private void sendWithRetry(Chat chat, JSONObject body, long sentAt, int attempt, Chat.Queued inserted) {
        bridge.post("/send", body, (status, data) -> {
            if (status == 200) return;
            if (status < 0 && attempt < 2) {
                ui.postDelayed(() -> sendWithRetry(chat, body, sentAt, attempt + 1, inserted), 1200);
                return;
            }
            if (inserted != null) {
                // Refused for sure: it waits for the reply instead. No answer: it may have gone (the
                // reply runs on regardless), so it is not sent twice.
                if (status >= 400 || status == Bridge.REFUSED) notInserted(chat, inserted, data.optString("error", String.valueOf(status)));
                return;
            }
            if (chat.lastEvent > sentAt) return; // it is running after all
            fail(chat, "发送失败", status == -2 ? "后台没有回应。" : status == -1 || status == Bridge.REFUSED ? "连不上后台。" : data.optString("error", String.valueOf(status)));
        });
    }

    void fail(Chat chat, String titleText, String text) {
        chat.running = false;
        Chat.Msg m = chat.reply(true);
        Chat.Block b = new Chat.Block(Chat.Block.ERROR);
        b.title = titleText;
        b.text = text == null ? "" : text;
        m.blocks.add(b);
        m.done = true;
        m.touch();
        app.render();
    }

    /** Answers Claude Code's question whether a tool may run. */
    /** answers: for AskUserQuestion, what the user chose (question -> answer), sent with the allow. */
    void answer(Chat chat, Chat.Block b, boolean allow, JSONObject answers) {
        String request = b.ask;
        if (request == null) return;
        b.ask = null;
        b.touch();
        app.renderNow();
        JSONObject body = obj("chat", chat.key, "sessionId", chat.sessionId, "requestId", request, "allow", allow);
        if (answers != null) {
            try { body.put("answers", answers); } catch (org.json.JSONException ignored) { /* a JSONObject */ }
        }
        bridge.post("/permission", body, (status, data) -> {
            if (status == 200 || status == 409) return; // 409: not asked any more (the turn ended)
            b.ask = request;
            b.touch();
            app.render();
            toast("没能回复：" + data.optString("error", String.valueOf(status)));
        });
    }

    void stop(Chat chat) {
        chat.stopping = true;
        // Nothing to stop (the reply had just ended, or the request was lost): no result will say it
        // was stopped, and the next turn must not be taken for one stopped.
        bridge.post("/stop", obj("chat", chat.key, "sessionId", chat.sessionId), (status, data) -> {
            if (status != 200 || !data.optBoolean("stopped")) chat.stopping = false;
        });
    }

    /** A new conversation's Claude Code (the bridge's spare) is made ready, so its first message
     *  does not wait for one to start. A saved conversation's starts only when a message is sent. */
    void opened(Chat chat) {
        if (chat == null || chat.sessionId != null) return;
        warmedAt = android.os.SystemClock.uptimeMillis();
        bridge.post("/open", obj("chat", chat.key, "sessionId", chat.sessionId, "cwd", chat.cwd,
                "model", claude.model.isEmpty() ? null : claude.model, "effort", claude.effort.isEmpty() ? null : claude.effort), null);
    }

    /** Something is being written in a new conversation: its Claude Code gets ready meanwhile
     *  (opened), at most every 20 s. */
    void warm() {
        if (!app.link.linked || app.current == null || app.current.running) return;
        if (android.os.SystemClock.uptimeMillis() - warmedAt < 20_000) return;
        opened(app.current);
    }
}
