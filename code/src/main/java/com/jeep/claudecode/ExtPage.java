package com.jeep.claudecode;

import android.app.Dialog;
import android.text.TextUtils;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Settings → 扩展: plugins, subagents and commands (Markdown files), the tools, and hooks. */
final class ExtPage extends Part {
    /** The 扩展 page's counts (skills, MCP servers, agents, commands, hooks), read off the main thread; null until read. */
    private int[] extCounts;
    /** agents: subagents; commands: slash commands. Each a .md file under .claude/<kind>. */
    private static final String AGENTS = "agents", COMMANDS = "commands";

    ExtPage(MainActivity app) { super(app); }

    void fillExt(LinearLayout box, JSONObject launch) {
        int[] n = extCounts;
        extCounts = null; // read again next time
        if (n == null) {
            work.execute(() -> {
                JSONObject hooks = claude.readJson(claude.settingsFile()).optJSONObject("hooks");
                int[] read = {app.skills.skillList().size(), app.mcp.mcpList().size(), fileItems(AGENTS).size(), fileItems(COMMANDS).size(),
                        hooks == null ? 0 : hooks.length()};
                ui.post(() -> {
                    if (!"ext".equals(app.settings.settingsAt) || app.settings.settingsPage == null) return;
                    extCounts = read;
                    app.settings.fillSettings(app.link.linked ? app.settings.launchCache : null);
                });
            });
        }
        LinearLayout ext = app.settings.card(box);
        sheets.option(ext, R.drawable.ic_spark, "技能", count(n, 0), false, app.skills::skillsSheet);
        sheets.option(ext, R.drawable.ic_file_plus, "插件", null, false, this::pluginsSheet);
        sheets.option(ext, R.drawable.ic_globe, "MCP", count(n, 1), false, app.mcp::mcpSheet);
        sheets.option(ext, R.drawable.ic_bot, "子代理", count(n, 2), false, () -> itemsSheet(AGENTS));
        sheets.option(ext, R.drawable.ic_edit_lines, "命令", count(n, 3), false, () -> itemsSheet(COMMANDS));
        if (launch != null) {
            int off = disallowed(launch).size();
            sheets.option(ext, R.drawable.ic_tool, "工具", off == 0 ? null : "关闭 " + off, false, () -> toolsSheet(launch));
        }
        sheets.option(ext, R.drawable.ic_refresh, "Hooks", count(n, 4), false, this::hooksSheet);
    }

    private static String count(int[] n, int i) { return n == null || n[i] == 0 ? null : String.valueOf(n[i]); }

