package com.jeep.claudecode;

import android.app.Dialog;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Settings → Claude Code and 模型: Claude Code's settings.json as its own schema describes it, its environment variables, the API. */
final class ClaudePage extends Part {
    /** The settings Claude Code knows (key, type, desc, choices), read from it by the bridge; null until read. */
    private JSONArray schema;

    ClaudePage(MainActivity app) { super(app); }

    void loadSchema(Runnable then) {
        if (schema != null || !app.link.linked) return;
        bridge.get("/settings-schema", (status, data) -> {
            if (status != 200) return;
            schema = data.optJSONArray("settings");
            if (schema == null) schema = new JSONArray();
            then.run();
        });
    }

    private JSONObject schemaOf(String key) {
        for (int i = 0; schema != null && i < schema.length(); i++) {
            JSONObject s = schema.optJSONObject(i);
            if (s != null && key.equals(s.optString("key"))) return s;
        }
        return null;
    }

    void fillClaude(LinearLayout box) {
        JSONObject json = claude.settingsJson();
        JSONObject env = json.optJSONObject("env");
        LinearLayout tools = app.settings.card(box);
        sheets.option(tools, R.drawable.ic_plus, "添加设置", null, false, this::addSettingSheet);
        sheets.option(tools, R.drawable.ic_list, "环境变量", env == null || env.length() == 0 ? null : String.valueOf(env.length()), false, this::envSheet);
        sheets.option(tools, R.drawable.ic_file, "settings.json", null, false, this::rawSettingsSheet);
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals("env")) keys.add(k);
        }
        if (keys.isEmpty()) return;
        java.util.Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        app.settings.section(box, "已设置");
        LinearLayout set = app.settings.card(box);
        for (String k : keys) sheets.option(set, 0, k, summary(json.opt(k)), false, () -> settingSheet(k));
    }

    private static String summary(Object v) {
        if (v instanceof Boolean) return (Boolean) v ? "开" : "关";
        if (v instanceof JSONObject) return ((JSONObject) v).length() == 0 ? "{ }" : "{ " + ((JSONObject) v).length() + " }";
        if (v instanceof JSONArray) return "[ " + ((JSONArray) v).length() + " ]";
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    /** One setting: its description, and a way to set it fitting its type; or to remove it. */
    private void settingSheet(String key) {
        JSONObject json = claude.settingsJson();
        Object value = json.opt(key);
        JSONObject spec = schemaOf(key);
        String type = spec != null ? spec.optString("type")
                : value instanceof Boolean ? "bool" : value instanceof Number ? "number"
                : value instanceof String ? "string" : "json";
        Dialog sheet = sheets.sheet(key, spec != null ? spec.optString("desc") : null);
        java.util.function.Consumer<Object> save = v -> {
            JSONObject now = claude.settingsJson();
            try {
                if (v == null) now.remove(key); else now.put(key, v);
            } catch (JSONException e) {
                return;
            }
            if (claude.saveSettings(now)) { toast("已保存"); sheet.dismiss(); app.settings.refreshSettings(); }
        };
        if (type.equals("bool") || type.equals("enum")) {
            LinearLayout card = sheets.card(sheet);
            List<Object> values = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (type.equals("bool")) {
                values.add(true); names.add("开");
                values.add(false); names.add("关");
            } else {
                JSONArray choices = spec.optJSONArray("choices");
                for (int i = 0; choices != null && i < choices.length(); i++) {
                    values.add(choices.opt(i));
                    names.add(String.valueOf(choices.opt(i)));
                }
                if (value != null && !values.contains(value)) { values.add(value); names.add(String.valueOf(value)); }
            }
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i);
                sheets.option(card, 0, names.get(i), null, false, () -> save.accept(v));
                if (v.equals(value)) sheets.check(card);
            }
            if (value != null) {
                LinearLayout reset = sheets.card(sheet);
                Ui.margins(reset, app, 0, 10, 0, 0);
                sheets.option(reset, R.drawable.ic_trash, "移除", null, true, () -> save.accept(null));
            }
            sheet.show();
            return;
        }
        EditText field = new EditText(app);
        boolean multi = type.equals("json") || (value instanceof String && ((String) value).contains("\n"));
        String text = "";
        if (value instanceof JSONObject || value instanceof JSONArray) {
            try { text = value instanceof JSONObject ? ((JSONObject) value).toString(2) : ((JSONArray) value).toString(2); } catch (JSONException ignored) { /* as is */ }
        } else if (value != null && value != JSONObject.NULL) {
            text = String.valueOf(value);
        }
        field.setText(text);
        field.setTextSize(14);
        field.setTypeface(Ui.MONO);
        field.setTextColor(col(R.color.text));
        field.setHintTextColor(col(R.color.text3));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        if (multi) {
            field.setMinLines(5);
            field.setMaxLines(14);
            field.setHint("JSON");
        } else {
            field.setSingleLine(true);
            if (type.equals("number")) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        sheets.content(sheet).addView(field, Ui.fill());
        LinearLayout actions = Ui.row(app);
        TextView remove = sheets.button(value == null ? "取消" : "移除", false, v -> {
            if (value == null) sheet.dismiss(); else save.accept(null);
        });
        TextView ok = sheets.button("保存", true, v -> {
            String t = field.getText().toString().trim();
            if (t.isEmpty()) { save.accept(null); return; }
            try {
                if (type.equals("number")) save.accept(t.matches("-?\\d+") ? (Object) Long.parseLong(t) : (Object) Double.parseDouble(t));
                else if (type.equals("json")) save.accept(new org.json.JSONTokener(t).nextValue());
                else save.accept(field.getText().toString());
            } catch (NumberFormatException | JSONException e) {
                toast(type.equals("number") ? "不是数字" : "不是有效的 JSON：" + e.getMessage());
            }
        });
        ok.setBackground(Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(remove, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, app, 10, 0, 0, 0);
        sheets.content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, app, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    /** Every setting Claude Code knows and settings.json lacks, found by name or description. */
    private void addSettingSheet() {
        if (schema == null) {
            if (!app.link.linked) { toast("Claude Code 还没启动好"); return; }
            loadSchema(this::addSettingSheet);
            return;
        }
        Dialog sheet = sheets.sheet("添加设置", null);
        EditText search = new EditText(app);
        search.setHint("搜索");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(col(R.color.text));
        search.setHintTextColor(col(R.color.text3));
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(Ui.round(col(R.color.surface2), dp(10)));
        sheets.content(sheet).addView(search, new LinearLayout.LayoutParams(-1, dp(42)));
        LinearLayout list = sheets.card(sheet);
        Ui.margins(list, app, 0, 10, 0, 0);
        JSONObject json = claude.settingsJson();
        List<View> rows = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < schema.length(); i++) {
            JSONObject s = schema.optJSONObject(i);
            if (s == null || json.has(s.optString("key"))) continue;
            String key = s.optString("key"), desc = s.optString("desc");
            LinearLayout row = Ui.column(app);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(Ui.press(app, null, dp(12)));
            row.addView(Ui.text(app, key, 15, R.color.text));
            TextView d = Ui.text(app, desc, 12.5f, R.color.text3);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, dp(2), 0, 0);
            row.addView(d);
            row.setOnClickListener(v -> { sheet.dismiss(); settingSheet(key); });
            list.addView(row, Ui.fill());
            rows.add(row);
            texts.add((key + " " + desc).toLowerCase(Locale.ROOT));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < rows.size(); i++) rows.get(i).setVisibility(q.isEmpty() || texts.get(i).contains(q) ? View.VISIBLE : View.GONE);
            }
        });
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    /** settings.json's env: Claude Code's environment variables. */
    private void envSheet() {
        Dialog sheet = sheets.sheet("环境变量", null);
        JSONObject env = claude.settingsEnv();
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = env.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        if (!keys.isEmpty()) {
            LinearLayout card = sheets.card(sheet);
            for (String k : keys) sheets.option(card, 0, k, env.optString(k), false, () -> { sheet.dismiss(); envEdit(k); });
            Ui.margins(card, app, 0, 0, 0, 10);
        }
        LinearLayout add = sheets.card(sheet);
        sheets.option(add, R.drawable.ic_plus, "添加变量", null, false, () -> { sheet.dismiss(); envEdit(null); });
        sheet.show();
    }

    private void envEdit(String key) {
        Dialog sheet = sheets.sheet(key == null ? "添加变量" : key, null);
        EditText name = sheets.field(sheet, "名称", key == null ? "" : key, "NAME");
        EditText value = sheets.field(sheet, "值", key == null ? "" : claude.settingsEnv().optString(key), "");
        LinearLayout actions = Ui.row(app);
        TextView remove = sheets.button(key == null ? "取消" : "删除", false, v -> {
            if (key == null) { sheet.dismiss(); envSheet(); return; }
            JSONObject json = claude.settingsJson();
            JSONObject env = json.optJSONObject("env");
            if (env != null) env.remove(key);
            if (claude.saveSettings(json)) { toast("已删除"); sheet.dismiss(); envSheet(); app.settings.refreshSettings(); }
        });
        TextView ok = sheets.button("保存", true, v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { toast("名称不能为空"); return; }
            JSONObject json = claude.settingsJson();
            JSONObject env = json.optJSONObject("env");
            if (env == null) env = new JSONObject();
            if (key != null) env.remove(key);
            try {
                env.put(n, value.getText().toString());
                json.put("env", env);
            } catch (JSONException e) {
                return;
            }
            if (claude.saveSettings(json)) { toast("已保存"); sheet.dismiss(); envSheet(); app.settings.refreshSettings(); }
        });
        ok.setBackground(Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(remove, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, app, 10, 0, 0, 0);
        sheets.content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, app, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    private void rawSettingsSheet() {
        String text;
        JSONObject now = claude.settingsJson();
        try { text = claude.settingsBroken ? claude.readFile(claude.settingsFile()) : now.toString(2); } catch (JSONException e) { text = "{}"; }
        sheets.textSheet("settings.json", text, "{ }", true, (t, done) -> {
            JSONObject json;
            try { json = new JSONObject(t.trim().isEmpty() ? "{}" : t); } catch (JSONException e) { toast("不是有效的 JSON：" + e.getMessage()); return; }
            claude.settingsBroken = false; // replaced whole
            if (claude.saveSettings(json)) { toast("已保存"); done.run(); }
        }, app.settings::refreshSettings);
    }

    void apiSheet() {
        Dialog sheet = sheets.sheet("API", null);
        JSONObject env = claude.settingsEnv();
        String key = env.optString("ANTHROPIC_AUTH_TOKEN", env.optString("ANTHROPIC_API_KEY"));
        EditText url = sheets.field(sheet, "地址", env.optString("ANTHROPIC_BASE_URL"), "https://api.anthropic.com");
        EditText token = sheets.field(sheet, "API Key", key, "sk-…");
        EditText name = sheets.field(sheet, "模型", env.optString("ANTHROPIC_MODEL"), "");
        LinearLayout actions = Ui.row(app);
        TextView raw = sheets.button("settings.json", false, v -> {
            sheet.dismiss();
            rawSettingsSheet();
        });
        TextView ok = sheets.button("保存", true, v -> {
            JSONObject json = claude.settingsJson();
            JSONObject e = json.optJSONObject("env");
            if (e == null) e = new JSONObject();
            String address = url.getText().toString().trim();
            put(e, "ANTHROPIC_BASE_URL", address);
            String k = token.getText().toString().trim();
            // Anthropic's own API (no address, or its own written out) takes a key as an API key
            // (x-api-key); other providers take theirs as a bearer token.
            String host = Uri.parse(address).getHost();
            boolean anthropic = address.isEmpty() || "anthropic.com".equals(host) || (host != null && host.endsWith(".anthropic.com"));
            e.remove("ANTHROPIC_API_KEY");
            e.remove("ANTHROPIC_AUTH_TOKEN");
            put(e, anthropic ? "ANTHROPIC_API_KEY" : "ANTHROPIC_AUTH_TOKEN", k);
            put(e, "ANTHROPIC_MODEL", name.getText().toString().trim());
            try { json.put("env", e); } catch (JSONException ignored) { /* string key */ }
            if (claude.saveSettings(json)) { toast("已保存"); sheet.dismiss(); app.settings.refreshSettings(); }
        });
        ok.setBackground(Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(raw, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, app, 10, 0, 0, 0);
        sheets.content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, app, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    private static void put(JSONObject o, String key, String value) {
        if (value.isEmpty()) { o.remove(key); return; }
        try { o.put(key, value); } catch (JSONException ignored) { /* string key */ }
    }
}
