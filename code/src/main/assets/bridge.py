"""Claude Code bridge for the Claude Code app.

Runs inside the app's own Linux userland (Alpine under proot, started by the app's foreground
service) and lets the app talk to Claude Code: each conversation is a `claude -p` process in stream-json mode, kept
alive between turns and resumed from its saved session when needed. Listens on a Unix socket in
the app's own files (SOCKET_FILE): no other app can reach it, so requests need no token.

Standard library only.
"""
import argparse
import base64
import collections
import json
import mmap
import os
import re
import shlex
import shutil
import signal
import socketserver
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

VERSION = 51  # Bridge.VERSION in the app: an app update replaces an older running bridge
HOME = os.path.expanduser('~')
CLAUDE_DIR = os.path.join(HOME, '.claude')
PROJECTS = os.path.join(CLAUDE_DIR, 'projects')
STATE_DIR = os.path.join(HOME, '.ccapp')
PID_FILE = os.path.join(STATE_DIR, 'bridge.pid')
LOCK_FILE = os.path.join(STATE_DIR, 'bridge.lock')
SOCKET_FILE = os.path.join(STATE_DIR, 'bridge.sock')  # Bridge.SOCKET in the app
DEFAULT_CWD = os.path.join(HOME, 'work')
LOG_FILE = os.path.join(STATE_DIR, 'bridge.log')
LAUNCH_FILE = os.path.join(STATE_DIR, 'launch.json')
MCP_FILE = os.path.join(STATE_DIR, 'mcp.json')  # MCP servers added in the app's settings
SPARE_FILE = os.path.join(STATE_DIR, 'spare.json')  # how the last new conversation was started
TZ_FILE = os.path.join(STATE_DIR, 'timezone')  # the phone's time zone, kept by the app (BridgeService)
STARTED = time.time()  # tells the app a new bridge took over (turns of the old one are gone)
# Idle this long, a claude's connections to the API may be gone (dropped by the phone's network, or
# all was frozen) with nothing telling it: a request sent on one gets no answer until Claude Code's
# timeout, and its retry may get another such one. It is started again first (Chat.refresh), unless
# the app's setting turns that off (restart false in /open and /send).
STALE_SECONDS = 3 * 60
# Without that restart (or when a connection is dropped sooner), a request on a dead connection has
# no response headers: Claude Code gives up on it after CLAUDE_STREAM_FIRST_BYTE_TIMEOUT_MS (its own
# default three minutes, more with a large body) and retries once on a new connection, waiting long
# then. The API's headers came within 20 s for every request in the logs, cache expired or not.
FIRST_BYTE_MS = '30000'
SHORT_TITLE = 16  # characters: a first message this short is its conversation's name until it has one
IDLE_SECONDS = 15 * 60  # an idle conversation's process is stopped; it resumes on the next message


class Hub:
    """Numbered events for the app; a reconnecting client asks for those after its last one."""

    def __init__(self, keep=4000):
        self.events = []
        self.seq = 0
        self.keep = keep
        self.cond = threading.Condition()

    def emit(self, chat, kind, data=None):
        with self.cond:
            self.seq += 1
            self.events.append({'seq': self.seq, 'chat': chat, 'kind': kind, 'data': data})
            if len(self.events) > self.keep:
                del self.events[:len(self.events) - self.keep]
            self.cond.notify_all()

    def oldest(self):
        """The first event still kept; seq + 1 when none is."""
        with self.cond:
            return self.events[0]['seq'] if self.events else self.seq + 1

    def after(self, seq, timeout):
        with self.cond:
            if self.seq <= seq:
                self.cond.wait(timeout)
            if not self.events:
                return []
            # Numbers follow one another: the first one after seq is found by its place.
            return self.events[max(0, seq + 1 - self.events[0]['seq']):]


hub = Hub()


class Launch:
    """How Claude Code is started, set in the app: permission mode, text appended to the system
    prompt, extra arguments, tools left out, and the MCP servers in MCP_FILE. A change (posting
    to /launch, even nothing) restarts idle conversations on their next message."""

    def __init__(self):
        self.gen = 0
        self.data = {'permissionMode': 'bypassPermissions', 'appendSystemPrompt': '', 'extraArgs': '',
                     'disallowedTools': ''}
        try:
            with open(LAUNCH_FILE, encoding='utf-8') as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass

    def set(self, data):
        new = dict(self.data)
        for key in ('permissionMode', 'appendSystemPrompt', 'extraArgs', 'disallowedTools'):
            if key in data:
                new[key] = str(data[key] or '')
        if choices.get('permission-mode') and new['permissionMode'] not in choices['permission-mode']:
            raise ValueError('unknown permission mode %s' % new['permissionMode'])
        shlex.split(new['extraArgs'])  # ValueError on bad quoting
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(LAUNCH_FILE, 'w', encoding='utf-8') as f:
            json.dump(new, f, ensure_ascii=False, indent=1)
        self.data = new
        self.gen += 1

    def args(self):
        args = ['--permission-mode', self.data['permissionMode']]
        if self.data['appendSystemPrompt'].strip():
            args += ['--append-system-prompt', self.data['appendSystemPrompt']]
        tools = self.data.get('disallowedTools', '').replace(',', ' ').split()
        if tools:
            args += ['--disallowedTools', ','.join(tools)]
        try:
            with open(MCP_FILE, encoding='utf-8') as f:
                if json.load(f).get('mcpServers'):
                    args += ['--mcp-config', MCP_FILE]
        except (OSError, ValueError, AttributeError):
            pass
        args += [os.path.expanduser(a) for a in shlex.split(self.data['extraArgs'])]
        return args


launch = Launch()


def memory_path(scope, cwd=None):
    """CLAUDE.md the app edits: the user's own (every conversation) or a working folder's."""
    if scope == 'global':
        return os.path.join(CLAUDE_DIR, 'CLAUDE.md')
    if scope == 'folder' and cwd:
        return os.path.join(os.path.expanduser(cwd), 'CLAUDE.md')
    raise ValueError('unknown scope')


def claude_path():
    return shutil.which('claude') or os.path.join(os.environ.get('PREFIX', '/usr'), 'bin', 'claude')


def claude_env():
    """A claude's environment: the bridge's, with the wait for the API's first byte (FIRST_BYTE_MS,
    unless set) and the phone's time zone in TZ. The userland has no /etc/localtime, and with
    neither Claude Code reads every file under /usr/share/zoneinfo as it starts, its main thread
    held 0.5 to 2.5 s by proot. No TZ when the app has told no zone the userland has."""
    env = dict(os.environ)
    env.setdefault('CLAUDE_STREAM_FIRST_BYTE_TIMEOUT_MS', FIRST_BYTE_MS)
    try:
        with open(TZ_FILE, encoding='utf-8') as f:
            tz = f.read().strip()
    except OSError:
        return env
    if re.fullmatch(r'[\w+-]+(/[\w+-]+)*', tz, re.ASCII) and os.path.isfile('/usr/share/zoneinfo/' + tz):
        env['TZ'] = tz
    return env


def claude_args(session_id, model, effort):
    # Questions whether a tool may run come here (the app answers them); in a mode that asks
    # nothing, there are none. Thinking comes summarized: with -p, Claude Code leaves the display to
    # the API, which sends newer models' thinking empty (showThinkingSummaries is read only in a
    # terminal session).
    args = [claude_path(), '-p', '--input-format', 'stream-json', '--output-format', 'stream-json',
            '--verbose', '--include-partial-messages', '--permission-prompt-tool', 'stdio',
            '--thinking-display', 'summarized']
    if session_id:
        args += ['--resume', session_id]
    if model:
        args += ['--model', model]
    if effort:
        # Through --settings: CLAUDE_CODE_EFFORT_LEVEL in the user's settings would otherwise
        # override both --effort and /effort.
        args += ['--settings', json.dumps({'env': {'CLAUDE_CODE_EFFORT_LEVEL': effort}})]
    return args + launch.args()


last_spawn = 0.0  # when a conversation's claude was last started


