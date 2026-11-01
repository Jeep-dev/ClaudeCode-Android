"""Antigravity's models for Claude Code: a local endpoint speaking Anthropic's Messages API.

A request for a model Antigravity serves (agy models: gemini-*, gpt-oss-*, claude-*-low|medium|high)
is turned into a Gemini request to Antigravity's Code Assist API (v1internal:streamGenerateContent,
as the agy CLI sends it), with agy's own sign-in (TOKEN_FILE, kept fresh by running `agy models`),
and its answer back into Anthropic's stream. Anything else (Claude Code's small fast model, a
/model switch to a Claude model) goes on to Anthropic as it came, with the caller's own sign-in.

Listens on 127.0.0.1 (Claude Code takes only an http(s) base URL), at a random port and under a
random path: another app on the phone can reach the port, not guess the path.

Standard library only.
"""
import contextlib
import http.client
import json
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
import uuid
import zlib
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser('~')
TOKEN_FILE = os.path.join(HOME, '.gemini', 'antigravity-cli', 'antigravity-oauth-token')
MODELS_FILE = os.path.join(HOME, '.ccapp', 'agy-models.json')
HOST = 'daily-cloudcode-pa.googleapis.com'
USER_AGENT = 'antigravity/cli/1.4.7 (aidev_client; os_type=linux; arch=arm64; cl=1006008713; auth_method=consumer)'
ANTHROPIC = 'api.anthropic.com'
# Gemini 3 wants the signature of the thinking behind each function call back with it; one not
# known (the bridge started again since) is let through with this value, as Google documents.
SKIP_SIGNATURE = 'skip_thought_signature_validator'

log = lambda text: None  # set by the bridge
models = []  # [{'value', 'displayName'}], as agy models lists them
project = None
signatures = {}  # tool_use id -> thoughtSignature of the call
token_lock = threading.Lock()
models_lock = threading.Lock()


def is_agy(model):
    """Whether a model name is one of Antigravity's (not one of Anthropic's own)."""
    if not model:
        return False
    model = model.strip()
    if any(m['value'] == model for m in models):
        return True
    return bool(re.fullmatch(r'(gemini-|gpt-oss-)[\w.-]+|claude-[\w.-]+-(low|medium|high)', model))


def agy_path():
    return shutil.which('agy')


def available():
    return bool(agy_path()) and os.path.exists(TOKEN_FILE)


def find_models():
    """Antigravity's models from `agy models` (which also refreshes agy's sign-in), kept in MODELS_FILE."""
    global models
    if not models_lock.acquire(blocking=False):
        return
    try:
        try:
            with open(MODELS_FILE, encoding='utf-8') as f:
                saved = json.load(f)
            if isinstance(saved, list):
                models = saved
            if models and time.time() - os.path.getmtime(MODELS_FILE) < 24 * 3600:
                return
        except (OSError, ValueError):
            pass
        if not available():
            return
        try:
            out = subprocess.run([agy_path(), 'models'], capture_output=True, text=True, timeout=120, cwd=HOME).stdout
        except (OSError, subprocess.SubprocessError) as e:
            log('agy models failed: %s' % e)
            return
        found = []
        for line in out.splitlines():
            parts = line.split('\t')
            if len(parts) == 2 and re.fullmatch(r'[\w.-]+', parts[0]):
                found.append({'value': parts[0], 'displayName': parts[1].strip()})
        if found:
            models = found
            os.makedirs(os.path.dirname(MODELS_FILE), exist_ok=True)
            with open(MODELS_FILE, 'w', encoding='utf-8') as f:
                json.dump(found, f, ensure_ascii=False)
    finally:
        models_lock.release()


# ---- sign-in ----

def read_token():
    with open(TOKEN_FILE, encoding='utf-8') as f:
        tok = json.load(f)['token']
    expiry = tok.get('expiry') or ''
    m = re.match(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d+)?(Z|[+-]\d\d:\d\d)$', expiry)
    left = 0
    if m:
        zone = '+00:00' if m.group(2) == 'Z' else m.group(2)
        left = datetime.fromisoformat(m.group(1) + zone).timestamp() - time.time()
    return tok['access_token'], left


def access_token(force=False):
    """agy's access token; when it ends within two minutes (or force), agy is run to refresh it."""
    with token_lock:
        token, left = read_token()
        if left > 120 and not force:
            return token
        log('agproxy: refreshing agy sign-in (%ds left)' % left)
        try:
            subprocess.run([agy_path(), 'models'], capture_output=True, timeout=120, cwd=HOME)
        except (OSError, subprocess.SubprocessError) as e:
            log('agproxy: agy models failed: %s' % e)
        return read_token()[0]


