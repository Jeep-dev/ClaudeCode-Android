package com.jeep.claudecode

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.widget.ImageView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Speaking into the box: the mic button beside send. It listens with Claude Code's own dictation (its
 * /voice, on the Claude.ai account it is logged in with: VoiceStream) unless a recognizer of the phone
 * is picked in Settings.
 * Tapped, it listens until tapped again (a pause starts the next sentence); held, it listens
 * until let go. The words go in where the cursor is, as they are heard.
 */
class Voice(app: MainActivity) : Part(app) {
    private var button: ImageView? = null
    private var rec: SpeechRecognizer? = null
    private var listen: Intent? = null
    /** Listening; held down (ends when let go); stopping (the last words still to come). */
    private var on = false
    private var held = false
    private var stopping = false
    /** Between two sentences (the next not started yet); anything heard since it began. */
    private var between = false
    private var heard = false
    /** The next sentence was just asked for without letting go of the last (a refusal is then retried, not shown). */
    private var restarted = false
    /** The press that stopped it: its release starts nothing. */
    private var pressStopped = false
    private var pressedAt = 0L
    /** The conversation the words go into, and where in its box: the words heard so far of this sentence. */
    private var chat: Chat? = null
    private var spanStart = 0
    private var spanEnd = 0
    /** Sentences heard as nothing, one after the other: twice, and a tap's listening ends by itself. */
    private var quiet = 0
    /** Counts each listening: a restart left over from one ended is not taken by the next. */
    private var session = 0
    private val giveUp = Runnable { finish() }
    /** Claude Code's dictation, while it listens with it. */
    private var stream: VoiceStream? = null
    /**
     * The sentence as last heard (recognizers send it whole each time), and how much of it was given up to
     * the box: the user edited the box meanwhile, so only what is heard after that goes in, at the cursor
     * (else words deleted would come back with the next words heard).
     */
    private var sentence = ""
    private var givenUp = 0
    /** A connection to Claude Code's dictation made ahead of the press (see warm). */
    private var spare: VoiceStream? = null
    /** While the app is in front, the connection made ahead is made again now and then, never left to go stale. */
    private val renewSpare: Runnable = Runnable {
        spare?.cancel()
        spare = null
        warm()
    }
    private val dropSpare = Runnable {
        ui.removeCallbacks(renewSpare)
        spare?.cancel()
        spare = null
    }
    /** The box is being written by this, not by the user. */
    private var writing = false

    /** The mic button, in the box just built. */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(b: ImageView) {
        cancel()
        button = b
        b.contentDescription = "语音输入"
        app.box.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(t: CharSequence, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(t: Editable) {
                if (!on || writing) return
                // Edited by hand while listening: what is written stays as the user left it; the words
                // heard next go where the cursor is.
                givenUp = sentence.length
                val at = app.box.input.selectionEnd
                spanStart = if (at < 0) t.length else at
                spanEnd = spanStart
            }
        })
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    pressStopped = on
                    if (on) {
                        stop()
                    } else {
                        pressedAt = e.eventTime
                        start(true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (!pressStopped && on && held) {
                        if (e.eventTime - pressedAt < HOLD_MS) { // a tap: on till tapped again
                            held = false
                            paint()
                        } else {
                            stop()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (on && held) stop()
                }
            }
            true
        }
        // Tapped by the accessibility service (no touch): on and off.
        b.setOnClickListener { if (on) stop() else start(false) }
        paint()
    }

    /** A new listening starts: nothing heard of it yet. */
    private fun begin(hold: Boolean) {
        on = true
        session++
        held = hold
        stopping = false
        between = false
        heard = false
        restarted = false
        sentence = ""
        givenUp = 0
    }

