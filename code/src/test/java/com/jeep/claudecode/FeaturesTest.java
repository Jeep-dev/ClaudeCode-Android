package com.jeep.claudecode;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

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

/** The drawer with its filters, the 功能 page and its sheets, rendered to PNG files (SHOTS directory). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w392dp-h850dp-xxhdpi")
public class FeaturesTest {
    private static final File OUT = new File(System.getProperty("shots", "build/shots"));

    private static void shot(View v, String name) throws Exception {
        OUT.mkdirs();
        Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        try (FileOutputStream f = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
    }

    private static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }

    private static void call(Object o, String method) throws Exception {
        Method m = o.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(o);
    }

    private static void idle() {
        for (int i = 0; i < 30; i++) ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static Chat.Block tool(String name, String input) throws Exception {
        Chat.Block b = Chat.Block.tool("t-" + name + input.length(), name);
        b.input = new JSONObject(input);
        b.running = false;
        return b;
    }

    @Test public void featuresAndFilters() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        set(a.link, "linked", true);
        set(a.link, "setup", null);
        long now = System.currentTimeMillis() / 1000;

        // The open conversation has something said, written files and a tool asking.
        Chat chat = a.current;
        chat.sessionId = "s1";
        chat.title = "帮我看看 work 里有什么";
        chat.cwd = "/root/work";
        chat.model = "claude-opus-5-5";
        Chat.Msg u = new Chat.Msg(Chat.Msg.USER);
        u.text = "帮我写个统计行数的脚本";
        chat.messages.add(u);
        Chat.Msg r = new Chat.Msg(Chat.Msg.ASSISTANT);
        Chat.Block t = new Chat.Block(Chat.Block.TEXT);
        t.text = "好的，先看一下目录。";
        r.blocks.add(t);
        r.blocks.add(tool("Bash", "{'command':'ls -la'}"));
        r.blocks.add(tool("Write", "{'file_path':'/root/work/count.py','content':'x'}"));
        r.blocks.add(tool("Edit", "{'file_path':'/root/work/count.py'}"));
        r.blocks.add(tool("Edit", "{'file_path':'/root/work/README.md'}"));
        r.done = true;
        chat.messages.add(r);

        // Another one waits for an answer, another runs.
        Chat waiting = new Chat("k2");
        waiting.sessionId = "s2";
        Chat.Msg wu = new Chat.Msg(Chat.Msg.USER);
        wu.text = "删掉旧的构建目录";
        waiting.messages.add(wu);
        Chat.Msg wr = new Chat.Msg(Chat.Msg.ASSISTANT);
        Chat.Block ask = tool("Bash", "{'command':'rm -rf build'}");
        ask.ask = "Bash 要运行 rm -rf build，允许吗？";
        wr.blocks.add(ask);
        waiting.messages.add(wr);
        a.chats.put("k2", waiting);
        a.bySession.put("s2", "k2");
        Chat running = new Chat("k3");
        running.sessionId = "s3";
        running.running = true;
        a.chats.put("k3", running);
        a.bySession.put("s3", "k3");

        set(a.drawer, "sessions", new JSONArray("[{\"id\":\"s1\",\"title\":\"帮我看看 work 里有什么\",\"cwd\":\"/root/work\",\"mtime\":" + now + "},"
                + "{\"id\":\"s2\",\"title\":\"删掉旧的构建目录\",\"cwd\":\"/root/work\",\"mtime\":" + (now - 600) + "},"
                + "{\"id\":\"s3\",\"title\":\"写一个鹈鹕骑自行车的 SVG 动画\",\"cwd\":\"/root/work\",\"mtime\":" + (now - 3600) + "},"
                + "{\"id\":\"s4\",\"title\":\"Xed-Editor 中文翻译\",\"cwd\":\"/root/Xed-Editor\",\"mtime\":" + (now - 90000) + "},"
                + "{\"id\":\"s5\",\"title\":\"termux 里装 claude code\",\"cwd\":\"/root\",\"mtime\":" + (now - 400000) + "}]"));
        // What was seen before: s4 changed since.
        a.prefs.edit().putString("seen", "{\"s1\":" + now + ",\"s2\":" + (now - 600) + ",\"s3\":" + (now - 3600)
                + ",\"s4\":" + (now - 200000) + ",\"s5\":" + (now - 400000) + "}").commit();
        a.drawer.renderSessions();
        ((DrawerFrame) a.frame).open();
        idle();
        View root = a.getWindow().getDecorView();
        shot(root, "20-drawer-all");
        for (int f = 1; f <= 3; f++) {
            set(a.drawer, "filter", f);
            a.drawer.renderSessions();
            idle();
            shot(root, "20-drawer-filter-" + f);
        }
        set(a.drawer, "filter", 0);
        a.drawer.renderSessions();

        a.features.open();
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "21-features");
        call(a.features, "details");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "22-details");
        ShadowDialog.getLatestDialog().dismiss();
        call(a.features, "changedFiles");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "23-files");
        ShadowDialog.getLatestDialog().dismiss();
        call(a.features, "export");
        idle();
        shot(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "24-export");
    }
}
