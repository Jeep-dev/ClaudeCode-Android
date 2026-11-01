package com.jeep.claudecode;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

/** What a conversation says about itself: its text as Markdown, the tools it used, the files it changed. */
final class Transcript {
    private Transcript() {}

    /** The tools that write a file, and the input that names it. */
    private static final String[][] WRITERS = {
            {"Write", "file_path"}, {"Edit", "file_path"}, {"MultiEdit", "file_path"}, {"NotebookEdit", "notebook_path"}};
    /** What a tool's input says it is about, the first one it has. */
    private static final String[] ABOUT = {"file_path", "notebook_path", "command", "pattern", "url", "query", "skill", "description", "prompt"};

    static final class Stats {
        int users, replies;
        /** Tool name -> times used, in the order first used. */
        final Map<String, Integer> tools = new LinkedHashMap<>();
        /** File path -> times written, in the order first written. */
        final Map<String, Integer> files = new LinkedHashMap<>();

        int toolCalls() {
            int n = 0;
            for (int c : tools.values()) n += c;
            return n;
        }
    }

    static Stats stats(Chat chat) {
        Stats s = new Stats();
        for (Chat.Msg m : chat.messages) {
            if (m.kind == Chat.Msg.USER) s.users++;
            if (m.kind != Chat.Msg.ASSISTANT) continue;
            s.replies++;
            for (Chat.Block b : m.blocks) {
                if (b.kind != Chat.Block.TOOL || b.name == null) continue;
                s.tools.merge(b.name, 1, Integer::sum);
                String path = written(b);
                if (!path.isEmpty()) s.files.merge(path, 1, Integer::sum);
            }
        }
        return s;
    }

    /** The file a tool wrote, or "". */
    private static String written(Chat.Block b) {
        for (String[] w : WRITERS) {
            if (w[0].equals(b.name)) return b.input.optString(w[1]).trim();
        }
        return "";
    }

    /** A tool's row in one line: what it was about. */
    static String about(Chat.Block b) {
        JSONObject in = b.input;
        for (String key : ABOUT) {
            String v = in.optString(key).trim();
            if (v.isEmpty()) continue;
            v = v.split("\n", 2)[0].trim();
            return v.length() > 160 ? v.substring(0, 160) + "…" : v;
        }
        return "";
    }

    /** The conversation as a Markdown document: what was said and which tools ran, without the thinking. */
    static String markdown(Chat chat, String when) {
        StringBuilder out = new StringBuilder();
        String title = chat.title.isEmpty() ? "对话" : chat.title;
        out.append("# ").append(title).append("\n\n");
        StringBuilder meta = new StringBuilder();
        if (chat.cwd != null && !chat.cwd.isEmpty()) meta.append("目录 `").append(chat.cwd).append('`');
        if (chat.model != null && !chat.model.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append("模型 ").append(chat.model);
        if (when != null && !when.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append("导出于 ").append(when);
        if (meta.length() > 0) out.append("> ").append(meta).append("\n\n");
        for (Chat.Msg m : chat.messages) {
            if (m.kind == Chat.Msg.DIVIDER) {
                if (!m.text.isEmpty()) out.append("---\n\n*").append(m.text.trim()).append("*\n\n");
            } else if (m.kind == Chat.Msg.USER) {
                out.append("## 你\n\n");
                if (!m.text.trim().isEmpty()) out.append(m.text.trim()).append("\n\n");
                if (!m.images.isEmpty()) out.append("*（图片 ").append(m.images.size()).append(" 张）*\n\n");
                for (String f : m.files) out.append("*（文件：").append(f).append("）*\n\n");
            } else if (m.kind == Chat.Msg.ASSISTANT) {
                StringBuilder body = new StringBuilder();
                for (Chat.Block b : m.blocks) {
                    if (b.kind == Chat.Block.TEXT && !b.text.trim().isEmpty()) body.append(b.text.trim()).append("\n\n");
                    else if (b.kind == Chat.Block.TOOL) {
                        String about = about(b);
                        body.append("> **").append(b.name).append("**");
                        if (!about.isEmpty()) body.append(" `").append(about.replace('`', '\'')).append('`');
                        body.append("\n\n");
                    } else if (b.kind == Chat.Block.ERROR && !b.text.trim().isEmpty()) body.append("> ⚠ ").append(b.text.trim()).append("\n\n");
                    else if (b.kind == Chat.Block.STOPPED) body.append("> （已停止）\n\n");
                }
                if (body.length() > 0) out.append("## Claude\n\n").append(body);
            }
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim() + "\n";
    }

    /** A title as a file name: no path or reserved characters, not too long. */
    static String fileName(String title) {
        String t = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "对话";
        if (t.length() > 40) t = t.substring(0, 40).trim();
        return t;
    }
}
