package com.jeep.claudecode;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.json.JSONObject;

/**
 * Dictation as Claude Code's own /voice does it: the microphone, 16 kHz mono 16-bit, streamed over a
 * WebSocket to Anthropic's speech-to-text (api/ws/speech_to_text/voice_stream), signed in with the
 * Claude.ai account Claude Code is logged in with (its ~/.claude/.credentials.json). The server sends
 * the sentence heard so far as it grows (TranscriptText), and says when it is done (TranscriptEndpoint).
 */
final class VoiceStream {
    interface Listener {
        /** What is heard of the sentence so far (the whole of it each time). */
        void text(String sentence);
        /** The sentence is done: the next goes after it. */
        void endpoint();
        void level(float rms);
        /** It ended: error null when it ended as asked. */
        void closed(String error);
    }

    private static final String HOST = "api.anthropic.com";
    private static final int RATE = 16000, CHUNK = RATE * 2 / 10; // 100 ms of audio

    private final Handler ui;
    /** Who hears it: none while it is only connected ahead (see connect). */
    private volatile Listener out;
    /** Why it ended, for a listener that comes after it did. */
    private volatile String endedWith;
    private volatile boolean recording, closing, done;
    /**
     * The microphone starts at the press, not once connected (that takes up to a second or more, and the
     * first words would be lost): what it hears and anything else to send waits here until the server
     * takes it, then goes in order.
     */
    private final java.util.ArrayList<byte[][]> waiting = new java.util.ArrayList<>();
    private boolean open; // guarded by waiting
    private volatile Socket socket;
    private OutputStream os;
    /** Every write to the socket, and closing it, in order on one thread of its own: never on the main thread. */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    VoiceStream(Handler ui) {
        this.ui = ui;
    }

    /** The OAuth token Claude Code is signed in with, or why there is none. */
    static String token(File rootfs, String[] why) {
        try {
            JSONObject creds = new JSONObject(Userland.read(new java.io.FileInputStream(new File(rootfs, "root/.claude/.credentials.json"))));
            JSONObject o = creds.optJSONObject("claudeAiOauth");
            String t = o == null ? "" : o.optString("accessToken");
            if (t.isEmpty()) { why[0] = "Claude Code 没有用 Claude.ai 账号登录：在终端里运行 claude，用 /login 登录"; return null; }
            if (o.optLong("expiresAt") > 0 && o.optLong("expiresAt") < System.currentTimeMillis()) {
                why[0] = "Claude Code 的登录已过期：发一条消息让 Claude Code 刷新登录，再试";
                return null;
            }
            return t;
        } catch (Exception e) {
            why[0] = "Claude Code 没有用 Claude.ai 账号登录：在终端里运行 claude，用 /login 登录";
            return null;
        }
    }

    /**
     * Connects, on threads of its own, ahead of the press if it can be: the server takes about a second to
     * get ready to listen, and waits (kept alive) however long it is before anything is said.
     */
    void connect(String token, String language, String userAgent) {
        new Thread(() -> run(token, language, userAgent), "voice-stream").start();
    }

    /** Not ended and not taken yet: a press can listen with it. */
    boolean ready() { return !done && !closing && out == null; }

    /** Records into it from now (connected already, or what is said waits until it is). */
    void begin(Listener l) {
        out = l;
        if (done) { String why = endedWith; ui.post(() -> l.closed(why)); return; }
        recording = true;
        new Thread(this::record, "voice-mic").start();
    }

    /** Stops recording; the last words still come, then it closes. */
    void stop() {
        if (!recording) return;
        recording = false;
        closing = true;
        sendText("{\"type\":\"CloseStream\"}");
        // The server closes once it has said all; a silent one is let go.
        ui.postDelayed(this::cancel, 5000);
    }

    /** Ends at once. */
    void cancel() {
        recording = false;
        closing = true;
        Socket s = socket;
        try {
            writer.execute(() -> {
                if (s != null) try { s.close(); } catch (IOException ignored) { /* closing */ }
            });
            writer.shutdown();
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // cancelled before
        }
        end(null);
    }

    private void end(String error) {
        if (done) return;
        endedWith = error;
        done = true;
        Listener l = out;
        if (l != null) ui.post(() -> l.closed(error));
    }