def code_assist(path, body, token):
    conn = http.client.HTTPSConnection(HOST, timeout=300)
    data = json.dumps(body).encode()
    conn.request('POST', '/v1internal:' + path, data, {
        'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'User-Agent': USER_AGENT,
        'Accept-Encoding': 'identity'})
    return conn, conn.getresponse()


def get_project(token):
    global project
    if project:
        return project
    try:
        conn, resp = code_assist('loadCodeAssist', {'metadata': {'ideType': 'ANTIGRAVITY'}}, token)
        with contextlib.closing(conn):
            data = json.loads(resp.read() or b'{}')
        p = data.get('cloudaicompanionProject')
        project = (p.get('id') if isinstance(p, dict) else p) or None
    except (OSError, ValueError, http.client.HTTPException) as e:
        log('agproxy: loadCodeAssist failed: %s' % e)
    return project or 'aicode-consumers'


# ---- Anthropic request -> Gemini request ----

SCHEMA_KEYS = {'type', 'description', 'properties', 'required', 'items', 'enum', 'nullable', 'anyOf', 'format',
               'minimum', 'maximum', 'minItems', 'maxItems', 'minLength', 'maxLength', 'pattern', 'title'}


def schema(s):
    """A JSON Schema as Gemini's function declarations take it (an OpenAPI subset)."""
    if isinstance(s, list):
        return [schema(x) for x in s]
    if not isinstance(s, dict):
        return s
    s = dict(s)
    if 'const' in s:
        s.setdefault('enum', [s.pop('const')])
    if 'oneOf' in s and 'anyOf' not in s:
        s['anyOf'] = s.pop('oneOf')
    t = s.get('type')
    if isinstance(t, list):
        kinds = [x for x in t if x != 'null']
        if len(kinds) < len(t):
            s['nullable'] = True
        s['type'] = kinds[0] if kinds else 'string'
    if 'enum' in s:
        s['enum'] = [str(x) for x in s['enum'] if x is not None]
        s['type'] = 'string'
    if s.get('format') not in (None, 'enum', 'date-time'):
        s.pop('format')
    out = {}
    for k, v in s.items():
        if k not in SCHEMA_KEYS:
            continue
        if k == 'properties' and isinstance(v, dict):
            out[k] = {name: schema(p) for name, p in v.items()}
        elif k in ('items',):
            out[k] = schema(v)
        elif k == 'anyOf':
            out[k] = [schema(x) for x in v]
        else:
            out[k] = v
    if 'required' in out and 'properties' in out:
        out['required'] = [r for r in out['required'] if r in out['properties']]
    if out.get('type') == 'object' and not out.get('properties'):
        out.pop('required', None)
    return out


def block_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return '\n'.join(b.get('text', '') for b in content if isinstance(b, dict) and b.get('type') == 'text')
    return ''


def media_part(block):
    src = block.get('source') or {}
    if src.get('type') == 'base64':
        return {'inlineData': {'mimeType': src.get('media_type') or 'application/octet-stream', 'data': src.get('data', '')}}
    if src.get('type') == 'url':
        return {'text': '[%s: %s]' % (block.get('type'), src.get('url'))}
    if src.get('type') == 'text':
        return {'text': src.get('data', '')}
    return None


