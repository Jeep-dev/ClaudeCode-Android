package com.jeep.claudecode;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Files picked for a message: read here (a picture brought down to what the model takes, a file only checked), put in the box by Box. */
final class Attachments extends Part {
    Attachments(MainActivity app) { super(app); }

    void pickFiles() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            app.startActivityForResult(pick, MainActivity.PICK_FILES);
        } catch (android.content.ActivityNotFoundException e) {
            toast("没有可用的文件选择器");
        }
    }

    /** Files picked (the result of pickFiles): each is read off the main thread, then put in the box of the conversation open now. */
    void picked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        Chat chat = app.current;
        for (Uri uri : uris.subList(0, Math.min(8, uris.size()))) {
            work.execute(() -> {
                Chat.Attachment attached = readAttachment(uri);
                ui.post(() -> {
                    if (attached == null) return;
                    chat.attachments.add(attached);
                    app.screen.updateChrome();
                });
            });
        }
    }

    private Chat.Attachment readAttachment(Uri uri) {
        String name = "file";
        long size = -1;
        try (Cursor c = app.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && c.getString(n) != null) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (RuntimeException ignored) {
            // name stays "file"
        }
        String type = app.getContentResolver().getType(uri);
        try {
            if (type != null && type.matches("image/(png|jpe?g|gif|webp|bmp|heic|heif|avif)")) {
                // Claude Code scales a picture for the model itself and keeps the original, whose path,
                // size and scale it gives the model: one it reads goes as it is, up to 10 MB (held in
                // memory as it is sent). HEIC, HEIF and AVIF, which it cannot read, and a larger one go
                // as JPEG of up to 4096 px.
                byte[] raw = type.matches("image/(heic|heif|avif)") ? null : readUpTo(uri, 10 << 20);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = app.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                int longer = Math.max(o.outWidth, o.outHeight), sample = 1;
                // Decoded only as large as needed: for the thread's copy (2000 px), or for the JPEG sent (4096 px).
                if (raw != null) while (longer / (sample * 2) >= 2000) sample *= 2;
                else while (longer / sample > 4096) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = app.getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o); }
                if (bmp == null) { ui.post(() -> toast("这张图片读不了")); return null; }
                byte[] sent = raw != null ? raw : jpeg(bmp, 4096, 95);
                Bitmap thumb = Bitmap.createScaledBitmap(bmp, dp(64), Math.max(1, dp(64) * bmp.getHeight() / bmp.getWidth()), true);
                Chat.Attachment a = new Chat.Attachment(true, name, raw == null || type.equals("image/jpg") ? "image/jpeg" : type,
                        Base64.encodeToString(sent, Base64.NO_WRAP), null, sent.length, thumb);
                // The thread keeps a copy to show, of the size the model sees, not the picture sent.
                int w = raw != null ? o.outWidth : bmp.getWidth(), h = raw != null ? o.outHeight : bmp.getHeight();
                a.shown = "data:image/jpeg;base64," + Base64.encodeToString(jpeg(bmp, modelLonger(w, h), 92), Base64.NO_WRAP);
                if (thumb != bmp) bmp.recycle(); // the same bitmap when it is already thumbnail size
                return a;
            }
            // A file is read only when it is saved, straight into the folder: never held here.
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
            }
            return new Chat.Attachment(false, name, type, null, uri, size, null);
        } catch (Exception e) {
            ui.post(() -> toast("这个文件读不了"));
            return null;
        }
    }

    /** The bytes at uri, or null when there are more than max. */
    private byte[] readUpTo(Uri uri, int max) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("unreadable");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > max) return null;
            }
        }
        return out.toByteArray();
    }

    /** The longer side of a w x h picture as the model sees it: Claude Code brings it down to 2000 px
     *  (as tried), then the API to at most 4784 visual tokens of 28 x 28 px (Claude 4.7 and later). */
    private static int modelLonger(int w, int h) {
        double s = Math.min(1, 2000.0 / Math.max(w, h));
        while (Math.ceil(w * s / 28) * Math.ceil(h * s / 28) > 4784) s *= 0.99;
        return (int) Math.round(Math.max(w, h) * s);
    }

    /** A picture as JPEG, scaled down to fit maxPx, on white (JPEG has no transparency). */
    private static byte[] jpeg(Bitmap bmp, int maxPx, int quality) {
        float scale = Math.min(1f, (float) maxPx / Math.max(bmp.getWidth(), bmp.getHeight()));
        Bitmap out = Bitmap.createBitmap(Math.max(1, Math.round(bmp.getWidth() * scale)),
                Math.max(1, Math.round(bmp.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        canvas.scale(scale, scale);
        // Filtered: unfiltered scaling drops pixels and leaves text jagged and blurred.
        canvas.drawBitmap(bmp, 0, 0, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, quality, jpeg);
        out.recycle();
        return jpeg.toByteArray();
    }
}