def spawn(args, cwd):
    global last_spawn
    last_spawn = time.time()
    os.makedirs(cwd, exist_ok=True)
    return subprocess.Popen(args, cwd=cwd, env=claude_env(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='replace',
                            bufsize=1, start_new_session=True)


class Spare:
    """A claude started ahead for the next new conversation: starting one is most of the wait for
    a first reply (seconds on a phone, where every process runs under proot). Started only while
    no turn runs; its output waits in the pipe until a conversation takes it and reads it."""

    def __init__(self):
        self.lock = threading.Lock()
        self.proc = None
        self.key = None
        # Folder, model and effort of the last new conversation (kept for the next bridge): the spare is
        # started with them, as switching a running claude to a model it does not know (another
        # provider's) costs about a second when the message comes.
        self.want = (DEFAULT_CWD, None, None)
        try:
            with open(SPARE_FILE, encoding='utf-8') as f:
                saved = json.load(f)
            if isinstance(saved, list) and len(saved) == 3 and all(v is None or isinstance(v, str) for v in saved):
                self.want = (saved[0] or DEFAULT_CWD, saved[1], saved[2])
        except (OSError, ValueError):
            pass
        self.retry_at = 0.0
        self.failures = 0
        self.started = 0.0

    def wish(self, cwd, model, effort):
        """The next spare is started for a new conversation like this one."""
        want = (cwd or DEFAULT_CWD, model, effort)
        if want == self.want:
            return
        self.want = want
        try:
            os.makedirs(STATE_DIR, exist_ok=True)
            with open(SPARE_FILE, 'w', encoding='utf-8') as f:
                json.dump(list(want), f)
        except OSError:
            pass

    def take(self, cwd, model, effort):
        """The spare's process and the model and effort it was started with, when it fits a new
        conversation in cwd (a different model or effort is changed in it); None otherwise."""
        with self.lock:
            proc, key, self.proc = self.proc, self.key, None
        if proc is None:
            return None
        if key[0] == cwd and key[3] == launch.gen and not (effort is None and key[2] is not None) \
                and proc.poll() is None:
            self.failures = 0
            return proc, key[1], key[2]
        threading.Thread(target=end, args=(proc,), daemon=True).start()
        return None

    def fill(self, fresh=False):
        """Starts the spare unless one fits; fresh: also when it was started STALE_SECONDS ago
        (with a subscription, Claude Code talks to Anthropic when it starts)."""
        cwd, model, effort = self.want
        key = (cwd, model, effort, launch.gen)
        with self.lock:
            if self.proc is not None:
                if self.proc.poll() is None and self.key == key and \
                        not (fresh and time.time() - self.started > STALE_SECONDS):
                    return
                old, self.proc = self.proc, None
                if old.poll() is not None:  # it ended by itself: wait longer each time
                    self.failures += 1
                    self.retry_at = time.time() + min(600, 30 * 2 ** self.failures)
                    log('spare claude exited with %s' % old.returncode)
                else:
                    threading.Thread(target=end, args=(old,), daemon=True).start()
            if time.time() < self.retry_at:
                return
            try:
                self.proc = spawn(claude_args(None, model, effort), cwd)
            except OSError as e:
                self.failures += 1
                self.retry_at = time.time() + min(600, 30 * 2 ** self.failures)
                log('spare claude not started: %s' % e)
                return
            self.key = key
            self.started = time.time()
            pid = self.proc.pid
        log('spare claude pid %d started for a new conversation in %s' % (pid, cwd))


spare = Spare()


def low(args):
    """args run at low priority, through nice when there is one: preexec_fn is not safe in a
    process with threads."""
    nice = shutil.which('nice')
    return [nice, '-n', '15'] + args if nice else args


class Chat:
    def __init__(self, key):
        self.key = key
        self.session_id = None
        self.cwd = DEFAULT_CWD
        self.model = None
        self.effort = None
        self.proc = None
        self.running = False
        self.used = time.time()
        self.stderr = []
        self.lock = threading.Lock()
        self.stopping = None
        self.launch_gen = None
        self.last_out = time.time()
        self.quiet_logged = False
        self.turn = 0  # counts messages sent: a forced stop is for the turn it was asked in
        self.began = 0  # counts turns begun (init), also those Claude Code starts for an inserted message
        self.asks = {}  # request id -> Claude Code's question whether a tool may run, not answered yet
        self.titling = False  # a name is being made from its first message
        self.write_lock = threading.Lock()
        self.waiting = {}  # request id -> {'event': ..., 'response': ...}: control requests of ours
        self.taken = False  # its claude was the spare, started ahead
        # The turn's wait for its reply to begin (logged by reply_timing): when its message came,
        # how its claude was got, and when (from then) the bridge wrote it, Claude Code took it up
        # (init) and the API began to answer, with the context the API read.
        self.sent_at = None
        self.sent_how = ''
        self.ready_at = self.init_at = self.api_at = None
        self.api_usage = {}

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def start(self):
        self.launch_gen = launch.gen
        self.stderr = []
        taken = None if self.session_id else spare.take(self.cwd, self.model, self.effort)
        proc = taken[0] if taken else spawn(claude_args(self.session_id, self.model, self.effort), self.cwd)
        self.proc = proc
        self.taken = bool(taken)
        if taken:
            self.switch(taken[1], taken[2])
        self.last_out = time.time()
        self.quiet_logged = False
        log('chat %s: claude pid %d %s, %s, model %s, effort %s, folder %s' % (
            self.key[:8], proc.pid, 'taken (started ahead)' if taken else 'started',
            'resuming ' + self.session_id if self.session_id else 'new session',
            self.model or 'default', self.effort or 'default', self.cwd))
        threading.Thread(target=self.read_out, args=(proc,), daemon=True).start()
        threading.Thread(target=self.read_err, args=(proc,), daemon=True).start()

    def read_out(self, proc):
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            self.last_out = time.time()
            if self.quiet_logged:
                log('chat %s: output again' % self.key[:8])
                self.quiet_logged = False
            if msg.get('session_id'):
                self.session_id = msg['session_id']
            kind, sub = msg.get('type'), msg.get('subtype')
            if self.sent_at is not None and proc is self.proc:
                self.reply_timing(msg, kind, sub)
            if kind == 'system' and sub == 'init' and proc is self.proc:
                # A turn begins: also one Claude Code starts by itself for a message inserted too late
                # for the turn before (Chat.send while running), which may be stopped like any.
                self.began += 1
                self.running = True
            if kind == 'control_request' and (msg.get('request') or {}).get('subtype') == 'can_use_tool':
                self.asks[msg.get('request_id')] = msg
                log('chat %s: asks to run %s' % (self.key[:8], msg['request'].get('tool_name')))
            elif kind == 'control_cancel_request':
                self.asks.pop(msg.get('request_id'), None)
            elif kind == 'control_response' and (msg.get('response') or {}).get('request_id') in self.waiting:
                slot = self.waiting.get(msg['response']['request_id'])
                if slot:
                    slot['response'] = msg['response']
                    slot['event'].set()
            elif kind == 'control_response' and (msg.get('response') or {}).get('subtype') == 'error' \
                    and str((msg.get('response') or {}).get('request_id')).startswith('set-'):
                log('chat %s: change refused: %s' % (self.key[:8], msg['response'].get('error')))
                self.launch_gen = None  # started again with it on the next message
            if kind == 'system' and sub == 'api_retry':
                gone = msg.get('no_response') or {}  # no headers in time: a dead connection, mostly
                log('chat %s: API retry %s/%s in %sms: %s %s%s' % (
                    self.key[:8], msg.get('attempt'), msg.get('max_retries'), msg.get('retry_delay_ms'),
                    msg.get('error_status') or '', msg.get('error') or '',
                    ' (no response in %.0fs, the retry waits %.0fs)' % (
                        gone.get('waited_ms', 0) / 1000, gone.get('retry_wait_ms', 0) / 1000) if gone else ''))
            elif kind == 'result':
                self.running = False
                self.used = time.time()
                log('chat %s: turn ended (%s%s), %.1fs, API %.1fs, %s turns' % (
                    self.key[:8], sub, ', error' if msg.get('is_error') else '',
                    (msg.get('duration_ms') or 0) / 1000, (msg.get('duration_api_ms') or 0) / 1000,
                    msg.get('num_turns')))
                if msg.get('is_error') and msg.get('result'):
                    log('chat %s: %s' % (self.key[:8], str(msg.get('result'))[:500]))
                if self.session_id and not msg.get('is_error') and not self.titling:
                    name_later(self.key, self.session_id)
            elif kind == 'assistant':
                err = msg.get('error')
                if err:
                    log('chat %s: assistant error %s' % (self.key[:8], err))
            hub.emit(self.key, 'cc', msg)
        code = proc.wait()
        log('chat %s: claude pid %d exited with %s%s' % (
            self.key[:8], proc.pid, code, ', stderr:\n  ' + '\n  '.join(self.stderr[-12:]) if self.stderr else ''))
        for slot in list(self.waiting.values()):
            slot['event'].set()
        if proc is self.proc:
            was_running = self.running
            self.running = False
            self.proc = None
            self.asks.clear()
            hub.emit(self.key, 'exit', {'code': code, 'running': was_running,
                                        'stderr': '\n'.join(self.stderr[-12:])})

    def read_err(self, proc):
        for line in proc.stderr:
            self.stderr.append(line.rstrip())
            del self.stderr[:-50]

    def send(self, content, cwd=None, model=None, session_id=None, effort=None, restart=True, message_uuid=None):
        came = time.time()
        with self.lock:
            before = self.proc if self.alive() else None
            # Written while a turn runs: Claude Code hands it to the model with its next request (or
            # starts a turn for it when this one ends); the turn's timing is left as it is.
            inserting = self.running and before is not None
            if session_id and not self.session_id:
                self.session_id = session_id
            if cwd and not self.alive():
                self.cwd = cwd
            if self.launch_gen != launch.gen or (effort is None and self.effort is not None):
                # Read only when it starts (an effort set can not be taken back but by starting again)
                self.model, self.effort = model, effort
                if self.alive() and not self.running:
                    self.close()  # restarts with the new settings, resuming the session
            else:
                old_model, old_effort = self.model, self.effort
                self.model, self.effort = model, effort
                if self.alive():  # changed in the running claude: no wait for one to start again
                    self.switch(old_model, old_effort)
            new = not self.session_id and not self.alive()
            if new:  # the next spare is started like this one
                spare.wish(self.cwd, model, effort)
            if restart:
                self.refresh()
            if not self.alive():
                self.start()
            if not inserting:
                self.sent_at, self.init_at, self.api_at, self.api_usage = came, None, None, {}
            self.sent_how = self.sent_how if inserting else ('running' if self.proc is before else 'started again' if before is not None
                             else 'taken (started ahead)' if self.taken else 'started')
            if new and not self.titling:
                name_new(self, content)  # alongside the first turn, not after it
            self.running = True
            self.turn += 1
            self.used = time.time()
            self.last_out = time.time()
            how = 'inserted' if inserting else 'sent'
            if isinstance(content, list):
                kinds = [b.get('type') for b in content if isinstance(b, dict)]
                log('chat %s: message %s (%d text, %d image)' % (self.key[:8], how, kinds.count('text'), kinds.count('image')))
            else:
                log('chat %s: message %s' % (self.key[:8], how))
            message = {'type': 'user', 'message': {'role': 'user', 'content': content}}
            if message_uuid:  # Claude Code tells its fate (command_lifecycle) and can take it back (Chat.cancel)
                message['uuid'] = message_uuid
            self.write(self.proc, message)
            self.ready_at = time.time() - came
            hub.emit(self.key, 'state', {'running': True})

    def reply_timing(self, msg, kind, sub):
        """Logs how long the turn's message waited for its reply to begin (its first block, thinking
        included), and where the time went: getting a claude, Claude Code until it took the message
        up, the API until it answered (longer with more context not read from its cache)."""
        at = time.time() - self.sent_at
        event = (msg.get('event') or {}) if kind == 'stream_event' else {}
        if kind == 'system' and sub == 'init' and self.init_at is None:
            self.init_at = at
        elif event.get('type') == 'message_start' and self.api_at is None:
            self.api_at = at
            self.api_usage = (event.get('message') or {}).get('usage') or {}
        elif event.get('type') == 'content_block_start' or kind in ('assistant', 'result'):
            u = self.api_usage
            mark = lambda t: '%.1fs' % t if t is not None else '-'
            log('chat %s: reply began after %.1fs (claude %s): message written %s, taken up %s, API answered %s; '
                'context %s read from cache, %s written to it, %s uncached' % (
                    self.key[:8], at, self.sent_how, mark(self.ready_at), mark(self.init_at), mark(self.api_at),
                    u.get('cache_read_input_tokens', '-'), u.get('cache_creation_input_tokens', '-'),
                    u.get('input_tokens', '-')))
            self.sent_at = None

    def switch(self, model, effort):
        """Changes the running claude, started with model and effort, to this chat's."""
        if model != self.model:
            self.control({'subtype': 'set_model', 'model': self.model} if self.model else {'subtype': 'set_model'})
        if effort != self.effort:
            self.apply_env()

    def apply_env(self):
        # Each env sent replaces the last one's: everything set this way goes in every time.
        env = {'CLAUDE_CODE_EFFORT_LEVEL': self.effort} if self.effort else {}
        self.control({'subtype': 'apply_flag_settings', 'settings': {'env': env}})

    def refresh(self):
        """Starts an idle claude again when it was idle so long its connections may be gone
        (STALE_SECONDS); resumed, with the session's history and cache. Called holding the lock."""
        if self.alive() and not self.running and self.session_id and time.time() - self.last_out > STALE_SECONDS:
            log('chat %s: idle %ds, starting its claude again' % (self.key[:8], time.time() - self.last_out))
            self.close()
            self.start()

    def write(self, proc, message):
        """One line to claude: the reading thread writes too (Chat.read_out)."""
        with self.write_lock:
            proc.stdin.write(json.dumps(message, ensure_ascii=False) + '\n')
            proc.stdin.flush()

    def control(self, request):
        self.write(self.proc, {'type': 'control_request', 'request_id': 'set-%s' % uuid.uuid4().hex[:12],
                                       'request': request})
        log('chat %s: %s %s' % (self.key[:8], request['subtype'], request.get('model') or
                                (request.get('settings') or {}).get('env') or 'default'))

    def ask_title(self, text):
        """A title from the running claude itself (Claude Code's own session naming: a request of
        its own with the small fast model, the conversation untouched). '' when it names nothing
        (it does not for a very short message), None when it could not be asked."""
        proc = self.proc
        if proc is None or proc.poll() is not None:
            return None
        rid = 'title-%s' % uuid.uuid4().hex[:12]
        slot = {'event': threading.Event()}
        self.waiting[rid] = slot
        try:
            self.write(proc, {'type': 'control_request', 'request_id': rid,
                              'request': {'subtype': 'generate_session_title', 'description': text, 'persist': False}})
            slot['event'].wait(120)
        except OSError:
            return None
        finally:
            self.waiting.pop(rid, None)
        response = slot.get('response') or {}
        if response.get('subtype') != 'success':
            log('chat %s: naming by claude failed: %s' % (self.key[:8], response.get('error') or 'no answer'))
            return None
        return ' '.join(str((response.get('response') or {}).get('title') or '').split())[:80]

    def cancel(self, message_uuid):
        """Takes back a message written while a turn ran, while Claude Code still holds it in its
        queue (the model has not got it): True when taken back, or when the claude that held it is
        gone; False when the model has it already (or it never came), None when there was no answer."""
        proc = self.proc
        if proc is None or proc.poll() is not None:
            return True
        rid = 'cancel-%s' % uuid.uuid4().hex[:12]
        slot = {'event': threading.Event()}
        self.waiting[rid] = slot
        try:
            self.write(proc, {'type': 'control_request', 'request_id': rid,
                              'request': {'subtype': 'cancel_async_message', 'message_uuid': message_uuid}})
            slot['event'].wait(10)
        except OSError:
            return True
        finally:
            self.waiting.pop(rid, None)
        response = slot.get('response')
        if response is None:
            return True if proc.poll() is not None else None
        cancelled = response.get('subtype') == 'success' and (response.get('response') or {}).get('cancelled') is True
        log('chat %s: queued message %s' % (self.key[:8], 'taken back' if cancelled else 'not taken back (the model has it)'))
        return cancelled

    def answer(self, request_id, allow, answers=None):
        """Answers Claude Code's question whether a tool may run; False when it is not asked any more.
        answers: for AskUserQuestion, what the user chose (question -> answer), given back with its input."""
        with self.lock:
            ask = self.asks.pop(request_id, None)
            if ask is None or not self.alive():
                return False
            if allow:
                updated = dict(ask['request'].get('input') or {})
                if answers:
                    updated['answers'] = answers
                response = {'behavior': 'allow', 'updatedInput': updated}
            else:  # what Claude Code itself tells the model when the user says no
                response = {'behavior': 'deny', 'message': "The user doesn't want to proceed with this tool use. The tool use "
                            "was rejected (eg. if it was a file edit, the new_string was NOT written to the file). STOP what "
                            "you are doing and wait for the user to tell you how to proceed."}
            self.write(self.proc, {'type': 'control_response', 'response': {'subtype': 'success', 'request_id': request_id,
                                                                             'response': response}})
            log('chat %s: %s %s' % (self.key[:8], 'allowed' if allow else 'denied', ask['request'].get('tool_name')))
            return True

    def stop(self):
        with self.lock:
            if not self.alive() or not self.running:
                return
            log('chat %s: stop requested' % self.key[:8])
            request = {'type': 'control_request', 'request_id': 'stop-%d' % time.time(),
                       'request': {'subtype': 'interrupt'}}
            try:
                self.write(self.proc, request)
            except OSError:
                pass
            proc, turn = self.proc, self.turn

        began = self.began

        def force():
            time.sleep(5)
            with self.lock:
                # Not a turn begun since (Claude Code's own, for an inserted message): only this one.
                if self.running and self.proc is proc and self.turn == turn and self.began == began:
                    self.close()
        threading.Thread(target=force, daemon=True).start()

    def close(self):
        end(self.proc)


def end(proc):
    """Ends a claude process: end of input, then SIGTERM, then SIGKILL; returns once it is gone,
    so a new one is never started (or written to) while the old one still runs."""
    if proc is None:
        return
    try:
        proc.stdin.close()
    except OSError:
        pass
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            proc.wait(3)
            return
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, sig)
            except OSError:
                proc.send_signal(sig)
    proc.wait()


