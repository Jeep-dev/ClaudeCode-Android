package com.jeep.claudecode;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The / commands: those the app handles itself, and the list of all that the box offers. */
final class SlashCommands extends Part {
    private static final String[][] LOCAL = {
            {"help", "", "所有命令"}, {"new", "", "新对话"}, {"clear", "", "新对话"},
            {"effort", "", "思考级别"},
            {"model", "", "模型"}, {"folder", "", "工作目录"},
            {"find", "<词>", "在本对话里搜索，↑↓ 换一处，不带词结束"},
            {"tree", "", "回到之前的某条消息，重新编辑"}, {"rewind", "", "同 /tree"},
            {"stop", "", "停止"}};

    SlashCommands(MainActivity app) { super(app); }

    /** name -> {args, description, "local" when the app handles it} */
    LinkedHashMap<String, String[]> commandList() {
        LinkedHashMap<String, String[]> all = new LinkedHashMap<>();
        for (String[] c : LOCAL) all.put(c[0], new String[]{c[1], c[2], "local"});
        for (String c : claude.commands) if (!all.containsKey(c)) all.put(c, new String[]{"", "", ""});
        return all;
    }

    private void note(CharSequence text) {
        Chat.Msg m = new Chat.Msg(Chat.Msg.NOTE);
        m.note = text;
        app.current.messages.add(m);
        app.screen.stick = true;
        app.renderNow();
    }

    /** Text from parts: strings as they are, {"b", text} bold, {"c", text} command. */
    private CharSequence styled(Object... parts) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Object p : parts) {
            if (p instanceof String[]) {
                String[] t = (String[]) p;
                int st = s.length();
                s.append(t[1]);
                if ("b".equals(t[0])) {
                    s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(col(R.color.text)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    s.setSpan(new TypefaceSpan("monospace"), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(col(R.color.accentPress)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                s.append(String.valueOf(p));
            }
        }
        return s;
    }

    boolean localCommand(String text) {
        String t = text.trim().substring(1);
        String name = t.split("\\s+", 2)[0];
        String arg = t.contains(" ") ? t.substring(t.indexOf(' ') + 1).trim() : "";
        switch (name) {
            case "help": {
                List<Object> parts = new ArrayList<>();
                parts.add(new String[]{"b", "命令"});
                for (Map.Entry<String, String[]> e : commandList().entrySet()) {
                    parts.add("\n");
                    parts.add(new String[]{"c", "/" + e.getKey() + (e.getValue()[0].isEmpty() ? "" : " " + e.getValue()[0])});
                    parts.add("  " + e.getValue()[1]);
                }
                note(styled(parts.toArray()));
                return true;
            }
            case "new": case "clear":
                app.switchTo(app.newChat());
                return true;
            case "stop":
                if (app.current.running) app.sender.stop(app.current); else toast("现在没有在运行");
                return true;
            case "find":
                app.finder.find(arg);
                return true;
            case "tree": case "rewind":
                app.messages.tree();
                return true;
            case "folder":
                if (app.current.sessionId != null) toast("已有对话的目录不能更改，新对话可以选");
                else app.pickers.folderSheet(null);
                return true;
            case "model":
                if (arg.isEmpty()) { app.pickers.modelSheet(); return true; }
                claude.model = arg;
                prefs.edit().putString("model", claude.model).apply();
                app.screen.updateChrome();
                return true;
            case "effort": {
                String a = arg.toLowerCase(Locale.ROOT);
                if (a.isEmpty() || (!claude.efforts.isEmpty() && !claude.efforts.contains(a))) { app.pickers.modelSheet(); return true; }
                claude.effort = a;
                prefs.edit().putString("effort", claude.effort).apply();
                app.screen.updateChrome();
                return true;
            }
            default:
                return false; // Claude Code's own command or skill
        }
    }
}
