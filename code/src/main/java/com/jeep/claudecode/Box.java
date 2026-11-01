package com.jeep.claudecode;

import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The box the message is written in: the text, the send button, the menu of / commands and the files waiting to be sent. */
final class Box extends Part {
    private ImageView sendBtn;
    EditText input;
    /** The files waiting to be sent (a row in a scroll of its own), and the / commands to pick from (a column). */
    private LinearLayout attachments, slashMenu;
    private HorizontalScrollView attachScroll;
    ScrollView slashScroll;
    /** The crab on top of the box, or null when it is turned off. */
    private Crab crab;

    Box(MainActivity app) { super(app); }

    /** The box, built (again after a restyle) for the layout in use. */
    View buildComposer() {
        LinearLayout wrap = Ui.column(app);
        wrap.setPadding(dp(32), dp(4), dp(32), dp(18));
        LinearLayout card;
        if (app.screen.immersive) {
            card = new Glass.Pane(app, app.screen.host, dp(26), col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                    Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border));
            card.setOrientation(LinearLayout.VERTICAL);
        } else {
            card = Ui.column(app);
            card.setBackground(Ui.round(col(R.color.surface), dp(26), dp(1), col(R.color.border)));
            card.setElevation(dp(2));
        }
        card.setPadding(dp(6), dp(4), dp(6), dp(4));

        crab = Themes.crab(app) ? new Crab(app) : null;
        if (crab != null) {
            wrap.addView(crab, new LinearLayout.LayoutParams(-2, -2));
            Ui.margins(crab, app, 4, 0, 0, 0);
        }

