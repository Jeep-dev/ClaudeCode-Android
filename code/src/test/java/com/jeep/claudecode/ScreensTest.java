package com.jeep.claudecode;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Renders the main screens to PNG files (SHOTS directory) for a visual check. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w392dp-h850dp-xxhdpi")
public class ScreensTest {
    private static final File OUT = new File(System.getProperty("shots", "build/shots"));

    private static void shot(View v, String name) throws Exception {
        OUT.mkdirs();
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream f = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    private static void dump(View v, int depth) {
        if (depth > 7) return;
        System.out.println("DUMP " + "  ".repeat(depth) + v.getClass().getSimpleName() + " " + v.getWidth() + "x" + v.getHeight()
                + " vis=" + v.getVisibility() + (v instanceof android.widget.TextView ? " '" + ((android.widget.TextView) v).getText() + "'" : ""));
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1);
        }
    }

    private static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static Object get(Object o, String field) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void call(Object o, String method) throws Exception {
        Method m = o.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(o);
    }

    private static void idle() {
        for (int i = 0; i < 30; i++) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static JSONObject ev(String chat, String kind, String data) throws Exception {
        return new JSONObject().put("chat", chat).put("kind", kind).put("data", data == null ? JSONObject.NULL : new JSONObject(data));
    }

    private static String se(String event) { return "{\"type\":\"stream_event\",\"event\":" + event + "}"; }

    /** Opening the app with the immersive layout and the title chosen (a cold start, not a switch). */
    @Test public void startsImmersive() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        Themes.setImmersive(c, true);
        Themes.setShowTitle(c, true);
        try {
            MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
            idle();
            shot(a.getWindow().getDecorView(), "00-start-immersive");
        } finally {
            Themes.setImmersive(c, false);
            Themes.setShowTitle(c, false);
        }
    }

    @Test public void screens() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        idle();
        shot(root, "01-setup");


        set(a, "linked", true);
        set(a, "setup", null);
        set(a, "home", "/data/data/com.termux/files/home");
        set(a, "defaultCwd", "/data/data/com.termux/files/home/work");
        set(a, "defaultModel", "deepseek-flash[1m]");
        Chat chat = (Chat) get(a, "current");
        chat.cwd = "/data/data/com.termux/files/home/work";
        call(a, "renderNow");
        idle();
        shot(root, "02-hero");

        // A conversation, through the same events the bridge sends.
        Chat.Msg user = new Chat.Msg(Chat.Msg.USER);
        user.text = "帮我看看 work 里有什么，再写个统计行数的脚本";
        chat.messages.add(user);
        chat.title = "帮我看看 work 里有什么";
        chat.running = true;
        chat.startedAt = System.currentTimeMillis() - 7000;
        String k = chat.key;
        List<JSONObject> evs = new ArrayList<>();
        evs.add(ev(k, "cc", "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\",\"model\":\"claude-opus-5-5[1m]\",\"slash_commands\":[\"compact\",\"context\",\"cost\",\"init\",\"simplify\",\"debug\"]}"));
        evs.add(ev(k, "cc", se("{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"usage\":{\"input_tokens\":1200,\"cache_read_input_tokens\":18000}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"用户想看目录内容，然后写脚本。先 ls。\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_stop\",\"index\":0}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"先看一下文件夹里的内容。\"}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls -la\\\",\\\"description\\\":\\\"列出 work 文件夹\\\"}\"}}")));
        evs.add(ev(k, "cc", "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"total 12\\ndrwx------ 3 u0_a1 u0_a1 4096 .\\n-rw------- 1 u0_a1 u0_a1  210 notes.txt\\n-rw------- 1 u0_a1 u0_a1 1830 pelican-bike.html\"}]}}"));
        evs.add(ev(k, "cc", se("{\"type\":\"message_start\",\"message\":{\"id\":\"m2\",\"usage\":{\"input_tokens\":900,\"cache_read_input_tokens\":19800}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"Write\",\"input\":{}}}")));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"file_path\\\":\\\"/data/data/com.termux/files/home/work/count.py\\\",\\\"content\\\":\\\"import sys\\\"}\"}}")));
        evs.add(ev(k, "cc", "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"content\":\"File created\"}]}}"));
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")));
        String md = "文件夹里有 **2 个文件**：\\n\\n| 文件 | 大小 |\\n|---|---|\\n| notes.txt | 210 B |\\n| pelican-bike.html | 1.8 KB |\\n\\n统计行数的脚本已经写好，保存为 `count.py`：\\n\\n```python\\nimport sys\\n\\nfor path in sys.argv[1:]:\\n    with open(path) as f:\\n        print(path, sum(1 for _ in f))\\n```\\n\\n运行：`python count.py *.txt`。";
        evs.add(ev(k, "cc", se("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + md + "\"}}")));
        a.events(evs);
        chat.queue.add(new Chat.Queued("顺便把 notes.txt 也统计一下", new ArrayList<>()));
        call(a, "renderNow");
        idle();
        shot(root, "03-running");
        chat.queue.clear();

        List<JSONObject> end = new ArrayList<>();
        end.add(ev(k, "cc", "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s1\",\"usage\":{\"output_tokens\":406},\"modelUsage\":{\"claude-opus-5-5[1m]\":{\"inputTokens\":2100,\"cacheReadInputTokens\":37800,\"contextWindow\":1000000}}}"));
        a.events(end);
        idle();
        shot(root, "04-done");

        // Expand the tool and the thinking
        for (Chat.Msg m : chat.messages) for (Chat.Block b : m.blocks) if (b.kind != Chat.Block.TEXT) b.open = true;
        call(a, "renderNow");
        idle();
        RecyclerScroll.top(a);
        idle();
        shot(root, "05-expanded");

        // Drawer
        set(a, "sessions", new JSONArray("[{\"id\":\"s1\",\"title\":\"帮我看看 work 里有什么\",\"cwd\":\"/data/data/com.termux/files/home/work\",\"mtime\":" + (System.currentTimeMillis() / 1000) + "},"
                + "{\"id\":\"s2\",\"title\":\"写一个鹈鹕骑自行车的 SVG 动画\",\"cwd\":\"/data/data/com.termux/files/home/work\",\"mtime\":" + (System.currentTimeMillis() / 1000 - 3600) + "},"
                + "{\"id\":\"s3\",\"title\":\"Xed-Editor 中文翻译\",\"cwd\":\"/data/data/com.termux/files/home/Xed-Editor\",\"mtime\":" + (System.currentTimeMillis() / 1000 - 90000) + "},"
                + "{\"id\":\"s4\",\"title\":\"termux 里装 claude code\",\"cwd\":\"/data/data/com.termux/files/home\",\"mtime\":" + (System.currentTimeMillis() / 1000 - 400000) + "}]"));
        call(a, "renderSessions");
        ((DrawerFrame) get(a, "frame")).open();
        idle();
        shot(root, "06-drawer");
        ((DrawerFrame) get(a, "frame")).close();
        idle();

        // Slash menu and a note
        EditText input = (EditText) get(a, "input");
        input.setText("/");
        idle();
        shot(root, "07-slash");
        input.setText("/effort");
        call(a, "send");
        idle();
        shot(root, "08-note");

        // Every tool's row
        Chat tools = new Chat("tools");
        Chat.Msg tm = new Chat.Msg(Chat.Msg.ASSISTANT);
        String[][] names = {{"Bash", "{\"command\":\"ls\",\"description\":\"List files\"}"}, {"Read", "{\"file_path\":\"/w/a.py\"}"},
                {"Write", "{\"file_path\":\"/w/b.py\"}"}, {"Edit", "{\"file_path\":\"/w/c.py\"}"}, {"MultiEdit", "{\"file_path\":\"/w/d.py\"}"},
                {"NotebookEdit", "{\"notebook_path\":\"/w/e.ipynb\"}"}, {"Glob", "{\"pattern\":\"**/*.py\"}"}, {"Grep", "{\"pattern\":\"TODO\"}"},
                {"WebFetch", "{\"url\":\"https://example.com\"}"}, {"WebSearch", "{\"query\":\"termux python\"}"},
                {"Task", "{\"description\":\"Explore repo\"}"}, {"TodoWrite", "{}"}, {"Skill", "{\"skill\":\"simplify\"}"},
                {"mcp__exa__web_fetch_exa", "{\"urls\":[\"https://api-docs.deepseek.com/quick_start/pricing\"],\"maxCharacters\":1500}"}};
        for (String[] n : names) {
            Chat.Block th = new Chat.Block(Chat.Block.THINKING);
            th.text = "想一下";
            tm.blocks.add(th);
            Chat.Block b = new Chat.Block(Chat.Block.TOOL);
            b.name = n[0];
            b.input = new JSONObject(n[1]);
            tm.blocks.add(b);
        }
        String png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAUAAAADICAIAAAAWZq/8AAAD2klEQVR4nO3ZMW5UWRBAUUDeCItgLeQTkDp0NsHsgJRgNsQKiNgKgSWL0TCMZZr/6r53TuzurpL6qn7Lr788vH8FNL1ZPQDwcgKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ9jdr7z47V9/32oOONnXP/942QtdYAgTMIQJGMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAh7G71ANzSu48fnvNnn+8//e5JuIaA854Z7X+9RMxpAq56Qbc/fx8lFwk45lbd/uSdlRwi4Izfl+4PP0jGCQIOuCzdf3+ojIfzb6TpltQ75NP5Xy7wXEPicYonc4GHGlLvk2nz8EjAE82sZeZUh/MIPcvwSDxOT+MCDzK83ieVOU8g4ClaVbSm3ZiARyj2UJx5PwKGMAGv1z1l3cm3IeDF6g3U568T8Ep7fPv32CJKwBAm4GV2Olw77dIi4DX2+8bvt1GCgCFMwAvseqx23WsyAUOYgK+295nae7uBBAxhAoYwAV/qhCfME3acQ8AQJmAIE/B1znm2PGfT5QQMYQKGMAFDmIAvctrPwtP2XUXAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBHyRz/efVo9wqdP2XUXAECZgCBMwhAn4Ouf8LDxn0+UEDGEChjABX+qEZ8sTdpxDwBAmYAgT8NX2fsLce7uBBAxhAl5g1zO1616TCRjCBLzGfsdqv40SBLzMTt/4nXZpETCECXilPQ7XHltECXix+re/Pn+dgNfrNtCdfBsChjABj1A8ZcWZ9yPgKVo9tKbdmIAHqVRRmfMEd6sH4B8e23j38cPqQX5MutO4wBPN7GTmVIcT8FDTapk2D488Qs815HFaupO5wNOt7Ue9w7nAAUtOsXQTBJxxWcbSDRFwzFNdNy9Zt0UCrrpVybpNE3De9wU+M2bRbkPAW1HmafwbCcIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ5iAIUzAECZgCBMwhAkYwgQMYQKGMAFDmIAhTMAQJmAIe/3l4f3qGYAXcoEhTMAQJmAIEzCECRjCBAxhAoYwAUOYgCFMwBAmYAgTMIQJGMIEDGEChjABQ5iAIUzAEPYNbUmr08oXlSsAAAAASUVORK5CYII=";
        Chat.Block pic = new Chat.Block(Chat.Block.TOOL);
        pic.name = "Read";
        pic.input = new JSONObject("{\"file_path\":\"/root/work/shot.png\"}");
        pic.result = "[图片]";
        pic.images.add(png);
        tm.blocks.add(0, pic);
        Chat.Block opened = new Chat.Block(Chat.Block.TOOL);
        opened.name = "Read";
        opened.input = new JSONObject("{\"file_path\":\"/root/work/shot.png\"}");
        opened.result = "[图片]";
        opened.images.add(png);
        opened.open = true;
        tm.blocks.add(1, opened);
        Chat.Block says = new Chat.Block(Chat.Block.TEXT);
        says.text = "画好了：\n\n![图](" + png + ")\n\n![没有的](/root/work/none.png)";
        tm.blocks.add(2, says);
        tm.done = true;
        tools.messages.add(tm);
        Method sw = MainActivity.class.getDeclaredMethod("switchTo", Chat.class);
        sw.setAccessible(true);
        sw.invoke(a, tools);
        idle();
        shot(root, "10-tools");
        RecyclerScroll.top(a);
        idle();
        shot(root, "10-tools-top");
        Themes.setShowThinking(a, false);
        call(a, "renderNow");
        idle();
        RecyclerScroll.top(a);
        idle();
        shot(root, "10-tools-nothinking");
        Themes.setShowThinking(a, true);
        call(a, "renderNow");
        idle();
        sw.invoke(a, chat);
        idle();

        call(a, "settingsSheet");
        idle();
        Dialog d = ShadowDialog.getLatestDialog();
        View sheet = d.getWindow().getDecorView();
        shot(sheet, "09-settings");
        Method sf = MainActivity.class.getDeclaredMethod("settingsFile");
        sf.setAccessible(true);
        java.io.File settingsFile = (java.io.File) sf.invoke(a);
        settingsFile.getParentFile().mkdirs();
        Userland.write(settingsFile, "{\"env\":{\"ANTHROPIC_BASE_URL\":\"https://api.deepseek.com/anthropic\",\"ANTHROPIC_AUTH_TOKEN\":\"sk-1234567890abcdef\",\"DISABLE_TELEMETRY\":\"1\"},"
                + "\"alwaysThinkingEnabled\":true,\"cleanupPeriodDays\":90,\"permissions\":{\"allow\":[\"Bash\"]},\"effortLevel\":\"high\"}");
        set(a, "schema", new org.json.JSONArray("[{\"key\":\"verbose\",\"type\":\"bool\",\"desc\":\"Show full tool output instead of truncated summaries\"},"
                + "{\"key\":\"effortLevel\",\"type\":\"enum\",\"choices\":[\"low\",\"medium\",\"high\",\"xhigh\"],\"desc\":\"Persisted effort level for supported models.\"},"
                + "{\"key\":\"cleanupPeriodDays\",\"type\":\"number\",\"desc\":\"Number of days to retain chat transcripts before automatic cleanup (default: 30).\"}]"));
        Method open = MainActivity.class.getDeclaredMethod("settingsOpen", String.class);
        open.setAccessible(true);
        for (String page : new String[]{"look", "talk", "model", "claude", "storage", "about"}) {
            open.invoke(a, page);
            idle();
            shot(sheet, "09-settings-" + page);
        }
        Method one = MainActivity.class.getDeclaredMethod("settingSheet", String.class);
        one.setAccessible(true);
        one.invoke(a, "effortLevel");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "09-setting-enum");
        ShadowDialog.getLatestDialog().dismiss();
        call(a, "addSettingSheet");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "09-setting-add");
        ShadowDialog.getLatestDialog().dismiss();
        Userland land = (Userland) get(a, "userland");
        java.io.File claudeJson = land.file(Userland.HOME + "/.claude.json");
        claudeJson.getParentFile().mkdirs();
        Userland.write(claudeJson, "{\"mcpServers\":{\"exa\":{\"type\":\"http\",\"url\":\"https://mcp.exa.ai/mcp\"}},\"projects\":{}}");
        Userland.write(land.file(Userland.HOME + "/.claude/skills/pdf-tools/SKILL.md"), "---\nname: pdf-tools\n---\n");
        a.getSharedPreferences("app", 0).edit().putString("skills",
                "[\"pdf-tools\",\"simplify\",\"code-review\",\"security-review\",\"document-skills:xlsx\"]").apply();
        call(a, "skillsSheet");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "09-setting-skills");
        ShadowDialog.getLatestDialog().dismiss();
        call(a, "mcpSheet");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "09-setting-mcp");
        ShadowDialog.getLatestDialog().dismiss();
        call(a, "envSheet");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "09-setting-env");
        ShadowDialog.getLatestDialog().dismiss();
        open.invoke(a, (Object) null);
        idle();
        d.dismiss();

        Method cfg = MainActivity.class.getDeclaredMethod("applyConfig", JSONObject.class);
        cfg.setAccessible(true);
        cfg.invoke(a, new JSONObject("{\"models\":[\"deepseek-flash[1m]\",\"deepseek-flash\"],\"defaultModel\":\"deepseek-flash[1m]\","
                + "\"efforts\":[\"low\",\"medium\",\"high\",\"xhigh\",\"max\"],\"effort\":\"max\","
                + "\"permissionModes\":[\"acceptEdits\",\"auto\",\"bypassPermissions\",\"manual\",\"dontAsk\",\"plan\"]}"));
        call(a, "modelSheet");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "11-model");
        ShadowDialog.getLatestDialog().dismiss();

        for (String theme : new String[]{"black", "white", "paper", "mist", "slate", "dark"}) {
            Themes.set(a, theme);
            call(a, "restyle");
            idle();
            shot(a.getWindow().getDecorView(), "12-" + theme);
        }
        Fonts.setSize(a, 2);
        call(a, "restyle");
        idle();
        shot(a.getWindow().getDecorView(), "13-large");
        androidx.recyclerview.widget.RecyclerView list = (androidx.recyclerview.widget.RecyclerView) get(a, "list");
        list.scrollBy(0, -400);
        call(a, "showJump");
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS);
        shot(a.getWindow().getDecorView(), "14-jump");
        Fonts.setSize(a, 1);
        Themes.set(a, "system");
        Themes.setImmersive(a, true);
        call(a, "restyle");
        idle();
        shot(a.getWindow().getDecorView(), "15-immersive");
        Themes.set(a, "dark");
        call(a, "restyle");
        idle();
        shot(a.getWindow().getDecorView(), "15-immersive-dark");
        Themes.setShowTitle(a, true);
        call(a, "restyle");
        idle();
        shot(a.getWindow().getDecorView(), "15-immersive-title");
        Themes.setShowTitle(a, false);
        Themes.set(a, "system");
        Themes.setImmersive(a, false);
    }

    static final class RecyclerScroll {
        static void top(MainActivity a) throws Exception {
            androidx.recyclerview.widget.RecyclerView list = (androidx.recyclerview.widget.RecyclerView) get(a, "list");
            list.scrollToPosition(0);
            set(a, "stick", false);
        }
    }
}
