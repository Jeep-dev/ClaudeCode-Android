# Claude Code for Android (v0.4.53)

Claude Code on the phone in one app, no Termux needed. Separate from the claude.ai app in
`app/`: its own application id (`com.jeep.claudecode`), name and icon. No WebView: the
interface is plain Android views (Java), so scrolling and text selection are the system's own.

## How it works

- The app carries its own Linux userland: Alpine (bash, Python, git, ripgrep, curl) with Claude
  Code's linux-arm64-musl build, packed as `assets/runtime/rootfs.tar.xz` by
  `runtime/build-rootfs.sh`, and Termux's proot (`runtime/fetch-proot.sh`). Both are built in
  CI (cached by the scripts' hash; Claude Code's version is pinned in the script). On first
  launch, or after an update that brings a new userland, the app unpacks it into its files
  (over what is there: `/root` and packages added with `apk add` stay). targetSdk is 28 so the
  app may run programs from its own files, as Termux does.
- `BridgeService`, a foreground service holding a wake lock it never releases, runs
  `assets/bridge.py` under proot as root of the userland (`IS_SANDBOX=1` lets Claude Code skip
  permission prompts as that root), with a random per-install token; it listens on
  `127.0.0.1:8766` only. Every request is signed with the token (HMAC over time, nonce, method
  and path; never the token itself, and never taken twice) and every answer proves the bridge
  knows it, so another app on the port neither learns the token nor is believed. The service starts it again
  when it dies on its own (at most three times a minute). To stop or replace it, the service sends SIGTERM to the bridge
  itself (proot ignores SIGTERM): the bridge ends its conversations and exits, and proot then
  ends what is left under it. Phone storage is shared in as
  `/sdcard` once the app may read it; DNS servers come from the current network.
- The app checks the bridge (nothing listening: started at once; no answer: three tries, so a
  busy one is not taken for dead), has the service start it when it is gone or of another
  version, and gives it 30 s to answer. The event stream resumes where it left off with the
  same bridge; events dropped meanwhile (it keeps the last 4000) make the app read the
  conversations again from their transcripts. When
  the link stays down for 2 s with the app open, it does the same, never twice within 30 s;
  turns the old bridge was running are marked interrupted (a new message continues the
  conversation). Requests to the bridge never go through a proxy. The bridge logs its starts,
  stops, crashes, requests, each Claude Code process (start, API retries, turn results, exit
  code and stderr), quiet turns and times it was paused by the system to `~/.ccapp/bridge.log`;
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
  storage; keeping it running; and 关于.
- The top bar's terminal button opens a terminal in the userland (`bash -l` as root in `/root`), built on
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
  idle minutes) and resumed from its saved session (`--resume`) when needed. In a permission
  mode that asks, Claude Code's question whether a tool may run comes to the app: the tool's row
  shows what it would do with 允许 / 拒绝. Stop sends Claude Code's `interrupt` control request,
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

Drawer with the conversation list (swipe right anywhere to open, left to close; long-press a
conversation to pin it on top or delete it), a one-line composer (+, the text, and a plain Enter icon to send, a ringed square to stop) where the keyboard's Enter (a Send key) or the button sends and the
keyboard then closes (Shift+Enter on a physical keyboard makes a new line), files from the system picker (pictures go to the model scaled to 1568 px JPEG; other
files are saved into the working folder, never overwriting, and their paths added to the
message), under the input box the model and thinking level (tap to change both) and the context in use against the model's window as Claude Code reports it (250k/1m), `/model` to pick a model (the models in Claude Code's settings and any model name typed there, remembered; none built in), thinking (opened, it scrolls inside a box of at
most 300 dp) and tool steps collapsed into rows as in Claude Code's terminal
(the tool's name in a box, then the step's summary or file; opened, the full command; file edits with a diff, to-do lists), selectable text, code
blocks with copy, token usage per reply (↑ context, ↓ output, CH cache hits), light and dark
colours.

While Claude Code works, the status line shows elapsed time, API retries (attempt and error)
and how long nothing has arrived once that passes 20 s. A send whose answer is lost is
repeated with the same message id, which the bridge ignores the second time. A message sent
while a reply runs is queued under it (edit, cancel, or 立即发送, which stops the reply and sends
it); queued messages go one by one as replies end, and wait after a reply stopped by hand.

## Slash commands

Typing `/` lists commands. Handled by the app: `/help`, `/new` and `/clear` (new
conversation), `/effort low|medium|high|xhigh|max|default`, `/model [name]`, `/folder`,
`/stop`. Effort and model apply from the next message: the bridge restarts the idle process
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