def to_gemini(req):
    names = {}  # tool_use id -> tool name, for the results
    contents = []

    def add(role, parts):
        if not parts:
            return
        if contents and contents[-1]['role'] == role:
            contents[-1]['parts'].extend(parts)
        else:
            contents.append({'role': role, 'parts': parts})

    for msg in req.get('messages') or []:
        role = 'model' if msg.get('role') == 'assistant' else 'user'
        content = msg.get('content')
        if isinstance(content, str):
            add(role, [{'text': content}] if content else [])
            continue
        parts, after = [], []
        first_call = True
        for b in content or []:
            if not isinstance(b, dict):
                continue
            kind = b.get('type')
            if kind == 'text':
                if b.get('text'):
                    parts.append({'text': b['text']})
            elif kind in ('image', 'document'):
                p = media_part(b)
                if p:
                    parts.append(p)
            elif kind == 'tool_use':
                names[b.get('id')] = b.get('name')
                call = {'functionCall': {'name': b.get('name'), 'args': b.get('input') or {}, 'id': b.get('id')}}
                if first_call:
                    call['thoughtSignature'] = signatures.get(b.get('id'), SKIP_SIGNATURE)
                    first_call = False
                parts.append(call)
            elif kind == 'tool_result':
                inner = b.get('content')
                text = block_text(inner)
                if isinstance(inner, list):
                    for x in inner:
                        if isinstance(x, dict) and x.get('type') in ('image', 'document'):
                            p = media_part(x)
                            if p:
                                after.append(p)
                key = 'error' if b.get('is_error') else 'output'
                parts.append({'functionResponse': {'name': names.get(b.get('tool_use_id')) or 'tool',
                                                   'id': b.get('tool_use_id'), 'response': {key: text}}})
            # thinking and redacted_thinking: Gemini keeps its own (the signatures above)
        add(role, parts + after)

    system = req.get('system')
    system = block_text(system) if not isinstance(system, str) else system
    out = {'contents': contents}
    if system:
        out['systemInstruction'] = {'role': 'user', 'parts': [{'text': system}]}
    tools = [t for t in req.get('tools') or [] if isinstance(t, dict) and t.get('name') and 'input_schema' in t]
    if tools:
        out['tools'] = [{'functionDeclarations': [
            {'name': t['name'], 'description': t.get('description', ''), 'parameters': schema(t['input_schema'])}
            for t in tools]}]
        choice = req.get('tool_choice') or {}
        mode = {'any': 'ANY', 'tool': 'ANY', 'none': 'NONE'}.get(choice.get('type'), 'AUTO')
        cfg = {'mode': mode}
        if choice.get('type') == 'tool' and choice.get('name'):
            cfg['allowedFunctionNames'] = [choice['name']]
        out['toolConfig'] = {'functionCallingConfig': cfg}
    gen = {'maxOutputTokens': req.get('max_tokens') or 8192}
    for a, b in (('temperature', 'temperature'), ('top_p', 'topP'), ('top_k', 'topK')):
        if req.get(a) is not None:
            gen[b] = req[a]
    if req.get('stop_sequences'):
        gen['stopSequences'] = req['stop_sequences']
    if (req.get('thinking') or {}).get('type') in ('enabled', 'adaptive'):
        gen['thinkingConfig'] = {'includeThoughts': True}
    out['generationConfig'] = gen
    user = str((req.get('metadata') or {}).get('user_id') or '')
    out['sessionId'] = str(-(zlib.crc32(user.encode()) * 2654435761 % (1 << 62))) if user else str(uuid.uuid4().int % (1 << 62))
    return out


# ---- Gemini stream -> Anthropic stream ----

