package com.jeep.claudecode;

import android.app.Dialog;
import android.net.Uri;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The sheets where the folder, the model and thinking level, the permission mode and CLAUDE.md are picked or written. */
final class Pickers extends Part {
    Pickers(MainActivity app) { super(app); }

    private static String shortModel(String m) {
        return m == null || m.isEmpty() ? "默认模型" : m.replaceAll("(?i)\\[1m\\]$", "");
    }

    /** The model in use and its thinking level, as the box's pill shows them ("Opus 5.5 中等"). */
    String modelLabel() {
        String name = null, value = claude.model;
        if (value.isEmpty()) {
            // Claude Code's own choice: the model its "default" stands for, by that one's name.
            String resolved = "";
            for (int i = 0; i < claude.claudeModels.length(); i++) {
                JSONObject cm = claude.claudeModels.optJSONObject(i);
                if (cm != null && cm.optString("value").equals("default")) resolved = cm.optString("resolvedModel");
            }
            for (int i = 0; i < claude.claudeModels.length() && name == null; i++) {
                JSONObject cm = claude.claudeModels.optJSONObject(i);
                if (cm != null && !cm.optString("value").equals("default") && !resolved.isEmpty()
                        && resolved.equals(cm.optString("resolvedModel"))) name = listedName(cm);
            }
            if (name == null) name = claude.defaultModel.isEmpty() ? "默认模型" : shortModel(claude.defaultModel);
        } else {
            for (int i = 0; i < claude.claudeModels.length() && name == null; i++) {
                JSONObject cm = claude.claudeModels.optJSONObject(i);
                if (cm != null && cm.optString("value").equals(value)) name = listedName(cm);
            }
            if (name == null) name = shortModel(value);
        }
        String level = effortName(claude.effortInUse());
        return level.isEmpty() ? name : name + " " + level;
    }

    /** A model of Claude Code's list by its name with the version ("Opus 5.5"), as the model menu lists it. */
    private static String listedName(JSONObject cm) {
        String desc = cm.optString("description");
        return desc.contains(" · ") ? desc.substring(0, desc.indexOf(" · ")) : cm.optString("displayName", cm.optString("value"));
    }

    private static String effortName(String e) {
        switch (e == null ? "" : e.toLowerCase(Locale.ROOT)) {
            case "low": return "低";
            case "medium": return "中等";
            case "high": return "高";
            case "xhigh": return "超高";
            case "max": return "最高";
            default: return e == null ? "" : e;
        }
    }

