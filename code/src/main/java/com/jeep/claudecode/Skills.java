package com.jeep.claudecode;

import android.app.Dialog;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;

/** Settings → 扩展 → 技能: the skills Claude Code has, those of the user (files here) made, edited and deleted. */
final class Skills extends Part {
    Skills(MainActivity app) { super(app); }

    private java.io.File skillsDir() { return userland.file(Userland.HOME + "/.claude/skills"); }

    private List<String> skillNames() {
        List<String> out = new ArrayList<>();
        java.io.File[] dirs = skillsDir().listFiles(java.io.File::isDirectory);
        if (dirs != null) for (java.io.File d : dirs) if (new java.io.File(d, "SKILL.md").isFile()) out.add(d.getName());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** A skill: its name, its folder when it is the user's or this folder's (editable), else where it comes from. */
    private static final class Skill {
        final String name, from;
        final java.io.File dir;

        Skill(String name, java.io.File dir, String from) {
            this.name = name;
            this.dir = dir;
            this.from = from;
        }
    }

    /**
     * The skills Claude Code has: the user's and this folder's (their files, editable), then the
     * rest it reported when a conversation last started (its own, a plugin's), shown only.
     */
    List<Skill> skillList() {
        List<Skill> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String n : skillNames()) {
            out.add(new Skill(n, new java.io.File(skillsDir(), n), null));
            seen.add(n);
        }
        java.io.File here = userland.file(claude.mcpFolder() + "/.claude/skills");
        java.io.File[] dirs = here.equals(skillsDir()) ? null : here.listFiles(java.io.File::isDirectory);
        if (dirs != null) {
            java.util.Arrays.sort(dirs);
            for (java.io.File d : dirs) {
                if (!new java.io.File(d, "SKILL.md").isFile() || !seen.add(d.getName())) continue;
                out.add(new Skill(d.getName(), d, "本目录"));
            }
        }
        JSONArray reported = savedArray("skills");
        for (int i = 0; i < reported.length(); i++) {
            String n = reported.optString(i);
            if (n.isEmpty() || !seen.add(n)) continue;
            int colon = n.indexOf(':');
            // A plugin's skill is plugin:skill: its name, and the plugin it comes from.
            out.add(colon > 0 ? new Skill(n.substring(colon + 1), null, n.substring(0, colon)) : new Skill(n, null, "内置"));
        }
        return out;
    }

    void skillsSheet() {
        Dialog sheet = sheets.sheet("技能", null);
        LinearLayout card = sheets.card(sheet);
        List<Skill> skills = skillList();
        for (Skill k : skills) {
            sheets.option(card, R.drawable.ic_spark, k.name, k.from, false, k.dir == null ? null : () -> { sheet.dismiss(); skillActions(k.dir); });
        }
        if (skills.isEmpty()) {
            sheets.empty(card, "还没有");
        }
        LinearLayout add = sheets.card(sheet);
        Ui.margins(add, app, 0, 10, 0, 0);
        sheets.option(add, R.drawable.ic_plus, "新建", null, false, () -> {
            sheet.dismiss();
            sheets.textSheet("新建技能", "", "名称：小写字母、数字和 -", false, (t, done) -> {
                String n = t.trim();
                if (!n.matches("[a-z0-9][a-z0-9-]*")) { toast("名称只能用小写字母、数字和 -"); return; }
                java.io.File f = new java.io.File(skillsDir(), n + "/SKILL.md");
                if (f.exists()) { toast("已经有这个技能了"); return; }
                try {
                    Userland.write(f, "---\nname: " + n + "\ndescription: 一句话写清楚什么时候该用这个技能\n---\n\n在这里写给 Claude 的做法和步骤。\n");
                } catch (java.io.IOException e) {
                    toast("新建失败：" + e.getMessage());
                    return;
                }
                done.run();
                editSkill(f.getParentFile());
            }, null);
        });
        sheet.show();
    }

    private void skillActions(java.io.File dir) {
        Dialog sheet = sheets.sheet(dir.getName(), null);
        LinearLayout card = sheets.card(sheet);
        sheets.option(card, R.drawable.ic_pencil, "编辑", null, false, () -> { sheet.dismiss(); editSkill(dir); });
        sheets.option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            Userland.delete(dir);
            claude.relaunch();
            toast("已删除");
            app.settings.refreshSettings();
            skillsSheet();
        });
        sheet.show();
    }

    private void editSkill(java.io.File dir) {
        java.io.File f = new java.io.File(dir, "SKILL.md");
        sheets.textSheet("技能 · " + dir.getName(), claude.readFile(f), "SKILL.md", true, (t, done) -> {
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
        }, this::skillsSheet);
    }
}