        slashScroll = new ScrollView(app) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.min(dp(300),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.4f)), MeasureSpec.AT_MOST));
            }
        };
        slashMenu = Ui.column(app);
        slashScroll.addView(slashMenu);
        slashScroll.setVisibility(View.GONE);
        card.addView(slashScroll, Ui.fill());

        attachScroll = new HorizontalScrollView(app);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachments = Ui.row(app);
        attachments.setPadding(dp(6), dp(4), dp(6), dp(4));
        attachScroll.addView(attachments);
        attachScroll.setVisibility(View.GONE);
        card.addView(attachScroll, Ui.fill());

        // One line: + , the text, send (or stop while a reply runs and nothing is typed).
        LinearLayout row = Ui.row(app);
        row.setGravity(Gravity.BOTTOM);
        ImageView attach = Ui.iconButton(app, R.drawable.ic_plus, 40, 22, R.color.text2);
        attach.setOnClickListener(v -> app.attachments.pickFiles());
        row.addView(attach, new LinearLayout.LayoutParams(dp(40), dp(40)));

        input = new EditText(app);
        input.setBackground(null);
        input.setHintTextColor(col(R.color.text3));
        input.setTextColor(col(R.color.text));
        input.setTextSize(16);
        input.setPadding(dp(4), dp(9), dp(4), dp(9));
        input.setMinHeight(dp(40));
        input.setMaxHeight((int) (app.getResources().getDisplayMetrics().heightPixels * 0.34f));
        // The keyboard's Enter sends (its key shows Send); the text still wraps over several lines.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(Integer.MAX_VALUE);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_NULL) {
                if (event == null || event.getAction() == android.view.KeyEvent.ACTION_DOWN) app.sender.send();
                return true;
            }
            return false;
        });
        // A physical Enter sends too; Shift+Enter makes a new line.
        input.setOnKeyListener((v, code, event) -> {
            if (code != android.view.KeyEvent.KEYCODE_ENTER || event.isShiftPressed()) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) app.sender.send();
            return true;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                // The /find line stays in the box while it looks; changed or cleared, it stops.
                if (app.finder.finding() && !s.toString().trim().equals(app.finder.findLine)) app.finder.findEnd();
                showMenu();
                app.screen.updateChrome();
                if (s.length() > 0) app.sender.warm();
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        sendBtn = new ImageView(app);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendBtn.setOnClickListener(v -> app.sender.send());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(34), dp(34));
        sp.setMargins(dp(2), 0, dp(2), dp(3));
        row.addView(sendBtn, sp);
        card.addView(row, Ui.fill());
        wrap.addView(card, Ui.fill());

        return wrap;
    }

    private void showMenu() {
        String v = input.getText().toString();
        if (!v.matches("^/\\S*$")) { hideMenu(); return; }
        String q = v.substring(1).toLowerCase(Locale.ROOT);
        List<Map.Entry<String, String[]>> hits = new ArrayList<>(), rest = new ArrayList<>();
        for (Map.Entry<String, String[]> e : app.slash.commandList().entrySet()) {
            String n = e.getKey().toLowerCase(Locale.ROOT);
            if (n.startsWith(q)) hits.add(e);
            else if (n.contains(q)) rest.add(e);
        }
        hits.addAll(rest);
        if (hits.isEmpty()) { hideMenu(); return; }
        slashMenu.removeAllViews();
        for (Map.Entry<String, String[]> e : hits) {
            LinearLayout row = Ui.row(app);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackground(Ui.press(app, null, dp(10)));
            TextView n = Ui.text(app, "/" + e.getKey(), 13.5f, R.color.accentPress);
            n.setTypeface(Ui.MONO);
            row.addView(n);
            TextView d = Ui.text(app, e.getValue()[1], 13.5f, R.color.text2);
            d.setSingleLine(true);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(dp(10), 0, 0, 0);
            row.addView(d, Ui.weight());
            String name = e.getKey();
            boolean args = !e.getValue()[0].isEmpty();
            boolean local = "local".equals(e.getValue()[2]);
            row.setOnClickListener(x -> {
                String value = "/" + name + (args ? " " : "");
                input.setText(value);
                input.setSelection(value.length());
                hideMenu();
                if (!args && local) app.sender.send();
            });
            slashMenu.addView(row, Ui.fill());
        }
        slashScroll.setVisibility(View.VISIBLE);
        slashScroll.scrollTo(0, 0);
    }

    void hideMenu() { slashScroll.setVisibility(View.GONE); }

    /** The send button as the box stands (text written or a file waiting; a reply running; linked or not), and the files waiting. */
    void update() {
        boolean typed = input.getText().toString().trim().length() > 0 || !app.current.attachments.isEmpty();
        boolean canSend = app.link.linked && typed;
        // Plain icons, no filled button: Enter to send, a ringed square to stop the reply.
        boolean stop = app.current.running && !typed;
        sendBtn.setImageResource(stop ? R.drawable.ic_stop_circle : R.drawable.ic_enter);
        sendBtn.setImageTintList(android.content.res.ColorStateList.valueOf(col(R.color.text)));
        sendBtn.setBackground(Ui.press(app, null, dp(18)));
        sendBtn.setAlpha(stop || canSend ? 1f : 0.35f);
        sendBtn.setEnabled(stop || canSend);
        sendBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        renderAttachments();
        if (crab != null) crab.busy(app.current.running);
    }

    private void renderAttachments() {
        List<Chat.Attachment> items = app.current.attachments;
        attachScroll.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        String key = items.isEmpty() ? "" : System.identityHashCode(items) + ":" + items.size() + ":" + System.identityHashCode(items.get(items.size() - 1));
        if (key.equals(attachments.getTag())) return;
        attachments.setTag(key);
        attachments.removeAllViews();
        for (Chat.Attachment a : new ArrayList<>(items)) {
            FrameLayout cell = new FrameLayout(app);
            cell.setPadding(0, dp(7), dp(7), 0);
            if (a.image) {
                ImageView img = new ImageView(app);
                img.setImageBitmap(a.thumb);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border)));
                img.setClipToOutline(true);
                cell.addView(img, new FrameLayout.LayoutParams(dp(64), dp(64)));
            } else {
                LinearLayout f = Ui.column(app);
                f.setGravity(Gravity.CENTER_VERTICAL);
                f.setPadding(dp(10), dp(8), dp(10), dp(8));
                f.setBackground(Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border)));
                f.addView(Ui.icon(app, R.drawable.ic_file, 16, R.color.text2));
                TextView n = Ui.text(app, a.name, 12.5f, R.color.text);
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                n.setPadding(0, dp(4), 0, dp(2));
                f.addView(n);
                f.addView(Ui.text(app, a.size >= 0 ? Ui.size(a.size) : "", 11, R.color.text3));
                cell.addView(f, new FrameLayout.LayoutParams(dp(150), dp(64)));
            }
            ImageView remove = Ui.icon(app, R.drawable.ic_x, 22, R.color.bg);
            remove.setPadding(dp(5), dp(5), dp(5), dp(5));
            remove.setBackground(Ui.round(col(R.color.text), dp(11), dp(2), col(R.color.surface)));
            remove.setOnClickListener(v -> { app.current.attachments.remove(a); attachments.setTag(null); app.screen.updateChrome(); });
            cell.addView(remove, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END | Gravity.TOP));
            ((FrameLayout.LayoutParams) remove.getLayoutParams()).setMargins(0, -dp(7), -dp(7), 0);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            attachments.addView(cell);
            Ui.margins(cell, app, 0, 0, 4, 0);
        }
    }

    void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) app.getSystemService(app.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) app.getSystemService(app.INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, 0);
    }
}