class Stream:
    """Turns Gemini's streamed chunks into Anthropic's events, written by emit(event, data)."""

    def __init__(self, emit, model):
        self.emit = emit
        self.model = model
        self.index = -1
        self.open = None  # 'text', 'thinking' or None
        self.started = False
        self.calls = 0
        self.usage = {'input_tokens': 0, 'output_tokens': 0}
        self.finish = None
        self.text = []  # for a reply asked without a stream
        self.blocks = []

    def start(self):
        if not self.started:
            self.started = True
            self.emit('message_start', {'type': 'message_start', 'message': {
                'id': 'msg_agy_' + uuid.uuid4().hex[:24], 'type': 'message', 'role': 'assistant',
                'model': self.model, 'content': [], 'stop_reason': None, 'stop_sequence': None,
                'usage': dict(self.usage)}})

    def close(self):
        if self.open:
            if self.open == 'thinking':
                self.emit('content_block_delta', {'type': 'content_block_delta', 'index': self.index,
                                                  'delta': {'type': 'signature_delta', 'signature': 'agy'}})
            self.emit('content_block_stop', {'type': 'content_block_stop', 'index': self.index})
            self.open = None

    def begin(self, block):
        self.close()
        self.index += 1
        self.blocks.append(block)
        self.emit('content_block_start', {'type': 'content_block_start', 'index': self.index, 'content_block': block})

    def chunk(self, data):
        resp = data.get('response', data)
        u = resp.get('usageMetadata') or {}
        if u:
            cached = u.get('cachedContentTokenCount') or 0
            self.usage = {'input_tokens': max(0, (u.get('promptTokenCount') or 0) - cached),
                          'cache_read_input_tokens': cached, 'cache_creation_input_tokens': 0,
                          'output_tokens': (u.get('candidatesTokenCount') or 0) + (u.get('thoughtsTokenCount') or 0)}
        self.start()
        for cand in resp.get('candidates') or []:
            for part in (cand.get('content') or {}).get('parts') or []:
                self.part(part)
            if cand.get('finishReason'):
                self.finish = cand['finishReason']

    def part(self, part):
        if 'functionCall' in part:
            fc = part['functionCall']
            tid = fc.get('id') or 'toolu_agy_' + uuid.uuid4().hex[:20]
            if part.get('thoughtSignature'):
                signatures[tid] = part['thoughtSignature']
                if len(signatures) > 5000:
                    for k in list(signatures)[:1000]:
                        signatures.pop(k, None)
            args = fc.get('args') or {}
            self.begin({'type': 'tool_use', 'id': tid, 'name': fc.get('name'), 'input': {}})
            self.blocks[-1]['input'] = args
            self.emit('content_block_delta', {'type': 'content_block_delta', 'index': self.index,
                                              'delta': {'type': 'input_json_delta', 'partial_json': json.dumps(args, ensure_ascii=False)}})
            self.emit('content_block_stop', {'type': 'content_block_stop', 'index': self.index})
            self.calls += 1
            return
        text = part.get('text')
        if not text:
            return
        kind = 'thinking' if part.get('thought') else 'text'
        if self.open != kind:
            self.begin({'type': kind, kind: ''} if kind == 'text' else {'type': 'thinking', 'thinking': '', 'signature': ''})
            self.open = kind
        self.blocks[-1][kind] += text
        delta = {'type': 'text_delta', 'text': text} if kind == 'text' else {'type': 'thinking_delta', 'thinking': text}
        self.emit('content_block_delta', {'type': 'content_block_delta', 'index': self.index, 'delta': delta})

    def stop_reason(self):
        if self.calls:
            return 'tool_use'
        return {'MAX_TOKENS': 'max_tokens'}.get(self.finish, 'end_turn')

    def end(self):
        self.start()
        self.close()
        self.emit('message_delta', {'type': 'message_delta', 'delta': {'stop_reason': self.stop_reason(), 'stop_sequence': None},
                                    'usage': self.usage})
        self.emit('message_stop', {'type': 'message_stop'})

    def message(self):
        for b in self.blocks:
            if b['type'] == 'thinking':
                b['signature'] = 'agy'
        return {'id': 'msg_agy_' + uuid.uuid4().hex[:24], 'type': 'message', 'role': 'assistant', 'model': self.model,
                'content': self.blocks, 'stop_reason': self.stop_reason(), 'stop_sequence': None, 'usage': self.usage}


ERROR_TYPES = {400: 'invalid_request_error', 401: 'authentication_error', 403: 'permission_error', 404: 'not_found_error',
               413: 'request_too_large', 429: 'rate_limit_error', 529: 'overloaded_error'}


def error_body(status, message):
    return {'type': 'error', 'error': {'type': ERROR_TYPES.get(status, 'api_error'), 'message': message}}


def upstream_message(raw):
    try:
        e = json.loads(raw)
        e = e[0] if isinstance(e, list) and e else e
        return str((e.get('error') or {}).get('message') or raw[:500])
    except (ValueError, AttributeError):
        return raw[:500].decode(errors='replace') if isinstance(raw, bytes) else str(raw)[:500]


