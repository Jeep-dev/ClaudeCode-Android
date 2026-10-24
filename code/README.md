# Claude Code for Android (v0.4.103)

Claude Code on the phone in one app, no Termux needed. Separate from the claude.ai app in
`app/`: its own application id (`com.jeep.claudecode`), name and icon. No WebView: the
interface is plain Android views (Java), so scrolling and text selection are the system's own.

## How it works

- The app carries its own Linux userland: Alpine (bash, Python, git, ripgrep, curl) with Claude
  Code's linux-arm64-musl build, packed as `assets/runtime/rootfs.tar.xz` by
  `runtime/build-rootfs.sh`, and Termux's proot (`runtime/fetch-proot.sh`). Both are built in
  CI (cached by the scripts' hash; Claude Code is not in it: on first launch the app installs the latest from Anthropic's releases with the userland's `claude-update`, which also updates it from the terminal). On first
  launch, or after an update that brings a new userland, the app unpacks it into its files
  (over what is there: `/root` and packages added with `apk add` stay). targetSdk is 28 so the
  app may run programs from its own files, as Termux does.
- `BridgeService`, a foreground service holding a wake lock it never releases, runs
  `assets/bridge.py` under proot as root of the userland (`IS_SANDBOX=1` lets Claude Code skip
  permission prompts as that root); it listens on a Unix socket, `~/.ccapp/bridge.sock` in the
  userland (in this app's own files, mode 0600), not on a port: no other app can reach it, so
  requests carry no token. The app speaks HTTP to it over a `LocalSocket`. The service starts it again
  when it dies on its own (at most three times a minute). To stop or replace it, the service sends SIGTERM to the bridge
  itself (proot ignores SIGTERM): the bridge ends its conversations and exits, and proot then
  ends what is left under it. Phone storage is shared in as
  `/sdcard` once the app may read it; DNS servers come from the current network.
- The app checks the bridge (nothing listening and no bridge process, as after the app was killed
  with it: started at once; no answer: three tries, so a busy one is not taken for dead), has the service start it when it is gone or of another
  version, and gives it 30 s to answer. The event stream resumes where it left off with the
  same bridge; events dropped meanwhile (it keeps the last 4000) make the app read the
  conversations again from their transcripts. When
  the link stays down for 2 s with the app open, it does the same, never twice within 30 s;
  turns the old bridge was running are marked interrupted (a new message continues the
  conversation). The bridge logs its starts,
  stops, crashes, requests, each Claude Code process (start, API retries, turn results, exit
  code and stderr), how long each message waited for its reply to begin (getting a claude,
  Claude Code taking it up, the API answering, and the context read from cache), quiet turns and times it was paused by the system to `~/.ccapp/bridge.log`;
  Settings → 运行日志 shows it with the service's own log.
- Settings → 模型 → API Key writes `ANTHROPIC_BASE_URL`, the key (`ANTHROPIC_API_KEY` for
  Anthropic's own API, `ANTHROPIC_AUTH_TOKEN` for another provider) and `ANTHROPIC_MODEL` into
  `~/.claude/settings.json` (or edits the file whole). A settings.json that does not read as
  JSON is never saved over; it and the MCP list are written aside and moved in. The `rish`
  command in Settings whitelists the app and turns off Android's limit on child processes.
- Settings is a full-screen page of pages: 外观; 对话 (the working folder for new
  conversations, Claude Code's launch options); 模型; Claude Code (its settings); 扩展: skills
  (`~/.claude/skills/<name>/SKILL.md`, made, edited and deleted here), tools (switched off
  with `--disallowedTools`; the list is the one Claude Code reported when a conversation last
  started) and MCP servers (kept in `~/.ccapp/mcp.json` and passed with `--mcp-config`; pasting
  a README's JSON adds them; servers added with `claude mcp add` are listed with their state);
  storage; keeping it running (and whether an idle Claude Code is started again); and 关于.
- The top bar's right button (three dots) opens a small menu for what the app adds, kept off the screen otherwise: 终端 and 搜索对话 (which puts `/find ` in the box). 终端 opens a terminal in the userland (`bash -l` as root in `/root`), built on
  Termux's terminal emulator and view (Apache 2.0): for commands, `apk add`, and interactive
  programs such as vim or `claude` itself (its `/login` for a Claude subscription). A row of
  keys gives ESC, TAB, CTRL, ALT, arrows, common symbols and paste; pinch changes the text
  size. It takes the app's colours (light or dark), with ANSI colours readable on them. Back
  (button or swipe) leaves it; the shell lives on while the app does (`exit` ends it).
- Each conversation is a `claude -p --input-format stream-json --output-format stream-json
  --permission-prompt-tool stdio` process (bypass-permissions mode unless changed in Settings →
  对话, which also sets text for `--append-system-prompt` and extra arguments, kept in
  `~/.ccapp/launch.json`, and edits the global `~/.claude/CLAUDE.md` and the working folder's
  `CLAUDE.md`; all apply from the next message), kept alive between turns (stopped after 15
  idle minutes) and resumed from its saved session (`--resume`) when needed. A saved
  conversation's process starts only when a message is sent (opening one starts nothing); only a
  new conversation's is made ready ahead (the spare, kept fresh when the app comes to the front, a
  new conversation is opened or something is typed in it). One idle three minutes or more is
  started again before its next message, so it has no API connection the network dropped
  meanwhile (Settings → 后台 → 闲置后重启 Claude Code turns it off). A request that still gets
  no response headers is given up after 30 s (`CLAUDE_STREAM_FIRST_BYTE_TIMEOUT_MS`, Claude
  Code's own default three minutes) and retried once on a new connection. Each is started with the
  phone's time zone in `TZ` (the app keeps it in `~/.ccapp/timezone`, also when it changes): the
  userland has no `/etc/localtime`, and with neither Claude Code reads every file under
  `/usr/share/zoneinfo` as it starts, 0.5 to 2.5 s through proot. In a permission
  mode that asks, Claude Code's question whether a tool may run comes to the app: the tool's row
  shows what it would do with 允许 / 拒绝. Claude Code's questions to the user (AskUserQuestion, asked so in
  every mode) show as their options to pick (several where allowed) and a line of one's own (其他),
  with 跳过 / 提交; the answers go back with the allow, and the row then shows each question with
  its answer. A message written while a reply runs waits below it (edited, taken back or sent now
  meanwhile). With 插入 (the default; Settings → 对话 → 回复中发消息) it is sent as soon as the reply
  runs a tool, with a uuid: Claude Code holds it in its queue and gives it to the model with the
  tool's result, or starts a turn for it when the reply ends. Until then it still waits as it was:
  edited or taken back, it is first taken back from Claude Code's queue (`cancel_async_message`,
  the bridge's `/cancel`), and 立即发送 only stops the reply (Claude Code then starts a turn for it).
  It is a message of the conversation once the model has it (Claude Code's `command_lifecycle`
  started), the reply going on below it; and the transcript
  keeps it as a queued command, shown in its place when the conversation is read again. With 排队,
  or when no tool runs, it is sent when the reply ends. Stop sends Claude Code's `interrupt` control request,
  and ends the process if that turn has not ended 5 s later.
- The conversation list and history come from Claude Code's own transcripts in
  `~/.claude/projects` (the list's titles kept in `~/.ccapp/sessions.json`, and by the app, so
  it shows at once on the next start). A saved conversation's pictures are read from its
  transcript when shown; a picture on the web in a reply only when tapped. Attached files are
  streamed into the working folder, never held whole. New conversations run in `~/work` by
  default; the folder can be changed per new conversation.
- `Bridge` talks to the bridge (HTTP, event stream); `Chat` turns Claude Code's stream-json
  output (or a transcript) into messages and blocks; `ThreadAdapter` shows them in a
  RecyclerView; `Md` renders Markdown with Markwon, fenced code as cards with a copy button;
  `DrawerFrame` is the drawer that a horizontal swipe anywhere opens.

## Interface

Drawer with the conversation list, each with how long ago its last message was at the end of its folder's
line (now, 5m, 1h, 2d, 3w, 1mo, 1y, in a column of one width, grey; the time of the transcript's last message, not
of the file, which Claude Code also writes to when its process ends; Settings → 外观 → 对话时间 hides it; 只显示当前目录 lists only the conversations of the folder new ones start in, not of its subfolders) (swipe right anywhere to open, left to close; long-press a
conversation to pin it on top or delete it), a one-line composer (+, the text, and a plain Enter icon to send, a ringed square to stop) where the keyboard's Enter (a Send key) or the button sends and the
keyboard then closes (Shift+Enter on a physical keyboard makes a new line), files from the system picker (pictures go as they are, up to 10 MB, Claude Code scaling them for the model and keeping the originals, whose paths it gives the model; HEIC, HEIF and AVIF, which it cannot read, and larger ones as JPEG of up to 4096 px; the thread shows a copy of each at the size the model sees it (2000 px on the long edge, as Claude Code scales them, and at most 4784 visual tokens); other
files are saved into the working folder, never overwriting, and their paths added to the
message), under the input box the model and thinking level (tap to change both) and the context in use against the model's window as Claude Code reports it (250k/1m), `/model` to pick a model (the models in Claude Code's settings and any model name typed there, remembered; none built in), thinking (asked for summarized, as the API sends it empty otherwise; opened, it scrolls inside a box of at
most 300 dp) and tool steps collapsed into rows as in Claude Code's terminal
(the tool's name in a box, then the step's summary or file, whole when opened; opened, the full command; file edits with a diff, to-do lists), a long
press on a message for its menu instead of selecting (复制, or 复制文字 and 复制 Markdown for a reply; 选择文字 in a sheet; 编辑重发 for the
user's, as `/tree`; 删除, out of the transcript too: the bridge's `/delete-message` takes the message's entries out, a reply's tool results with it,
and links what came after to what came before), code blocks with copy, token usage per reply (↑ context, ↓ output, CH cache hits), light and dark
colours.

While Claude Code works, the status line shows elapsed time, API retries (attempt and error)
and how long nothing has arrived once that passes 20 s. A send whose answer is lost is
repeated with the same message id, which the bridge ignores the second time. A message sent
while a reply runs is queued under it (edit, cancel, or 立即发送, which stops the reply and sends
it); queued messages go one by one as replies end, and wait after a reply stopped by hand.

## Slash commands

Typing `/` lists commands. Handled by the app: `/help`, `/new` and `/clear` (new
conversation), `/effort low|medium|high|xhigh|max|default`, `/model [name]`, `/folder`,
`/find <words>` (marks the words in this conversation's messages and replies and goes to the
last place, ↑↓ then going between the places; the `/find` line stays in the box meanwhile,
and changing or clearing it, `/find` alone or another conversation ends it),
`/tree` or `/rewind` (lists the user's messages, the last first; the one picked and all after it
are cut from the transcript by the bridge's `/rewind`, its text goes back into the box, and the
next message resumes from the reply before it: a request the API's safeguards refused is gone
with it, instead of every message after being refused too), `/stop`. Effort and model apply from the next message: the bridge restarts the idle process
with `--model` and `--settings '{"env":{"CLAUDE_CODE_EFFORT_LEVEL":…}}'` and resumes the
session (a `CLAUDE_CODE_EFFORT_LEVEL` in the user's settings would override `--effort` and
Claude Code's own `/effort`). Everything else, such as `/compact`, `/context`, `/cost`,
`/init` and skills, goes to Claude Code as typed.

## Setup on the phone

Install and open: the app unpacks its userland (about half a minute), then asks for an API
address and key, or to import the old setup from Termux.

## Screenshots without a phone

`ScreensTest` renders the main screens with Robolectric's native graphics:
`gradle :code:testDebugUnitTest --tests com.jeep.claudecode.ScreensTest -Dshots=<dir>`.
