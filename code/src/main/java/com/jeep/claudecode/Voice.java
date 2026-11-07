package com.jeep.claudecode;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Speaking into the box, with the phone's own speech recognizer: the mic button beside send.
 * Tapped, it listens until tapped again (a pause starts the next sentence); held, it listens
 * until let go. The words go in where the cursor is, as they are heard.
 */
final class Voice extends Part {
    static final int ASK_MIC = 3;
    private static final String MIC = android.Manifest.permission.RECORD_AUDIO;
    /** A press longer than this is holding the button down to talk; a shorter one is a tap. */
    private static final long HOLD_MS = 350;

    private ImageView button;
    private SpeechRecognizer rec;
    private Intent listen;
    /** Listening; held down (ends when let go); stopping (the last words still to come). */
    private boolean on, held, stopping;
    /** Between two sentences (the next not started yet); anything heard since it began. */
    private boolean between, heard;
    /** The next sentence was just asked for without letting go of the last (a refusal is then retried, not shown). */
    private boolean restarted;
    /** The press that stopped it: its release starts nothing. */
    private boolean pressStopped;
    private long pressedAt;
    /** The conversation the words go into, and where in its box: the words heard so far of this sentence. */
    private Chat chat;
    private int spanStart, spanEnd;
    /** Sentences heard as nothing, one after the other: twice, and a tap's listening ends by itself. */
    private int quiet;
    /** Counts each listening: a restart left over from one ended is not taken by the next. */
    private int session;
    private final Runnable giveUp = this::finish;

    Voice(MainActivity app) { super(app); }

    /** The mic button, in the box just built. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    void attach(ImageView b) {
        cancel();
        button = b;
        b.setContentDescription("语音输入");
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    pressStopped = on;
                    if (on) stop();
                    else { pressedAt = e.getEventTime(); start(true); }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (!pressStopped && on && held) {
                        if (e.getEventTime() - pressedAt < HOLD_MS) { held = false; paint(); } // a tap: on till tapped again
                        else stop();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (on && held) stop();
                    return true;
                default:
                    return true;
            }
        });
        // Tapped by the accessibility service (no touch): on and off.
        b.setOnClickListener(v -> { if (on) stop(); else start(false); });
        paint();
    }

    private void start(boolean hold) {
        if (app.checkSelfPermission(MIC) != PackageManager.PERMISSION_GRANTED) {
            app.requestPermissions(new String[]{MIC}, ASK_MIC);
            return;
        }
        EditText in = app.box.input;
        chat = app.current;
        spanStart = Math.max(0, Math.min(in.getSelectionStart(), in.getSelectionEnd()));
        spanEnd = Math.max(spanStart, Math.max(in.getSelectionStart(), in.getSelectionEnd()));
        if (in.getSelectionStart() < 0) spanStart = spanEnd = in.length();
        listen = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // No EXTRA_CALLING_PACKAGE: the claude.ai app's recognizer checks the microphone for that package
        // under its own uid, finds no such pair and refuses ("caller without permission").
        android.content.ComponentName chosen = chosen();
        if (chosen == null && !SpeechRecognizer.isRecognitionAvailable(app)) {
            // No recognizer to listen with here: the system's own voice input page, if there is one.
            if (listen.resolveActivity(app.getPackageManager()) == null) {
                toast("这台手机没有语音识别服务：可以用输入法自带的语音键");
                return;
            }
            listen.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            app.startActivityForResult(listen, MainActivity.VOICE);
            return;
        }
        rec = chosen != null ? SpeechRecognizer.createSpeechRecognizer(app, chosen) : SpeechRecognizer.createSpeechRecognizer(app);
        rec.setRecognitionListener(listener);
        on = true;
        session++;
        held = hold;
        stopping = between = heard = restarted = false;
        quiet = 0;
        rec.startListening(listen);
        paint();
    }

    /**
     * The language to listen for, as recognizers know it: language and country only ("zh-CN", "en-US").
     * The system's own tag may carry a script ("zh-Hans-CN"), which some take for a language they do not
     * have, and then listen for another.
     */
    private static String language() {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage(), country = l.getCountry();
        if (lang.equals("zh") && country.isEmpty()) country = "Hant".equals(l.getScript()) ? "TW" : "CN";
        return country.isEmpty() ? lang : lang + "-" + country;
    }

