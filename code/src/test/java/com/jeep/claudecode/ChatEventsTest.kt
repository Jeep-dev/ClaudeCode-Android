package com.jeep.claudecode

import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** What the app does with the bridge's events for conversations it has deleted, or whose claude was ended. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatEventsTest {
    private fun ev(chat: String, kind: String, data: String): JSONObject =
        JSONObject().put("chat", chat).put("kind", kind).put("data", JSONObject(data))

    private fun idle() = ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

    private fun activity(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test fun eventsOfADeletedConversationAreLetGo() {
        val a = activity()
        val chat = Chat("c-1")
        chat.sessionId = "s-1"
        a.chats[chat.key] = chat
        a.bySession["s-1"] = chat.key
        a.drop(chat.key, "s-1")
        val known = a.chats.size
        // What its claude still said while it was being ended, by its key and by its session.
        a.events(listOf(
            ev("c-1", "cc", "{'type':'assistant','session_id':'s-1','message':{'id':'m','content':[{'type':'text','text':'x'}]}}"),
            ev("c-1", "cc", "{'type':'result','subtype':'success','session_id':'s-1'}"),
            ev("n-9", "cc", "{'type':'result','subtype':'success','session_id':'s-1'}")))
        idle()
        assertEquals(known, a.chats.size)
        assertFalse(a.chats.containsKey("c-1"))
        assertFalse(a.bySession.containsKey("s-1"))
    }

    @Test fun aSessionPointingAtNothingMakesTheConversationAgain() {
        val a = activity()
        a.bySession["s-2"] = "gone"
        a.events(listOf(ev("x", "cc", "{'type':'result','subtype':'success','session_id':'s-2'}")))
        idle()
        assertTrue(a.chats.containsKey("x"))
        assertEquals("x", a.bySession["s-2"])
    }

    @Test fun aClaudeThatEndedMidStopLeavesNoStopBehind() {
        val a = activity()
        val chat = Chat("c-3")
        chat.sessionId = "s-3"
        chat.running = true
        chat.stopping = true // stop was asked, and the bridge ended the claude when it did not answer
        a.chats[chat.key] = chat
        a.bySession["s-3"] = chat.key
        a.events(listOf(ev("c-3", "exit", "{'code':-9,'running':true,'stderr':''}")))
        idle()
        assertFalse(chat.stopping)
        assertFalse(chat.running)
        assertTrue(chat.stopped)
    }
}