    void folderSheet(String path) {
        String at = path != null ? path : !claude.cwd.isEmpty() ? claude.cwd : claude.defaultCwd;
        bridge.get("/dirs?path=" + Uri.encode(at), (status, d) -> {
            if (status != 200) { toast("读取目录失败"); return; }
            String here = d.optString("path");
            Dialog sheet = sheets.sheet("工作目录", claude.tilde(here));
            LinearLayout card = sheets.card(sheet);
            String parent = d.isNull("parent") ? "" : d.optString("parent");
            if (!parent.isEmpty()) sheets.option(card, R.drawable.ic_up, "上一级", null, false, () -> { sheet.dismiss(); folderSheet(parent); });
            JSONArray dirs = d.optJSONArray("dirs");
            for (int i = 0; dirs != null && i < dirs.length(); i++) {
                String name = dirs.optString(i);
                sheets.option(card, R.drawable.ic_folder, name, null, false, () -> { sheet.dismiss(); folderSheet(here + "/" + name); });
            }
            if (dirs == null || dirs.length() == 0) {
                sheets.empty(card, "没有子目录");
            }
            LinearLayout field = Ui.row(app);
            EditText name = new EditText(app);
            name.setHint("新建目录名");
            name.setSingleLine(true);
            name.setTextSize(15);
            name.setTextColor(col(R.color.text));
            name.setHintTextColor(col(R.color.text3));
            name.setPadding(dp(12), 0, dp(12), 0);
            name.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
            field.addView(name, new LinearLayout.LayoutParams(0, dp(40), 1));
            TextView make = sheets.button("新建", false, v -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty() || n.contains("/")) { toast("目录名不能为空，也不能带 /"); return; }
                bridge.post("/mkdir", obj("path", here + "/" + n), (s2, r2) -> {
                    if (s2 == 200) { sheet.dismiss(); folderSheet(here + "/" + n); } else toast("新建失败");
                });
            });
            field.addView(make);
            Ui.margins(make, app, 8, 0, 0, 0);
            sheets.content(sheet).addView(field, Ui.fill());
            Ui.margins(field, app, 0, 10, 0, 0);
            TextView cancel = sheets.button("取消", false, v -> sheet.dismiss());
            TextView use = sheets.button("使用这个目录", true, v -> {
                claude.cwd = here;
                prefs.edit().putString("cwd", claude.cwd).apply();
                if (app.current.sessionId == null) app.current.cwd = claude.cwd;
                sheet.dismiss();
                app.renderNow();
                app.drawer.renderSessions(); // the drawer may list only this folder's
                app.settings.refreshSettings();
            });
            use.setBackground(Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10)));
            use.setTextColor(col(R.color.bg));
            sheets.actions(sheet, cancel, use);
            sheet.show();
        });
    }

    void modelSheet() {
        Dialog sheet = sheets.sheet("模型", null);
        // Thinking: one segmented bar.
        LinearLayout bar = Ui.row(app);
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.setBackground(Ui.round(col(R.color.surface2), dp(12)));
        String level = claude.effortInUse();
        for (String e : claude.efforts) {
            boolean on = e.equals(level);
            TextView b = Ui.text(app, e, 13.5f, on ? R.color.text : R.color.text2);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            if (on) {
                b.setTypeface(Ui.MEDIUM);
                b.setBackground(Ui.round(col(R.color.bg), dp(9), dp(1), col(R.color.border)));
            }
            b.setOnClickListener(v -> {
                claude.effort = e;
                prefs.edit().putString("effort", claude.effort).apply();
                sheet.dismiss();
                app.screen.updateChrome();
            });
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(34), 1));
        }
        if (!claude.efforts.isEmpty()) {
            sheets.content(sheet).addView(bar, Ui.fill());
            Ui.margins(bar, app, 0, 0, 0, 12);
        }
        // Claude Code's own list first ("default": no --model, its own choice), then ones from the
        // settings and typed here; each once.
        LinearLayout own = null;
        List<String> listed = new ArrayList<>();
        for (int i = 0; i < claude.claudeModels.length(); i++) {
            JSONObject cm = claude.claudeModels.optJSONObject(i);
            if (cm == null) continue;
            String value = cm.optString("value");
            String resolved = cm.optString("resolvedModel");
            listed.add(value);
            listed.add(resolved);
            // "default" is one of the others under another name: not listed twice.
            if (value.equals("default")) continue;
            String pick = value;
            if (own == null) own = sheets.card(sheet);
            // Its full name with the version, as Claude Code describes it ("Opus 5.5 · …").
            String desc = cm.optString("description");
            String name = desc.contains(" · ") ? desc.substring(0, desc.indexOf(" · ")) : cm.optString("displayName", value);
            sheets.option(own, 0, name, null, false, () -> {
                claude.model = pick;
                prefs.edit().putString("model", claude.model).apply();
                sheet.dismiss();
                app.screen.updateChrome();
            });
            if (claude.model.equals(pick) || (claude.model.isEmpty() && claude.defaultResolved(resolved))) sheets.check(own);
        }
        if (own != null) Ui.margins(own, app, 0, 0, 0, 10);
        List<String> all = new ArrayList<>();
        if (!claude.defaultModel.isEmpty()) all.add(claude.defaultModel);
        for (String m : claude.models) if (!all.contains(m)) all.add(m);
        for (String m : usedModels()) if (!all.contains(m)) all.add(m);
        all.removeAll(listed);
        String inUse = claude.model.isEmpty() ? claude.defaultModel : claude.model;
        LinearLayout card = sheets.card(sheet);
        for (String m : all) {
            boolean big = m.toLowerCase(Locale.ROOT).endsWith("[1m]");
            sheets.option(card, 0, shortModel(m), big ? "1M" : null, false, () -> {
                claude.model = m;
                prefs.edit().putString("model", claude.model).apply();
                sheet.dismiss();
                app.screen.updateChrome();
            });
            if (m.equals(inUse)) sheets.check(card);
        }
        sheets.option(card, R.drawable.ic_plus, "其他模型", null, false, () -> {
            sheet.dismiss();
            sheets.textSheet("其他模型", "", "模型名", false, (t, done) -> {
                String name = t.trim();
                if (name.isEmpty()) return;
                List<String> used = usedModels();
                used.remove(name);
                used.add(0, name);
                prefs.edit().putString("usedModels", new JSONArray(used.subList(0, Math.min(8, used.size()))).toString()).apply();
                claude.model = name;
                prefs.edit().putString("model", claude.model).apply();
                app.screen.updateChrome();
                done.run();
            }, null);
        });
        sheet.show();
    }

    private List<String> usedModels() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("usedModels", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (org.json.JSONException ignored) { /* none */ }
        return out;
    }

    void permissionSheet(String mode) {
        Dialog sheet = sheets.sheet("权限模式", null);
        LinearLayout card = sheets.card(sheet);
        List<String> modes = new ArrayList<>(claude.permissionModes);
        if (!modes.contains(mode)) modes.add(0, mode);
        for (String m : modes) {
            sheets.option(card, 0, m, null, false, () -> claude.saveLaunch("permissionMode", m, () -> {
                sheet.dismiss();
                app.settings.refreshSettings();
            }));
            if (m.equals(mode)) sheets.check(card);
        }
        sheet.show();
    }

    void memorySheet(String scope, String folder, String title) {
        String q = "/memory?scope=" + scope + (folder == null ? "" : "&cwd=" + Uri.encode(folder));
        bridge.get(q, (status, data) -> {
            if (status != 200) { toast("读取 CLAUDE.md 失败"); return; }
            sheets.textSheet(title, data.optString("text"), "", true, (t, done) -> {
                JSONObject body = obj("scope", scope, "text", t);
                if (folder != null) body = obj("scope", scope, "cwd", folder, "text", t);
                bridge.post("/memory", body, (s2, r2) -> {
                    if (s2 == 200) { toast("已保存"); done.run(); } else toast("保存失败");
                });
            }, app.settings::refreshSettings);
        });
    }
}