    private fun start(hold: Boolean) {
        if (app.checkSelfPermission(MIC) != PackageManager.PERMISSION_GRANTED) {
            app.requestPermissions(arrayOf(MIC), ASK_MIC)
            return
        }
        val input = app.box.input
        chat = app.current
        spanStart = max(0, min(input.selectionStart, input.selectionEnd))
        spanEnd = max(spanStart, max(input.selectionStart, input.selectionEnd))
        if (input.selectionStart < 0) {
            spanStart = input.length()
            spanEnd = spanStart
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        listen = intent
        // No EXTRA_CALLING_PACKAGE: the claude.ai app's recognizer checks the microphone for that package
        // under its own uid, finds no such pair and refuses ("caller without permission").
        if (claudeCode()) {
            startStream(hold)
            return
        }
        val chosen = chosen()
        if (chosen == null && !SpeechRecognizer.isRecognitionAvailable(app)) {
            // No recognizer to listen with here: the system's own voice input page, if there is one.
            if (intent.resolveActivity(app.packageManager) == null) {
                toast("这台手机没有语音识别服务：可以用输入法自带的语音键")
                return
            }
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            @Suppress("DEPRECATION")
            app.startActivityForResult(intent, MainActivity.VOICE)
            return
        }
        val r = if (chosen != null) SpeechRecognizer.createSpeechRecognizer(app, chosen) else SpeechRecognizer.createSpeechRecognizer(app)
        rec = r
        r.setRecognitionListener(listener)
        begin(hold)
        quiet = 0
        r.startListening(intent)
        paint()
    }

    /** Listens with Claude Code's dictation (the default; Settings → 对话 → 语音识别). */
    private fun claudeCode(): Boolean = prefs.getString("voice", "claudecode") == "claudecode"

    private fun startStream(hold: Boolean) {
        // The connection made ahead (see warm), else one now: what is said meanwhile waits, not lost.
        var st = spare?.takeIf { it.ready() }
        spare = null
        ui.removeCallbacks(renewSpare)
        if (st == null) {
            val token = VoiceStream.token(userland.rootfs)
            st = connectNew(token.getOrElse {
                toast(it.message ?: "")
                return
            })
        }
        val self = st
        stream = st
        begin(hold)
        st.begin(object : VoiceStream.Listener {
            override fun text(sentence: String) {
                if (stream === self) write(sentence)
            }

            override fun endpoint() {
                if (stream === self) nextSentence()
            }

            override fun level(rms: Float) {
                val b = button
                if (stream !== self || b == null) return
                val l = (rms * 8f).coerceIn(0f, 1f)
                b.animate().scaleX(1f + 0.14f * l).scaleY(1f + 0.14f * l).setDuration(90).start()
            }

            override fun closed(error: String?) {
                if (stream !== self) return
                val said = heard
                stream = null
                finish()
                if (error != null) toast(error) else if (!said) toast("没听清，请再说一次")
                warm() // the next press, as quick
            }
        })
        paint()
    }

    private fun connectNew(token: String): VoiceStream {
        val lang = Locale.getDefault().language
        val version = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (ignored: PackageManager.NameNotFoundException) {
            "" // ours
        }
        val st = VoiceStream(ui)
        st.connect(token, lang.ifEmpty { "en" }, "ClaudeCode-Android/$version")
        return st
    }

    /**
     * Connects Claude Code's dictation ahead of the press (the server takes about a second to get ready to
     * listen): whenever the app comes to the front, and after each use; kept while the app is in front (made
     * again every few minutes), let go when it is left.
     */
    fun warm() {
        if (!claudeCode() || on || spare?.ready() == true) return
        if (app.checkSelfPermission(MIC) != PackageManager.PERMISSION_GRANTED) return // asked at the first press
        val token = VoiceStream.token(userland.rootfs).getOrNull() ?: return
        spare = connectNew(token)
        ui.removeCallbacks(renewSpare)
        ui.postDelayed(renewSpare, 4 * 60_000L)
    }

    /** The app is left: listening ends, and the connection made ahead is let go. */
    fun pause() {
        cancel()
        dropSpare.run()
    }

    /** The recognizers on the phone (Google's, the claude.ai app's, a voice keyboard's, …). */
    private fun services(): List<ResolveInfo> =
        app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)

    /** The recognizer picked in Settings, while it is still there; null: the system's default. */
    private fun chosen(): ComponentName? {
        val kept = prefs.getString("voiceService", "") ?: ""
        if (kept.isEmpty()) return null
        val c = ComponentName.unflattenFromString(kept) ?: return null
        return c.takeIf { services().any { r -> r.serviceInfo.packageName == c.packageName && r.serviceInfo.name == c.className } }
    }

