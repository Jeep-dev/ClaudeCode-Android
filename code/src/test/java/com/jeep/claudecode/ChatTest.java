package com.jeep.claudecode;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * What Chat makes of Claude Code's stream-json messages and of a saved transcript: scripts of
 * events are applied and the conversation is written out after each, to be compared with
 * the files of src/test/resources/chat. A refactoring of Chat must leave them as they are; a change
 * of behaviour is recorded with -Drecord=true (and -Dgolden=<folder> to say where they are).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChatTest {
    private static final File GOLDEN = new File(System.getProperty("golden", "src/test/resources/chat"));

    private final StringBuilder out = new StringBuilder();

    private static JSONObject json(String lenient) throws Exception {
        return new JSONObject(lenient); // single quotes are fine for org.json
    }

    /** Applies each event and writes down what it returned and what the conversation is after it. */
    private void script(String name, Chat chat, String... events) throws Exception {
        out.append("=== ").append(name).append('\n');
        for (String e : events) {
            if (e.equals("@sleep")) { // a thinking block is timed in milliseconds: some must pass
                Thread.sleep(5);
                continue;
            }
            boolean ended = chat.apply(json(e));
            out.append("--- ").append(e).append(" -> ").append(ended).append('\n');
            dump(chat);
        }
    }

    private void dump(Chat c) {
        out.append("chat session=").append(c.sessionId).append(" model=").append(c.model)
                .append(" running=").append(c.running).append(" stopping=").append(c.stopping)
                .append(" stopped=").append(c.stopped).append(" detail=").append(c.detail)
                .append(" verb=").append(java.util.Arrays.asList(Chat.VERBS).contains(c.verb) ? "<random>" : c.verb) // one is picked at random
                .append(" toolRunning=").append(c.toolRunning()).append(" handed=").append(c.handed()).append('\n');
        for (Chat.Msg m : c.messages) {
            out.append("  msg kind=").append(m.kind).append(" done=").append(m.done).append(" uuid=").append(m.uuid)
                    .append(" text=").append(m.text).append(" note=").append(m.note).append(" images=").append(m.images)
                    .append(" files=").append(m.files).append(" version=").append(m.version)
                    .append(" index=").append(new TreeSet<>(m.index.keySet())).append(" streamed=").append(new TreeSet<>(m.streamed))
                    .append('\n');
            for (Chat.Block b : m.blocks) {
                out.append("    block kind=").append(b.kind).append(" text=").append(b.text).append(" title=").append(b.title)
                        .append(" toolId=").append(b.toolId).append(" name=").append(b.name).append(" result=").append(b.result)
                        .append(" partial=").append(b.partial).append(" input=").append(b.input).append(" running=").append(b.running)
                        .append(" error=").append(b.error).append(" ask=").append(b.ask).append(" streaming=").append(b.streaming)
                        .append(" timed=").append(b.millis > 0).append(" open=").append(b.open).append(" images=").append(b.images)
                        .append(" picked=").append(b.picked).append(" other=").append(b.other).append(" version=").append(b.version)
                        .append('\n');
            }
        }
        for (Chat.Queued q : c.queue) {
            out.append("  queued text=").append(q.text).append(" inserted=").append(q.inserted).append(" uuid=").append(q.uuid)
                    .append(" now=").append(q.now).append(" notInserted=").append(q.notInserted).append('\n');
        }
    }

    private static String se(String event) { return "{'type':'stream_event','event':" + event + "}"; }

    @Test public void streamedReply() throws Exception {
        Chat c = new Chat("k1");
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
                "{'type':'assistant','message':{'id':'msg1','content':[{'type':'text','text':'Hello'},"
                        + "{'type':'tool_use','id':'t1','name':'Write','input':{'file_path':'/a','content':'xy'}}]}}",
                "{'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'t1','content':'File created'}]}}",
                "{'type':'user','message':{'content':'plain text'}}",
                "{'type':'result','subtype':'success','session_id':'s1'}");
        assertSameAsGolden();
    }

    @Test public void notStreamedReply() throws Exception {
        Chat c = new Chat("k2");
        script("not streamed", c,
                "{'type':'system','subtype':'init','session_id':'s2'}",
                "{'type':'assistant','message':{'id':'a1','content':[{'type':'thinking','thinking':'think'},{'type':'thinking','thinking':''},"
                        + "{'type':'text','text':'Said'},{'type':'text','text':''},"
                        + "{'type':'tool_use','id':'t1','name':'Bash','input':{'command':'ls'}},{'type':'tool_use','id':'t2','name':'Read'}]}}",
                "{'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'t1','is_error':true,'content':"
                        + "[{'type':'text','text':'no'},{'type':'image','source':{'type':'base64','media_type':'image/jpeg','data':'QUJD'}}]},"
                        + "{'type':'tool_result','tool_use_id':'gone','content':'x'},{'type':'text','text':'y'},"
                        + "{'type':'tool_result','tool_use_id':'t2','content':[{'type':'image','source':{'type':'url'}}]}]}}",
                "{'type':'result','subtype':'success','is_error':true,'result':'Rate limited','session_id':'s2'}");
        script("error during execution", c,
                "{'type':'system','subtype':'init','session_id':'s2'}",
                "{'type':'assistant','message':{'id':'a2','content':[{'type':'tool_use','id':'t3','name':'Bash','input':{}}]}}",
                "{'type':'result','subtype':'error_during_execution'}");
        c.stopping = true;
        script("stopped", c,
                "{'type':'system','subtype':'init'}",
                "{'type':'assistant','message':{'id':'a3','content':[{'type':'text','text':'partly'}]}}",
                "{'type':'result','subtype':'success','session_id':'s9'}");
        script("result without a reply", new Chat("k2b"), "{'type':'result','subtype':'success','is_error':true}", "{'type':'other'}");
        assertSameAsGolden();
    }

    @Test public void systemMessagesAndSideChains() throws Exception {
        Chat c = new Chat("k3");
        script("system", c,
                "{'type':'system','subtype':'api_retry','attempt':2,'max_retries':10,'error_status':529,'error':'overloaded'}",
                "{'type':'system','subtype':'api_retry','attempt':1,'max_retries':10,'error_status':null,'error':'unknown'}",
                "{'type':'system','subtype':'api_retry','attempt':1,'max_retries':10,'error_status':null,'error':null}",
                "{'type':'system','subtype':'api_retry','attempt':3,'max_retries':10,'error_status':null,'error':'ECONNRESET'}",
                "{'type':'system','subtype':'status','status':'requesting'}",
                "{'type':'system','subtype':'compact_boundary'}",
                "{'type':'system','subtype':'init','session_id':'s3','model':'m3'}",
                "{'type':'system','subtype':'init'}");
        script("side chain", c,
                "{'type':'stream_event','parent_tool_use_id':'t0','event':{'type':'content_block_start','index':0,'content_block':{'type':'text'}}}",
                "{'type':'assistant','parent_tool_use_id':'t0','message':{'id':'x','content':[{'type':'text','text':'inner'}]}}",
                "{'type':'user','parent_tool_use_id':'t0','message':{'content':[{'type':'tool_result','tool_use_id':'t','content':'x'}]}}",
                "{'type':'assistant','parent_tool_use_id':null,'message':{'id':'y','content':[{'type':'text','text':'outer'}]}}");
        assertSameAsGolden();
    }

    @Test public void permissionRequests() throws Exception {
        Chat c = new Chat("k4");
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
                "{'type':'control_request','request_id':'r6','request':{'subtype':'can_use_tool','tool_use_id':'t7','tool_name':'AskUserQuestion',"
                        + "'input':{'questions':[{'question':'Which?','options':[{'label':'A'},{'label':'B'}]}]}}}",
                "{'type':'user','message':{'content':[{'type':'tool_result','tool_use_id':'t7','content':'User has answered: \"Which?\"=\"A\"'}]}}",
                "{'type':'result','subtype':'success'}");
        assertSameAsGolden();
    }

    @Test public void insertedMessages() throws Exception {
        Chat c = new Chat("k5");
        Chat.Queued first = new Chat.Queued("insert me", new ArrayList<>());
        first.inserted = true;
        first.uuid = "q1";
        Chat.Queued second = new Chat.Queued("too late", new ArrayList<>());
        second.inserted = true;
        second.uuid = "q2";
        Chat.Queued waiting = new Chat.Queued("waits", new ArrayList<>());
        c.queue.add(first);
        c.queue.add(second);
        c.queue.add(waiting);
        script("inserted", c,
                "{'type':'system','subtype':'init','session_id':'s5'}",
                "{'type':'assistant','message':{'id':'p1','content':[{'type':'tool_use','id':'t1','name':'Bash','input':{'command':'sleep 1'}}]}}",
                "{'type':'command_lifecycle','command_uuid':'q1','state':'queued'}",
                "{'type':'command_lifecycle','command_uuid':'nobody','state':'started'}",
                "{'type':'command_lifecycle','command_uuid':'q1','state':'started'}",
                "{'type':'assistant','message':{'id':'p2','content':[{'type':'text','text':'after the insertion'}]}}",
                "{'type':'command_lifecycle','command_uuid':'q2','state':'cancelled'}",
                "{'type':'result','subtype':'success','session_id':'s5'}");
        c.unhand();
        dump(c);
        script("handed at the end", c, "{'type':'system','subtype':'init'}");
        Chat.Queued third = new Chat.Queued("again", new ArrayList<>());
        third.inserted = true;
        c.queue.add(third);
        script("a turn ends while something is handed", c,
                "{'type':'assistant','message':{'id':'p3','content':[{'type':'text','text':'x'}]}}",
                "{'type':'result','subtype':'success'}");
        out.append("reply(false)=").append(c.reply(false) != null).append(" tool(t1)=").append(c.tool("t1") != null)
                .append(" tool(none)=").append(c.tool("none") != null).append('\n');
        assertSameAsGolden();
    }

    @Test public void savedTranscript() throws Exception {
        Chat c = new Chat("k6");
        c.sessionId = "s6";
        c.apply(json("{'type':'system','subtype':'init','session_id':'s6'}"));
        c.apply(json("{'type':'assistant','message':{'id':'live','content':[{'type':'text','text':'live reply'}]}}"));
        c.load(json("{'model':'saved-model','messages':["
                + "{'role':'divider','text':'上下文已压缩'},"
                + "{'role':'user','uuid':'u1','text':'question','images':['data:image/png;base64,AA',null]},"
                + "{'role':'assistant','uuid':'a1','blocks':["
                + "{'type':'thinking','text':'hmm'},{'type':'text','text':'answer'},"
                + "{'type':'tool','id':'t1','name':'Read','input':{'file_path':'/x'},'result':'contents','images':['ccimg:1'],'isError':false},"
                + "{'type':'tool','id':'t2','name':'Bash','isError':true},{'type':'unknown'},null]},"
                + "{'role':'user','uuid':null,'text':'second'},"
                + "{'role':'system','text':'ignored'},null]}"));
        dump(c);
        out.append("=== reload without a live reply\n");
        Chat d = new Chat("k6b");
        d.model = "kept";
        d.load(json("{'model':'other','messages':[]}"));
        dump(d);
        out.append("owner=").append(c.owner(c.messages.get(2).blocks.get(0)) == c.messages.get(2)).append(" tool(t2).error=")
                .append(c.tool("t2").error).append('\n');
        assertSameAsGolden();
    }

    @Test public void helpers() throws Exception {
        String[] cut = {"", "  ", "{", "{\"a\"", "{\"a\":", "{\"a\":\"he", "{\"a\":\"he\\", "{\"a\":\"h\\u00e", "{\"a\":\"x\\u00e9y\\n\\\"q\"}",
                "{\"a\":\"x\",\"b\":12", "{\"a\":\"x\",\"b\":12,", "{\"a\":\"x\",\"b\":[1,2", "{\"a\":\"x\",\"b\":[1,2],\"c\":\"y",
                "{\"a\":{\"n\":1},\"b\":\"z", "{\"a\":{\"n\":1", "{\"a\":true,\"b\":null,\"c\":\"w\"}", "[1,2]", "{,\"a\":\"1\"}",
                "{\"a\":\"1\",,\"b\":\"2\"}", "{\"a\" \"1\"}", "{\"a\":\"line1\\nline2\\ttab"};
        out.append("=== partialInput\n");
        for (String s : cut) out.append(s.replace("\n", "\\n")).append(" => ").append(Chat.partialInput(s)).append('\n');
        out.append("=== results\n");
        Object[] contents = {"text", new org.json.JSONArray("[{'type':'text','text':'a'},{'type':'image'},null,{'type':'text','text':'b'}]"),
                new org.json.JSONArray("[{'type':'image','source':{'type':'base64','media_type':'image/webp','data':'QQ=='}},"
                        + "{'type':'image','source':{'type':'base64','data':''}},{'type':'image','source':{'type':'base64','data':'Qg=='}}]"),
                null, 5};
        for (Object o : contents) {
            out.append(o).append(" => text=").append(Chat.resultText(o)).append(" images=").append(Chat.resultImages(o)).append('\n');
        }
        assertSameAsGolden();
    }

    // ---- golden ----

    private void assertSameAsGolden() throws Exception {
        String test = new Throwable().getStackTrace()[1].getMethodName();
        File file = new File(GOLDEN, test + ".txt");
        String text = out.toString();
        if (Boolean.getBoolean("record")) {
            file.getAbsoluteFile().getParentFile().mkdirs();
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        String want = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertEquals(want, text);
    }
}
