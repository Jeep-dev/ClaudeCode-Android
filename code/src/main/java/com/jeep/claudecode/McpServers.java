package com.jeep.claudecode;

import android.app.Dialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Settings → 扩展 → MCP: the servers Claude Code has for the folder, and those added here before. */
final class McpServers extends Part {
    McpServers(MainActivity app) { super(app); }

    private java.io.File mcpFile() { return userland.file(Userland.HOME + "/.ccapp/mcp.json"); }

    /** The MCP servers added here, by name. */
    private JSONObject mcpServers() {
        try {
            JSONObject s = new JSONObject(claude.readFile(mcpFile())).optJSONObject("mcpServers");
            return s != null ? s : new JSONObject();
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private boolean saveMcp(JSONObject servers) {
        try {
            Userland.replace(mcpFile(), new JSONObject().put("mcpServers", servers).toString(2) + "\n");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
            return false;
        }
        claude.relaunch();
        app.settings.refreshSettings();
        return true;
    }

    /** An MCP server: its name, where it is kept, and its settings. */
    private static final class Mcp {
        /** user, local (this folder), project (the folder's .mcp.json): Claude Code's; app: added here before. */
        final String name, scope;
        final JSONObject config;

        Mcp(String name, String scope, JSONObject config) {
            this.name = name;
            this.scope = scope;
            this.config = config;
        }
    }

    /** Every MCP server Claude Code has for this folder (as `claude mcp` keeps them), and those added here before. */
    List<Mcp> mcpList() {
        List<Mcp> out = new ArrayList<>();
        String folder = claude.mcpFolder();
        JSONObject cc = claude.readJson(userland.file(Userland.HOME + "/.claude.json"));
        JSONObject projects = cc.optJSONObject("projects");
        JSONObject here = projects == null ? null : projects.optJSONObject(folder);
        addMcp(out, cc.optJSONObject("mcpServers"), "user");
        addMcp(out, here == null ? null : here.optJSONObject("mcpServers"), "local");
        addMcp(out, claude.readJson(userland.file(folder + "/.mcp.json")).optJSONObject("mcpServers"), "project");
        addMcp(out, mcpServers(), "app");
        return out;
    }

    private static void addMcp(List<Mcp> out, JSONObject servers, String scope) {
        if (servers == null) return;
        for (java.util.Iterator<String> it = servers.keys(); it.hasNext(); ) {
            String n = it.next();
            JSONObject cfg = servers.optJSONObject(n);
            if (cfg != null) out.add(new Mcp(n, scope, cfg));
        }
    }

    /** Has Claude Code itself add, replace or remove one of its servers; then shows the list again. */
    private void mcpChange(String action, Mcp m, JSONObject config, Runnable done) {
        if (!app.link.linked) { toast("Claude Code 还没启动好"); return; }
        toast("正在保存…");
        JSONObject body = obj("action", action, "name", m.name, "scope", m.scope, "cwd", claude.mcpFolder());
        try { if (config != null) body.put("config", config); } catch (JSONException ignored) { /* string key */ }
        bridge.post("/mcp", body, (status, data) -> {
            if (status != 200) { toast("失败：" + data.optString("error")); return; }
            toast("remove".equals(action) ? "已删除" : "已保存");
            if (done != null) done.run();
            app.settings.refreshSettings();
            mcpSheet();
        });
    }

    void mcpSheet() {
        Dialog sheet = sheets.sheet("MCP", null);
        LinearLayout card = sheets.card(sheet);
        // Their state, from when a conversation last started.
        Map<String, String> state = new HashMap<>();
        JSONArray st = savedArray("mcpServers");
        for (int i = 0; i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            if (o != null) state.put(o.optString("name"), o.optString("status"));
        }
        List<Mcp> servers = mcpList();
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (Mcp m : servers) {
            String s = state.get(m.name);
            shown.add(m.name);
            sheets.option(card, R.drawable.ic_globe, m.name, s == null ? null : mcpState(s), false, () -> { sheet.dismiss(); mcpActions(m); });
        }
        // Servers Claude Code reported but keeps elsewhere (plugins, claude.ai): shown, not changed here.
        for (Map.Entry<String, String> e : state.entrySet()) {
            if (shown.contains(e.getKey())) continue;
            sheets.option(card, R.drawable.ic_globe, e.getKey(), mcpState(e.getValue()), false, null);
        }
        if (servers.isEmpty() && state.isEmpty()) {
            TextView none = Ui.text(app, "还没有", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(18));
            card.addView(none, Ui.fill());
        }
        LinearLayout add = sheets.card(sheet);
        Ui.margins(add, app, 0, 10, 0, 0);
        sheets.option(add, R.drawable.ic_plus, "添加", null, false, () -> {
            sheet.dismiss();
            sheets.textSheet("添加 MCP", "", "{\n  \"名称\": {\n    \"type\": \"http\",\n    \"url\": \"https://…\"\n  }\n}", true, (t, done) -> {
                JSONObject in;
                try {
                    in = new JSONObject(t.trim());
                } catch (JSONException e) {
                    toast("不是有效的 JSON：" + e.getMessage());
                    return;
                }
                // Docs give {"mcpServers": {...}}, or just the servers.
                JSONObject add2 = in.optJSONObject("mcpServers") != null ? in.optJSONObject("mcpServers") : in;
                List<Mcp> adding = new ArrayList<>();
                for (java.util.Iterator<String> keys = add2.keys(); keys.hasNext(); ) {
                    String k = keys.next();
                    if (add2.optJSONObject(k) == null) { toast(k + " 的配置不是一个对象"); return; }
                    adding.add(new Mcp(k, "user", add2.optJSONObject(k)));
                }
                if (adding.isEmpty()) { toast("没有找到服务器配置"); return; }
                // Into Claude Code's own list (user scope), one after another.
                Runnable[] next = new Runnable[1];
                int[] at = {0};
                next[0] = () -> {
                    if (at[0] >= adding.size()) { done.run(); return; }
                    Mcp m = adding.get(at[0]++);
                    mcpChange("set", m, m.config, next[0]);
                };
                next[0].run();
            }, this::mcpSheet);
        });
        sheet.show();
    }

    private static String mcpState(String s) {
        switch (s) {
            case "connected": return "已连接";
            case "failed": return "连接失败";
            case "pending": return "连接中";
            case "needs-auth": return "需要授权";
            default: return s;
        }
    }

    private void mcpActions(Mcp m) {
        Dialog sheet = sheets.sheet(m.name, null);
        LinearLayout card = sheets.card(sheet);
        sheets.option(card, R.drawable.ic_pencil, "编辑", null, false, () -> {
            sheet.dismiss();
            String text;
            try { text = m.config.toString(2).replace("\\/", "/"); } catch (JSONException e) { text = "{}"; }
            sheets.textSheet("MCP · " + m.name, text, "{ }", true, (t, done) -> {
                JSONObject cfg;
                try { cfg = new JSONObject(t.trim()); } catch (JSONException e) { toast("不是有效的 JSON：" + e.getMessage()); return; }
                if (!m.scope.equals("app")) { mcpChange("set", m, cfg, done); return; }
                JSONObject all = mcpServers();
                try { all.put(m.name, cfg); } catch (JSONException ignored) { /* string key */ }
                if (saveMcp(all)) { toast("已保存"); done.run(); }
            }, this::mcpSheet);
        });
        sheets.option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            if (!m.scope.equals("app")) { mcpChange("remove", m, null, null); return; }
            JSONObject all = mcpServers();
            all.remove(m.name);
            if (saveMcp(all)) toast("已删除");
            mcpSheet();
        });
        sheet.show();
    }
}