    /** The recognizers on the phone (Google's, the claude.ai app's, a voice keyboard's, …). */
    private java.util.List<android.content.pm.ResolveInfo> services() {
        return app.getPackageManager().queryIntentServices(new Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0);
    }

    /** The recognizer picked in Settings, while it is still there; null: the system's default. */
    private android.content.ComponentName chosen() {
        String kept = prefs.getString("voiceService", "");
        if (kept.isEmpty()) return null;
        android.content.ComponentName c = android.content.ComponentName.unflattenFromString(kept);
        for (android.content.pm.ResolveInfo r : services()) {
            if (c != null && r.serviceInfo.packageName.equals(c.getPackageName()) && r.serviceInfo.name.equals(c.getClassName())) return c;
        }
        return null;
    }

    private String label(android.content.ComponentName c) {
        try {
            return String.valueOf(app.getPackageManager().getApplicationInfo(c.getPackageName(), 0).loadLabel(app.getPackageManager()));
        } catch (PackageManager.NameNotFoundException e) {
            return c.getPackageName();
        }
    }

    /** The system's default recognizer (Settings → voice input), if one is set. */
    private android.content.ComponentName systemDefault() {
        String s = android.provider.Settings.Secure.getString(app.getContentResolver(), "voice_recognition_service");
        return s == null || s.isEmpty() ? null : android.content.ComponentName.unflattenFromString(s);
    }

    /** What Settings shows: the recognizer in use. */
    String serviceLabel() {
        android.content.ComponentName c = chosen();
        if (c != null) return label(c);
        android.content.ComponentName d = systemDefault();
        return d == null ? "系统默认" : "系统默认 · " + label(d);
    }

    /** Picks the recognizer to listen with: the system's default, or one of those on the phone. */
    void serviceSheet(Runnable after) {
        android.app.Dialog sheet = sheets.sheet("语音识别", "识别由手机里的语音识别服务完成，不经过 Claude");
        android.widget.LinearLayout card = sheets.card(sheet);
        String kept = chosen() == null ? "" : prefs.getString("voiceService", "");
        android.content.ComponentName d = systemDefault();
        sheets.option(card, 0, "系统默认", d == null ? null : label(d), false, () -> {
            prefs.edit().putString("voiceService", "").apply();
            sheet.dismiss();
            after.run();
        });
        if (kept.isEmpty()) sheets.check(card);
        for (android.content.pm.ResolveInfo r : services()) {
            android.content.ComponentName c = new android.content.ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            String flat = c.flattenToString();
            sheets.option(card, 0, label(c), c.getPackageName(), false, () -> {
                cancel();
                prefs.edit().putString("voiceService", flat).apply();
                sheet.dismiss();
                after.run();
            });
            if (flat.equals(kept)) sheets.check(card);
        }
        sheet.show();
    }

    /** The microphone may be used now (asked when the button was first pressed): it listens, till tapped again. */
    void granted(boolean yes) {
        if (yes) start(false);
        else toast("没有麦克风权限：可以在系统设置里给这个 App 打开");
    }

    /** Ends listening; the words still on their way are written in. */
    void stop() {
        if (!on || stopping) return;
        if (between) { if (!heard) toast("没听清，请再说一次"); finish(); return; }
        stopping = true;
        held = false;
        rec.stopListening();
        ui.postDelayed(giveUp, 2500); // a recognizer that never answers once stopped
        paint();
    }

    /** Ends listening at once (sent, another conversation, the app left): what is written stays. */
    void cancel() {
        if (rec != null) rec.cancel();
        finish();
    }

    private void finish() {
        ui.removeCallbacks(giveUp);
        if (rec != null) rec.destroy();
        rec = null;
        session++;
        on = held = stopping = between = false;
        paint();
    }

    /** The words from the system's voice input page (where there was no recognizer to use here). */
    void picked(int result, Intent data) {
        if (result != android.app.Activity.RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) write(said.get(0));
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { restarted = false; }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int type, Bundle params) {}

