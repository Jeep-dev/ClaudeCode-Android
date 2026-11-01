package com.jeep.claudecode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The conversation as Markdown, what it counts of itself, and what the drawer's 待确认 looks at. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TranscriptTest {
    private static Chat.Block text(String t) {
        Chat.Block b = new Chat.Block(Chat.Block.TEXT);
        b.text = t;
        return b;
    }

    private static Chat.Block tool(String name, String input) throws Exception {
        Chat.Block b = Chat.Block.tool("t-" + name, name);
        b.input = new JSONObject(input);
        b.running = false;
        return b;
    }

    private static Chat sample() throws Exception {
        Chat chat = new Chat("k");
        chat.title = "统计行数";
        chat.cwd = "/root/work";
        chat.model = "claude-opus-5-5";
        Chat.Msg u = new Chat.Msg(Chat.Msg.USER);
        u.text = "写个脚本";
        u.images.add("data:image/png;base64,AA==");
        chat.messages.add(u);
        Chat.Msg a = new Chat.Msg(Chat.Msg.ASSISTANT);
        Chat.Block think = new Chat.Block(Chat.Block.THINKING);
        think.text = "不该出现在导出里";
        a.blocks.add(think);
        a.blocks.add(text("先看看目录。"));
        a.blocks.add(tool("Bash", "{'command':'ls -la\\npwd'}"));
        a.blocks.add(tool("Write", "{'file_path':'/root/work/count.py','content':'x'}"));
        a.blocks.add(tool("Edit", "{'file_path':'/root/work/count.py'}"));
        a.blocks.add(tool("Edit", "{'file_path':'notes.md'}"));
        a.blocks.add(text("写好了。"));
        chat.messages.add(a);
        return chat;
    }

    @Test public void markdownHasWhatWasSaid() throws Exception {
        String md = Transcript.markdown(sample(), "2026-11-01 20:00");
        assertTrue(md, md.startsWith("# 统计行数\n\n> 目录 `/root/work` · 模型 claude-opus-5-5 · 导出于 2026-11-01 20:00\n\n## 你\n\n写个脚本\n\n*（图片 1 张）*\n\n## Claude\n\n先看看目录。\n"));
        assertTrue(md, md.contains("> **Bash** `ls -la`\n"));
        assertTrue(md, md.contains("> **Write** `/root/work/count.py`\n"));
        assertTrue(md, md.endsWith("写好了。\n"));
        assertFalse("thinking stays out", md.contains("不该出现"));
    }

    @Test public void statsCountToolsAndFiles() throws Exception {
        Transcript.Stats s = Transcript.stats(sample());
        assertEquals(1, s.users);
        assertEquals(1, s.replies);
        assertEquals(4, s.toolCalls());
        assertEquals(2, (int) s.tools.get("Edit"));
        assertEquals(2, s.files.size());
        assertEquals(2, (int) s.files.get("/root/work/count.py"));
        assertEquals(1, (int) s.files.get("notes.md"));
    }

    @Test public void fileNameIsSafe() {
        assertEquals("a b c", Transcript.fileName("a/b:c"));
        assertEquals("对话", Transcript.fileName("  ??  "));
        assertEquals(40, Transcript.fileName("x".repeat(100)).length());
    }

    @Test public void pendingIsAToolAskingInTheLastTurn() throws Exception {
        Chat chat = sample();
        assertFalse(chat.pending());
        chat.messages.get(1).blocks.get(2).ask = "Bash 要运行 ls，允许吗？";
        assertTrue(chat.pending());
        // Once the person writes again, the question belongs to a turn that is over.
        chat.messages.add(new Chat.Msg(Chat.Msg.USER));
        assertFalse(chat.pending());
    }
}
