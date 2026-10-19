package com.jeep.claudecode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;

/** One conversation, built from Claude Code's stream-json output or its saved transcript. */
final class Chat {
    private static final AtomicLong IDS = new AtomicLong(1);
    static final String[] VERBS = {"琢磨中", "推敲中", "酝酿中", "构思中", "运转中"};

    static final class Msg {
        static final int USER = 0, ASSISTANT = 1, NOTE = 2, DIVIDER = 3;
        final long id = IDS.getAndIncrement();
        final int kind;
        String text = "";
        CharSequence note;
        final List<String> images = new ArrayList<>(); // data: URLs, null when not kept
        final List<String> files = new ArrayList<>();
        final List<Block> blocks = new ArrayList<>();
        boolean done;
        int version;
        final Map<Integer, Block> index = new HashMap<>(); // stream block index -> block
        final java.util.Set<String> streamed = new java.util.HashSet<>();

        Msg(int kind) { this.kind = kind; }
        void touch() { version++; }
    }

    static final class Block {
        static final int TEXT = 0, THINKING = 1, TOOL = 2, ERROR = 3, STOPPED = 4;
        final long id = IDS.getAndIncrement();
        final int kind;
        String text = "";
        String title = "";
        // tools
        String toolId, name, partial = "", result;
        /** Images in the tool's result (a picture Read): data: URLs, or ccimg: references to a saved one. */
        final List<String> images = new ArrayList<>();
        JSONObject input = new JSONObject();
        boolean running, error;
        /** Claude Code asks whether this tool may run: the question to answer; null once answered. */
        String ask;
        // thinking
        boolean streaming;
        long started, millis;
        boolean open;
        int version;

        Block(int kind) { this.kind = kind; }
        void touch() { version++; }
    }

    final String key;
    String sessionId, title = "", cwd = "", model;
    final List<Msg> messages = new ArrayList<>();
    boolean running, stopping, loaded = true;
    /** The last turn ended because it was stopped: queued messages then wait to be sent by hand. */
    boolean stopped;
    /** Messages written while a turn runs, sent one by one when it ends. */
    final List<Queued> queue = new ArrayList<>();
    long startedAt, lastEvent;
    String verb = VERBS[0], detail = "";
    String draft = "";
    final List<Attachment> attachments = new ArrayList<>();

    static final class Attachment {
        final boolean image;
        final String name, type, data; // a picture: base64
        /** A file: where it is, read when it is saved (never held here). */
        final android.net.Uri uri;
        final long size;
        final android.graphics.Bitmap thumb;

        Attachment(boolean image, String name, String type, String data, android.net.Uri uri, long size, android.graphics.Bitmap thumb) {
            this.image = image; this.name = name; this.type = type; this.data = data; this.uri = uri; this.size = size; this.thumb = thumb;
        }
    }

    static final class Queued {
        final long id = IDS.getAndIncrement();
        final String text;
        final List<Attachment> attachments;
        /** Sent as soon as the turn being stopped for it has ended. */
        boolean now;

        Queued(String text, List<Attachment> attachments) { this.text = text; this.attachments = attachments; }
    }

    Chat(String key) { this.key = key; }

