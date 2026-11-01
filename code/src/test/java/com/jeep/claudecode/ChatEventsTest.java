package com.jeep.claudecode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;

/** What the app does with the bridge's events for conversations it has deleted, or whose claude was ended. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChatEventsTest {
    private static JSONObject ev(String chat, String kind, String data) throws Exception {
        return new JSONObject().put("chat", chat).put("kind", kind).put("data", new JSONObject(data));
    }

    private static void idle() {
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Test public void eventsOfADeletedConversationAreLetGo() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Chat chat = new Chat("c-1");
        chat.sessionId = "s-1";
        a.chats.put(chat.key, chat);
        a.bySession.put("s-1", chat.key);
        a.drop(chat.key, "s-1");
        int known = a.chats.size();
        // What its claude still said while it was being ended, by its key and by its session.
        a.events(Arrays.asList(
                ev("c-1", "cc", "{'type':'assistant','session_id':'s-1','message':{'id':'m','content':[{'type':'text','text':'x'}]}}"),
                ev("c-1", "cc", "{'type':'result','subtype':'success','session_id':'s-1'}"),
                ev("n-9", "cc", "{'type':'result','subtype':'success','session_id':'s-1'}")));
        idle();
        assertEquals(known, a.chats.size());
        assertFalse(a.chats.containsKey("c-1"));
        assertFalse(a.bySession.containsKey("s-1"));
    }

    @Test public void aSessionPointingAtNothingMakesTheConversationAgain() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.bySession.put("s-2", "gone");
        a.events(Arrays.asList(ev("x", "cc", "{'type':'result','subtype':'success','session_id':'s-2'}")));
        idle();
        assertTrue(a.chats.containsKey("x"));
        assertEquals("x", a.bySession.get("s-2"));
    }

    @Test public void aClaudeThatEndedMidStopLeavesNoStopBehind() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Chat chat = new Chat("c-3");
        chat.sessionId = "s-3";
        chat.running = true;
        chat.stopping = true; // stop was asked, and the bridge ended the claude when it did not answer
        a.chats.put(chat.key, chat);
        a.bySession.put("s-3", chat.key);
        a.events(Arrays.asList(ev("c-3", "exit", "{'code':-9,'running':true,'stderr':''}")));
        idle();
        assertFalse(chat.stopping);
        assertFalse(chat.running);
        assertTrue(chat.stopped);
    }
}
