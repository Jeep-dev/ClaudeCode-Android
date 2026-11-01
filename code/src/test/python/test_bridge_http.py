"""Characterization test for bridge.py's HTTP interface.

Starts the bridge's Handler on a Unix socket in a temporary HOME, with a fake `claude` on PATH, calls
every route and compares status and body with bridge_http.golden.json. A refactoring of the bridge
must leave it as it is; a change of the interface is recorded with --record.

    python3 -I code/src/test/python/test_bridge_http.py [--record] [path/to/bridge.py]
"""
import http.client
import importlib.machinery
import importlib.util
import json
import os
import re
import socket
import stat
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
GOLDEN = os.path.join(HERE, 'bridge_http.golden.json')

FAKE_CLAUDE = r'''#!/usr/bin/env python3
import hashlib, json, os, sys, time
a = sys.argv[1:]
if a[:1] == ['--version']:
    print('9.9.9 (Claude Code)'); sys.exit(0)
if a[:1] == ['--help']:
    print('  --effort <level>   Effort (low, medium, high)')
    print('  --permission-mode <mode>   Mode (choices: "default", "plan", "bypassPermissions")')
    sys.exit(0)
if a[:1] == ['mcp']:
    with open(os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])), 'mcp.log'), 'a') as log:
        log.write(json.dumps(a) + '\n')
    if 'bad' in a:
        print('boom', file=sys.stderr); sys.exit(1)
    if a[1:2] == ['add-json'] and '"broken"' in a[3]:
        print('Invalid configuration', file=sys.stderr); sys.exit(1)
    sys.exit(0)
if a[:1] == ['plugin']:
    if 'list' in a:
        print('[]'); sys.exit(0)
    if 'fail' in a:
        print('\x1b[31mno such plugin\x1b[0m', file=sys.stderr); sys.exit(1)
    print('done'); sys.exit(0)

def out(o):
    print(json.dumps(o), flush=True)
sid = None
for i, x in enumerate(a):
    if x == '--resume':
        sid = a[i + 1]
for line in sys.stdin:
    try:
        m = json.loads(line)
    except ValueError:
        continue
    if m.get('type') == 'control_request':
        r = m.get('request') or {}
        sub = r.get('subtype')
        rid = m['request_id']
        if sub == 'generate_session_title':
            out({'type': 'control_response', 'response': {'subtype': 'error', 'request_id': rid, 'error': 'no'}})
        elif sub == 'initialize':
            out({'type': 'control_response', 'response': {'subtype': 'success', 'request_id': rid,
                 'response': {'models': [{'value': 'm1', 'displayName': 'M1'}]}}})
        elif sub == 'cancel_async_message':
            out({'type': 'control_response', 'response': {'subtype': 'success', 'request_id': rid,
                 'response': {'cancelled': r.get('message_uuid') == 'late'}}})
        else:
            out({'type': 'control_response', 'response': {'subtype': 'success', 'request_id': rid, 'response': {}}})
            if sub == 'interrupt':
                out({'type': 'result', 'subtype': 'error_during_execution', 'session_id': sid})
        continue
    if m.get('type') != 'user':
        continue
    c = m['message']['content']
    text = c if isinstance(c, str) else ' '.join(b.get('text', '') for b in c if isinstance(b, dict))
    sid = sid or 'sid-' + hashlib.sha1(text.encode()).hexdigest()[:6]
    out({'type': 'system', 'subtype': 'init', 'session_id': sid, 'model': 'fake'})
    if text == 'ask':
        out({'type': 'control_request', 'request_id': 'r1', 'request': {'subtype': 'can_use_tool',
             'tool_name': 'Bash', 'tool_use_id': 't1', 'input': {'command': 'ls'}}})
        for l2 in sys.stdin:
            r2 = json.loads(l2)
            if r2.get('type') == 'control_response':
                break
    if text == 'slow':
        continue
    out({'type': 'assistant', 'session_id': sid, 'message': {'id': 'x', 'content': [{'type': 'text', 'text': 'ok'}]}})
    out({'type': 'result', 'subtype': 'success', 'session_id': sid, 'is_error': False})
'''