    static String newKey() {
        return "n-" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1 << 20), 36);
    }

    String randomVerb() { return VERBS[new Random().nextInt(VERBS.length)]; }

    /** The reply being written, created on demand. */
    Msg reply(boolean create) {
        Msg last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last != null && last.kind == Msg.ASSISTANT && !last.done) return last;
        if (!create) return null;
        Msg m = new Msg(Msg.ASSISTANT);
        messages.add(m);
        return m;
    }

    Block tool(String id) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            for (Block b : messages.get(i).blocks) if (b.kind == Block.TOOL && id.equals(b.toolId)) return b;
        }
        return null;
    }

    Msg owner(Block block) {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i).blocks.contains(block)) return messages.get(i);
        return null;
    }

    /** The images in a tool result, as data: URLs. */
    static List<String> resultImages(Object content) {
        List<String> out = new ArrayList<>();
        if (!(content instanceof JSONArray)) return out;
        JSONArray a = (JSONArray) content;
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            JSONObject src = c == null || !"image".equals(c.optString("type")) ? null : c.optJSONObject("source");
            if (src != null && "base64".equals(src.optString("type")) && !src.optString("data").isEmpty()) {
                out.add("data:" + src.optString("media_type", "image/png") + ";base64," + src.optString("data"));
            }
        }
        return out;
    }

    static String resultText(Object content) {
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray a = (JSONArray) content;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                if (s.length() > 0) s.append('\n');
                if ("text".equals(c.optString("type"))) s.append(c.optString("text"));
                else if ("image".equals(c.optString("type"))) s.append("[图片]");
            }
            return s.toString();
        }
        return "";
    }

    /**
     * Applies one Claude Code stream-json message. Returns true when the reply ended (the
     * conversation list should be refreshed).
     */
    boolean apply(JSONObject d) {
        String t = d.optString("type");
        String sub = d.optString("subtype");
        boolean sideChain = !d.isNull("parent_tool_use_id") && d.has("parent_tool_use_id");
        if ("control_request".equals(t)) {
            // Claude Code asks whether a tool may run (a permission mode that asks): its row asks the user.
            JSONObject r = d.optJSONObject("request");
            if (r == null || !"can_use_tool".equals(r.optString("subtype"))) return false;
            String id = r.optString("tool_use_id");
            Block b = id.isEmpty() ? null : tool(id);
            if (b == null) {
                Msg m = reply(true);
                b = new Block(Block.TOOL);
                b.toolId = id;
                b.name = r.optString("tool_name");
                b.running = true;
                m.blocks.add(b);
                m.touch();
            }
            if (r.optJSONObject("input") != null) b.input = r.optJSONObject("input");
            b.ask = d.optString("request_id");
            b.touch();
            return false;
        }
        if ("control_cancel_request".equals(t)) {
            String id = d.optString("request_id");
            for (Msg m : messages) for (Block b : m.blocks) if (id.equals(b.ask)) { b.ask = null; b.touch(); }
            return false;
        }
        if ("system".equals(t)) {
            if ("init".equals(sub)) {
                if (d.has("session_id")) sessionId = d.optString("session_id");
                model = d.optString("model", model);
            } else if ("api_retry".equals(sub)) {
                String why = d.has("error_status") ? d.optString("error_status") + (d.has("error") ? " " + d.optString("error") : "")
                        : d.optString("error", "");
                detail = "重试 " + d.optInt("attempt") + "/" + d.optInt("max_retries") + (why.isEmpty() ? "" : " · " + why);
            } else if ("status".equals(sub)) {
                if ("requesting".equals(d.optString("status"))) detail = "";
            } else if ("compact_boundary".equals(sub)) {
                Msg m = new Msg(Msg.DIVIDER);
                m.text = "上下文已压缩";
                messages.add(m);
            }
            return false;
        }
        if ("stream_event".equals(t)) {
            if (sideChain) return false; // inside a sub-agent: shown as its step
            detail = "";
            JSONObject e = d.optJSONObject("event");
            if (e == null) return false;
            Msg m = reply(true);
            String et = e.optString("type");
            int index = e.optInt("index");
            if ("message_start".equals(et)) {
                m.index.clear();
                JSONObject msg = e.optJSONObject("message");
                if (msg != null) {
                    m.streamed.add(msg.optString("id"));
                }
            } else if ("content_block_start".equals(et)) {
                JSONObject cb = e.optJSONObject("content_block");
                if (cb == null) return false;
                Block b = null;
                switch (cb.optString("type")) {
                    case "text":
                        b = new Block(Block.TEXT);
                        b.text = cb.optString("text");
                        break;
                    case "thinking":
                        b = new Block(Block.THINKING);
                        b.streaming = true;
                        b.started = System.currentTimeMillis();
                        verb = "思考中";
                        break;
                    case "tool_use":
                        b = new Block(Block.TOOL);
                        b.toolId = cb.optString("id");
                        b.name = cb.optString("name");
                        b.running = true;
                        verb = "运行中";
                        break;
                    default:
                }
                if (b != null) {
                    m.blocks.add(b);
                    m.index.put(index, b);
                    m.touch();
                }
            } else if ("content_block_delta".equals(et)) {
                Block b = m.index.get(index);
                JSONObject dl = e.optJSONObject("delta");
                if (b == null || dl == null) return false;
                switch (dl.optString("type")) {
                    case "text_delta":
                        b.text += dl.optString("text");
                        verb = "生成中";
                        break;
                    case "thinking_delta":
                        b.text += dl.optString("thinking");
                        break;
                    case "input_json_delta":
                        b.partial += dl.optString("partial_json");
                        // Read when it can be whole, not on every piece (a long file's input is thousands).
                        if (b.partial.trim().endsWith("}")) {
                            try { b.input = new JSONObject(b.partial); } catch (Exception ignored) { /* incomplete */ }
                        }
                        break;
                    default:
                }
                b.touch();
            } else if ("content_block_stop".equals(et)) {
                Block b = m.index.get(index);
                if (b != null && b.kind == Block.THINKING) {
                    b.streaming = false;
                    b.millis = System.currentTimeMillis() - b.started;
                    b.touch();
                }
            }
            return false;
        }
        if ("assistant".equals(t)) {
            if (sideChain) return false;
            JSONObject msg = d.optJSONObject("message");
            if (msg == null) return false;
            Msg m = reply(true);
            boolean streamed = m.streamed.contains(msg.optString("id"));
            JSONArray content = msg.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c == null) continue;
                String ct = c.optString("type");
                if ("tool_use".equals(ct)) {
                    Block b = tool(c.optString("id"));
                    if (b != null) {
                        if (c.optJSONObject("input") != null) b.input = c.optJSONObject("input");
                        b.touch();
                    } else {
                        b = new Block(Block.TOOL);
                        b.toolId = c.optString("id");
                        b.name = c.optString("name");
                        b.input = c.optJSONObject("input") != null ? c.optJSONObject("input") : new JSONObject();
                        b.running = true;
                        m.blocks.add(b);
                        m.touch();
                    }
                } else if (!streamed) {
                    if ("text".equals(ct) && !c.optString("text").isEmpty()) {
                        Block b = new Block(Block.TEXT);
                        b.text = c.optString("text");
                        m.blocks.add(b);
                    } else if ("thinking".equals(ct) && !c.optString("thinking").isEmpty()) {
                        Block b = new Block(Block.THINKING);
                        b.text = c.optString("thinking");
                        m.blocks.add(b);
                    }
                    m.touch();
                }
            }
            return false;
        }
        if ("user".equals(t)) {
            if (sideChain) return false;
            JSONObject msg = d.optJSONObject("message");
            JSONArray content = msg == null ? null : msg.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject c = content.optJSONObject(i);
                if (c == null || !"tool_result".equals(c.optString("type"))) continue;
                Block b = tool(c.optString("tool_use_id"));
                if (b == null) continue;
                b.result = resultText(c.opt("content"));
                b.images.clear();
                b.images.addAll(resultImages(c.opt("content")));
                b.error = c.optBoolean("is_error");
                b.running = false;
                b.ask = null;
                b.touch();
            }
            verb = randomVerb();
            return false;
        }
        if ("result".equals(t)) {
            Msg m = reply(true);
            for (Block b : m.blocks) {
                if (b.kind == Block.TOOL && (b.running || b.ask != null)) { b.running = false; b.ask = null; b.touch(); }
                if (b.kind == Block.THINKING && b.streaming) { b.streaming = false; b.touch(); }
            }
            if ("error_during_execution".equals(sub) || stopping) {
                m.blocks.add(new Block(Block.STOPPED));
            } else if (d.optBoolean("is_error")) {
                Block b = new Block(Block.ERROR);
                b.title = "请求失败";
                b.text = d.optString("result", sub);
                m.blocks.add(b);
            }
            m.done = true;
            m.touch();
            running = false;
            stopped = stopping || "error_during_execution".equals(sub);
            stopping = false;
            if (d.has("session_id")) sessionId = d.optString("session_id");
            return true;
        }
        return false;
    }

    /** Messages from the bridge's /history (Claude Code's transcript). */
    void load(JSONObject history) {
        Msg live = reply(false); // a reply under way is not in the transcript yet: it stays, after it
        messages.clear();
        if (!history.optString("model").isEmpty() && model == null) model = history.optString("model");
        JSONArray list = history.optJSONArray("messages");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject h = list.optJSONObject(i);
            if (h == null) continue;
            String role = h.optString("role");
            if ("divider".equals(role)) {
                Msg m = new Msg(Msg.DIVIDER);
                m.text = h.optString("text");
                messages.add(m);
            } else if ("user".equals(role)) {
                Msg m = new Msg(Msg.USER);
                m.text = h.optString("text");
                JSONArray imgs = h.optJSONArray("images");
                for (int k = 0; imgs != null && k < imgs.length(); k++) m.images.add(imgs.isNull(k) ? null : imgs.optString(k));
                messages.add(m);
            } else if ("assistant".equals(role)) {
                Msg m = new Msg(Msg.ASSISTANT);
                m.done = true;
                JSONArray blocks = h.optJSONArray("blocks");
                for (int k = 0; blocks != null && k < blocks.length(); k++) {
                    JSONObject o = blocks.optJSONObject(k);
                    if (o == null) continue;
                    switch (o.optString("type")) {
                        case "text": {
                            Block b = new Block(Block.TEXT);
                            b.text = o.optString("text");
                            m.blocks.add(b);
                            break;
                        }
                        case "thinking": {
                            Block b = new Block(Block.THINKING);
                            b.text = o.optString("text");
                            m.blocks.add(b);
                            break;
                        }
                        case "tool": {
                            Block b = new Block(Block.TOOL);
                            b.toolId = o.optString("id");
                            b.name = o.optString("name");
                            b.input = o.optJSONObject("input") != null ? o.optJSONObject("input") : new JSONObject();
                            b.result = o.has("result") ? o.optString("result") : null;
                            JSONArray imgs = o.optJSONArray("images");
                            for (int n = 0; imgs != null && n < imgs.length(); n++) b.images.add(imgs.optString(n));
                            b.error = o.optBoolean("isError");
                            m.blocks.add(b);
                            break;
                        }
                        default:
                    }
                }
                messages.add(m);
            }
        }
        if (live != null) messages.add(live);
    }
}