        @Override public void onRmsChanged(float rms) {
            if (button == null || !on) return;
            float level = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
            button.animate().scaleX(1f + 0.14f * level).scaleY(1f + 0.14f * level).setDuration(90).start();
        }

        @Override public void onPartialResults(Bundle b) {
            String t = first(b);
            if (t != null && !t.isEmpty()) write(t);
        }

        @Override public void onResults(Bundle b) {
            String t = first(b);
            if (t != null && !t.isEmpty()) { write(t); quiet = 0; } else quiet++;
            spanStart = spanEnd; // this sentence is done: the next one goes after it
            next();
        }

        @Override public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                quiet++;
                spanEnd = Math.max(spanStart, spanEnd); // what was partly heard stays
                spanStart = spanEnd;
                next();
                return;
            }
            if (restarted && on && !stopping && (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) { retry(); return; }
            boolean was = on;
            finish();
            String why = message(error);
            // Refused by a recognizer picked in Settings: its own microphone, not this app's.
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && chosen() != null)
                why = "这个语音识别服务不让用麦克风：可以到 设置 → 对话 → 语音识别 换一个";
            if (was && why != null) toast(why);
        }
    };

    /** After a sentence: listens for the next while held or tapped on (two silent ones end a tap's), otherwise ends. */
    private void next() {
        if (!on) return;
        if (stopping || (!held && quiet >= 2)) {
            if (!heard) toast("没听清，请再说一次");
            finish();
            return;
        }
        // The next sentence at once, so its first words are not lost.
        restarted = true;
        rec.startListening(listen);
    }

    /** A recognizer that would not take the next sentence at once: let go of the last, and started again a moment later. */
    private void retry() {
        restarted = false;
        rec.cancel();
        between = true;
        int at = session;
        ui.postDelayed(() -> {
            if (at != session || !on) return;
            between = false;
            if (!stopping && rec != null) rec.startListening(listen);
        }, 120);
    }

    private static String first(Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private static String message(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "没有麦克风权限：可以在系统设置里给这个 App 打开";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "语音识别连不上网络";
            case SpeechRecognizer.ERROR_AUDIO: return "录不到声音";
            case SpeechRecognizer.ERROR_SERVER: return "语音识别服务出错";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "语音识别正忙，稍后再试";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "语音识别不支持当前语言";
            default: return null; // the client's own (stopped, cancelled): nothing to say
        }
    }

    /** Puts what is heard of this sentence in its place in the box, in place of what was heard of it before. */
    private void write(String text) {
        if (app.current != chat) { cancel(); return; }
        EditText in = app.box.input;
        Editable e = in.getText();
        int s = Math.min(spanStart, e.length()), end = Math.max(s, Math.min(spanEnd, e.length()));
        String t = text;
        heard = true;
        if (s > 0 && spaced(e.charAt(s - 1), t.charAt(0))) t = " " + t;
        e.replace(s, end, t);
        spanStart = s;
        spanEnd = s + t.length();
        in.setSelection(spanEnd);
    }

    /** Words in letters after words in letters are spaced; Chinese is not. */
    private static boolean spaced(char before, char first) {
        boolean latinBefore = before < 128 && (Character.isLetterOrDigit(before) || ".,!?;:".indexOf(before) >= 0);
        return latinBefore && first < 128 && Character.isLetterOrDigit(first);
    }

    /** The button as it stands: plain, or filled while it listens. */
    private void paint() {
        if (button == null) return;
        boolean lit = on && !stopping;
        button.setBackground(Ui.press(app, Ui.round(lit ? col(R.color.accent) : Box.fill(app), dp(Box.BUTTON / 2)), dp(Box.BUTTON / 2)));
        button.setImageTintList(android.content.res.ColorStateList.valueOf(col(lit ? R.color.onAccent : R.color.text)));
        if (!on) button.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        EditText in = app.box.input;
        if (in != null) in.setHint(!on ? "回复 Claude…" : stopping ? "正在识别…" : held ? "正在听…松开结束" : "正在听…再点麦克风结束");
    }
}
