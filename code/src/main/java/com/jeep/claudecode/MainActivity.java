package com.jeep.claudecode;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/** Claude Code front end: native interface for Claude Code running in this app's own userland behind the bridge. */
public final class MainActivity extends Activity implements Bridge.Events {
    /** What is asked of another app (a file to attach, a font file) and of the system (the storage permission). */
    static final int PICK_FILES = 1, PICK_FONT = 2, VOICE = 3;
    private static final int ASK_STORAGE = 2;
    private static final String STORAGE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

    // What the parts use: the bridge to Claude Code, the userland it runs in, and the app's own threads and settings.
    Bridge bridge;
    Userland userland;
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService work = Executors.newSingleThreadExecutor();
    Md md;

    // The parts the screen is made of: each is built for this activity and reaches the others through it (see Part).
    Claude claude;
    Sheets sheets;
    Link link;
    ChatScreen screen;
    Box box;
    Attachments attachments;
    SlashCommands slash;
    Sender sender;
    Finder finder;
    MessageActions messages;
    Drawer drawer;
    Settings settings;
    LookPage look;
    ClaudePage claudePage;
    ExtPage ext;
    Skills skills;
    McpServers mcp;
    AppPage appPage;
    Pickers pickers;
    Voice voice;
    UsageSheet usage;

    // state: the conversations, by key and by the session each comes to have
    final Map<String, Chat> chats = new HashMap<>();
    final Map<String, String> bySession = new HashMap<>();
    private final Set<String> dropped = new HashSet<>(); // keys and sessions of conversations deleted: events still on the way are let go
    Chat current;