chats = {}
chats_lock = threading.Lock()
seen_messages = []  # ids of messages already sent: the app may repeat a request whose answer it lost


def chat_for(key, session_id=None, create=True):
    """The conversation for the app's key. A session already running under another key (the app
    was restarted meanwhile) is taken over, so one session never has two processes. None when
    there is none and create is False."""
    with chats_lock:
        if key in chats:
            return chats[key]
        if session_id:
            for old_key, chat in list(chats.items()):
                if chat.session_id == session_id:
                    del chats[old_key]
                    chat.key = key
                    chats[key] = chat
                    return chat
        if not create:
            return None
        chats[key] = Chat(key)
        return chats[key]


def reaper():
    """Stops idle conversations, and logs a running turn with no output for a minute and the
    bridge itself having been paused (frozen by the system: it did not run for a while)."""
    tick = time.monotonic()
    while True:
        time.sleep(5)
        now = time.monotonic()
        if now - tick > 20:
            log('bridge was paused for %ds (frozen by the system?)' % (now - tick - 5))
        tick = now
        with chats_lock:
            live = list(chats.values())
        for chat in live:
            if chat.alive() and not chat.running and time.time() - chat.used > IDLE_SECONDS:
                with chat.lock:  # a message being sent meanwhile keeps it
                    if chat.alive() and not chat.running and time.time() - chat.used > IDLE_SECONDS:
                        log('chat %s: idle, stopping its claude' % chat.key[:8])
                        chat.close()
            if chat.running and not chat.quiet_logged and time.time() - chat.last_out > 60:
                chat.quiet_logged = True
                log('chat %s: no output from claude for 60s (alive: %s)' % (chat.key[:8], chat.alive()))
        # Not while a turn runs or a claude was just started: it would slow them down.
        if not any(chat.running for chat in live) and time.time() - last_spawn > 10:
            spare.fill()


# ---- Saved sessions (Claude Code's own transcripts) ----