# ---- HTTP ----

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    prefix = ''

    def log_message(self, *a):
        pass

    def do_GET(self):
        self.route()

    def do_POST(self):
        self.route()

    def route(self):
        self.began = False  # whether the reply's headers are out: one under way cannot begin again as an error
        if not self.path.startswith(self.prefix + '/'):
            self.send_error(404)
            return
        path = self.path[len(self.prefix):]
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else b''
        req = None
        if self.command == 'POST' and path.split('?')[0] in ('/v1/messages', '/v1/messages/count_tokens'):
            try:
                req = json.loads(body)
            except ValueError:
                req = None
        if isinstance(req, dict) and is_agy(req.get('model')):
            try:
                if path.startswith('/v1/messages/count_tokens'):
                    self.reply_json(200, {'input_tokens': len(body) // 4})
                else:
                    self.messages(req)
            except (BrokenPipeError, ConnectionResetError):
                # The client left, or the upstream was reset under a stream: either way the reply is over, and
                # the connection must end with it (kept, it would leave a client waiting for the rest).
                self.close_connection = True
            except Exception as e:  # noqa: BLE001 - told to Claude Code as an API error
                log('agproxy: %s: %r' % (req.get('model'), e))
                try:
                    if self.began:
                        self.stream_error(500, 'agproxy: %r' % e)
                    else:
                        self.reply_json(500, error_body(500, 'agproxy: %r' % e))
                except OSError:
                    pass
            return
        self.forward(path, body)

    def reply_json(self, status, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def stream_error(self, status, message):
        """A stream under way ends with an error event (Anthropic's own way), and its connection is not
        kept: a JSON reply written into it would be read as a chunk."""
        self.close_connection = True
        self.chunked(('event: error\ndata: %s\n\n' % json.dumps(error_body(status, message), ensure_ascii=False)).encode())
        self.chunked(b'')

    def messages(self, req):
        model = req['model'].strip()
        token = access_token()
        body = {'project': get_project(token), 'requestId': 'agent/' + str(uuid.uuid4()), 'request': to_gemini(req),
                'model': model, 'userAgent': 'antigravity', 'requestType': 'agent'}
        began = time.time()
        conn, resp = code_assist('streamGenerateContent?alt=sse', body, token)
        if resp.status == 401:
            conn.close()
            token = access_token(force=True)
            conn, resp = code_assist('streamGenerateContent?alt=sse', body, token)
        with contextlib.closing(conn):
            if resp.status != 200:
                raw = resp.read()
                msg = upstream_message(raw)
                log('agproxy: %s: HTTP %d %s' % (model, resp.status, msg[:300]))
                status = resp.status if resp.status in (400, 404, 413, 429) else 529 if resp.status == 503 else \
                    400 if resp.status in (401, 403) else 500
                self.reply_json(status, error_body(status, 'Antigravity: ' + msg))
                return
            streaming = bool(req.get('stream'))
            if streaming:
                self.send_response(200)
                self.send_header('Content-Type', 'text/event-stream')
                self.send_header('Cache-Control', 'no-cache')
                self.send_header('Transfer-Encoding', 'chunked')
                self.end_headers()
                self.began = True

            def emit(event, data):
                if streaming:
                    self.chunked(('event: %s\ndata: %s\n\n' % (event, json.dumps(data, ensure_ascii=False))).encode())

            out = Stream(emit, model)
            for line in resp:
                line = line.strip()
                if not line.startswith(b'data:'):
                    continue
                try:
                    data = json.loads(line[5:])
                except ValueError:
                    continue
                out.chunk(data)
            out.end()
            log('agproxy: %s: %.1fs, %s in, %s out, %s' % (model, time.time() - began, out.usage.get('input_tokens'),
                                                           out.usage.get('output_tokens'), out.stop_reason()))
            if streaming:
                self.chunked(b'')
            else:
                self.reply_json(200, out.message())

    def chunked(self, data):
        self.wfile.write(b'%x\r\n%s\r\n' % (len(data), data))
        self.wfile.flush()

    def forward(self, path, body):
        """To Anthropic as it came; the answer streamed back as it comes."""
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in ('host', 'connection', 'content-length', 'transfer-encoding', 'accept-encoding')}
        headers['Accept-Encoding'] = 'identity'
        conn = http.client.HTTPSConnection(ANTHROPIC, timeout=600)
        try:
            conn.request(self.command, path, body if self.command == 'POST' else None, headers)
            resp = conn.getresponse()
            self.send_response(resp.status)
            for k, v in resp.getheaders():
                if k.lower() not in ('connection', 'content-length', 'transfer-encoding', 'content-encoding'):
                    self.send_header(k, v)
            self.send_header('Transfer-Encoding', 'chunked')
            self.end_headers()
            self.began = True
            while True:
                data = resp.read1(65536)
                if not data:
                    break
                self.chunked(data)
            self.chunked(b'')
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True  # as in route: the client left, or the upstream was reset under the body
        except (OSError, http.client.HTTPException) as e:
            log('agproxy: to Anthropic %s: %r' % (path, e))
            if self.began:
                self.close_connection = True  # cut short: the client sees the body end without its last chunk
            else:
                try:
                    self.reply_json(502, error_body(529, 'agproxy: %r' % e))
                except OSError:
                    pass
        finally:
            conn.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True


base_url = None


def start(logger=None):
    """Starts the endpoint in a thread of this process; its base URL for ANTHROPIC_BASE_URL."""
    global log, base_url
    if logger:
        log = logger
    prefix = '/' + secrets.token_urlsafe(18)
    handler = type('H', (Handler,), {'prefix': prefix})
    server = Server(('127.0.0.1', 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Thread(target=find_models, daemon=True).start()
    base_url = 'http://127.0.0.1:%d%s' % (server.server_address[1], prefix)
    return base_url


if __name__ == '__main__':
    import sys
    print(start(lambda t: print(t, file=sys.stderr, flush=True)), flush=True)
    threading.Event().wait()
