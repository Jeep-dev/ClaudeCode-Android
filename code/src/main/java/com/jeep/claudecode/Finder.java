package com.jeep.claudecode;

import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /find: the places in a conversation where some words are, marked, and the way between them. */
final class Finder extends Part {
    String findQuery = ""; // lower case; empty when not looking
    String findLine = ""; // the /find line as sent, kept in the box while it looks
    private final List<int[]> findHits = new ArrayList<>(); // {row, which place in the row}
    int findAt;
    private Toast findToast;

    Finder(MainActivity app) { super(app); }

    private static final class Found extends android.text.style.BackgroundColorSpan {
        Found(int color) { super(color); }
    }

    boolean finding() { return !findHits.isEmpty(); }

    /**
     * /find: the places in this conversation's messages and replies where the words are, marked,
     * from the last; ↑↓ go between them. With no words it stops looking.
     */
    void find(String words) {
        findQuery = words.toLowerCase(Locale.ROOT);
        findHits.clear();
        unCache();
        for (int p = 0; !findQuery.isEmpty() && p < app.screen.adapter.getItemCount(); p++) {
            List<String> texts = app.screen.adapter.searchText(p);
            if (texts == null) continue;
            // Counted in the texts the row's views show, in their order, as mark and findSpot count.
            int k = 0;
            for (String text : texts) {
                String s = text.toLowerCase(Locale.ROOT);
                for (int i = s.indexOf(findQuery); i >= 0; i = s.indexOf(findQuery, i + findQuery.length())) findHits.add(new int[]{p, k++});
            }
        }
        if (findHits.isEmpty()) {
            findEnd();
            return;
        }
        findLine = app.box.input.getText().toString().trim();
        app.box.hideKeyboard();
        findGo(findHits.size() - 1);
    }

    void findEnd() {
        findQuery = "";
        findHits.clear();
        unCache();
        for (int i = 0; i < app.screen.list.getChildCount(); i++) mark(app.screen.list.getChildAt(i), app.screen.list.getChildAdapterPosition(app.screen.list.getChildAt(i)));
        app.screen.showJump();
    }

    /** Rows kept aside off screen come back without being bound again: they must not keep old marks. */
    private void unCache() {
        app.screen.list.setItemViewCacheSize(0);
        app.screen.list.setItemViewCacheSize(2); // RecyclerView's default
    }

    /** Goes to a place found: its line a third of the way down the screen. */
    void findGo(int i) {
        findAt = Math.max(0, Math.min(findHits.size() - 1, i));
        int[] at = findHits.get(findAt);
        app.screen.stick = false;
        app.screen.adapter.opened = null;
        app.screen.list.stopScroll();
        LinearLayoutManager lm = (LinearLayoutManager) app.screen.list.getLayoutManager();
        lm.scrollToPositionWithOffset(at[0], app.screen.list.getHeight() / 4);
        app.screen.list.post(() -> {
            if (!finding()) return;
            for (int k = 0; k < app.screen.list.getChildCount(); k++) mark(app.screen.list.getChildAt(k), app.screen.list.getChildAdapterPosition(app.screen.list.getChildAt(k)));
            View row = lm.findViewByPosition(at[0]);
            int y = row == null ? -1 : findSpot(row, at[1]);
            if (y >= 0) app.screen.list.scrollBy(0, y - app.screen.list.getHeight() / 3);
            app.screen.showJump();
        });
        if (findToast != null) findToast.cancel();
        findToast = Toast.makeText(app, (findAt + 1) + " / " + findHits.size(), Toast.LENGTH_SHORT);
        findToast.show();
    }

    /** The text a row shows that /find looks in: a message's own (tagged findable), not labels such as 复制. */
    static void findTexts(View v, List<TextView> out) {
        if (v instanceof TextView) { if (v.getTag(R.id.findable) != null) out.add((TextView) v); return; }
        if (!(v instanceof ViewGroup)) return;
        for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) findTexts(((ViewGroup) v).getChildAt(i), out);
    }

    /** How far down the list the k-th place found in a row is; -1 when it is not shown. */
    private int findSpot(View row, int k) {
        List<TextView> texts = new ArrayList<>();
        findTexts(row, texts);
        int n = 0;
        for (TextView t : texts) {
            String s = t.getText().toString().toLowerCase(Locale.ROOT);
            for (int i = s.indexOf(findQuery); i >= 0; i = s.indexOf(findQuery, i + findQuery.length())) {
                if (n++ < k) continue;
                android.text.Layout layout = t.getLayout();
                if (layout == null) return -1;
                int[] a = new int[2], b = new int[2];
                t.getLocationInWindow(a);
                app.screen.list.getLocationInWindow(b);
                return a[1] - b[1] + t.getTotalPaddingTop() + layout.getLineTop(layout.getLineForOffset(i));
            }
        }
        return -1;
    }

    /** Marks the places found in a row, the one gone to darker; or takes the marks away. */
    void mark(View row, int position) {
        boolean marked = !findQuery.isEmpty() && app.screen.adapter.searchable(position);
        int[] at = finding() ? findHits.get(findAt) : null;
        List<TextView> texts = new ArrayList<>();
        findTexts(row, texts);
        int n = 0;
        for (TextView t : texts) {
            // A message's own text is plain (a user's, code): made one that takes marks.
            if (!(t.getText() instanceof android.text.Spannable)) t.setText(t.getText(), TextView.BufferType.SPANNABLE);
            android.text.Spannable s = (android.text.Spannable) t.getText();
            for (Found f : s.getSpans(0, s.length(), Found.class)) s.removeSpan(f);
            if (!marked) continue;
            String lower = s.toString().toLowerCase(Locale.ROOT);
            for (int i = lower.indexOf(findQuery); i >= 0; i = lower.indexOf(findQuery, i + findQuery.length()), n++) {
                boolean here = at != null && at[0] == position && at[1] == n;
                s.setSpan(new Found(Glass.alpha(col(R.color.accent), here ? 0.55f : 0.2f)),
                        i, Math.min(s.length(), i + findQuery.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