def is_prompt_text(text):
    t = text.lstrip()
    return bool(t) and not t.startswith(('<system-reminder>', '<command-', '<local-command', '<bash-',
                                         '<user-memory', 'Caveat:', '[Request interrupted'))


def entry_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return '\n'.join(b.get('text', '') for b in content if isinstance(b, dict) and b.get('type') == 'text')
    return ''


SUMMARY_FILE = os.path.join(STATE_DIR, 'sessions.json')  # the summaries, kept for the next start
_summary_cache = None
_summary_changed = False
summary_lock = threading.Lock()  # held while the summaries are read, changed or saved


def summary_cache():
    """path -> (mtime, summary); read from SUMMARY_FILE on first use, so a new bridge does not read
    every transcript again. Those of another bridge version are read again: its titles may differ."""
    global _summary_cache
    if _summary_cache is None:
        _summary_cache = {}
        try:
            with open(SUMMARY_FILE, encoding='utf-8') as f:
                saved = json.load(f)
            if saved.get('version') == VERSION:
                _summary_cache = {p: tuple(v) for p, v in saved['sessions'].items()}
        except (OSError, ValueError, AttributeError, TypeError, KeyError):
            pass
    return _summary_cache


def save_summaries(paths):
    """Keeps the summaries of the transcripts still there, when any changed."""
    global _summary_changed
    cache = summary_cache()
    for gone in [p for p in cache if p not in paths]:
        del cache[gone]
        _summary_changed = True
    if not _summary_changed:
        return
    _summary_changed = False
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(SUMMARY_FILE + '.tmp', 'w', encoding='utf-8') as f:
            json.dump({'version': VERSION, 'sessions': cache}, f, ensure_ascii=False)
        os.replace(SUMMARY_FILE + '.tmp', SUMMARY_FILE)
    except OSError:
        pass


def last_custom_title(path):
    """The name a transcript was last given (/rename), found by a byte search instead of reading it."""
    try:
        with open(path, 'rb') as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as data:
            at = data.rfind(b'"customTitle"')
            if at < 0:
                return None
            end = data.find(b'\n', at)
            line = data[data.rfind(b'\n', 0, at) + 1:end if end >= 0 else len(data)]
        return json.loads(line.decode('utf-8', 'replace')).get('customTitle')
    except (OSError, ValueError, AttributeError):
        return None


TITLES_FILE = os.path.join(STATE_DIR, 'titles.json')
titles = {}  # session id -> a short name made for it (a name given with /rename comes first)
titles_lock = threading.Lock()
naming = set()
try:
    with open(TITLES_FILE, encoding='utf-8') as f:
        titles = {k: v for k, v in json.load(f).items() if isinstance(v, str)}
except (OSError, ValueError, AttributeError):
    pass


def first_prompt(path, limit=2000):
    """What the user first wrote in a transcript, to name it by."""
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if isinstance(e, dict) and e.get('type') == 'user' and not e.get('isMeta') and not e.get('isSidechain'):
                text = entry_text((e.get('message') or {}).get('content'))
                if is_prompt_text(text):
                    return text[:limit]
    return ''


def rename(session_id, title):
    """Names a conversation as /rename does: an entry in its transcript (Claude Code's /resume
    sees it too), the transcript's time kept so it does not move up the list."""
    path = find_session(session_id)
    if not path:
        return False
    st = os.stat(path)
    with open(path, 'a', encoding='utf-8') as f:
        f.write(json.dumps({'type': 'custom-title', 'customTitle': title, 'sessionId': session_id}, ensure_ascii=False) + '\n')
    os.utime(path, ns=(st.st_atime_ns, st.st_mtime_ns))
    with summary_lock:
        summary_cache().pop(path, None)  # same time, new name: read again
    return True


def keep_title(key, session_id, title):
    with titles_lock:
        titles[session_id] = title
        os.makedirs(STATE_DIR, exist_ok=True)
        with open(TITLES_FILE + '.tmp', 'w', encoding='utf-8') as f:
            json.dump(titles, f, ensure_ascii=False)
        os.replace(TITLES_FILE + '.tmp', TITLES_FILE)
    hub.emit(key, 'title', {'session_id': session_id, 'title': title})


def name_new(chat, content):
    """Names a new conversation from its first message while its first turn runs, by its own claude
    (Chat.ask_title)."""
    text = content if isinstance(content, str) else '\n'.join(
        b.get('text', '') for b in content if isinstance(b, dict) and b.get('type') == 'text')
    if not text.strip():
        return
    chat.titling = True

    def run():
        try:
            title = chat.ask_title(text[:2000])
            for _ in range(600):  # the session's id comes with its first output
                if chat.session_id:
                    break
                time.sleep(0.5)
            if title and chat.session_id:
                keep_title(chat.key, chat.session_id, title)
        finally:
            chat.titling = False
    threading.Thread(target=run, daemon=True).start()


def name_later(key, session_id):
    """Names a conversation after a turn when it has no name yet (its first turn did not give one),
    by its claude (Chat.ask_title); once."""
    with titles_lock:
        if session_id in titles or session_id in naming:
            return
        naming.add(session_id)

    def run():
        try:
            with chats_lock:
                chat = next((c for c in chats.values() if c.session_id == session_id), None)
            path = find_session(session_id)
            text = first_prompt(path) if path and chat else ''
            title = chat.ask_title(text) if text else None
            if title:
                keep_title(key, session_id, title)
        except OSError as e:
            log('naming %s failed: %s' % (session_id[:8], e))
        finally:
            with titles_lock:
                naming.discard(session_id)
    threading.Thread(target=run, daemon=True).start()

def last_said(path, tail=262_144):
    """When the conversation's last message was said (seconds since the epoch), from the end of its
    transcript; None when none is there. Not the file's time: Claude Code also appends bookkeeping
    (last-prompt, cost-state) when its process ends, with nothing said."""
    import datetime
    try:
        with open(path, 'rb') as f:
            f.seek(max(0, os.path.getsize(path) - tail))
            lines = f.read().split(b'\n')
    except OSError:
        return None
    for raw in reversed(lines):
        if b'"timestamp"' not in raw:
            continue
        try:
            e = json.loads(raw.decode('utf-8', 'replace'))
        except ValueError:
            continue
        if not isinstance(e, dict) or e.get('type') not in ('user', 'assistant') or e.get('isMeta') or e.get('isSidechain'):
            continue
        try:
            return datetime.datetime.fromisoformat(str(e.get('timestamp')).replace('Z', '+00:00')).timestamp()
        except ValueError:
            continue
    return None


def session_summary(path):
    global _summary_changed
    st = os.stat(path)
    cached = summary_cache().get(path)
    if cached and cached[0] == st.st_mtime:
        return cached[1]
    title, cwd, custom = None, None, None
    with open(path, encoding='utf-8', errors='replace') as f:
        for n, line in enumerate(f):
            if '"customTitle"' in line or '"custom-title"' in line:
                try:
                    custom = json.loads(line).get('customTitle') or custom
                except ValueError:
                    pass
            if title and cwd:
                if n > 400 and custom is None and st.st_size > 2_000_000:
                    custom = last_custom_title(path)  # a long one is read only at its start
                    break
                continue
            try:
                e = json.loads(line)
            except ValueError:
                continue
            cwd = cwd or e.get('cwd')
            if not title and e.get('type') == 'user' and not e.get('isMeta') and not e.get('isSidechain'):
                text = entry_text((e.get('message') or {}).get('content'))
                if is_prompt_text(text):
                    title = ' '.join(text.split())[:80]
    info = None
    if title:
        info = {'id': os.path.basename(path)[:-6], 'title': custom or title, 'named': bool(custom), 'cwd': cwd or '',
                'mtime': last_said(path) or st.st_mtime, 'size': st.st_size}
    summary_cache()[path] = (st.st_mtime, info)
    _summary_changed = True
    return info


def session_files():
    if not os.path.isdir(PROJECTS):
        return []
    out = []
    for project in os.listdir(PROJECTS):
        d = os.path.join(PROJECTS, project)
        if not os.path.isdir(d):
            continue
        for name in os.listdir(d):
            if name.endswith('.jsonl'):
                out.append(os.path.join(d, name))
    return out


def find_session(session_id):
    """A session's transcript, looked for in each project's folder (not by listing every one: each
    picture of a conversation shown asks for it)."""
    if not session_id or '/' in session_id or not os.path.isdir(PROJECTS):
        return None
    for project in os.listdir(PROJECTS):
        path = os.path.join(PROJECTS, project, session_id + '.jsonl')
        if os.path.isfile(path):
            return path
    return None


def list_sessions():
    items = []
    with summary_lock:
        paths = session_files()
        for path in paths:
            try:
                info = session_summary(path)
            except OSError:
                continue
            if info:
                item = dict(info)  # a copy: 'running' and names below stay out of the saved summaries
                if not item.pop('named', False):
                    # Not named: a made name, or the first message when it is short (Claude Code names
                    # none for those), otherwise none yet (the app shows a placeholder)
                    item['title'] = titles.get(item['id']) or (item['title'] if len(item['title']) <= SHORT_TITLE else '')
                items.append(item)
        save_summaries(set(paths))
    items.sort(key=lambda i: -i['mtime'])
    with chats_lock:
        live = list(chats.values())
    for chat in live:
        for item in items:
            if item['id'] == chat.session_id:
                item['running'] = chat.running
    return items[:300]


