package com.jeep.claudecode

import java.io.File
import java.io.FileFilter
import java.io.IOException

/** Settings → 扩展 → 技能: the skills Claude Code has, those of the user (files here) made, edited and deleted. */
class Skills(app: MainActivity) : Part(app) {

    private fun skillsDir(): File = userland.file(Userland.HOME + "/.claude/skills")

    private fun skillNames(): List<String> {
        val out = ArrayList<String>()
        skillsDir().listFiles(FileFilter { it.isDirectory })?.forEach { d -> if (File(d, "SKILL.md").isFile) out.add(d.name) }
        out.sortWith(String.CASE_INSENSITIVE_ORDER)
        return out
    }

    /** A skill: its name, its folder when it is the user's or this folder's (editable), else where it comes from. */
    class Skill(val name: String, val dir: File?, val from: String?)

    /**
     * The skills Claude Code has: the user's and this folder's (their files, editable), then the
     * rest it reported when a conversation last started (its own, a plugin's), shown only.
     */
    fun skillList(): List<Skill> {
        val out = ArrayList<Skill>()
        val seen = HashSet<String>()
        for (n in skillNames()) {
            out.add(Skill(n, File(skillsDir(), n), null))
            seen.add(n)
        }
        val here = userland.file(claude.mcpFolder() + "/.claude/skills")
        val dirs = if (here == skillsDir()) null else here.listFiles(FileFilter { it.isDirectory })
        if (dirs != null) {
            dirs.sort()
            for (d in dirs) {
                if (!File(d, "SKILL.md").isFile || !seen.add(d.name)) continue
                out.add(Skill(d.name, d, "本目录"))
            }
        }
        val reported = savedArray("skills")
        for (i in 0 until reported.length()) {
            val n = reported.optString(i)
            if (n.isEmpty() || !seen.add(n)) continue
            val colon = n.indexOf(':')
            // A plugin's skill is plugin:skill: its name, and the plugin it comes from.
            out.add(if (colon > 0) Skill(n.substring(colon + 1), null, n.substring(0, colon)) else Skill(n, null, "内置"))
        }
        return out
    }

    fun skillsSheet() {
        val sheet = sheets.sheet("技能", null)
        val card = sheets.card(sheet)
        val skills = skillList()
        for (k in skills) {
            val dir = k.dir
            sheets.option(card, R.drawable.ic_spark, k.name, k.from, false,
                if (dir == null) null else Runnable { sheet.dismiss(); skillActions(dir) })
        }
        if (skills.isEmpty()) sheets.empty(card, "还没有")
        val add = sheets.card(sheet)
        Ui.margins(add, app, 0, 10, 0, 0)
        sheets.option(add, R.drawable.ic_plus, "新建", null, false) {
            sheet.dismiss()
            sheets.textSheet("新建技能", "", "名称：小写字母、数字和 -", false, { t, done ->
                val n = t.trim()
                if (!n.matches(Regex("[a-z0-9][a-z0-9-]*"))) {
                    toast("名称只能用小写字母、数字和 -")
                    return@textSheet
                }
                val f = File(skillsDir(), "$n/SKILL.md")
                if (f.exists()) {
                    toast("已经有这个技能了")
                    return@textSheet
                }
                try {
                    Userland.write(f, "---\nname: $n\ndescription: 一句话写清楚什么时候该用这个技能\n---\n\n在这里写给 Claude 的做法和步骤。\n")
                } catch (e: IOException) {
                    toast("新建失败：" + e.message)
                    return@textSheet
                }
                done.run()
                editSkill(f.parentFile!!)
            }, null)
        }
        sheet.show()
    }

    private fun skillActions(dir: File) {
        val sheet = sheets.sheet(dir.name, null)
        val card = sheets.card(sheet)
        sheets.option(card, R.drawable.ic_pencil, "编辑", null, false) { sheet.dismiss(); editSkill(dir) }
        sheets.option(card, R.drawable.ic_trash, "删除", null, true) {
            sheet.dismiss()
            Userland.delete(dir)
            claude.relaunch()
            toast("已删除")
            app.settings.refreshSettings()
            skillsSheet()
        }
        sheet.show()
    }

    private fun editSkill(dir: File) {
        val f = File(dir, "SKILL.md")
        sheets.textSheet("技能 · " + dir.name, claude.readFile(f), "SKILL.md", true, { t, done ->
            try {
                Userland.write(f, t)
            } catch (e: IOException) {
                toast("保存失败：" + e.message)
                return@textSheet
            }
            claude.relaunch()
            toast("已保存")
            done.run()
            app.settings.refreshSettings()
        }, ::skillsSheet)
    }
}