    private fun label(c: ComponentName): String = try {
        app.packageManager.getApplicationInfo(c.packageName, 0).loadLabel(app.packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        c.packageName
    }

    /** The system's default recognizer (Settings → voice input), if one is set. */
    private fun systemDefault(): ComponentName? {
        val s = Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
        return if (s.isNullOrEmpty()) null else ComponentName.unflattenFromString(s)
    }

    /** What Settings shows: the recognizer in use. */
    fun serviceLabel(): String {
        if (claudeCode()) return "Claude Code"
        chosen()?.let { return label(it) }
        val d = systemDefault()
        return if (d == null) "系统默认" else "系统默认 · " + label(d)
    }

    /** Picks the recognizer to listen with: the system's default, or one of those on the phone. */
    fun serviceSheet(after: Runnable) {
        val sheet = sheets.sheet("语音识别", null)
        val card = sheets.card(sheet)
        val cc = claudeCode()
        val kept = if (cc) null else if (chosen() == null) "" else prefs.getString("voiceService", "")
        val use = { voice: String, service: String? ->
            cancel()
            val e = prefs.edit().putString("voice", voice)
            if (service != null) e.putString("voiceService", service)
            e.apply()
            sheet.dismiss()
            after.run()
        }
        sheets.option(card, 0, "Claude Code", "/voice 同款，用 Claude.ai 账号", false) { use("claudecode", null) }
        if (cc) sheets.check(card)
        val d = systemDefault()
        sheets.option(card, 0, "系统默认", d?.let(::label), false) { use("android", "") }
        if (kept == "") sheets.check(card)
        for (r in services()) {
            val c = ComponentName(r.serviceInfo.packageName, r.serviceInfo.name)
            val flat = c.flattenToString()
            sheets.option(card, 0, label(c), c.packageName, false) { use("android", flat) }
            if (flat == kept) sheets.check(card)
        }
        sheet.show()
    }

    /** The microphone may be used now (asked when the button was first pressed): it listens, till tapped again. */
    fun granted(yes: Boolean) {
        if (yes) start(false) else toast("没有麦克风权限：可以在系统设置里给这个 App 打开")
    }

    /** Ends listening; the words still on their way are written in. */
    fun stop() {
        if (!on || stopping) return
        val st = stream
        if (st != null) {
            stopping = true
            held = false
            st.stop()
            paint()
            return
        }
        if (between) {
            if (!heard) toast("没听清，请再说一次")
            finish()
            return
        }
        stopping = true
        held = false
        rec?.stopListening()
        ui.postDelayed(giveUp, 2500) // a recognizer that never answers once stopped
        paint()
    }

    /** Ends listening at once (sent, another conversation, the app left): what is written stays. */
    fun cancel() {
        stream?.let {
            stream = null
            it.cancel()
        }
        rec?.cancel()
        finish()
    }

    private fun finish() {
        ui.removeCallbacks(giveUp)
        stream?.let {
            stream = null
            it.cancel()
        }
        rec?.destroy()
        rec = null
        session++
        on = false
        held = false
        stopping = false
        between = false
        paint()
    }

    /** The words from the system's voice input page (where there was no recognizer to use here). */
    fun picked(result: Int, data: Intent?) {
        if (result != Activity.RESULT_OK || data == null) return
        val said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (!said.isNullOrEmpty()) write(said[0])
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            restarted = false
        }

        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(type: Int, params: Bundle?) {}

        override fun onRmsChanged(rms: Float) {
            val b = button
            if (b == null || !on) return
            val level = ((rms + 2f) / 12f).coerceIn(0f, 1f)
            b.animate().scaleX(1f + 0.14f * level).scaleY(1f + 0.14f * level).setDuration(90).start()
        }

        override fun onPartialResults(b: Bundle?) {
            val t = first(b)
            if (!t.isNullOrEmpty()) write(t)
        }

        override fun onResults(b: Bundle?) {
            val t = first(b)
            if (!t.isNullOrEmpty()) {
                write(t)
                quiet = 0
            } else {
                quiet++
            }
            nextSentence()
            next()
        }

        override fun onError(error: Int) {
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                quiet++
                spanEnd = max(spanStart, spanEnd) // what was partly heard stays
                spanStart = spanEnd
                next()
                return
            }
            if (restarted && on && !stopping && (error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                retry()
                return
            }
            val was = on
            finish()
            var why = message(error)
            // Refused by a recognizer picked in Settings: its own microphone, not this app's.
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && chosen() != null) {
                why = "这个语音识别服务不让用麦克风：可以到 设置 → 对话 → 语音识别 换一个"
            }
            if (was && why != null) toast(why)
        }
    }

    /** After a sentence: listens for the next while held or tapped on (two silent ones end a tap's), otherwise ends. */
    private fun next() {
        if (!on) return
        if (stopping || (!held && quiet >= 2)) {
            if (!heard) toast("没听清，请再说一次")
            finish()
            return
        }
        // The next sentence at once, so its first words are not lost.
        restarted = true
        rec?.startListening(listen)
    }

    /** A recognizer that would not take the next sentence at once: let go of the last, and started again a moment later. */
    private fun retry() {
        restarted = false
        rec?.cancel()
        between = true
        val at = session
        ui.postDelayed({
            if (at != session || !on) return@postDelayed
            between = false
            if (!stopping) rec?.startListening(listen)
        }, 120)
    }

    /** Puts what is heard of this sentence in its place in the box, in place of what was heard of it before. */
    private fun write(text: String) {
        if (app.current !== chat) {
            cancel()
            return
        }
        sentence = text
        var t = text.substring(min(givenUp, text.length))
        val input = app.box.input
        val e = input.text
        val s = min(spanStart, e.length)
        val end = max(s, min(spanEnd, e.length))
        heard = true
        if (t.isNotEmpty() && s > 0 && spaced(e[s - 1], t[0])) t = " $t"
        writing = true
        try {
            e.replace(s, end, t)
        } finally {
            writing = false
        }
        spanStart = s
        spanEnd = s + t.length
        input.setSelection(spanEnd)
    }

    /** A sentence is done: the next one goes after it, whole. */
    private fun nextSentence() {
        spanStart = spanEnd
        sentence = ""
        givenUp = 0
    }

    /** The button as it stands: plain, or filled while it listens. */
    private fun paint() {
        val b = button ?: return
        val lit = on && !stopping
        b.background = Ui.press(app, Ui.round(if (lit) col(R.color.accent) else Box.fill(app), dp(Box.BUTTON / 2)), dp(Box.BUTTON / 2))
        b.imageTintList = ColorStateList.valueOf(col(if (lit) R.color.onAccent else R.color.text))
        if (!on) b.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        app.box.input.hint = if (!on) "回复 Claude…" else if (stopping) "正在识别…" else if (held) "正在听…松开结束" else "正在听…再点麦克风结束"
    }

    companion object {
        const val ASK_MIC = 3
        private const val MIC = Manifest.permission.RECORD_AUDIO
        /** A press longer than this is holding the button down to talk; a shorter one is a tap. */
        private const val HOLD_MS = 350L

        /**
         * The language to listen for, as recognizers know it: language and country only ("zh-CN", "en-US").
         * The system's own tag may carry a script ("zh-Hans-CN"), which some take for a language they do not
         * have, and then listen for another.
         */
        private fun language(): String {
            val l = Locale.getDefault()
            val lang = l.language
            var country = l.country
            if (lang == "zh" && country.isEmpty()) country = if (l.script == "Hant") "TW" else "CN"
            return if (country.isEmpty()) lang else "$lang-$country"
        }

        private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        private fun message(error: Int): String? = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限：可以在系统设置里给这个 App 打开"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别连不上网络"
            SpeechRecognizer.ERROR_AUDIO -> "录不到声音"
            SpeechRecognizer.ERROR_SERVER -> "语音识别服务出错"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，稍后再试"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "语音识别不支持当前语言"
            else -> null // the client's own (stopped, cancelled): nothing to say
        }

        /** Words in letters after words in letters are spaced; Chinese is not. */
        private fun spaced(before: Char, first: Char): Boolean {
            val latinBefore = before.code < 128 && (Character.isLetterOrDigit(before) || ".,!?;:".indexOf(before) >= 0)
            return latinBefore && first.code < 128 && Character.isLetterOrDigit(first)
        }
    }
}
