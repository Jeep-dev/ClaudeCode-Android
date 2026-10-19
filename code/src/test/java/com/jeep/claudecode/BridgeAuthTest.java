package com.jeep.claudecode;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * The app's {@link Bridge} against the real bridge.py: requests signed here are taken there,
 * answers proven there are believed here, and a saved conversation's picture comes by reference.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BridgeAuthTest {
    @Test public void signsAndProves() throws Exception {
        File script = new File("src/main/assets/bridge.py");
        Assume.assumeTrue(script.isFile() && new ProcessBuilder("python3", "--version").start().waitFor() == 0);
        File home = Files.createTempDirectory("bridge-home").toFile();
        File projects = new File(home, ".claude/projects/-w");
        projects.mkdirs();
        String picture = android.util.Base64.encodeToString("picture".getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        Userland.write(new File(projects, "sess-a.jsonl"), new JSONObject()
                .put("type", "user").put("cwd", "/w")
                .put("message", new JSONObject().put("role", "user").put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "hi"))
                        .put(new JSONObject().put("type", "image").put("source", new JSONObject()
                                .put("type", "base64").put("media_type", "image/png").put("data", picture)))))
                + "\n");

        Context c = RuntimeEnvironment.getApplication();
        ProcessBuilder pb = new ProcessBuilder("python3", "-I", script.getAbsolutePath(),
                "--token", Bridge.token(c), "--port", String.valueOf(Bridge.PORT));
        pb.environment().put("HOME", home.getPath());
        pb.redirectErrorStream(true).redirectOutput(new File(home, "bridge.out"));
        Process p = pb.start();
        try {
            Bridge b = new Bridge(c, new Bridge.Events() {
                @Override public void events(List<JSONObject> batch) {}
                @Override public void link(boolean up) {}
            });
            Object[] r = {0, null};
            for (int i = 0; i < 50 && (int) r[0] != 200; i++) {
                r = call(b, "/health", null);
                if ((int) r[0] != 200) Thread.sleep(200);
            }
            assertEquals(200, (int) r[0]);
            // What the connection may re-encode on the way does not change the signature.
            assertEquals(200, (int) call(b, "/search?q=" + android.net.Uri.encode("it's a b/中文 %+&="), null)[0]);
            r = call(b, "/history?id=sess-a", null);
            assertEquals(200, (int) r[0]);
            String ref = ((JSONObject) r[1]).getJSONArray("messages").getJSONObject(0).getJSONArray("images").getString(0);
            assertArrayEquals("picture".getBytes(StandardCharsets.UTF_8), b.image(ref));
            assertEquals(200, (int) call(b, "/stop", new JSONObject().put("chat", "nobody"))[0]);
        } finally {
            p.destroy();
        }
    }

    /** A request through the bridge, its answer waited for: {status, data}. */
    private static Object[] call(Bridge b, String path, JSONObject body) throws InterruptedException {
        Object[] r = {0, null};
        Bridge.Reply reply = (status, data) -> { r[1] = data; r[0] = status; };
        if (body == null) b.get(path, reply); else b.post(path, body, reply);
        long end = System.currentTimeMillis() + 5000;
        while ((int) r[0] == 0 && System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(20);
        }
        return r;
    }
}