    private void run(String token, String language, String userAgent) {
        try {
            Socket plain = new Socket();
            socket = plain;
            plain.connect(new InetSocketAddress(HOST, 443), 10000);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, HOST, 443, true);
            socket = s;
            // The certificate must be api.anthropic.com's (the token goes over this socket).
            javax.net.ssl.SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(params);
            s.startHandshake();
            os = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            String path = "/api/ws/speech_to_text/voice_stream?encoding=linear16&sample_rate=" + RATE + "&channels=1"
                    + "&endpointing_ms=300&utterance_end_ms=1000&language=" + language + "&use_conversation_engine=true";
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + HOST + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\nSec-WebSocket-Version: 13\r\n"
                    + "Authorization: Bearer " + token + "\r\nUser-Agent: " + userAgent + "\r\n\r\n";
            os.write(req.getBytes(StandardCharsets.UTF_8));
            os.flush();
            String status = readHead(in);
            if (!status.contains(" 101 ")) {
                end(status.contains(" 401 ") || status.contains(" 403 ")
                        ? "Claude Code 的登录不能用来语音识别（" + status.trim() + "）：在终端里运行 claude 重新 /login"
                        : "语音识别连不上：" + status.trim());
                recording = false;
                return;
            }
            // Connected: what was said meanwhile goes first, then all as it comes.
            synchronized (waiting) {
                open = true;
                byte[] keepAlive = "{\"type\":\"KeepAlive\"}".getBytes(StandardCharsets.UTF_8);
                write(1, keepAlive, keepAlive.length);
                for (byte[][] w : waiting) write(w[0][0], w[1], w[1].length);
                waiting.clear();
            }
            Thread keep = new Thread(() -> {
                while (!done && !closing) {
                    try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
                    if (!closing) sendText("{\"type\":\"KeepAlive\"}");
                }
            }, "voice-keepalive");
            keep.start();
            read(in);
        } catch (IOException e) {
            recording = false;
            end(closing ? null : "语音识别断开了：" + e.getMessage());
        }
    }

    private static String readHead(DataInputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            b.append((char) c);
            if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) break;
        }
        String head = b.toString();
        int nl = head.indexOf("\r\n");
        return nl < 0 ? head : head.substring(0, nl);
    }

    private void record() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 4));
        } catch (SecurityException e) {
            end("没有麦克风权限");
            cancel();
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            end("录不到声音：麦克风被占用了？");
            cancel();
            return;
        }
        byte[] buf = new byte[CHUNK];
        try {
            rec.startRecording();
            while (recording) {
                int n = 0;
                while (n < CHUNK && recording) {
                    int r = rec.read(buf, n, CHUNK - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n <= 0) continue;
                long sum = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                    sum += (long) v * v;
                }
                float rms = (float) Math.sqrt(sum / Math.max(1.0, n / 2.0)) / 32768f;
                Listener l = out;
                if (l != null) ui.post(() -> l.level(rms));
                if (recording) send(2, buf, n);
            }
        } finally {
            rec.stop();
            rec.release();
        }
    }

    /** Frames from the server until it closes. */
    private void read(DataInputStream in) throws IOException {
        java.io.ByteArrayOutputStream message = new java.io.ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            if (b0 < 0) break;
            int b1 = in.readUnsignedByte();
            long len = b1 & 0x7f;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            byte[] mask = null;
            if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
            byte[] data = new byte[(int) len];
            in.readFully(data);
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            int op = b0 & 0x0f;
            if (op == 8) break;                               // close
            if (op == 9) { send(10, data, data.length); continue; } // ping: pong
            if (op == 1 || op == 0) {
                message.write(data);
                if ((b0 & 0x80) == 0) continue;               // more to come
                handle(new String(message.toByteArray(), StandardCharsets.UTF_8));
                message.reset();
            }
        }
        end(null);
    }

    private void handle(String json) {
        try {
            JSONObject m = new JSONObject(json);
            switch (m.optString("type")) {
                case "TranscriptText":
                case "TranscriptInterim": {
                    String t = m.optString("data");
                    Listener l = out;
                    if (!t.isEmpty() && l != null) ui.post(() -> l.text(t));
                    break;
                }
                case "TranscriptEndpoint": {
                    Listener l = out;
                    if (l != null) ui.post(l::endpoint);
                    break;
                }
                case "TranscriptError":
                case "error": {
                    String why = m.optString("description", m.optString("message", m.optString("error_code", "识别出错")));
                    end("语音识别出错：" + why);
                    cancel();
                    break;
                }
                default:
                    break;
            }
        } catch (org.json.JSONException ignored) {
            // not JSON: nothing to show
        }
    }

    private void sendText(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        send(1, b, b.length);
    }

    /** One masked frame (a client's frames are masked). */
    private void send(int op, byte[] data, int n) {
        synchronized (waiting) {
            if (!open) {
                if (!done) waiting.add(new byte[][]{{(byte) op}, java.util.Arrays.copyOf(data, n)});
                return;
            }
            write(op, data, n);
        }
    }

    private void write(int op, byte[] data, int n) {
        OutputStream o = os;
        if (o == null) return;
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        byte[] frame;
        int head;
        if (n < 126) { frame = new byte[2 + 4 + n]; frame[1] = (byte) (0x80 | n); head = 2; }
        else if (n < 65536) { frame = new byte[4 + 4 + n]; frame[1] = (byte) (0x80 | 126); frame[2] = (byte) (n >> 8); frame[3] = (byte) n; head = 4; }
        else { frame = new byte[10 + 4 + n]; frame[1] = (byte) (0x80 | 127); for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((long) n >> (8 * (7 - i))); head = 10; }
        frame[0] = (byte) (0x80 | op);
        System.arraycopy(mask, 0, frame, head, 4);
        for (int i = 0; i < n; i++) frame[head + 4 + i] = (byte) (data[i] ^ mask[i % 4]);
        try {
            writer.execute(() -> {
                try {
                    o.write(frame);
                    o.flush();
                } catch (IOException e) {
                    if (!closing) { end("语音识别断开了：" + e.getMessage()); cancel(); }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closed: nothing more is sent
        }
    }
}
