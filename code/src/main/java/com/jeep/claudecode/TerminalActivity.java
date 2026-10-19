package com.jeep.claudecode;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.IOException;
import java.util.List;

/**
 * A terminal in the userland (bash as root in /root), for commands and interactive programs
 * such as vim or Claude Code's own /login. The shell lives on while the app does: leaving the
 * screen keeps it, `exit` ends it.
 */
public final class TerminalActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    /** The running shell, kept across visits to this screen. */
    private static TerminalSession session;
    private static int textSizeDp = 12;

    private TerminalView view;
    private boolean ctrl, alt;
    private TextView ctrlKey, altKey;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Userland userland = new Userland(this);
        if (!userland.ready()) {
            Toast.makeText(this, "运行环境还没准备好", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        Themes.window(getWindow(), this);
        applyColors();
        view = new TerminalView(this, null);
        view.setTerminalViewClient(this);
        view.setTextSize(Ui.dp(this, textSizeDp)); // makes the renderer (monospace): before anything else on the view
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(keys(), new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
        setContentView(root);

        if (session == null || !session.isRunning()) {
            try {
                List<String> cmd = userland.command("/bin/bash", "-l");
                session = new TerminalSession(cmd.get(0), userland.base.getPath(), cmd.toArray(new String[0]),
                        userland.env(), 3000, this);
            } catch (IOException e) {
                Toast.makeText(this, "启动终端失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        } else {
            session.updateTerminalSessionClient(this);
        }
        view.attachSession(session);
        if (session.getEmulator() != null) session.getEmulator().mColors.reset(); // a shell from before: new colours
        view.requestFocus();
    }

    /**
     * The app's own colours: its background and text, and in the light theme ANSI colours dark
     * enough to read on it (the standard ones are made for a black screen).
     */
    private void applyColors() {
        java.util.Properties p = new java.util.Properties();
        p.put("background", hex(Ui.color(this, R.color.bg)));
        p.put("foreground", hex(Ui.color(this, R.color.text)));
        p.put("cursor", hex(Ui.color(this, R.color.accent)));
        boolean night = Themes.dark(this);
        String[] ansi = night
                ? new String[]{"#000000", "#cd3131", "#0dbc79", "#e5e510", "#2472c8", "#bc3fbc", "#11a8cd", "#e5e5e5",
                        "#666666", "#f14c4c", "#23d18b", "#f5f543", "#3b8eea", "#d670d6", "#29b8db", "#ffffff"}
                : new String[]{"#1f1e1d", "#c4261d", "#1e7a34", "#946200", "#1f56c7", "#a2349f", "#0e7584", "#6b6a66",
                        "#5c5b57", "#d93a2f", "#2a9145", "#a87400", "#3567d9", "#b842b4", "#148a9b", "#3d3c39"};
        for (int i = 0; i < ansi.length; i++) p.put("color" + i, ansi[i]);
        com.termux.terminal.TerminalColors.COLOR_SCHEME.updateWith(p);
    }

    private static String hex(int color) { return String.format("#%06x", color & 0xFFFFFF); }

    /**
     * Back (the button or the system's swipe) leaves the screen, the shell staying; the terminal
     * view would otherwise take it as a key. Ending a text selection comes first.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
            if (view != null && view.isSelectingText()) view.stopTextSelectionMode();
            else finish();
        }
        return true;
    }

    /** ESC, TAB, CTRL, ALT, arrows and the symbols phone keyboards hide. */
    private View keys() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(Ui.color(this, R.color.surface2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        key(row, "ESC", () -> view.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0));
        key(row, "TAB", () -> view.handleKeyCode(KeyEvent.KEYCODE_TAB, 0));
        ctrlKey = key(row, "CTRL", () -> { ctrl = !ctrl; paint(); });
        altKey = key(row, "ALT", () -> { alt = !alt; paint(); });
        key(row, "←", () -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0));
        key(row, "↓", () -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0));
        key(row, "↑", () -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0));
        key(row, "→", () -> view.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        for (String c : new String[]{"/", "-", "|", "~", "*", "&", "$", ">"}) key(row, c, () -> type(c.codePointAt(0)));
        key(row, "粘贴", this::paste);
        scroll.addView(row);
        return scroll;
    }

    private TextView key(LinearLayout row, String label, Runnable action) {
        TextView k = new TextView(this);
        k.setText(label);
        k.setTextColor(Ui.color(this, R.color.text));
        k.setTextSize(14);
        k.setTypeface(Typeface.MONOSPACE);
        k.setGravity(Gravity.CENTER);
        k.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        k.setMinWidth(Ui.dp(this, 44));
        k.setOnClickListener(v -> action.run());
        row.addView(k, new LinearLayout.LayoutParams(-2, -1));
        return k;
    }

    private void paint() {
        int on = Ui.color(this, R.color.accent), off = Ui.color(this, R.color.text);
        ctrlKey.setTextColor(ctrl ? on : off);
        altKey.setTextColor(alt ? on : off);
    }

    private void type(int codePoint) {
        view.inputCodePoint(codePoint, readControlKey(), readAltKey());
    }

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null && session != null) session.getEmulator().paste(text.toString());
    }

    private void showKeyboard() {
        view.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(view, 0);
    }

    // ---- TerminalSessionClient ----

    @Override public void onTextChanged(TerminalSession s) {
        view.onScreenUpdated();
    }
    @Override public void onTitleChanged(TerminalSession s) {}
    @Override public void onSessionFinished(TerminalSession s) {
        if (session == s) session = null;
        finish();
    }
    @Override public void onCopyTextToClipboard(TerminalSession s, String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("终端", text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }
    @Override public void onPasteTextFromClipboard(TerminalSession s) { paste(); }
    @Override public void onBell(TerminalSession s) {}
    @Override public void onColorsChanged(TerminalSession s) {}
    @Override public void onTerminalCursorStateChange(boolean state) {}
    @Override public Integer getTerminalCursorStyle() { return null; }

    // ---- TerminalViewClient ----

    @Override public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            textSizeDp = Math.max(7, Math.min(28, textSizeDp + (scale > 1 ? 1 : -1)));
            view.setTextSize(Ui.dp(this, textSizeDp));
            return 1f;
        }
        return scale;
    }
    @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return false; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent e) { return false; }
    @Override public boolean readControlKey() {
        boolean on = ctrl;
        if (on) { ctrl = false; paint(); }
        return on;
    }
    @Override public boolean readAltKey() {
        boolean on = alt;
        if (on) { alt = false; paint(); }
        return on;
    }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) { return false; }
    @Override public void onEmulatorSet() {}

    // ---- logging (both interfaces) ----

    @Override public void logError(String tag, String message) {}
    @Override public void logWarn(String tag, String message) {}
    @Override public void logInfo(String tag, String message) {}
    @Override public void logDebug(String tag, String message) {}
    @Override public void logVerbose(String tag, String message) {}
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
    @Override public void logStackTrace(String tag, Exception e) {}
}
