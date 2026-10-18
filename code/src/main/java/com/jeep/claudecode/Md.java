package com.jeep.claudecode;

import android.content.Context;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.linkify.LinkifyPlugin;

/** Markdown in native views: prose through Markwon, fenced code as cards with a copy button. */
final class Md {
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*([^`\\s]*).*$");
    /** A table's second line: |---|:--:| */
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-*:?\\s*)*\\|?\\s*$");
    /** A line that is only a picture: ![alt](where "title") */
    private static final Pattern IMAGE = Pattern.compile("^\\s*!\\[([^\\]]*)\\]\\(\\s*<?([^)>\\s]+)>?(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)\\s*$");

    private final Context c;
    private Markwon markwon;
    /** Bumped when the conversation font changes: text set before is set again. */
    private int fontGen;
    private final Consumer<String> copy;
    private final Consumer<String> openLink;
    /** A picture's path in the userland (absolute, ~/…, or from the conversation's folder) as a file. */
    private final java.util.function.Function<String, java.io.File> files;

    Md(Context c, Consumer<String> copy, Consumer<String> openLink, java.util.function.Function<String, java.io.File> files) {
        this.c = c;
        this.copy = copy;
        this.openLink = openLink;
        this.files = files;
        markwon = build();
    }

    /** The conversation font changed: headings and text take it on their next update. */
    void fontChanged() {
        markwon = build();
        fontGen++;
    }

    private Markwon build() {
        TableTheme table = TableTheme.buildWithDefaults(c)
                .tableBorderColor(Ui.color(c, R.color.border))
                .tableBorderWidth(Ui.dp(c, 1))
                .tableCellPadding(Ui.dp(c, 8))
                .tableHeaderRowBackgroundColor(Ui.color(c, R.color.surface2))
                .tableEvenRowBackgroundColor(Ui.color(c, R.color.bg))
                .tableOddRowBackgroundColor(Ui.color(c, R.color.bg))
                .build();
        return Markwon.builder(c)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(table))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override public void configureTheme(MarkwonTheme.Builder b) {
                        b.codeTextColor(Ui.color(c, R.color.inlineCode))
                                .codeBackgroundColor(Ui.color(c, R.color.surface2))
                                .codeTypeface(Ui.MONO)
                                .codeTextSize((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, c.getResources().getDisplayMetrics()))
                                .codeBlockTextColor(Ui.color(c, R.color.text))
                                .codeBlockBackgroundColor(Ui.color(c, R.color.codeBg))
                                .linkColor(Ui.color(c, R.color.accentPress))
                                .blockQuoteColor(Ui.color(c, R.color.borderStrong))
                                .blockMargin(Ui.dp(c, 18))
                                .bulletWidth(Ui.dp(c, 5))
                                .listItemColor(Ui.color(c, R.color.text2))
                                .headingBreakHeight(0)
                                .headingTypeface(Fonts.body(c))
                                .headingTextSizeMultipliers(new float[]{1.4f, 1.25f, 1.12f, 1f, 1f, 1f})
                                .thematicBreakColor(Ui.color(c, R.color.border));
                    }
                })
                .build();
    }

    /**
     * Text, code and table segments of a reply; an unclosed fence (still streaming) is code.
     * Tables are views of their own: Markwon's measure their rows after drawing, so a table being
     * streamed changed height on every update and shook the list.
     */
    static List<String[]> split(String text) {
        List<String[]> out = new ArrayList<>();
        StringBuilder prose = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher picture = lines[i].contains("![") ? IMAGE.matcher(lines[i]) : null;
            if (picture != null && picture.matches()) {
                if (prose.toString().trim().length() > 0) out.add(new String[]{"text", prose.toString()});
                prose.setLength(0);
                out.add(new String[]{"image", picture.group(2), picture.group(1)});
                continue;
            }
            if (lines[i].contains("|") && i + 1 < lines.length && lines[i + 1].contains("-")
                    && TABLE_RULE.matcher(lines[i + 1]).matches()) {
                StringBuilder table = new StringBuilder(lines[i]).append('\n').append(lines[i + 1]);
                int j = i + 2;
                for (; j < lines.length && lines[j].contains("|") && !lines[j].trim().isEmpty(); j++) table.append('\n').append(lines[j]);
                if (prose.toString().trim().length() > 0) out.add(new String[]{"text", prose.toString()});
                prose.setLength(0);
                out.add(new String[]{"table", table.toString()});
                i = j - 1;
                continue;
            }
            Matcher m = FENCE.matcher(lines[i]);
            if (!m.matches()) {
                prose.append(lines[i]).append('\n');
                continue;
            }
            String fence = m.group(1);
            String lang = m.group(2);
            StringBuilder code = new StringBuilder();
            int j = i + 1;
            for (; j < lines.length; j++) {
                if (lines[j].trim().startsWith(fence) && lines[j].trim().replace(fence.substring(0, 1), "").isEmpty()) break;
                if (code.length() > 0) code.append('\n');
                code.append(lines[j]);
            }
            if (prose.toString().trim().length() > 0) out.add(new String[]{"text", prose.toString()});
            prose.setLength(0);
            out.add(new String[]{"code", code.toString(), lang});
            i = j;
        }
        if (prose.toString().trim().length() > 0) out.add(new String[]{"text", prose.toString()});
        return out;
    }

    /**
     * Fills the container with views for the reply text, reusing the views already there (a reply
     * being streamed keeps its views, their widths and the reader's place).
     */
    void render(LinearLayout box, String text) {
        List<String[]> parts = split(text);
        for (int i = 0; i < parts.size(); i++) {
            String[] p = parts.get(i);
            View old = i < box.getChildCount() ? box.getChildAt(i) : null;
            if (old != null && p[0].equals(old.getTag())) {
                if ("code".equals(p[0])) updateCode(old, p[1], p[2]);
                else if ("table".equals(p[0])) fillTable(old, p[1]);
                else if ("image".equals(p[0])) { if (!p[1].equals(old.getTag(R.id.markdown))) { box.removeViewAt(i); box.addView(picture(p[1], p[2]), i); } }
                else setProse((TextView) old, p[1]);
                continue;
            }
            while (box.getChildCount() > i) box.removeViewAt(i);
            View v = "code".equals(p[0]) ? code(p[1], p[2]) : "table".equals(p[0]) ? table(p[1])
                    : "image".equals(p[0]) ? picture(p[1], p[2]) : prose(p[1]);
            if (i > 0) Ui.margins(v, c, 0, 14, 0, 0);
            box.addView(v);
        }
        while (box.getChildCount() > parts.size()) box.removeViewAt(parts.size());
    }

    TextView prose(String markdown) {
        TextView t = new TextView(c);
        t.setTag("text");
        t.setLayoutParams(Ui.fill());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        t.setTextColor(Ui.color(c, R.color.text));
        t.setLineSpacing(0, 1.05f);
        t.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        selectable(t);
        setProse(t, markdown);
        return t;
    }

    private void setProse(TextView t, String markdown) {
        String md = markdown.trim();
        if (md.equals(t.getTag(R.id.markdown)) && Integer.valueOf(fontGen).equals(t.getTag(R.id.font))) return;
        t.setTag(R.id.markdown, md);
        t.setTag(R.id.font, fontGen);
        t.setTypeface(Fonts.body(c));
        markwon.setMarkdown(t, md);
        if (t.getWidth() == 0 && md.contains("|")) {
            // Tables size their columns from the text's width: render again once that is known.
            t.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int top, int r, int b, int ol, int ot, int or, int ob) {
                    if (r - l == 0) return;
                    t.removeOnLayoutChangeListener(this);
                    t.post(() -> markwon.setMarkdown(t, (String) t.getTag(R.id.markdown)));
                }
            });
        }
    }

    private View table(String markdown) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setTag("table");
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(Ui.fill());
        android.widget.TableLayout t = new android.widget.TableLayout(c);
        t.setStretchAllColumns(true);
        t.setBackgroundColor(Ui.color(c, R.color.border));
        int line = Math.max(1, Ui.dp(c, 1));
        t.setPadding(line, line, 0, 0);
        scroll.addView(t, new android.widget.FrameLayout.LayoutParams(-1, -2));
        fillTable(scroll, markdown);
        return scroll;
    }

    /** Rows of cells, each measured as it is laid out: the same height on every update. */
    private void fillTable(View scroll, String markdown) {
        if (markdown.equals(scroll.getTag(R.id.markdown)) && Integer.valueOf(fontGen).equals(scroll.getTag(R.id.font))) return;
        scroll.setTag(R.id.markdown, markdown);
        scroll.setTag(R.id.font, fontGen);
        android.widget.TableLayout t = (android.widget.TableLayout) ((android.view.ViewGroup) scroll).getChildAt(0);
        String[] lines = markdown.split("\n");
        List<String> aligns = cells(lines[1]);
        int line = Math.max(1, Ui.dp(c, 1));
        int maxCell = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.7f);
        int r = 0;
        for (int i = 0; i < lines.length; i++) {
            if (i == 1) continue;
            List<String> cells = cells(lines[i]);
            android.widget.TableRow row = r < t.getChildCount() ? (android.widget.TableRow) t.getChildAt(r) : null;
            if (row == null) {
                row = new android.widget.TableRow(c);
                t.addView(row);
            }
            int n = Math.max(cells.size(), aligns.size());
            for (int k = 0; k < n; k++) {
                TextView cell = k < row.getChildCount() ? (TextView) row.getChildAt(k) : null;
                if (cell == null) {
                    cell = new TextView(c);
                    cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                    cell.setTextColor(Ui.color(c, R.color.text));
                    cell.setLineSpacing(0, 1.1f);
                    cell.setMaxWidth(maxCell);
                    cell.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 10), Ui.dp(c, 7));
                    cell.setBackgroundColor(Ui.color(c, i == 0 ? R.color.surface2 : R.color.bg));
                    android.widget.TableRow.LayoutParams lp = new android.widget.TableRow.LayoutParams(-2, -1);
                    lp.setMargins(0, 0, line, line);
                    row.addView(cell, lp);
                }
                String a = k < aligns.size() ? aligns.get(k) : "";
                cell.setGravity(a.startsWith(":") && a.endsWith(":") ? android.view.Gravity.CENTER_HORIZONTAL
                        : a.endsWith(":") ? android.view.Gravity.END : android.view.Gravity.START);
                String md = k < cells.size() ? cells.get(k) : "";
                cell.setTypeface(i == 0 ? android.graphics.Typeface.create(Fonts.body(c), android.graphics.Typeface.BOLD) : Fonts.body(c));
                if (!md.equals(cell.getTag(R.id.markdown)) || cell.getTag(R.id.font) == null || (int) cell.getTag(R.id.font) != fontGen) {
                    cell.setTag(R.id.font, fontGen);
                    cell.setTag(R.id.markdown, md);
                    CharSequence styled = markwon.toMarkdown(md);
                    int end = styled.length();
                    while (end > 0 && Character.isWhitespace(styled.charAt(end - 1))) end--;
                    cell.setText(styled.subSequence(0, end));
                }
            }
            while (row.getChildCount() > n) row.removeViewAt(n);
            r++;
        }
        while (t.getChildCount() > r) t.removeViewAt(r);
    }

    /** A table line's cells: | a | b | → [a, b]; \| stays in its cell. */
    private static List<String> cells(String line) {
        String s = line.trim();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length() - 1);
        List<String> out = new ArrayList<>();
        for (String cell : s.split("(?<!\\\\)\\|", -1)) out.add(cell.trim().replace("\\|", "|"));
        return out;
    }

    /** A picture in the reply: a file Claude made or a web address; a tap shows it whole. */
    private View picture(String where, String alt) {
        LinearLayout box = Ui.column(c);
        box.setTag("image");
        box.setTag(R.id.markdown, where);
        box.setLayoutParams(Ui.fill());
        boolean remote = where.startsWith("http://") || where.startsWith("https://") || where.startsWith("data:");
        java.io.File file = remote ? null : files.apply(where.startsWith("file://") ? where.substring(7) : where);
        String src = remote ? where : null;
        ImageView img = new ImageView(c);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_START);
        img.setMaxHeight((int) (c.getResources().getDisplayMetrics().heightPixels * 0.6f));
        img.setBackground(Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 12), Ui.dp(c, 1), Ui.color(c, R.color.border)));
        img.setClipToOutline(true);
        img.setContentDescription(alt);
        img.setOnClickListener(v -> Images.show(c, src, file));
        box.addView(img, Ui.wrap());
        Images.load(img, src, file, c.getResources().getDisplayMetrics().widthPixels, () -> {
            box.removeAllViews();
            TextView t = Ui.text(c, "图片打不开：" + where, 13, R.color.text3);
            t.setTypeface(Ui.MONO);
            box.addView(t);
        });
        return box;
    }

    private void updateCode(View card, String code, String lang) {
        TextView name = card.findViewWithTag("lang");
        TextView body = card.findViewWithTag("body");
        name.setText(lang.isEmpty() ? "代码" : lang);
        if (!code.contentEquals(body.getText())) body.setText(Highlight.code(c, code, lang));
        card.setTag(R.id.markdown, code);
    }

    private View code(String code, String lang) {
        LinearLayout card = Ui.column(c);
        card.setTag("code");
        card.setTag(R.id.markdown, code);
        card.setLayoutParams(Ui.fill());
        card.setBackground(Ui.round(Ui.color(c, R.color.codeBg), Ui.dp(c, 12), Ui.dp(c, 1), Ui.color(c, R.color.border)));
        card.setClipToOutline(true);
        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 4), 0);
        head.setMinimumHeight(Ui.dp(c, 34));
        TextView name = Ui.text(c, lang.isEmpty() ? "代码" : lang, 12.5f, R.color.text3);
        name.setTag("lang");
        head.addView(name, Ui.weight());
        LinearLayout copyBtn = Ui.row(c);
        copyBtn.setPadding(Ui.dp(c, 8), Ui.dp(c, 5), Ui.dp(c, 8), Ui.dp(c, 5));
        copyBtn.setBackground(Ui.press(c, null, Ui.dp(c, 7)));
        copyBtn.addView(Ui.icon(c, R.drawable.ic_copy, 14, R.color.text2));
        TextView label = Ui.text(c, "复制", 12.5f, R.color.text2);
        label.setPadding(Ui.dp(c, 5), 0, 0, 0);
        copyBtn.addView(label);
        copyBtn.setOnClickListener(v -> copy.accept((String) card.getTag(R.id.markdown)));
        head.addView(copyBtn);
        card.addView(head, Ui.fill());
        View line = new View(c);
        line.setBackgroundColor(Ui.color(c, R.color.border));
        card.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(c, 1)));
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        TextView body = Ui.text(c, Highlight.code(c, code, lang), 13, R.color.text);
        body.setTag("body");
        body.setTypeface(Ui.MONO);
        body.setLineSpacing(0, 1.12f);
        body.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12));
        body.setHorizontallyScrolling(true);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        card.addView(scroll, Ui.fill());
        return card;
    }

    /** Selectable text whose links still open with a tap. */
    void selectable(TextView t) {
        t.setTextIsSelectable(true);
        t.setOnTouchListener((v, e) -> {
            if (e.getAction() != MotionEvent.ACTION_UP || !(t.getText() instanceof Spanned)) return false;
            if (t.hasSelection() || e.getEventTime() - e.getDownTime() > 350) return false;
            Layout layout = t.getLayout();
            if (layout == null) return false;
            int x = (int) e.getX() - t.getTotalPaddingLeft() + t.getScrollX();
            int y = (int) e.getY() - t.getTotalPaddingTop() + t.getScrollY();
            int line = layout.getLineForVertical(y);
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false;
            int off = layout.getOffsetForHorizontal(line, x);
            ClickableSpan[] links = ((Spanned) t.getText()).getSpans(off, off, ClickableSpan.class);
            if (links.length == 0) return false;
            if (links[0] instanceof android.text.style.URLSpan) openLink.accept(((android.text.style.URLSpan) links[0]).getURL());
            else links[0].onClick(t);
            return true;
        });
    }
}