    // ---- 插件: Claude Code's own `claude plugin` ----
    private void pluginsSheet() {
        if (!app.link.linked) { toast("Claude Code 还没启动好"); return; }
        Dialog sheet = sheets.sheet("插件", null);
        LinearLayout card = sheets.card(sheet);
        sheets.empty(card, "…");
        LinearLayout more = sheets.card(sheet);
        Ui.margins(more, app, 0, 10, 0, 0);
        sheets.option(more, R.drawable.ic_plus, "安装", null, false, () -> {
            sheet.dismiss();
            sheets.textSheet("安装插件", "", "名称 或 名称@市场", false, (t, done) -> pluginDo("install", t.trim(), done), this::pluginsSheet);
        });
        sheets.option(more, R.drawable.ic_globe, "市场", null, false, () -> { sheet.dismiss(); marketsSheet(); });
        sheet.show();
        bridge.get("/plugins", (status, data) -> {
            card.removeAllViews();
            JSONArray list = status == 200 ? data.optJSONArray("plugins") : null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id");
                String name = id.contains("@") ? id.substring(0, id.indexOf('@')) : id;
                boolean on = p.optBoolean("enabled", true);
                sheets.option(card, R.drawable.ic_file_plus, name, on ? p.optString("version") : "已停用", false, () -> { sheet.dismiss(); pluginActions(id, on); });
            }
            if (list == null || list.length() == 0) {
                sheets.empty(card, status == 200 ? "还没有" : "读取失败");
            }
        });
    }

    private void pluginActions(String id, boolean on) {
        Dialog sheet = sheets.sheet(id, null);
        LinearLayout card = sheets.card(sheet);
        sheets.option(card, on ? R.drawable.ic_stop_circle : R.drawable.ic_check, on ? "停用" : "启用", null, false,
                () -> { sheet.dismiss(); pluginDo(on ? "disable" : "enable", id, null); });
        sheets.option(card, R.drawable.ic_refresh, "更新", null, false, () -> { sheet.dismiss(); pluginDo("update", id, null); });
        sheets.option(card, R.drawable.ic_trash, "卸载", null, true, () -> { sheet.dismiss(); pluginDo("uninstall", id, null); });
        sheet.show();
    }

    /** One `claude plugin` change; its own message on failure. */
    private void pluginDo(String action, String name, Runnable done) {
        if (name.isEmpty()) return;
        toast("正在处理…");
        bridge.post("/plugin", obj("action", action, "name", name), (status, data) -> {
            if (status != 200) { sheets.textSheetMessage("没有成功", data.optString("error")); return; }
            toast("完成");
            if (done != null) done.run();
            app.settings.refreshSettings();
            if (action.startsWith("market")) marketsSheet(); else pluginsSheet();
        });
    }

    private void marketsSheet() {
        Dialog sheet = sheets.sheet("市场", null);
        LinearLayout card = sheets.card(sheet);
        sheets.empty(card, "…");
        LinearLayout more = sheets.card(sheet);
        Ui.margins(more, app, 0, 10, 0, 0);
        sheets.option(more, R.drawable.ic_plus, "添加", null, false, () -> {
            sheet.dismiss();
            sheets.textSheet("添加市场", "", "GitHub 仓库（owner/repo）或网址", false, (t, done) -> pluginDo("market-add", t.trim(), done), this::marketsSheet);
        });
        sheet.show();
        bridge.get("/plugins", (status, data) -> {
            card.removeAllViews();
            JSONArray list = status == 200 ? data.optJSONArray("marketplaces") : null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                String name = m == null ? "" : m.optString("name");
                if (name.isEmpty()) continue;
                sheets.option(card, R.drawable.ic_globe, name, null, false, () -> {
                    sheet.dismiss();
                    Dialog ask = sheets.sheet(name, null);
                    LinearLayout c2 = sheets.card(ask);
                    sheets.option(c2, R.drawable.ic_trash, "删除", null, true, () -> { ask.dismiss(); pluginDo("market-remove", name, null); });
                    ask.show();
                });
            }
            if (card.getChildCount() == 0) {
                sheets.empty(card, status == 200 ? "还没有" : "读取失败");
            }
        });
    }

    // ---- 子代理 and 命令: Markdown files Claude Code reads, and those it reported ----

    /** A file extension: its name, its file when the user's or this folder's (editable), else where it comes from. */
    private static final class Item {
        final String name, from;
        final java.io.File file;

        Item(String name, java.io.File file, String from) {
            this.name = name;
            this.file = file;
            this.from = from;
        }
    }

    private java.io.File itemsDir(String kind) { return userland.file(Userland.HOME + "/.claude/" + kind); }

    /**
     * The user's and this folder's files (commands in subfolders are dir:name), then those Claude
     * Code reported when a conversation last started: for subagents its own and plugins'; for
     * commands only plugins' (its own commands are not extensions; skills have their own page).
     */
    private List<Item> fileItems(String kind) {
        List<Item> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.io.File here = userland.file(claude.mcpFolder() + "/.claude/" + kind);
        addItems(out, seen, itemsDir(kind), itemsDir(kind), null);
        if (!here.equals(itemsDir(kind))) addItems(out, seen, here, here, "本目录");
        JSONArray reported = savedArray(kind.equals(AGENTS) ? "agents" : "slashCommands");
        java.util.Set<String> skills = new java.util.HashSet<>();
        JSONArray sk = savedArray("skills");
        for (int i = 0; i < sk.length(); i++) skills.add(sk.optString(i));
        for (int i = 0; i < reported.length(); i++) {
            String n = reported.optString(i);
            int colon = n.indexOf(':');
            if (n.isEmpty() || seen.contains(n) || skills.contains(n)) continue;
            if (kind.equals(COMMANDS) && colon <= 0) continue;
            seen.add(n);
            out.add(colon > 0 ? new Item(n.substring(colon + 1), null, n.substring(0, colon)) : new Item(n, null, "内置"));
        }
        return out;
    }

    private static void addItems(List<Item> out, java.util.Set<String> seen, java.io.File root, java.io.File dir, String from) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (java.io.File f : files) {
            if (f.isDirectory()) { addItems(out, seen, root, f, from); continue; }
            if (!f.getName().endsWith(".md")) continue;
            String rel = root.toURI().relativize(f.toURI()).getPath();
            String n = rel.substring(0, rel.length() - 3).replace('/', ':');
            if (seen.add(n)) out.add(new Item(n, f, from));
        }
    }

    private void itemsSheet(String kind) {
        boolean agents = kind.equals(AGENTS);
        Dialog sheet = sheets.sheet(agents ? "子代理" : "命令", null);
        LinearLayout card = sheets.card(sheet);
        List<Item> items = fileItems(kind);
        for (Item it : items) {
            sheets.option(card, agents ? R.drawable.ic_bot : R.drawable.ic_edit_lines, agents ? it.name : "/" + it.name, it.from, false,
                    it.file == null ? null : () -> { sheet.dismiss(); itemActions(kind, it.file); });
        }
        if (items.isEmpty()) {
            sheets.empty(card, "还没有");
        }
        LinearLayout add = sheets.card(sheet);
        Ui.margins(add, app, 0, 10, 0, 0);
        sheets.option(add, R.drawable.ic_plus, "新建", null, false, () -> {
            sheet.dismiss();
            sheets.textSheet(agents ? "新建子代理" : "新建命令", "", "名称：小写字母、数字和 -", false, (t, done) -> {
                String n = t.trim();
                if (!n.matches("[a-z0-9][a-z0-9-]*")) { toast("名称只能用小写字母、数字和 -"); return; }
                java.io.File f = new java.io.File(itemsDir(kind), n + ".md");
                if (f.exists()) { toast("已经有了"); return; }
                String body = agents
                        ? "---\nname: " + n + "\ndescription: 一句话写清楚什么时候该交给这个子代理\n---\n\n在这里写给它的指示。\n"
                        : "在这里写 /" + n + " 要 Claude 做的事。$ARGUMENTS 是命令后面跟的内容。\n";
                try {
                    Userland.write(f, body);
                } catch (java.io.IOException e) {
                    toast("新建失败：" + e.getMessage());
                    return;
                }
                done.run();
                editItem(kind, f);
            }, null);
        });
        sheet.show();
    }

    private void itemActions(String kind, java.io.File f) {
        Dialog sheet = sheets.sheet(f.getName().replaceAll("\\.md$", ""), null);
        LinearLayout card = sheets.card(sheet);
        sheets.option(card, R.drawable.ic_pencil, "编辑", null, false, () -> { sheet.dismiss(); editItem(kind, f); });
        sheets.option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            f.delete();
            claude.relaunch();
            toast("已删除");
            app.settings.refreshSettings();
            itemsSheet(kind);
        });
        sheet.show();
    }

    private void editItem(String kind, java.io.File f) {
        sheets.textSheet(f.getName(), claude.readFile(f), "", true, (t, done) -> {
            try {
                Userland.write(f, t);
            } catch (java.io.IOException e) {
                toast("保存失败：" + e.getMessage());
                return;
            }
            claude.relaunch();
            toast("已保存");
            done.run();
            app.settings.refreshSettings();
        }, () -> itemsSheet(kind));
    }

    // ---- Hooks: settings.json's hooks ----

    /** settings.json's hooks as JSON; an example rule to start from when there are none. */
    private void hooksEdit() {
        JSONObject hooks = claude.settingsJson().optJSONObject("hooks");
        String text;
        try {
            text = hooks != null && hooks.length() > 0 ? hooks.toString(2).replace("\\/", "/")
                    : "{\n  \"PostToolUse\": [\n    {\n      \"matcher\": \"Edit|Write\",\n      \"hooks\": [\n        { \"type\": \"command\", \"command\": \"要运行的命令\" }\n      ]\n    }\n  ]\n}";
        } catch (JSONException e) {
            text = "{}";
        }
        sheets.textSheet("Hooks", text, "{ }", true, (t, done) -> {
            JSONObject now = claude.settingsJson();
            try {
                JSONObject h = t.trim().isEmpty() ? new JSONObject() : new JSONObject(t);
                if (h.length() == 0) now.remove("hooks"); else now.put("hooks", h);
            } catch (JSONException e) {
                toast("不是有效的 JSON：" + e.getMessage());
                return;
            }
            if (claude.saveSettings(now)) { toast("已保存"); done.run(); app.settings.refreshSettings(); }
        }, this::hooksSheet);
    }

    private void hooksSheet() {
        Dialog sheet = sheets.sheet("Hooks", null);
        JSONObject json = claude.settingsJson();
        JSONObject hooks = json.optJSONObject("hooks");
        LinearLayout card = sheets.card(sheet);
        // Each event's rules: what they match, and the command they run.
        for (java.util.Iterator<String> it = hooks == null ? null : hooks.keys(); it != null && it.hasNext(); ) {
            String event = it.next();
            JSONArray rules = hooks.optJSONArray(event);
            for (int i = 0; rules != null && i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                JSONArray hs = rule == null ? null : rule.optJSONArray("hooks");
                for (int k = 0; hs != null && k < hs.length(); k++) {
                    JSONObject h = hs.optJSONObject(k);
                    String what = h == null ? "" : h.optString("command", h.optString("url", h.optString("prompt", h.optString("type"))));
                    String matcher = rule.optString("matcher");
                    sheets.option(card, R.drawable.ic_refresh, matcher.isEmpty() ? event : event + " · " + matcher, what, false,
                            () -> { sheet.dismiss(); hooksEdit(); });
                }
            }
        }
        if (card.getChildCount() == 0) {
            sheets.empty(card, "还没有");
        }
        LinearLayout more = sheets.card(sheet);
        Ui.margins(more, app, 0, 10, 0, 0);
        sheets.option(more, R.drawable.ic_pencil, "编辑", null, false, () -> { sheet.dismiss(); hooksEdit(); });
        boolean off = json.optBoolean("disableAllHooks");
        sheets.option(more, off ? R.drawable.ic_check : R.drawable.ic_stop_circle, off ? "全部启用" : "全部停用", null, false, () -> {
            JSONObject now = claude.settingsJson();
            try {
                if (off) now.remove("disableAllHooks"); else now.put("disableAllHooks", true);
            } catch (JSONException e) {
                return;
            }
            if (claude.saveSettings(now)) { toast("已保存"); sheet.dismiss(); app.settings.refreshSettings(); hooksSheet(); }
        });
        sheet.show();
    }

    private static List<String> disallowed(JSONObject launch) {
        List<String> out = new ArrayList<>();
        for (String t : launch.optString("disallowedTools").replace(',', ' ').trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private void toolsSheet(JSONObject launch) {
        Dialog sheet = sheets.sheet("工具", null);
        LinearLayout card = sheets.card(sheet);
        JSONArray tools = savedArray("tools");
        List<String> off = disallowed(launch);
        List<String> all = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) all.add(tools.optString(i));
        for (String t : off) if (!all.contains(t)) all.add(t);
        for (String t : all) {
            boolean on = !off.contains(t);
            String sub = t.startsWith("mcp__") ? t.split("__")[1] : null;
            String label = t.startsWith("mcp__") && t.split("__").length > 2 ? t.substring(t.indexOf("__", 5) + 2) : t;
            sheets.option(card, 0, label, sub, false, () -> {
                List<String> next = new ArrayList<>(off);
                if (on) next.add(t); else next.remove(t);
                claude.saveLaunch("disallowedTools", TextUtils.join(" ", next), () -> {
                    sheet.dismiss();
                    app.settings.refreshSettings();
                    bridge.get("/launch", (status, l) -> { if (status == 200) { app.settings.launchCache = l; toolsSheet(l); } });
                });
            });
            if (on) sheets.check(card);
        }
        if (all.isEmpty()) {
            sheets.empty(card, "发一条消息后显示");
        }
        sheet.show();
    }
}
