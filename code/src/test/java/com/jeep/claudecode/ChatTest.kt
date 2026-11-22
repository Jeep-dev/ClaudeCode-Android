package com.jeep.claudecode

import java.io.File
import java.util.TreeSet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Chat makes of Claude Code's stream-json messages and of a saved transcript: scripts of
 * events are applied and the conversation is written out after each, to be compared with
 * the files of src/test/resources/chat. A refactoring of Chat must leave them as they are; a change
 * of behaviour is recorded with -Drecord=true (and -Dgolden=<folder> to say where they are).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTest {
    private val golden = File(System.getProperty("golden", "src/test/resources/chat"))

    private val out = StringBuilder()

    private fun json(lenient: String) = JSONObject(lenient) // single quotes are fine for org.json

    /** Applies each event and writes down what it returned and what the conversation is after it. */
    private fun script(name: String, chat: Chat, vararg events: String) {
        out.append("=== ").append(name).append('\n')
        for (e in events) {
            if (e == "@sleep") { // a thinking block is timed in milliseconds: some must pass
                Thread.sleep(5)
                continue
            }
            val ended = chat.apply(json(e))
            out.append("--- ").append(e).append(" -> ").append(ended).append('\n')
            dump(chat)
        }
    }

    private fun dump(c: Chat) {
        val verb = if (c.verb in Chat.VERBS) "<random>" else c.verb // one is picked at random
        out.append("chat session=${c.sessionId} model=${c.model} running=${c.running} stopping=${c.stopping}")
            .append(" stopped=${c.stopped} detail=${c.detail} verb=$verb")
            .append(" toolRunning=${c.toolRunning()} handed=${c.handed()}\n")
        for (m in c.messages) {
            out.append("  msg kind=${m.kind} done=${m.done} uuid=${m.uuid} text=${m.text} note=${m.note} images=${m.images}")
                .append(" files=${m.files} version=${m.version}")
                .append(" index=${TreeSet(m.index.keys)} streamed=${TreeSet(m.streamed)}\n")
            for (b in m.blocks) {
                out.append("    block kind=${b.kind} text=${b.text} title=${b.title}")
                    .append(" toolId=${b.toolId} name=${b.name} result=${b.result}")
                    .append(" partial=${b.partial} input=${b.input} running=${b.running}")
                    .append(" error=${b.error} ask=${b.ask} streaming=${b.streaming}")
                    .append(" timed=${b.millis > 0} open=${b.open} images=${b.images}")
                    .append(" picked=${b.picked} other=${b.other} version=${b.version}\n")
            }
        }
        for (q in c.queue) {
            out.append("  queued text=${q.text} inserted=${q.inserted} uuid=${q.uuid}")
                .append(" now=${q.now} notInserted=${q.notInserted}\n")
        }
    }

    private fun se(event: String) = "{'type':'stream_event','event':$event}"

    private fun queued(text: String) = Chat.Queued(text, ArrayList())

    @Test fun streamedReply() {
        val c = Chat("k1")
        script("streamed reply", c,
            "{'type':'system','subtype':'init','session_id':'s1','model':'m1'}",
            "{'type':'system','subtype':'status','status':'requesting'}",
            se("{'type':'message_start','message':{'id':'msg1'}}"),
            se("{'type':'content_block_start','index':0,'content_block':{'type':'thinking'}}"),
            se("{'type':'content_block_delta','index':0,'delta':{'type':'thinking_delta','thinking':'hm'}}"),
            "@sleep",
            se("{'type':'content_block_stop','index':0}"),
            se("{'type':'content_block_start','index':1,'content_block':{'type':'text','text':'He'}}"),
            se("{'type':'content_block_delta','index':1,'delta':{'type':'text_delta','text':'llo'}}"),
            se("{'type':'content_block_stop','index':1}"),
            se("{'type':'content_block_start','index':2,'content_block':{'type':'tool_use','id':'t1','name':'Write'}}"),
            se("{'type':'content_block_delta','index':2,'delta':{'type':'input_json_delta','partial_json':'{\"file_path\":\"/a\",\"content\":\"x'}}"),
            se("{'type':'content_block_delta','index':2,'delta':{'type':'input_json_delta','partial_json':'y\"}'}}"),
            se("{'type':'content_block_stop','index':2}"),
            se("{'type':'content_block_start','index':3,'content_block':{'type':'nothing'}}"),
            se("{'type':'content_block_delta','index':9,'delta':{'type':'text_delta','text':'lost'}}"),
            se("{'type':'content_block_delta','index':1,'delta':{'type':'other_delta'}}"),
            se("{'type':'ping'}"),
            "{'type':'stream_event'}",
            "{'type':'assistant','message':{'id':'msg1','content':[{'type':'text','text':'Hello'}," +
                "{'type':'tool_use','id':'t1','name':'Write','input':{'file_path':'/a','content':'xy'}}]}}",
            "{'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'t1','content':'File created'}]}}",
            "{'type':'user','message':{'content':'plain text'}}",
            "{'type':'result','subtype':'success','session_id':'s1'}")
        assertSameAsGolden("streamedReply")
    }

    @Test fun notStreamedReply() {
        val c = Chat("k2")
        script("not streamed", c,
            "{'type':'system','subtype':'init','session_id':'s2'}",
            "{'type':'assistant','message':{'id':'a1','content':[{'type':'thinking','thinking':'think'},{'type':'thinking','thinking':''}," +
                "{'type':'text','text':'Said'},{'type':'text','text':''}," +
                "{'type':'tool_use','id':'t1','name':'Bash','input':{'command':'ls'}},{'type':'tool_use','id':'t2','name':'Read'}]}}",
            "{'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'t1','is_error':true,'content':" +
                "[{'type':'text','text':'no'},{'type':'image','source':{'type':'base64','media_type':'image/jpeg','data':'QUJD'}}]}," +
                "{'type':'tool_result','tool_use_id':'gone','content':'x'},{'type':'text','text':'y'}," +
                "{'type':'tool_result','tool_use_id':'t2','content':[{'type':'image','source':{'type':'url'}}]}]}}",
            "{'type':'result','subtype':'success','is_error':true,'result':'Rate limited','session_id':'s2'}")
        script("error during execution", c,
            "{'type':'system','subtype':'init','session_id':'s2'}",
            "{'type':'assistant','message':{'id':'a2','content':[{'type':'tool_use','id':'t3','name':'Bash','input':{}}]}}",
            "{'type':'result','subtype':'error_during_execution'}")
        c.stopping = true
        script("stopped", c,
            "{'type':'system','subtype':'init'}",
            "{'type':'assistant','message':{'id':'a3','content':[{'type':'text','text':'partly'}]}}",
            "{'type':'result','subtype':'success','session_id':'s9'}")
        script("result without a reply", Chat("k2b"), "{'type':'result','subtype':'success','is_error':true}", "{'type':'other'}")
        assertSameAsGolden("notStreamedReply")
    }

    @Test fun systemMessagesAndSideChains() {
        val c = Chat("k3")
        script("system", c,
            "{'type':'system','subtype':'api_retry','attempt':2,'max_retries':10,'error_status':529,'error':'overloaded'}",
            "{'type':'system','subtype':'api_retry','attempt':1,'max_retries':10,'error_status':null,'error':'unknown'}",
            "{'type':'system','subtype':'api_retry','attempt':1,'max_retries':10,'error_status':null,'error':null}",
            "{'type':'system','subtype':'api_retry','attempt':3,'max_retries':10,'error_status':null,'error':'ECONNRESET'}",
            "{'type':'system','subtype':'status','status':'requesting'}",
            "{'type':'system','subtype':'compact_boundary'}",
            "{'type':'system','subtype':'init','session_id':'s3','model':'m3'}",
            "{'type':'system','subtype':'init'}")
        script("side chain", c,
            "{'type':'stream_event','parent_tool_use_id':'t0','event':{'type':'content_block_start','index':0,'content_block':{'type':'text'}}}",
            "{'type':'assistant','parent_tool_use_id':'t0','message':{'id':'x','content':[{'type':'text','text':'inner'}]}}",
            "{'type':'user','parent_tool_use_id':'t0','message':{'content':[{'type':'tool_result','tool_use_id':'t','content':'x'}]}}",
            "{'type':'assistant','parent_tool_use_id':null,'message':{'id':'y','content':[{'type':'text','text':'outer'}]}}")
        assertSameAsGolden("systemMessagesAndSideChains")
    }

    @Test fun permissionRequests() {
        val c = Chat("k4")
        script("permissions", c,
            "{'type':'system','subtype':'init','session_id':'s4'}",
            "{'type':'control_request','request_id':'r1','request':{'subtype':'can_use_tool','tool_use_id':'t1','tool_name':'Bash','input':{'command':'rm x'}}}",
            "{'type':'control_request','request_id':'r2','request':{'subtype':'other'}}",
            "{'type':'control_request','request_id':'r3'}",
            "{'type':'assistant','message':{'id':'q','content':[{'type':'tool_use','id':'t1','name':'Bash','input':{'command':'rm x'}}]}}",
            "{'type':'control_request','request_id':'r4','request':{'subtype':'can_use_tool','tool_use_id':'t1','tool_name':'Bash','input':{'command':'rm y'}}}",
            "{'type':'control_request','request_id':'r5','request':{'subtype':'can_use_tool','tool_name':'Edit'}}",
            "{'type':'control_cancel_request','request_id':'r4'}",
            "{'type':'control_cancel_request','request_id':'unknown'}",
            "{'type':'control_request','request_id':'r6','request':{'subtype':'can_use_tool','tool_use_id':'t7','tool_name':'AskUserQuestion'," +
                "'input':{'questions':[{'question':'Which?','options':[{'label':'A'},{'label':'B'}]}]}}}",
            "{'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'t7','content':'User has answered: \"Which?\"=\"A\"'}]}}",
            "{'type':'result','subtype':'success'}")
        assertSameAsGolden("permissionRequests")
    }

    @Test fun insertedMessages() {
        val c = Chat("k5")
        val first = queued("insert me").apply { inserted = true; uuid = "q1" }
        val second = queued("too late").apply { inserted = true; uuid = "q2" }
        val waiting = queued("waits")
        c.queue.add(first)
        c.queue.add(second)
        c.queue.add(waiting)
        script("inserted", c,
            "{'type':'system','subtype':'init','session_id':'s5'}",
            "{'type':'assistant','message':{'id':'p1','content':[{'type':'tool_use','id':'t1','name':'Bash','input':{'command':'sleep 1'}}]}}",
            "{'type':'command_lifecycle','command_uuid':'q1','state':'queued'}",
            "{'type':'command_lifecycle','command_uuid':'nobody','state':'started'}",
            "{'type':'command_lifecycle','command_uuid':'q1','state':'started'}",
            "{'type':'assistant','message':{'id':'p2','content':[{'type':'text','text':'after the insertion'}]}}",
            "{'type':'command_lifecycle','command_uuid':'q2','state':'cancelled'}",
            "{'type':'result','subtype':'success','session_id':'s5'}")
        c.unhand()
        dump(c)
        script("handed at the end", c, "{'type':'system','subtype':'init'}")
        c.queue.add(queued("again").apply { inserted = true })
        script("a turn ends while something is handed", c,
            "{'type':'assistant','message':{'id':'p3','content':[{'type':'text','text':'x'}]}}",
            "{'type':'result','subtype':'success'}")
        out.append("reply(false)=${c.reply(false) != null} tool(t1)=${c.tool("t1") != null}")
            .append(" tool(none)=${c.tool("none") != null}\n")
        assertSameAsGolden("insertedMessages")
    }

    @Test fun savedTranscript() {
        val c = Chat("k6")
        c.sessionId = "s6"
        c.apply(json("{'type':'system','subtype':'init','session_id':'s6'}"))
        c.apply(json("{'type':'assistant','message':{'id':'live','content':[{'type':'text','text':'live reply'}]}}"))
        c.load(json("{'model':'saved-model','messages':[" +
            "{'role':'divider','text':'上下文已压缩'}," +
            "{'role':'user','uuid':'u1','text':'question','images':['data:image/png;base64,AA',null]}," +
            "{'role':'assistant','uuid':'a1','blocks':[" +
            "{'type':'thinking','text':'hmm'},{'type':'text','text':'answer'}," +
            "{'type':'tool','id':'t1','name':'Read','input':{'file_path':'/x'},'result':'contents','images':['ccimg:1'],'isError':false}," +
            "{'type':'tool','id':'t2','name':'Bash','isError':true},{'type':'unknown'},null]}," +
            "{'role':'user','uuid':null,'text':'second'}," +
            "{'role':'system','text':'ignored'},null]}"))
        dump(c)
        out.append("=== reload without a live reply\n")
        val d = Chat("k6b")
        d.model = "kept"
        d.load(json("{'model':'other','messages':[]}"))
        dump(d)
        out.append("owner=${c.owner(c.messages[2].blocks[0]) === c.messages[2]} tool(t2).error=${c.tool("t2")!!.error}\n")
        assertSameAsGolden("savedTranscript")
    }

    @Test fun helpers() {
        val cut = arrayOf("", "  ", "{", "{\"a\"", "{\"a\":", "{\"a\":\"he", "{\"a\":\"he\\", "{\"a\":\"h\\u00e", "{\"a\":\"x\\u00e9y\\n\\\"q\"}",
            "{\"a\":\"x\",\"b\":12", "{\"a\":\"x\",\"b\":12,", "{\"a\":\"x\",\"b\":[1,2", "{\"a\":\"x\",\"b\":[1,2],\"c\":\"y",
            "{\"a\":{\"n\":1},\"b\":\"z", "{\"a\":{\"n\":1", "{\"a\":true,\"b\":null,\"c\":\"w\"}", "[1,2]", "{,\"a\":\"1\"}",
            "{\"a\":\"1\",,\"b\":\"2\"}", "{\"a\" \"1\"}", "{\"a\":\"line1\\nline2\\ttab")
        out.append("=== partialInput\n")
        for (s in cut) out.append(s.replace("\n", "\\n")).append(" => ").append(Chat.partialInput(s)).append('\n')
        out.append("=== results\n")
        val contents = arrayOf<Any?>("text", JSONArray("[{'type':'text','text':'a'},{'type':'image'},null,{'type':'text','text':'b'}]"),
            JSONArray("[{'type':'image','source':{'type':'base64','media_type':'image/webp','data':'QQ=='}}," +
                "{'type':'image','source':{'type':'base64','data':''}},{'type':'image','source':{'type':'base64','data':'Qg=='}}]"),
            null, 5)
        for (o in contents) {
            out.append(o).append(" => text=").append(Chat.resultText(o)).append(" images=").append(Chat.resultImages(o)).append('\n')
        }
        assertSameAsGolden("helpers")
    }

    // ---- golden ----

    private fun assertSameAsGolden(test: String) {
        val file = File(golden, "$test.txt")
        val text = out.toString()
        if (java.lang.Boolean.getBoolean("record")) {
            file.absoluteFile.parentFile!!.mkdirs()
            file.writeText(text)
            return
        }
        assertEquals(file.readText(), text)
    }
}