_text_cache = collections.OrderedDict()  # path -> (mtime, text, lower), the least recently searched first
TEXT_CACHE_CHARS = 16_000_000  # kept for searching again; more is read again when searched
text_lock = threading.Lock()


def session_text(path):
    """What was said in a session (the prompts and the replies' text) on one line, and the same in
    lower case, for search."""
    st = os.stat(path)
    with text_lock:
        cached = _text_cache.get(path)
        if cached and cached[0] == st.st_mtime:
            _text_cache.move_to_end(path)
            return cached[1], cached[2]
    parts = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if '"user"' not in line and '"assistant"' not in line and 'queued_command' not in line:
                continue
            try:
                e = json.loads(line)
            except ValueError:
                continue
            if not isinstance(e, dict) or e.get('isSidechain') or e.get('isMeta'):
                continue
            content = (e.get('message') or {}).get('content')
            if e.get('type') == 'attachment':
                parts.append(inserted_text(e))
            elif e.get('type') == 'user':
                text = entry_text(content)
                if is_prompt_text(text):
                    parts.append(text)
            elif e.get('type') == 'assistant' and isinstance(content, list):
                parts.extend(b.get('text', '') for b in content if isinstance(b, dict) and b.get('type') == 'text')
    text = ' '.join(' '.join(parts).split())
    lower = text.lower()
    with text_lock:
        _text_cache[path] = (st.st_mtime, text, lower)
        _text_cache.move_to_end(path)
        total = sum(len(t) + len(low) for _, t, low in _text_cache.values())
        while total > TEXT_CACHE_CHARS and len(_text_cache) > 1:
            _, t, low = _text_cache.popitem(last=False)[1]
            total -= len(t) + len(low)
    return text, lower


def search_sessions(query):
    """Sessions whose conversation contains the words, with the text around the first match."""
    q = ' '.join(query.lower().split())
    out = []
    if not q:
        return out
    paths = session_files()
    present = set(paths)
    with text_lock:
        for gone in [p for p in _text_cache if p not in present]:
            del _text_cache[gone]
    for path in paths:
        try:
            flat, lower = session_text(path)
        except OSError:
            continue
        i = lower.find(q)
        if i < 0:
            continue
        start = max(0, i - 16)
        out.append({'id': os.path.basename(path)[:-6],
                    'snippet': ('…' if start else '') + flat[start:i + len(q) + 60]})
    return out


def tool_result_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for b in content:
            if isinstance(b, dict):
                if b.get('type') == 'text':
                    parts.append(b.get('text', ''))
                elif b.get('type') == 'image':
                    parts.append('[图片]')
        return '\n'.join(parts)
    return ''


def inserted_text(entry):
    """A message the user sent while a turn ran, as the transcript keeps it (an attachment, queued
    command, given to the model with the next request); '' for any other entry."""
    a = entry.get('attachment') if isinstance(entry, dict) and entry.get('type') == 'attachment' else None
    if not isinstance(a, dict) or a.get('type') != 'queued_command' or a.get('commandMode', 'prompt') != 'prompt':
        return ''
    text = entry_text(a.get('prompt'))
    return text if is_prompt_text(text) else ''


def entry_images(entry):
    """The pictures in a transcript entry, in order, from its own blocks and its tool results:
    (media type, base64). The n-th is what a reference ccimg:<session>:<offset>:<n> names."""
    out = []
    content = (entry.get('message') or {}).get('content') if isinstance(entry, dict) else None
    for b in content if isinstance(content, list) else []:
        if not isinstance(b, dict):
            continue
        inner = b.get('content') if b.get('type') == 'tool_result' else [b]
        for x in inner if isinstance(inner, list) else []:
            src = x.get('source') if isinstance(x, dict) and x.get('type') == 'image' else None
            if isinstance(src, dict) and src.get('type') == 'base64' and src.get('data'):
                out.append((src.get('media_type') or 'image/png', src['data']))
    return out


def session_image(session_id, at, n):
    """A picture of a transcript by its reference: the entry at that offset, its n-th picture."""
    path = find_session(session_id) if re.match(r'^[\w-]+$', session_id or '') else None
    if not path:
        return None
    with open(path, 'rb') as f:
        f.seek(at)
        try:
            images = entry_images(json.loads(f.readline().decode('utf-8', 'replace')))
        except ValueError:
            return None
    return images[n] if 0 <= n < len(images) else None


def load_history(session_id):
    path = find_session(session_id)
    if not path:
        return None
    messages, tools, cwd, custom, skipped = [], {}, None, None, 0
    context, model = 0, None  # of the last reply: tokens it read, and its model
    with open(path, 'rb') as f:
        offset = 0
        for raw in f:
            at, offset = offset, offset + len(raw)
            try:
                e = json.loads(raw.decode('utf-8', 'replace'))
            except ValueError:
                continue
            # Pictures go as references, read when shown: a conversation of screenshots stays small.
            pictures = iter(range(1 << 30))

            def picture():
                return 'ccimg:%s:%d:%d' % (session_id, at, next(pictures))
            if not isinstance(e, dict):
                continue
            try:
                kind = e.get('type')
                if kind == 'custom-title' or e.get('customTitle'):
                    custom = e.get('customTitle') or custom
                if e.get('isSidechain') or e.get('isMeta'):
                    continue
                if kind == 'attachment':
                    text = inserted_text(e)
                    if text and not (messages and messages[-1]['role'] == 'user' and messages[-1]['text'] == text):
                        messages.append({'role': 'user', 'text': text, 'images': []})
                    continue
                cwd = e.get('cwd') or cwd
                if kind == 'system' and e.get('subtype') == 'compact_boundary':
                    messages.append({'role': 'divider', 'text': '上下文已压缩'})
                    continue
                msg = e.get('message') or {}
                content = msg.get('content')
                if kind == 'user':
                    if isinstance(content, str):
                        content = [{'type': 'text', 'text': content}]
                    if not isinstance(content, list):
                        continue
                    text_parts, images = [], []
                    for b in content:
                        if not isinstance(b, dict):
                            continue
                        if b.get('type') == 'tool_result':
                            tool = tools.get(b.get('tool_use_id'))
                            inner = b.get('content') if isinstance(b.get('content'), list) else []
                            shown = [picture() for x in inner if isinstance(x, dict) and x.get('type') == 'image'
                                     and isinstance(x.get('source'), dict) and x['source'].get('type') == 'base64'
                                     and x['source'].get('data')]
                            if tool is not None:
                                tool['result'] = tool_result_text(b.get('content'))[:20000]
                                tool['images'] = shown
                                tool['isError'] = bool(b.get('is_error'))
                                tool['status'] = 'error' if tool['isError'] else 'done'
                        elif b.get('type') == 'text' and is_prompt_text(b.get('text', '')):
                            text_parts.append(b['text'])
                        elif b.get('type') == 'image':
                            src = b.get('source') or {}
                            images.append(picture() if src.get('type') == 'base64' and src.get('data') else None)
                    text = '\n'.join(text_parts)
                    repeated = messages and messages[-1]['role'] == 'user' and messages[-1]['text'] == text and not images
                    if (text_parts or images) and not repeated:  # an inserted one may come as both
                        messages.append({'role': 'user', 'text': text, 'images': images})
                elif kind == 'assistant' and isinstance(content, list):
                    usage = msg.get('usage') or {}
                    if usage.get('input_tokens') is not None:
                        context = sum(usage.get(k) or 0 for k in
                                      ('input_tokens', 'cache_read_input_tokens', 'cache_creation_input_tokens'))
                    if msg.get('model') and not str(msg['model']).startswith('<'):
                        model = msg['model']
                    if not messages or messages[-1]['role'] != 'assistant':
                        messages.append({'role': 'assistant', 'blocks': []})
                    blocks = messages[-1]['blocks']
                    for b in content:
                        if not isinstance(b, dict):
                            continue
                        t = b.get('type')
                        if t == 'text' and b.get('text', '').strip():
                            blocks.append({'type': 'text', 'text': b['text']})
                        elif t == 'thinking' and b.get('thinking', '').strip():
                            blocks.append({'type': 'thinking', 'text': b['thinking']})
                        elif t == 'tool_use':
                            tool = {'type': 'tool', 'id': b.get('id'), 'name': b.get('name'),
                                    'input': b.get('input') or {}, 'status': 'done'}
                            tools[b.get('id')] = tool
                            blocks.append(tool)
            except Exception:  # an entry this bridge does not understand is left out
                if not skipped:
                    import traceback
                    log('history %s: skipped an entry\n%s' % (session_id, traceback.format_exc()))
                skipped += 1
    return {'id': session_id, 'cwd': cwd, 'title': custom or titles.get(session_id), 'messages': messages,
            'context': context, 'model': model}


claude_version = ''
choices = {}  # option -> the values Claude Code's --help lists for it, e.g. effort, permission-mode


def help_choices(text):
    """From --help: '--effort <level>  ... (low, medium, high)' -> {'effort': ['low', ...]}."""
    out, name, desc = {}, None, []

    def done():
        found = re.findall(r'\(([^()]*)\)', ' '.join(desc))
        if name and found:
            values = [v.strip().strip('"\'') for v in found[-1].replace('choices:', '').split(',')]
            if len(values) > 1 and all(re.match(r'^[\w-]+$', v) for v in values):
                out[name] = values

    for line in text.splitlines():
        m = re.match(r'^\s+(?:-\w, )?--([\w-]+)', line)
        if m:
            done()
            name, desc = m.group(1), [line]
        elif name:
            desc.append(line)
    done()
    return out


INFO_FILE = os.path.join(STATE_DIR, 'claude-info.json')