    // views
    DrawerFrame frame;
    private boolean renderQueued;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        bridge = new Bridge(this, this);
        Images.saved = bridge::image;
        userland = new Userland(this);
        // The parts of the screen, the first two being what the others reach by name (Part, which checks it).
        claude = new Claude(this);
        sheets = new Sheets(this);
        link = new Link(this);
        screen = new ChatScreen(this);
        box = new Box(this);
        attachments = new Attachments(this);
        slash = new SlashCommands(this);
        sender = new Sender(this);
        finder = new Finder(this);
        messages = new MessageActions(this);
        drawer = new Drawer(this);
        settings = new Settings(this);
        look = new LookPage(this);
        claudePage = new ClaudePage(this);
        ext = new ExtPage(this);
        skills = new Skills(this);
        mcp = new McpServers(this);
        appPage = new AppPage(this);
        pickers = new Pickers(this);
        voice = new Voice(this);
        usage = new UsageSheet(this);
        md = new Md(talkContext(), this::copy, this::openUrl, this::userFile);
        // The keyboard opens when the box is tapped, never by itself (coming back from the background).
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        build();
        switchTo(newChat());
        drawer.renderSessions();
        work.execute(() -> {
            JSONArray kept = drawer.keptSessions();
            ui.post(() -> {
                if (drawer.sessionsRead || kept.length() == 0) return; // the bridge's own list came first
                drawer.sessions = kept;
                drawer.renderSessions();
            });
        });
        link.connect(false);
        ui.postDelayed(ticker, 120);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (screen.adapter != null) screen.adapter.tick();
            drawer.updateLink();
            ui.postDelayed(this, 120);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        link.resume();
        voice.warm(); // the mic listens at once when pressed
    }

    @Override protected void onPause() {
        super.onPause();
        link.pause();
        voice.pause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(ticker);
        bridge.close();
        work.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (box.slashScroll.getVisibility() == View.VISIBLE) { box.hideMenu(); return; }
        if (frame.isOpen()) { frame.close(); return; }
        moveTaskToBack(true);
    }

    /** The screen: the conversation, and the drawer beside it. Built again when the colours or text size change. */
    private void build() {
        drawer.reset();
        View main = screen.build();
        frame = new DrawerFrame(this, main, drawer.buildDrawer());
        screen.placeImmersive(); // the drawer too, now that it is there
        frame.setListener(() -> {
            box.hideKeyboard();
            drawer.loadSessions();
        });
        setContentView(frame);
    }

    Chat newChat() {
        Chat chat = new Chat(Chat.newKey());
        chat.cwd = claude.cwd.isEmpty() ? claude.defaultCwd : claude.cwd;
        chats.put(chat.key, chat);
        return chat;
    }

    void switchTo(Chat chat) {
        if (finder.finding()) finder.findEnd();
        voice.cancel();
        if (current != null) current.draft = box.input.getText().toString();
        current = chat;
        box.input.setText(chat.draft);
        box.input.setSelection(box.input.length());
        screen.stick = true;
        screen.adapter.show(chat, true);
        renderNow();
        drawer.renderSessions();
    }

    /** Coalesces updates to one per frame. */
    void render() {
        if (renderQueued) return;
        renderQueued = true;
        screen.list.postOnAnimation(() -> { renderQueued = false; renderNow(); });
    }

    void renderNow() {
        screen.adapter.show(current, false);
        boolean empty = current.messages.isEmpty() && !current.running;
        screen.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) screen.fillEmpty();
        if (screen.stick) screen.scrollToEnd(false);
        screen.updateChrome();
        drawer.updateLink();
    }

    /** A path as Claude Code means it (absolute, ~/…, or from this conversation's folder) as a file here. */
    private java.io.File userFile(String path) {
        String p = Uri.decode(path);
        if (p.startsWith("~/")) p = Userland.HOME + p.substring(1);
        else if (!p.startsWith("/")) p = (current.cwd == null || current.cwd.isEmpty() ? claude.defaultCwd : current.cwd) + "/" + p;
        return userland.file(p);
    }

    /** Reads a saved conversation once; a failed read is tried again on the next link or tap. */
    void loadHistory(Chat target) {
        if (target.loaded || target.sessionId == null || !link.linked) return;
        target.loaded = true;
        bridge.get("/history?id=" + Uri.encode(target.sessionId), (status, data) -> {
            if (status != 200) {
                target.loaded = false;
                toast(status == -2 ? "读取对话失败：桥接 30 秒没有回应" : status == -1 || status == Bridge.REFUSED ? "读取对话失败：连不上桥接" : "读取对话失败：" + data.optString("error", "HTTP " + status));
                return;
            }
            target.load(data);
            if (!data.optString("cwd").isEmpty()) target.cwd = data.optString("cwd");
            if (current == target) { screen.adapter.show(target, true); screen.stick = true; renderNow(); }
        });
    }

    @Override public void events(List<JSONObject> batch) {
        for (JSONObject ev : batch) onEvent(ev);
        for (Chat ch : new ArrayList<>(chats.values())) sender.sendQueued(ch);
        render();
        // Events come many times a second while a reply streams: the list is built again only when
        // what it shows of the live conversations changed.
        if (frame.isOpen() && !drawer.drawerState().equals(drawer.drawnState)) drawer.renderSessions();
    }

    /** A conversation deleted: it and its session are forgotten, and what its claude still says is let go (see chatFor). */
    void drop(String key, String sessionId) {
        chats.remove(key);
        bySession.remove(sessionId);
        bySession.values().remove(key);
        dropped.add(key);
        dropped.add(sessionId);
    }

    /** The conversation an event belongs to, made when it is not known yet; null for one deleted. */
    private Chat chatFor(JSONObject ev) {
        String key = ev.optString("chat");
        Chat chat = chats.get(key);
        if (chat != null) return chat;
        if (dropped.contains(key)) return null;
        JSONObject data = ev.optJSONObject("data");
        String sid = data != null && data.has("session_id") ? data.optString("session_id") : key.startsWith("n-") ? null : key;
        if (sid != null && dropped.contains(sid)) return null;
        if (sid != null && bySession.containsKey(sid) && chats.containsKey(bySession.get(sid))) return chats.get(bySession.get(sid));
        // Started before this app was: its history loads from the transcript when opened.
        chat = new Chat(key);
        chat.sessionId = sid;
        chat.cwd = claude.defaultCwd;
        chat.loaded = false;
        chats.put(key, chat);
        if (sid != null) bySession.put(sid, key);
        return chat;
    }

    private void onEvent(JSONObject ev) {
        if ("gap".equals(ev.optString("kind"))) { gap(); return; }
        Chat chat = chatFor(ev);
        if (chat == null) return;
        chat.lastEvent = System.currentTimeMillis();
        String kind = ev.optString("kind");
        JSONObject data = ev.optJSONObject("data");
        if ("cc".equals(kind) && data != null) {
            if ("system".equals(data.optString("type")) && "init".equals(data.optString("subtype"))) keepWhatItOffers(data);
            if (chat.apply(data)) {
                drawer.loadSessions();
                loadHistory(chat); // one not read yet (or since a gap) is read now that the turn is whole
            }
            if (chat.sessionId != null) bySession.put(chat.sessionId, chat.key);
        } else if ("title".equals(kind)) {
            if (data != null && !data.optString("title").isEmpty()) {
                chat.title = data.optString("title"); // a short name made after the first turn
                screen.updateChrome();
                drawer.loadSessions();
            }
        } else if ("state".equals(kind)) {
            if (data != null && data.optBoolean("running") && !chat.running) {
                chat.running = true;
                chat.startedAt = System.currentTimeMillis();
            }
        } else if ("exit".equals(kind)) {
            exited(chat, data);
        }
    }

    /** What a new Claude Code run says it has (its init message): the tools, servers, skills, agents and commands, kept for the screens that list them. */
    private void keepWhatItOffers(JSONObject data) {
        SharedPreferences.Editor e = prefs.edit();
        if (data.optJSONArray("tools") != null) e.putString("tools", data.optJSONArray("tools").toString());
        if (data.optJSONArray("mcp_servers") != null) e.putString("mcpServers", data.optJSONArray("mcp_servers").toString());
        if (data.optJSONArray("skills") != null) e.putString("skills", data.optJSONArray("skills").toString());
        if (data.optJSONArray("agents") != null) e.putString("agents", data.optJSONArray("agents").toString());
        if (data.optJSONArray("slash_commands") != null) e.putString("slashCommands", data.optJSONArray("slash_commands").toString());
        e.apply();
        JSONArray cmds = data.optJSONArray("slash_commands");
        if (cmds != null && cmds.length() > 0) {
            claude.commands.clear();
            for (int i = 0; i < cmds.length(); i++) if (!cmds.optString(i).startsWith("_")) claude.commands.add(cmds.optString(i));
            prefs.edit().putString("commands", new JSONArray(claude.commands).toString()).apply();
        }
    }

    /** Claude Code's process ended; when it did so in the middle of a turn, the turn ends with an error saying how. */
    private void exited(Chat chat, JSONObject data) {
        if (data != null && data.optBoolean("running")) {
            Chat.Msg m = chat.reply(true);
            Chat.Block b = new Chat.Block(Chat.Block.ERROR);
            b.title = "Claude Code 意外退出";
            int code = data.optInt("code");
            // Popen gives a negative code for a signal; 9 is the system killing it.
            String how = code == -9 ? "被系统强制结束（SIGKILL）"
                    : code < 0 ? "被信号 " + (-code) + " 结束" : "退出码 " + code;
            StringBuilder err = new StringBuilder();
            for (String line : data.optString("stderr").split("\n")) {
                // Claude Code notes models it does not know (DeepSeek's) on every run: not the cause.
                if (line.trim().isEmpty() || line.contains("unrecognized_model")) continue;
                err.append('\n').append(line);
            }
            b.text = how + err;
            m.blocks.add(b);
            m.done = true;
            m.touch();
            chat.stopped = true; // Claude Code died: what is queued waits to be sent by hand
        }
        chat.unhand(); // what it held waits here again
        chat.running = false;
        chat.stopping = false; // no result is coming to end the stop asked for: the next turn is its own
    }

    @Override public void link(boolean up) { link.changed(up); }

    boolean hasStorage() { return checkSelfPermission(STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    void requestStorage() {
        requestPermissions(new String[]{STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == Voice.ASK_MIC) {
            voice.granted(results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
            return;
        }
        if (request != ASK_STORAGE) return;
        settings.refreshSettings();
        if (!hasStorage()) { toast("没有存储权限：Claude Code 看不到手机里的文件"); return; }
        // /sdcard is shared into the userland when the bridge starts.
        if (!link.installing) link.connect(true);
    }

    /**
     * Events were dropped while the app was away (the bridge keeps only the last ones): the
     * conversations are read again from their transcripts, a running one when its turn ends.
     */
    private void gap() {
        for (Chat ch : chats.values()) if (ch.sessionId != null) ch.loaded = false;
        if (!current.running) loadHistory(current);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_FONT && result == RESULT_OK && data != null && data.getData() != null) {
            look.addFont(data.getData());
            return;
        }
        if (request == VOICE) { voice.picked(result, data); return; }
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        attachments.picked(data);
    }

    /** The colours or text size changed: the screen is built again around the same conversations. */
    void restyle() {
        String at = settings.settingsAt;
        boolean open = settings.settingsPage != null;
        int scrolled = open ? settings.settingsScroll().getScrollY() : 0;
        if (open) {
            // Its dismissal is told later: Settings is let go of now, to be opened again below.
            settings.settingsPage.dismiss();
            settings.settingsPage = null;
        }
        // The drawer (Settings is opened from it) and a /find are as they were, in the new screen.
        boolean drawerOpen = frame.isOpen();
        String finding = finder.finding() ? finder.findQuery : null;
        int foundAt = finder.findAt;
        Chat chat = current;
        chat.draft = box.input.getText().toString();
        current = null;
        md = new Md(talkContext(), this::copy, this::openUrl, this::userFile);
        build();
        if (drawerOpen) frame.openNow();
        switchTo(chat);
        if (finding != null) screen.list.post(() -> {
            finder.find(finding);
            if (finder.finding()) finder.findGo(foundAt);
        });
        drawer.renderSessions();
        drawer.updateLink();
        if (open) {
            settings.settingsAt = at;
            settings.settingsInstant = true;
            settings.settingsSheet();
            settings.settingsInstant = false;
            // Where it was, not back at its top.
            ScrollView again = settings.settingsScroll();
            again.post(() -> again.scrollTo(0, scrolled));
        }
    }

    /** The context conversation text is made in: the chosen text size over the system's. */
    android.content.Context talkContext() {
        float k = Fonts.scale(this);
        if (k == 1f) return this;
        android.content.res.Configuration o = new android.content.res.Configuration();
        o.fontScale = getResources().getConfiguration().fontScale * k;
        android.view.ContextThemeWrapper w = new android.view.ContextThemeWrapper(this, getTheme());
        w.applyOverrideConfiguration(o);
        return w;
    }

    /** The system's light or dark when the screen was built, to follow it when it changes. */
    private int night;

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        int now = config.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (now == night) return;
        night = now;
        if (Themes.mode(this) == 0) restyle();
    }

    void openTerminal() {
        if (!userland.ready()) { toast("运行环境还没准备好"); return; }
        startActivity(new Intent(this, TerminalActivity.class));
        overridePendingTransition(R.anim.page_in, R.anim.page_under_out);
    }

    void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Claude Code", text));
        toast("已复制");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast(url);
        }
    }

    void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