def transcript(path, entries):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        for e in entries:
            f.write(json.dumps(e) + '\n')
    os.utime(path, (1_700_000_000, 1_700_000_000))


def user(uuid, parent, text, sid):
    return {'type': 'user', 'uuid': uuid, 'parentUuid': parent, 'sessionId': sid, 'isSidechain': False,
            'timestamp': '2025-01-01T00:00:00Z', 'cwd': '/tmp/proj',
            'message': {'role': 'user', 'content': text}}


def reply(uuid, parent, text, sid):
    return {'type': 'assistant', 'uuid': uuid, 'parentUuid': parent, 'sessionId': sid, 'isSidechain': False,
            'timestamp': '2025-01-01T00:00:01Z',
            'message': {'id': 'm' + uuid, 'role': 'assistant', 'content': [{'type': 'text', 'text': text}]}}


class Client:
    def __init__(self, sock):
        self.sock = sock

    def call(self, method, path, body=None, raw=None):
        class Conn(http.client.HTTPConnection):
            def connect(inner):
                inner.sock = socket.socket(socket.AF_UNIX)
                inner.sock.settimeout(30)
                inner.sock.connect(self.sock)
        c = Conn('bridge')
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        c.request(method, path, body=data)
        r = c.getresponse()
        text = r.read().decode('utf-8', 'replace')
        c.close()
        try:
            return r.status, json.loads(text)
        except ValueError:
            return r.status, text


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    record = '--record' in sys.argv
    bridge_path = os.path.abspath(args[0] if args else os.path.join(HERE, '..', '..', 'main', 'assets', 'bridge.py'))
    tmp = tempfile.mkdtemp(prefix='bridgetest')
    home = os.path.join(tmp, 'home')
    os.makedirs(os.path.join(tmp, 'bin'))
    os.makedirs(home)
    claude = os.path.join(tmp, 'bin', 'claude')
    with open(claude, 'w') as f:
        f.write(FAKE_CLAUDE)
    os.chmod(claude, os.stat(claude).st_mode | stat.S_IXUSR)
    os.environ['HOME'] = home
    os.environ['PATH'] = os.path.join(tmp, 'bin') + os.pathsep + os.environ['PATH']

    spec = importlib.util.spec_from_file_location('bridge', bridge_path,
                                                  loader=importlib.machinery.SourceFileLoader('bridge', bridge_path))
    bridge = importlib.util.module_from_spec(spec)
    sys.modules['bridge'] = bridge
    spec.loader.exec_module(bridge)
    sock = os.path.join(tmp, 's.sock')
    server = bridge.Server(sock, bridge.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    bridge.find_claude_info()
    client = Client(sock)
    records = []

    def norm(v):
        text = json.dumps(v).replace(tmp, '<T>')
        text = re.sub(r'(title|cancel)-[0-9a-f]{12}', r'\1-X', text)
        return json.loads(re.sub(r'stop-\d+', 'stop-X', text))

    def call(method, path, body=None, raw=None, note=None):
        status, data = client.call(method, path, body, raw)
        records.append({'req': norm('%s %s %s' % (method, path, '' if body is None else json.dumps(body, sort_keys=True))),
                        'status': status, 'body': norm(data)})
        return status, data

    # ---- GET ----
    call('GET', '/health')  # started is volatile
    records[-1]['body'].pop('started', None)
    call('GET', '/config')
    call('GET', '/plugins')
    call('GET', '/launch')
    call('GET', '/memory?scope=global')
    call('GET', '/memory?scope=nope')
    call('GET', '/memory?scope=folder&cwd=' + os.path.join(tmp, 'f'))
    call('GET', '/dirs?path=' + tmp)
    call('GET', '/dirs?path=/definitely/not/here')
    call('GET', '/image?id=none&at=0&n=0')
    call('GET', '/history?id=none')
    call('GET', '/nothing')
    call('GET', '/events?since=abc')  # int() fails: ValueError

    # ---- transcripts ----
    proj = os.path.join(home, '.claude', 'projects', '-tmp-proj')
    s1, s2, s3 = 'aaaaaaaa-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000002', \
        'aaaaaaaa-0000-0000-0000-000000000003'
    transcript(os.path.join(proj, s1 + '.jsonl'), [
        user('u1', None, 'first question', s1), reply('a1', 'u1', 'first answer', s1),
        user('u2', 'a1', 'second question about pelicans', s1), reply('a2', 'u2', 'second answer', s1)])
    transcript(os.path.join(proj, s2 + '.jsonl'), [
        user('v1', None, 'other conversation', s2), reply('b1', 'v1', 'other answer', s2),
        user('v2', 'b1', 'another', s2), reply('b2', 'v2', 'done', s2)])
    transcript(os.path.join(proj, s3 + '.jsonl'), [user('w1', None, 'only one', s3), reply('c1', 'w1', 'yes', s3)])
    call('GET', '/sessions')
    call('GET', '/search?q=pelicans')
    call('GET', '/search?q=')
    call('GET', '/history?id=' + s1)

    # ---- POST: simple ----
    call('POST', '/launch', {'permissionMode': 'plan', 'appendSystemPrompt': 'be brief'})
    call('POST', '/launch', {'permissionMode': 'unheard-of'})
    call('POST', '/launch', {'extraArgs': "'unclosed"})
    call('POST', '/launch', {})
    call('POST', '/memory', {'scope': 'global', 'text': 'remember me'})
    call('POST', '/memory', {'scope': 'folder', 'cwd': os.path.join(tmp, 'f'), 'text': 'folder text'})
    call('POST', '/memory', {'scope': 'bogus', 'text': 'x'})
    call('GET', '/memory?scope=global')
    call('GET', '/memory?scope=folder&cwd=' + os.path.join(tmp, 'f'))
    call('POST', '/mkdir', {'path': os.path.join(tmp, 'new', 'dir')})
    call('POST', '/mkdir', {'path': os.path.join(tmp, 'bin', 'claude', 'x')})
    call('POST', '/mkdir', {})
    call('POST', '/mcp', {'name': 'srv', 'action': 'set', 'config': {'command': 'x'}})
    call('POST', '/mcp', {'name': 'srv', 'action': 'remove'})
    call('POST', '/mcp', {'name': 'bad', 'action': 'set', 'config': {}})
    call('POST', '/mcp', {'name': 'bad name', 'action': 'set'})
    call('POST', '/mcp', {'name': 'srv', 'action': 'nope'})
    call('POST', '/mcp', {'name': 'srv', 'action': 'set', 'scope': 'galaxy'})
    call('POST', '/plugin', {'action': 'install', 'name': 'p1'})
    call('POST', '/plugin', {'action': 'market-add', 'name': 'owner/repo'})
    call('POST', '/plugin', {'action': 'install', 'name': 'fail'})
    call('POST', '/plugin', {'action': 'install', 'name': '-x'})
    call('POST', '/plugin', {'action': 'explode', 'name': 'p'})
    call('POST', '/title', {'id': s2, 'title': '  A   new   name '})
    call('POST', '/title', {'id': 'nonexistent', 'title': 'x'})
    call('POST', '/title', {'id': s2, 'title': '   '})
    call('POST', '/nothing', {})
    call('POST', '/send', raw=b'{not json')
    call('POST', '/send', {})
    call('POST', '/stop', {'chat': 'unknown'})
    call('POST', '/cancel', {'chat': 'unknown', 'uuid': 'x'})
    call('POST', '/permission', {'chat': 'unknown', 'requestId': 'r1', 'allow': True})
    call('POST', '/open', {'cwd': os.path.join(tmp, 'work2')})
    call('POST', '/open', {'sessionId': s1})

    # ---- upload ----
    for name in ('doc.txt', 'doc.txt', '../evil.txt'):
        status, data = client.call('POST', '/upload?cwd=%s&name=%s' % (os.path.join(tmp, 'up'), name), raw=b'hello')
        records.append({'req': 'POST /upload name=%s' % name, 'status': status, 'body': norm(data)})
    records.append({'req': 'upload files', 'status': 0, 'body': sorted(os.listdir(os.path.join(tmp, 'up')))})

    # ---- /send, /stop, /cancel, /permission ----
    call('POST', '/send', {'chat': 'c1', 'content': 'hello', 'mid': 'm1', 'cwd': os.path.join(tmp, 'w')})
    call('POST', '/send', {'chat': 'c1', 'content': 'hello', 'mid': 'm1'})  # repeated
    call('POST', '/send', {'chat': 'c1', 'content': [{'type': 'text', 'text': 'blocks'}], 'mid': 'm2', 'uuid': 'u-x'})
    call('POST', '/send', {'chat': 'c2', 'content': 'slow', 'mid': 'm3'})
    time.sleep(1)
    call('POST', '/cancel', {'chat': 'c2', 'uuid': 'late'})
    call('POST', '/cancel', {'chat': 'c2', 'uuid': 'early'})
    call('POST', '/stop', {'chat': 'c2'})
    call('POST', '/stop', {'chat': 'c1'})
    call('POST', '/send', {'chat': 'c3', 'content': 'ask', 'mid': 'm4'})
    time.sleep(1)
    call('POST', '/permission', {'chat': 'c3', 'requestId': 'nope', 'allow': True})
    # a chat that is asking is running: rewind and delete-message refuse it
    call('POST', '/rewind', {'chat': 'c3', 'sessionId': '', 'uuid': 'x'})
    call('POST', '/delete-message', {'chat': 'c3', 'sessionId': '', 'uuid': 'x', 'role': 'user'})
    call('POST', '/permission', {'chat': 'c3', 'requestId': 'r1', 'allow': True, 'answers': {'q': 'a'}})
    call('POST', '/permission', {'chat': 'c3', 'requestId': 'r1', 'allow': True})
    # sends that fail
    real_spawn = bridge.spawn
    for exc in (OSError('cannot start'), ValueError('bad value'), RuntimeError('odd')):
        def failing(*a, _e=exc, **k):
            raise _e
        bridge.spawn = failing
        call('POST', '/send', {'chat': 'bad-' + type(exc).__name__, 'content': 'x', 'mid': 'f-' + type(exc).__name__})
        bridge.spawn = real_spawn
    call('POST', '/send', {'chat': 'bad-OSError', 'content': 'x', 'mid': 'f-OSError'})  # the retry is not "repeated"

    # ---- events ----
    deadline = time.time() + 20
    events, seen = [], -1
    while time.time() < deadline:
        s = socket.socket(socket.AF_UNIX)
        s.connect(sock)
        s.settimeout(1.5)
        s.sendall(b'GET /events?since=0 HTTP/1.1\r\nHost: b\r\n\r\n')
        buf = b''
        try:
            while True:
                chunk = s.recv(65536)
                if not chunk:
                    break
                buf += chunk
        except socket.timeout:
            pass
        s.close()
        events = []
        for line in buf.decode('utf-8', 'replace').split('\n'):
            if line.startswith('data: '):
                e = json.loads(line[6:])
                e.pop('seq', None)
                events.append(e)
        done = sum(1 for e in events if (e.get('data') or {}).get('type') == 'result') >= 4
        if done and len(events) == seen:  # all the turns ended and nothing came since the last look
            break
        seen = len(events)
    records.append({'req': 'GET /events?since=0', 'status': 200,
                    'body': sorted(norm(events), key=lambda e: json.dumps(e, sort_keys=True))})

    # ---- rewind, delete-message, delete (transcripts) ----
    call('POST', '/rewind', {'chat': 'none', 'sessionId': s1, 'uuid': 'nope'})
    call('POST', '/rewind', {'chat': 'none', 'sessionId': s1, 'uuid': 'u2'})
    call('GET', '/history?id=' + s1)
    call('POST', '/delete-message', {'chat': 'none', 'sessionId': s2, 'uuid': 'b1', 'role': 'assistant'})
    call('POST', '/delete-message', {'chat': 'none', 'sessionId': s2, 'uuid': 'zz', 'role': 'user'})
    call('POST', '/delete-message', {'chat': 'none', 'sessionId': s2, 'uuid': 'v2'})
    call('GET', '/history?id=' + s2)
    call('POST', '/rewind', {'chat': 'none', 'sessionId': s3, 'uuid': 'w1'})  # nothing left: the file goes
    call('GET', '/sessions')
    call('POST', '/delete', {'id': s1})
    call('POST', '/delete', {'id': 'nonexistent'})
    call('POST', '/delete', {})
    call('GET', '/sessions')

    # ---- a conversation whose claude moved on (cd): it began where list_sessions says ----
    s4 = 'aaaaaaaa-0000-0000-0000-000000000004'
    moved = user('x2', 'c1', 'after cd', s4)
    moved['cwd'] = '/tmp/proj/sub'
    transcript(os.path.join(proj, s4 + '.jsonl'), [user('x1', None, 'begins here', s4), reply('d1', 'x1', 'ok', s4), moved])
    call('GET', '/history?id=' + s4)
    call('GET', '/sessions')

    # ---- a saved permission mode that --help no longer lists does not stop other settings ----
    bridge.launch.data['permissionMode'] = 'saved-long-ago'
    call('POST', '/launch', {'appendSystemPrompt': 'kept'})
    call('POST', '/launch', {'permissionMode': 'saved-long-ago'})
    call('POST', '/launch', {'permissionMode': 'unheard-of'})

    # ---- an MCP server whose new config is refused is put back as it was ----
    with open(os.path.join(home, '.claude.json'), 'w', encoding='utf-8') as f:
        json.dump({'mcpServers': {'srv2': {'type': 'stdio', 'command': 'old'}}}, f)
    open(os.path.join(tmp, 'bin', 'mcp.log'), 'w').close()
    call('POST', '/mcp', {'name': 'srv2', 'action': 'set', 'config': {'broken': True}})
    call('POST', '/mcp', {'name': 'srv3', 'action': 'set', 'config': {'broken': True}})  # none was there: nothing to put back
    with open(os.path.join(tmp, 'bin', 'mcp.log'), encoding='utf-8') as f:
        records.append({'req': 'claude mcp calls', 'status': 0, 'body': [json.loads(l) for l in f]})

    # ---- a conversation that cannot be named by its claude is asked once, not after every turn ----
    class Namer:
        session_id = s4
        asked = 0

        def ask_title(self, text):
            self.asked += 1
            return ''
    namer = Namer()
    bridge.chats['namer'] = namer
    for _ in range(3):
        bridge.name_later('namer', s4)
        time.sleep(0.5)
    del bridge.chats['namer']
    records.append({'req': 'name_later, claude names nothing, 3 turns', 'status': 0, 'body': {'asked': namer.asked}})

    # ---- end ----
    for chat in list(bridge.chats.values()):
        bridge.end(chat.proc)
    if bridge.spare.proc is not None:
        bridge.end(bridge.spare.proc)
    server.shutdown()

    result = {'records': records}
    if record:
        with open(GOLDEN, 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=1, sort_keys=True)
        print('recorded %d requests' % len(records))
        return 0
    with open(GOLDEN, encoding='utf-8') as f:
        golden = json.load(f)
    got = json.loads(json.dumps(result, sort_keys=True))
    bad = 0
    for i, (g, r) in enumerate(zip(golden['records'], got['records'])):
        if g != r:
            bad += 1
            print('DIFF #%d %s\n  golden: %s\n  now:    %s' % (i, g['req'], json.dumps(g, ensure_ascii=False)[:600],
                                                             json.dumps(r, ensure_ascii=False)[:600]))
    if len(golden['records']) != len(got['records']):
        bad += 1
        print('DIFF: %d records, golden has %d' % (len(got['records']), len(golden['records'])))
    print('%d requests, %d different' % (len(got['records']), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    code = main()
    sys.stdout.flush()
    os._exit(code)