def find_claude_info():
    """Claude Code's version and the choices of its options, as it reports them. Asked once per
    Claude Code binary (kept in INFO_FILE), at low priority: it never slows a start."""
    global claude_version, choices
    try:
        path = claude_path()
        st = os.stat(path)
        stamp = '%d-%d' % (st.st_size, st.st_mtime)
        try:
            with open(INFO_FILE, encoding='utf-8') as f:
                saved = json.load(f)
            if saved.get('stamp') == stamp:
                claude_version, choices = saved.get('version', ''), saved.get('choices', {})
                return
        except (OSError, ValueError, AttributeError):
            pass

        def ask(arg):
            return subprocess.run(low([path, arg]), capture_output=True, text=True, timeout=120).stdout
        out = ask('--version')
        version = out.split()[0] if out.strip() else ''
        found = help_choices(ask('--help'))
        claude_version, choices = version, found
        if version:
            os.makedirs(STATE_DIR, exist_ok=True)
            with open(INFO_FILE, 'w', encoding='utf-8') as f:
                json.dump({'stamp': stamp, 'version': version, 'choices': found}, f)
    except (OSError, subprocess.SubprocessError):
        pass


SCHEMA_FILE = os.path.join(STATE_DIR, 'settings-schema.json')
schema_lock = threading.Lock()


def js_end(text, i):
    """Index of the ',' or closing bracket ending the JavaScript expression that starts at i."""
    depth, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch in '"\'`':
            i += 1
            while i < n and text[i] != ch:
                i += 2 if text[i] == '\\' else 1
        elif ch == '/' and text[i - 1] in '(,=:[!&|?{};':  # a regular expression
            i, inside = i + 1, False
            while i < n and (text[i] != '/' or inside):
                if text[i] == '\\':
                    i += 1
                elif text[i] in '[]':
                    inside = text[i] == '['
                i += 1
        elif ch in '([{':
            depth += 1
        elif ch in ')]}' or (ch == ',' and depth == 0):
            if depth == 0:
                return i
            depth -= 1
        i += 1
    return n


JS_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'|`(?:[^`\\]|\\.)*`', re.S)


def js_string(source):
    """The text of a JavaScript string literal, or of literals joined with +; None for anything else."""
    parts, at = [], 0
    while True:
        m = JS_LITERAL.match(source, at)
        if not m or (m.group(0)[0] == '`' and '${' in m.group(0)):
            return None
        parts.append(m.group(0)[1:-1])
        rest = re.match(r'\s*\+\s*', source[m.end():])
        if not rest:
            break
        at = m.end() + rest.end()
    if source[m.end():].strip():
        return None
    simple = {'n': '\n', 't': '\t', 'r': '', 'b': '', 'f': '', 'v': '', '0': ''}

    def unescape(e):
        e = e.group(1)
        if e[0] in 'ux' and len(e) > 1:
            return chr(int(e.strip('ux{}'), 16))
        return simple.get(e, e)
    return re.sub(r'\\(u\{[0-9a-fA-F]+\}|u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])', unescape, ''.join(parts))


def parse_settings_schema(line):
    """Claude Code's settings as its own schema defines them: each a lazy field with a description,
    name:()=>type()...describe("..."). Types are told apart by what the schema chains on them."""
    fields = []
    for m in re.finditer(r'([A-Za-z_$][\w$]*):\(\)=>', line):
        start = m.end()
        expr = line[start:js_end(line, start)]
        if not expr or expr[0] in '({':
            continue
        # The field's own description: the last .describe( outside any brackets.
        depth, desc_at, i = 0, -1, 0
        while i < len(expr):
            ch = expr[i]
            if ch in '"\'`':
                q, i = ch, i + 1
                while i < len(expr) and expr[i] != q:
                    i += 2 if expr[i] == '\\' else 1
            elif ch in '([{':
                depth += 1
            elif ch in ')]}':
                depth -= 1
            elif depth == 0 and expr.startswith('.describe(', i):
                desc_at = i
            i += 1
        if desc_at < 0:
            continue
        arg_at = desc_at + len('.describe(')
        desc = js_string(expr[arg_at:js_end(expr, arg_at)].strip())
        if desc is None or desc.startswith('@internal'):
            continue
        fields.append((m.group(1), expr[:desc_at], desc))
    if len(fields) < 20:
        return []

    def most(pattern, skip=()):
        counts = {}
        for name in re.findall(pattern, line):
            if name not in skip:
                counts[name] = counts.get(name, 0) + 1
        return max(counts, key=counts.get) if counts else None
    num = most(r'(?<![\w$.])([A-Za-z_$][\w$]*)\(\)\.(?:int|positive|nonnegative|gte?|lte?|finite)\(')
    text = most(r'(?<![\w$.])([A-Za-z_$][\w$]*)\(\)\.(?:url|regex|startsWith|endsWith|email|trim)\(', (num,))
    plain = r'^([A-Za-z_$][\w$]*)\(\)\.optional\(\)(?:\.catch\([^()]*\))?$'
    counts = {}
    for _, expr, _ in fields:
        p = re.match(plain, expr)
        if p and p.group(1) not in (num, text):
            counts[p.group(1)] = counts.get(p.group(1), 0) + 1
    flag = max(counts, key=counts.get) if counts else None
    out = []
    for name, expr, desc in fields:
        base = re.match(r'([A-Za-z_$][\w$]*)\(', expr)
        call = base.group(1) if base else ''
        item = {'key': name, 'desc': desc, 'type': 'json'}
        if call == flag and re.match(plain, expr):
            item['type'] = 'bool'
        elif call == num and num:
            item['type'] = 'number'
        elif call == text and text and re.match(r'^[A-Za-z_$][\w$]*\(\)', expr):
            item['type'] = 'string'
        elif base and expr[base.end():].startswith('["'):
            try:
                item['choices'] = json.loads(expr[base.end():js_end(expr, base.end())])
                item['type'] = 'enum'
            except ValueError:
                pass
        out.append(item)
    return out


def settings_schema():
    """The settings Claude Code knows, read from its program once per version (kept in SCHEMA_FILE)."""
    with schema_lock:
        try:
            path = os.path.realpath(claude_path())
            st = os.stat(path)
            stamp = '%d-%d' % (st.st_size, st.st_mtime)
            try:
                with open(SCHEMA_FILE, encoding='utf-8') as f:
                    saved = json.load(f)
                if saved.get('stamp') == stamp:
                    return saved.get('settings', [])
            except (OSError, ValueError, AttributeError):
                pass
            # The line of its bundled source holding the most lazy fields with a description.
            best, best_count = b'', 0
            with open(path, 'rb') as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as data:
                at = data.find(b':()=>')
                while at >= 0:
                    begin = data.rfind(b'\n', 0, at) + 1
                    end = data.find(b'\n', at)
                    end = len(data) if end < 0 else end
                    chunk = data[begin:end]
                    count = chunk.count(b').describe(')
                    if count > best_count and chunk.count(b':()=>') > 20:
                        best, best_count = chunk, count
                    at = data.find(b':()=>', end)
            found = parse_settings_schema(best.decode('utf-8', 'replace'))
            if found:
                os.makedirs(STATE_DIR, exist_ok=True)
                with open(SCHEMA_FILE, 'w', encoding='utf-8') as f:
                    json.dump({'stamp': stamp, 'settings': found}, f)
            return found
        except (OSError, ValueError):
            return []


def list_plugins():
    """Installed plugins and marketplaces, as `claude plugin list --json` and `marketplace list --json` tell."""
    def ask(*args):
        try:
            out = subprocess.run([claude_path(), 'plugin', *args, '--json'], cwd=HOME,
                                 capture_output=True, text=True, timeout=120)
            return json.loads(out.stdout or '[]')
        except (OSError, subprocess.SubprocessError, ValueError):
            return []
    found = {}
    threads = [threading.Thread(target=lambda k=k, a=a: found.__setitem__(k, ask(*a)))
               for k, a in (('plugins', ('list',)), ('marketplaces', ('marketplace', 'list')))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return found


MODELS_FILE = os.path.join(STATE_DIR, 'models.json')
claude_models = []  # Claude Code's own model list ({value, displayName, resolvedModel}), as /model shows it
models_lock = threading.Lock()


def models_stamp():
    parts = []
    for path in (os.path.realpath(claude_path()), os.path.join(CLAUDE_DIR, 'settings.json'),
                 os.path.join(CLAUDE_DIR, '.credentials.json')):
        try:
            st = os.stat(path)
            parts.append('%d-%d' % (st.st_size, st.st_mtime))
        except OSError:
            parts.append('-')
    return 'v2/' + '/'.join(parts)  # v2: models carry their description


def find_models():
    """The models Claude Code offers, from its reply to an SDK initialize request; asked again only
    when Claude Code, its settings or the login change (kept in MODELS_FILE), at low priority."""
    global claude_models
    if not models_lock.acquire(blocking=False):
        return
    try:
        stamp = models_stamp()
        try:
            with open(MODELS_FILE, encoding='utf-8') as f:
                saved = json.load(f)
            if saved.get('stamp') == stamp:
                claude_models = saved.get('models', [])
                return
        except (OSError, ValueError, AttributeError):
            pass
        found = None
        try:
            proc = subprocess.Popen(low([claude_path(), '-p', '--input-format', 'stream-json', '--output-format', 'stream-json',
                                         '--verbose']), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                    text=True, cwd=HOME, env=claude_env())
        except OSError:
            return
        timer = threading.Timer(120, proc.kill)
        timer.start()
        try:
            proc.stdin.write(json.dumps({'type': 'control_request', 'request_id': 'models',
                                         'request': {'subtype': 'initialize'}}) + '\n')
            proc.stdin.flush()
            for line in proc.stdout:
                try:
                    d = json.loads(line)
                except ValueError:
                    continue
                if d.get('type') == 'control_response':
                    r = d.get('response') or {}
                    found = (r.get('response') or r).get('models')
                    break
        except (OSError, ValueError):
            pass
        finally:
            timer.cancel()
            proc.kill()
            proc.wait()
            for f in (proc.stdin, proc.stdout):
                try:
                    f.close()
                except OSError:
                    pass
        if isinstance(found, list) and found:
            claude_models = [{k: m.get(k) for k in ('value', 'displayName', 'resolvedModel', 'description')} for m in found
                             if isinstance(m, dict) and m.get('value')]
            os.makedirs(STATE_DIR, exist_ok=True)
            with open(MODELS_FILE, 'w', encoding='utf-8') as f:
                json.dump({'stamp': stamp, 'models': claude_models}, f)
    finally:
        models_lock.release()


def read_config():
    threading.Thread(target=find_models, daemon=True).start()  # a no-op unless something changed
    models, settings = [], {}
    try:
        with open(os.path.join(CLAUDE_DIR, 'settings.json'), encoding='utf-8') as f:
            settings = json.load(f)
    except (OSError, ValueError):
        pass
    if not isinstance(settings, dict):
        settings = {}
    env = settings.get('env') if isinstance(settings.get('env'), dict) else {}
    # Something to sign in with: a key or token in settings or the environment, a key helper, or a login.
    keys = ('ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY', 'CLAUDE_CODE_OAUTH_TOKEN')
    auth = (any(env.get(k) or os.environ.get(k) for k in keys) or bool(settings.get('apiKeyHelper'))
            or os.path.exists(os.path.join(CLAUDE_DIR, '.credentials.json')))
    # Every model the settings name: the model setting, then each *_MODEL variable.
    default = settings.get('model') or env.get('ANTHROPIC_MODEL') or ''
    for value in [default] + [v for k, v in env.items() if k.endswith('_MODEL')]:
        if isinstance(value, str) and value and value not in models:
            models.append(value)
    base = env.get('ANTHROPIC_BASE_URL', '')
    return {'models': models, 'defaultModel': default, 'baseUrl': base, 'auth': auth,
            'claudeVersion': claude_version, 'efforts': choices.get('effort', []),
            'effort': env.get('CLAUDE_CODE_EFFORT_LEVEL') or settings.get('effortLevel') or '',
            'permissionModes': choices.get('permission-mode', []), 'claudeModels': claude_models}


def list_dirs(path):
    path = os.path.realpath(os.path.expanduser(path or DEFAULT_CWD))
    names = []
    try:
        for name in sorted(os.listdir(path), key=str.lower):
            if not name.startswith('.') and os.path.isdir(os.path.join(path, name)):
                names.append(name)
    except OSError as e:
        return {'path': path, 'parent': os.path.dirname(path), 'dirs': [], 'error': str(e)}
    return {'path': path, 'parent': os.path.dirname(path) if path != '/' else None, 'dirs': names, 'home': HOME}


# ---- HTTP ----

class Handler(BaseHTTPRequestHandler):
    server_version = 'ccbridge/%d' % VERSION
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def reply(self, status, data):
        self.status = status
        body = json.dumps(data, ensure_ascii=False).encode('utf-8', 'replace')  # lone surrogates
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return json.loads(self.rfile.read(n) or b'{}') if n else {}

    def logged(self, method, run):
        """Runs a request and logs it (not health checks), with its status and time."""
        path = self.path.split('?')[0]
        start = time.time()
        self.status = None
        try:
            run()
        finally:
            if path not in ('/health', '/events'):
                log('%s %s -> %s, %dms' % (method, path, self.status or 'no answer', (time.time() - start) * 1000))

    def do_GET(self):
        self.logged('GET', self.do_get)

    def do_POST(self):
        self.logged('POST', self.do_post)

    def do_get(self):
        try:
            self.get()
        except ValueError as e:
            self.reply(400, {'error': str(e)})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            self.failed(e)

    def get(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == '/health':
            self.reply(200, {'ok': True, 'version': VERSION, 'started': STARTED, 'home': HOME, 'defaultCwd': DEFAULT_CWD,
                             'claude': shutil.which('claude')})
        elif url.path == '/events':
            self.events(int(q.get('since') or 0), q.get('bridge'))
        elif url.path == '/image':
            found = session_image(q.get('id'), int(q.get('at') or 0), int(q.get('n') or 0))
            if not found:
                return self.reply(404, {'error': 'not found'})
            self.reply(200, {'type': found[0], 'data': found[1]})
        elif url.path == '/sessions':
            self.reply(200, {'sessions': list_sessions()})
        elif url.path == '/search':
            self.reply(200, {'results': search_sessions(q.get('q', ''))})
        elif url.path == '/history':
            data = load_history(q.get('id', ''))
            self.reply(200 if data else 404, data or {'error': 'not found'})
        elif url.path == '/config':
            self.reply(200, read_config())
        elif url.path == '/plugins':
            self.reply(200, list_plugins())
        elif url.path == '/settings-schema':
            self.reply(200, {'settings': settings_schema()})
        elif url.path == '/launch':
            self.reply(200, launch.data)
        elif url.path == '/memory':
            path = memory_path(q.get('scope'), q.get('cwd'))
            try:
                with open(path, encoding='utf-8') as f:
                    text = f.read()
            except OSError:
                text = ''
            self.reply(200, {'path': path, 'text': text})
        elif url.path == '/dirs':
            self.reply(200, list_dirs(q.get('path')))
        else:
            self.reply(404, {'error': 'not found'})

    def do_post(self):
        try:
            path = urlparse(self.path).path
            if path == '/upload':
                return self.upload()
            self.post(path, self.body())
        except (ValueError, KeyError, TypeError) as e:
            self.reply(400, {'error': 'bad request: %s' % e})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            self.failed(e)

    def failed(self, e):
        """An error the bridge did not expect: logged, and answered so the app can show it."""
        import traceback
        log('%s failed:\n%s' % (self.path.split('?')[0], traceback.format_exc()))
        try:
            self.reply(500, {'error': '%s: %s' % (type(e).__name__, e)})
        except OSError:
            pass

    def post(self, path, data):
        if path == '/send':
            mid = data.get('mid')
            with chats_lock:
                repeated = mid is not None and mid in seen_messages
                if mid is not None and not repeated:
                    seen_messages.append(mid)
                    del seen_messages[:-200]
            if repeated:
                return self.reply(200, {'ok': True, 'repeated': True})
            chat = chat_for(data['chat'], data.get('sessionId'))
            try:
                chat.send(data['content'], cwd=data.get('cwd'), model=data.get('model') or None,
                          session_id=data.get('sessionId'), effort=data.get('effort') or None,
                          restart=data.get('restart') is not False, message_uuid=data.get('uuid') or None)
            except Exception as e:
                chat.running = False
                with chats_lock:
                    if mid in seen_messages:
                        seen_messages.remove(mid)  # not sent: the app's retry sends it
                if isinstance(e, (OSError, ValueError)):
                    return self.reply(500, {'error': str(e)})
                raise
            self.reply(200, {'ok': True})
        elif path == '/open':
            # A new conversation was opened: the spare is made ready for it (a saved one's claude
            # starts only when a message is sent).
            restart = data.get('restart') is not False
            if not data.get('sessionId'):
                spare.wish(data.get('cwd'), data.get('model') or None, data.get('effort') or None)
            threading.Thread(target=spare.fill, args=(restart,), daemon=True).start()
            self.reply(200, {'ok': True})
        elif path == '/launch':
            launch.set(data)
            self.reply(200, launch.data)
        elif path == '/memory':
            target = memory_path(data.get('scope'), data.get('cwd'))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, 'w', encoding='utf-8') as f:
                f.write(data.get('text') or '')
            launch.gen += 1  # Claude Code reads CLAUDE.md when it starts
            self.reply(200, {'ok': True, 'path': target})
        elif path == '/mcp':
            self.mcp(data)
        elif path == '/plugin':
            self.plugin(data)
        elif path == '/cancel':
            chat = chat_for(data['chat'], data.get('sessionId'), create=False)
            cancelled = True if chat is None else chat.cancel(str(data.get('uuid') or ''))
            if cancelled is None:
                return self.reply(504, {'error': 'Claude Code did not answer'})
            self.reply(200, {'cancelled': cancelled})
        elif path == '/stop':
            chat = chat_for(data['chat'], data.get('sessionId'), create=False)
            if chat:
                chat.stop()
            self.reply(200, {'ok': True})
        elif path == '/delete':
            session_id = data.get('id', '')
            target = find_session(session_id) if session_id else None
            # Its claude goes first: it would write the transcript again. No exit event: the app let it go.
            with chats_lock:
                gone = [chats.pop(k) for k in [k for k, c in chats.items() if session_id and c.session_id == session_id]]
            for chat in gone:
                with chat.lock:
                    chat.running = False
                    proc, chat.proc = chat.proc, None
                    end(proc)
            if target:
                os.remove(target)
                shutil.rmtree(target[:-6], ignore_errors=True)
            self.reply(200, {'ok': bool(target)})
        elif path == '/title':
            session_id = str(data.get('id') or '')
            title = ' '.join(str(data.get('title') or '').split())[:200]
            if not title:
                return self.reply(400, {'error': 'bad request'})
            ok = rename(session_id, title)
            self.reply(200 if ok else 404, {'ok': ok})
        elif path == '/permission':
            chat = chat_for(data['chat'], data.get('sessionId'), create=False)
            answers = data.get('answers') if isinstance(data.get('answers'), dict) else None
            if not chat or not chat.answer(data['requestId'], bool(data.get('allow')), answers):
                return self.reply(409, {'error': 'not asked any more'})
            self.reply(200, {'ok': True})
        elif path == '/mkdir':
            try:
                os.makedirs(os.path.expanduser(data['path']), exist_ok=True)
                self.reply(200, {'ok': True})
            except OSError as e:
                self.reply(400, {'error': str(e)})
        else:
            self.reply(404, {'error': 'not found'})

    def upload(self):
        """A file the app attached, its bytes as the body (streamed to disk, never held whole), into
        the folder given; never over a file already there: name (1).ext, name (2).ext …"""
        q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
        folder = os.path.expanduser(q.get('cwd') or DEFAULT_CWD)
        name = os.path.basename(str(q.get('name') or 'file')).strip() or 'file'
        left = int(self.headers.get('Content-Length') or 0)
        os.makedirs(folder, exist_ok=True)
        stem, ext = os.path.splitext(name)
        target, n = os.path.join(folder, name), 1
        while os.path.exists(target):
            target, n = os.path.join(folder, '%s (%d)%s' % (stem, n, ext)), n + 1
        try:
            with open(target, 'wb') as f:
                while left > 0:
                    chunk = self.rfile.read(min(left, 1 << 16))
                    if not chunk:
                        raise OSError('the upload was cut short')
                    f.write(chunk)
                    left -= len(chunk)
        except OSError as e:
            try:
                os.remove(target)
            except OSError:
                pass
            return self.reply(400, {'error': str(e)})
        self.reply(200, {'ok': True, 'path': target})

    def mcp(self, data):
        """Adds, replaces or removes one of Claude Code's own MCP servers with its `claude mcp`
        (which owns ~/.claude.json, written by running conversations too)."""
        name, scope, action = str(data.get('name') or ''), data.get('scope') or 'user', data.get('action')
        if not re.match(r'^[\w.-]+$', name) or scope not in ('user', 'local', 'project') or action not in ('set', 'remove'):
            return self.reply(400, {'error': 'bad request'})
        folder = os.path.expanduser(data.get('cwd') or HOME)
        run = lambda *a: subprocess.run([claude_path(), 'mcp', *a], cwd=folder, capture_output=True, text=True, timeout=120)
        try:
            out = run('remove', name, '-s', scope)
            if action == 'set':
                out = run('add-json', name, json.dumps(data.get('config') or {}), '-s', scope)
        except (OSError, subprocess.SubprocessError) as e:
            return self.reply(500, {'error': str(e)})
        if out.returncode != 0:
            return self.reply(400, {'error': (out.stderr or out.stdout).strip()[-1000:]})
        launch.gen += 1  # conversations start again with it
        self.reply(200, {'ok': True})

    def plugin(self, data):
        """Installs, enables, disables, updates or uninstalls a plugin, or adds or removes a
        marketplace, with Claude Code's own `claude plugin`."""
        commands = {'install': ['install'], 'enable': ['enable'], 'disable': ['disable'], 'update': ['update'],
                    'uninstall': ['uninstall'], 'market-add': ['marketplace', 'add'], 'market-remove': ['marketplace', 'remove']}
        action, name = data.get('action'), str(data.get('name') or '').strip()
        if action not in commands or not name or name.startswith('-') or re.search(r'\s', name):
            return self.reply(400, {'error': 'bad request'})
        try:
            out = subprocess.run([claude_path(), 'plugin', *commands[action], name], cwd=HOME,
                                 capture_output=True, text=True, timeout=300)
        except (OSError, subprocess.SubprocessError) as e:
            return self.reply(500, {'error': str(e)})
        text = re.sub(r'\x1b\[[0-9;]*m', '', (out.stdout + out.stderr)).strip()[-1500:]
        if out.returncode != 0:
            return self.reply(400, {'error': text})
        launch.gen += 1  # conversations start again with it
        self.reply(200, {'ok': True, 'output': text})

    def events(self, since, bridge=None):
        """The events after `since`, then each as it happens. `bridge` is the start the app's count
        is from: another bridge's count starts over here. Events dropped meanwhile (only the last
        ones are kept) are told as one 'gap'. A new app (since -1), or one after a gap, is told what
        is still asked."""
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        try:
            fresh = since < 0
            if fresh:  # only what happens from now on
                since = hub.seq
            elif since > hub.seq or (bridge and bridge != str(STARTED)):  # another bridge's count: start over
                since = 0
            self.wfile.write((': hello %s\n\n' % STARTED).encode())

            def send(e):
                self.wfile.write(b'data: ' + json.dumps(e, ensure_ascii=False).encode('utf-8', 'replace') + b'\n\n')
            oldest = hub.oldest()
            gap = since < oldest - 1
            if gap:
                since = oldest - 1
                send({'seq': since, 'chat': '', 'kind': 'gap', 'data': None})
            if fresh or gap:
                with chats_lock:
                    live = list(chats.values())
                for chat in live:
                    for ask in list(chat.asks.values()):
                        send({'seq': since, 'chat': chat.key, 'kind': 'cc', 'data': dict(ask, session_id=chat.session_id)})
            self.wfile.flush()
            log('events: app connected')
            while True:
                batch = hub.after(since, 5)  # a ping at least every 5 s: the app can tell a live bridge
                if not batch:
                    self.wfile.write(b': ping\n\n')
                for e in batch:
                    since = e['seq']
                    send(e)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            log('events: app gone')


class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    request_queue_size = 64  # the app opens many connections at once when it starts

    def server_bind(self):
        """Over a socket file left by a bridge before (only one bridge runs: replace_running_bridge),
        readable by this app alone."""
        try:
            os.remove(self.server_address)
        except OSError:
            pass
        old = os.umask(0o177)
        try:
            super().server_bind()
        finally:
            os.umask(old)


def running_bridge():
    """The pid of the bridge running before this one, if it still runs (not a process that got its pid since)."""
    try:
        with open(PID_FILE) as f:
            pid = int(f.read().strip())
        with open('/proc/%d/cmdline' % pid, 'rb') as f:
            if b'bridge.py' not in f.read():
                return None
        return pid if pid != os.getpid() else None
    except (OSError, ValueError):
        return None


_lock = None


def replace_running_bridge():
    """Leaves exactly one bridge, the newest: it stops the one running and takes the lock that
    bridge held for its life, so two started at once end with one."""
    import fcntl
    global _lock
    os.makedirs(STATE_DIR, exist_ok=True)
    _lock = open(LOCK_FILE, 'a')
    deadline = time.time() + 12
    while True:
        old = running_bridge()
        if old:
            try:
                os.kill(old, signal.SIGTERM)
            except OSError:
                pass
        try:
            fcntl.flock(_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except OSError:
            if time.time() > deadline:
                log('exit pid %d: another bridge keeps the lock' % os.getpid())
                sys.exit(1)
            time.sleep(0.2)
    old = running_bridge()  # a bridge from before the lock
    if old:
        try:
            os.kill(old, signal.SIGTERM)
            for _ in range(30):
                time.sleep(0.1)
                os.kill(old, 0)
        except OSError:
            pass
    with open(PID_FILE, 'w') as f:
        f.write(str(os.getpid()))


def log(text):
    try:
        if os.path.getsize(LOG_FILE) > 200_000:
            os.replace(LOG_FILE, LOG_FILE + '.1')
    except OSError:
        pass
    try:
        with open(LOG_FILE, 'a') as f:
            f.write(time.strftime('%Y-%m-%d %H:%M:%S ') + text + '\n')
    except OSError:
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--socket', default=SOCKET_FILE)
    parser.add_argument('--reason', default='')
    args = parser.parse_args()
    replace_running_bridge()
    log('start pid %d version %d (%s)' % (os.getpid(), VERSION, args.reason or 'started by hand'))
    os.makedirs(DEFAULT_CWD, exist_ok=True)

    def shutdown(signum, _frame):
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        log('stop pid %d: signal %d' % (os.getpid(), signum))
        procs = [chat.proc for chat in list(chats.values()) if chat.proc is not None]
        if spare.proc is not None:
            procs.append(spare.proc)
        for proc in procs:
            try:
                proc.stdin.close()
            except OSError:
                pass
        end = time.time() + 3
        for proc in procs:
            try:
                proc.wait(max(0.1, end - time.time()))
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except OSError:
                    pass
        os._exit(0)
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGHUP, signal.SIG_IGN)

    def crashed(kind, value, tb):
        import traceback
        log('crash pid %d:\n%s' % (os.getpid(), ''.join(traceback.format_exception(kind, value, tb))))
    sys.excepthook = crashed
    threading.excepthook = lambda a: crashed(a.exc_type, a.exc_value, a.exc_traceback)

    try:
        server = Server(args.socket, Handler)
    except OSError as e:
        log('exit pid %d: cannot listen on %s: %s' % (os.getpid(), args.socket, e))
        sys.exit('cannot listen on %s: %s' % (args.socket, e))
    threading.Thread(target=reaper, daemon=True).start()
    threading.Thread(target=spare.fill, daemon=True).start()  # ready for the first new conversation
    threading.Thread(target=lambda: (find_claude_info(), find_models()), daemon=True).start()
    server.serve_forever()


if __name__ == '__main__':
    main()
