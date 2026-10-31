package com.jeep.claudecode;

import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

/** The conversation as a list: one row per user message, note, reply block and status. */
final class ThreadAdapter extends RecyclerView.Adapter<ThreadAdapter.Holder> {
    static final int USER = 0, NOTE = 1, DIVIDER = 2, TEXT = 3, THINKING = 4, TOOL = 5, ERROR = 6,
            STOPPED = 7, STATUS = 8, QUEUED = 10;
    /** 翻译 in thinking's selection menu (the system's items have ids of android.R.id). */
    private static final int TRANSLATE = 1;

    /** What the queued messages' buttons do. */
    interface Queue { void edit(Chat.Queued q); void cancel(Chat.Queued q); void sendNow(Chat.Queued q); }
    static final String[] GLYPHS = {"·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"};

    static final class Item {
        final int type;
        final long id;
        final long sig;
        final Chat.Msg msg;
        final Chat.Block block;
        final int gap;
        Chat.Queued queued;

        Item(int type, long id, long sig, Chat.Msg msg, Chat.Block block, int gap) {
            this.type = type; this.id = id; this.sig = sig; this.msg = msg; this.block = block; this.gap = gap;
        }
    }

    private final Context c;
    private final Md md;
    /** A row was opened or closed; gets the row's view. */
    private final Consumer<View> changed;
    Queue queue;
    /** Answers Claude Code's question whether a tool may run. */
    interface Asks { void answer(Chat chat, Chat.Block b, boolean allow, JSONObject answers); }
    Asks asks;
    /** Told of each row once it is bound or its text rendered again (marks what /find looks for). */
    interface Marker { void mark(View row, int position); }
    Marker marker;
    /** A message was pressed long: its menu (copy, select, delete…). */
    interface Menu { void open(Chat.Msg m); }
    Menu menu;
    /** Translates text selected in thinking (the model's, in English): its 翻译 beside 复制. */
    Consumer<String> translate;
    private List<Item> items = new ArrayList<>();
    private Chat chat;
    final List<Holder> statusRows = new ArrayList<>();

    ThreadAdapter(Context c, Md md, Consumer<View> changed) {
        this.c = c;
        this.md = md;
        this.changed = changed;
        setHasStableIds(true);
    }

    /** Rebuilds the rows for the chat and updates only what changed. */
    void show(Chat chat, boolean reset) {
        this.chat = chat;
        List<Item> next = flatten(chat, Themes.showThinking(c));
        if (reset) {
            opened = null;
            items = next;
            notifyDataSetChanged();
            return;
        }
        List<Item> prev = items;
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return prev.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) { return prev.get(o).id == next.get(n).id; }
            @Override public boolean areContentsTheSame(int o, int n) {
                return prev.get(o).sig == next.get(n).sig && prev.get(o).gap == next.get(n).gap;
            }
        }, false);
        items = next;
        diff.dispatchUpdatesTo(this);
    }

    private static List<Item> flatten(Chat chat, boolean thinking) {
        List<Item> out = new ArrayList<>();
        List<Chat.Msg> msgs = chat.messages;
        for (int i = 0; i < msgs.size(); i++) {
            Chat.Msg m = msgs.get(i);
            int top = out.isEmpty() ? 6 : 24;
            if (m.kind == Chat.Msg.USER) out.add(new Item(USER, m.id, m.version, m, null, top));
            else if (m.kind == Chat.Msg.NOTE) out.add(new Item(NOTE, m.id, m.version, m, null, top));
            else if (m.kind == Chat.Msg.DIVIDER) out.add(new Item(DIVIDER, m.id, m.version, m, null, top));
            else {
                Chat.Block prev = null;
                for (Chat.Block b : m.blocks) {
                    // The model may send a thinking block with nothing in it: once it ends, there is nothing to show.
                    if (b.kind == Chat.Block.THINKING && !b.streaming && b.text.trim().isEmpty()) continue;
                    // Thinking can be left out altogether (a setting), whatever the model.
                    if (b.kind == Chat.Block.THINKING && !thinking) continue;
                    // Thinking in progress is the status row's "思考中… Ns"; tapping that opens it here.
                    if (b.kind == Chat.Block.THINKING && b.streaming && !b.open) continue;
                    int type = b.kind == Chat.Block.TEXT ? TEXT : b.kind == Chat.Block.THINKING ? THINKING
                            : b.kind == Chat.Block.TOOL ? TOOL : b.kind == Chat.Block.ERROR ? ERROR : STOPPED;
                    // Steps (tools and thinking) one after another sit close, as one list.
                    boolean step = b.kind == Chat.Block.TOOL || b.kind == Chat.Block.THINKING;
                    boolean prevStep = prev != null && (prev.kind == Chat.Block.TOOL || prev.kind == Chat.Block.THINKING);
                    int gap = prev == null ? (out.isEmpty() ? 6 : 20) : step && prevStep ? 2 : 14;
                    long sig = ((long) b.version << 1) | (b.open ? 1 : 0);
                    out.add(new Item(type, b.id, sig, m, b, gap));
                    prev = b;
                }
                boolean last = i == msgs.size() - 1;
                if (last && chat.running) out.add(new Item(STATUS, -1, 0, m, null, m.blocks.isEmpty() ? 20 : 12));
            }
        }
        if (chat.running && (msgs.isEmpty() || msgs.get(msgs.size() - 1).kind != Chat.Msg.ASSISTANT)) {
            out.add(new Item(STATUS, -1, 0, null, null, 20));
        }
        for (Chat.Queued q : chat.queue) {
            Item it = new Item(QUEUED, -q.id - 1_000_000_000L, q.now ? 1 : 0, null, null, out.isEmpty() ? 6 : 18);
            it.queued = q;
            out.add(it);
        }
        return out;
    }

    /** The text /find looks in for a row: the user's messages and the replies' text; null for the rest. */
    String searchText(int position) {
        if (position < 0 || position >= items.size()) return null;
        Item it = items.get(position);
        return it.type == USER ? it.msg.text : it.type == TEXT ? it.block.text : null;
    }

    @Override public int getItemCount() { return items.size(); }
    @Override public long getItemId(int position) { return items.get(position).id; }
    @Override public int getItemViewType(int position) { return items.get(position).type; }

    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout box;
        Item item;
        // status row parts
        TextView glyph, verb, elapsed, detail;
        // thinking row parts, updated in place while it streams
        TextView thinkLabel, thinkBody;
        MaxScroll thinkScroll;
        boolean thinkOpen;
        // reply text: when it may be rendered again while it streams
        long nextRender;
        boolean renderPending;

        Holder(LinearLayout box) {
            super(box);
            this.box = box;
        }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LinearLayout box = Ui.column(c);
        box.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        return new Holder(box);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        bind(h, position);
        if (marker != null) marker.mark(h.box, position);
    }

    private void bind(Holder h, int position) {
        Item it = items.get(position);
        boolean same = h.item != null && h.item.id == it.id && h.item.type == it.type;
        if (!same && opened == h.box) opened = null; // another row in its box now
        h.item = it;
        if (same && it.type == TEXT) { // the same reply text, grown: update its views in place
            h.box.setPadding(0, Ui.dp(c, it.gap), 0, 0);
            text(h, it);
            return;
        }
        if (same && it.type == THINKING && h.thinkOpen == it.block.open) { // same: no rebuild, no jump
            h.box.setPadding(0, Ui.dp(c, it.gap), 0, 0);
            h.thinkLabel.setText(thinkingLabel(it.block));
            String text = thinkingText(it.block);
            if (h.thinkBody != null && !text.contentEquals(h.thinkBody.getText())) {
                h.thinkBody.setText(text); // the box follows the end unless scrolled up by hand
            }
            return;
        }
        h.box.removeAllViews();
        h.box.setOnClickListener(null); // the status row's; a reused box must not keep it
        h.box.setClickable(false);
        h.box.setPadding(0, Ui.dp(c, it.gap), 0, 0);
        h.box.setGravity(Gravity.NO_GRAVITY);
        statusRows.remove(h);
        switch (it.type) {
            case USER: user(h.box, it.msg); break;
            case NOTE: note(h.box, it.msg); break;
            case DIVIDER: divider(h.box, it.msg); break;
            case TEXT: h.nextRender = 0; text(h, it); break;
            case THINKING: thinking(h, it.block); break;
            case TOOL: tool(h.box, it.block); break;
            case ERROR: error(h.box, it.block); break;
            case STOPPED: h.box.addView(Ui.text(c, "已停止", 13.5f, R.color.text3)); break;
            case STATUS: status(h); break;
            case QUEUED: queued(h.box, it.queued); break;
            default:
        }
        if (it.block != null && it.block == growing) {
            growing = null;
            grow(h.box, it.block);
        }
    }

    @Override public void onViewRecycled(@NonNull Holder h) {
        statusRows.remove(h);
        if (opened == h.box) opened = null;
    }

    /**
     * A reply's text, rendered again as it grows. A long one costs more each time: while it
     * streams, it is rendered again only after a pause three times as long as the last rendering
     * took, so the screen keeps up however long it gets. The last version is always rendered.
     */
    private void text(Holder h, Item it) {
        long now = SystemClock.uptimeMillis();
        if (!it.msg.done && now < h.nextRender) {
            if (!h.renderPending) {
                h.renderPending = true;
                h.box.postDelayed(() -> {
                    h.renderPending = false;
                    if (h.item != null && h.item.type == TEXT) text(h, h.item);
                }, h.nextRender - now);
            }
            return;
        }
        md.render(h.box, it.block.text);
        pressable(h.box, it.msg);
        if (marker != null && h.getBindingAdapterPosition() != RecyclerView.NO_POSITION) marker.mark(h.box, h.getBindingAdapterPosition());
        long end = SystemClock.uptimeMillis();
        h.nextRender = end + 3 * (end - now);
    }

    // ---- rows ----

    private void user(LinearLayout box, Chat.Msg m) {
        box.setGravity(Gravity.END);
        int max = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.78f);
        if (!m.images.isEmpty()) {
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.END);
            for (String src : m.images) {
                ImageView img = new ImageView(c);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 14), Ui.dp(c, 1), Ui.color(c, R.color.border)));
                img.setClipToOutline(true);
                if (src != null) {
                    Images.load(img, src, null, Ui.dp(c, 224), null);
                    img.setOnClickListener(v -> Images.show(c, src, null));
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 112), Ui.dp(c, 112));
                p.leftMargin = Ui.dp(c, 6);
                row.addView(img, p);
            }
            box.addView(row, Ui.wrap());
            Ui.margins(row, c, 0, 0, 0, 6);
        }
        for (String f : m.files) {
            LinearLayout chip = Ui.row(c);
            chip.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
            chip.setBackground(Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 14), Ui.dp(c, 1), Ui.color(c, R.color.border)));
            chip.addView(Ui.icon(c, R.drawable.ic_file, 17, R.color.text2));
            TextView name = Ui.text(c, f, 14, R.color.text);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setMaxWidth(max - Ui.dp(c, 50));
            name.setPadding(Ui.dp(c, 8), 0, 0, 0);
            chip.addView(name);
            box.addView(chip, Ui.wrap());
            Ui.margins(chip, c, 0, 0, 0, 6);
        }
        if (!m.text.isEmpty()) {
            TextView bubble = Ui.text(c, m.text, 16, R.color.text);
            bubble.setTypeface(Fonts.body(c));
            bubble.setIncludeFontPadding(true);
            bubble.setLineSpacing(0, 1.15f);
            bubble.setMaxWidth(max);
            bubble.setPadding(Ui.dp(c, 15), Ui.dp(c, 10), Ui.dp(c, 15), Ui.dp(c, 10));
            android.graphics.drawable.GradientDrawable bg = Ui.round(Ui.color(c, R.color.user), 0);
            float r = Ui.dp(c, 20), s = Ui.dp(c, 6);
            bg.setCornerRadii(new float[]{r, r, r, r, s, s, r, r});
            bubble.setBackground(bg);
            bubble.setTag(R.id.findable, true);
            pressable(bubble, m);
            box.addView(bubble, Ui.wrap());
        }
    }

    /** A message waiting for the turn to end: its text, dimmed, and edit, cancel and send-now. */
    private void queued(LinearLayout box, Chat.Queued q) {
        box.setGravity(Gravity.END);
        int max = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.78f);
        StringBuilder text = new StringBuilder(q.text);
        for (Chat.Attachment a : q.attachments) text.append(text.length() > 0 ? "\n" : "").append(a.image ? "[图片] " : "[文件] ").append(a.name);
        TextView bubble = Ui.text(c, text, 16, R.color.text2);
        bubble.setIncludeFontPadding(true);
        bubble.setLineSpacing(0, 1.15f);
        bubble.setMaxWidth(max);
        bubble.setPadding(Ui.dp(c, 15), Ui.dp(c, 10), Ui.dp(c, 15), Ui.dp(c, 10));
        bubble.setBackground(Ui.round(0, Ui.dp(c, 20), Ui.dp(c, 1), Ui.color(c, R.color.borderStrong)));
        box.addView(bubble, Ui.wrap());
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (q.now) {
            row.addView(Ui.text(c, "停止当前回复后发送…", 13, R.color.text3));
        } else {
            ImageView edit = Ui.iconButton(c, R.drawable.ic_pencil, 34, 17, R.color.text3);
            edit.setOnClickListener(v -> { if (queue != null) queue.edit(q); });
            row.addView(edit, new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
            ImageView cancel = Ui.iconButton(c, R.drawable.ic_x, 34, 17, R.color.text3);
            cancel.setOnClickListener(v -> { if (queue != null) queue.cancel(q); });
            row.addView(cancel, new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34)));
            TextView now = Ui.text(c, "立即发送", 13.5f, R.color.text2);
            now.setPadding(Ui.dp(c, 10), Ui.dp(c, 7), Ui.dp(c, 4), Ui.dp(c, 7));
            now.setOnClickListener(v -> { if (queue != null) queue.sendNow(q); });
            row.addView(now);
        }
        box.addView(row, Ui.wrap());
        Ui.margins(row, c, 0, 4, 0, 0);
    }

    private void note(LinearLayout box, Chat.Msg m) {
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = Ui.text(c, m.note, 14, R.color.text2);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        t.setBackground(Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 14), Ui.dp(c, 1), Ui.color(c, R.color.border)));
        t.setTextIsSelectable(true);
        box.addView(t, Ui.wrap());
    }

    private void divider(LinearLayout box, Chat.Msg m) {
        LinearLayout row = Ui.row(c);
        View a = new View(c), b = new View(c);
        a.setBackgroundColor(Ui.color(c, R.color.border));
        b.setBackgroundColor(Ui.color(c, R.color.border));
        row.addView(a, new LinearLayout.LayoutParams(0, Ui.dp(c, 1), 1));
        TextView t = Ui.text(c, m.text, 12.5f, R.color.text3);
        t.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        row.addView(t);
        row.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(c, 1), 1));
        box.addView(row, Ui.fill());
    }

    private LinearLayout head(int icon, boolean boxed) {
        LinearLayout head = Ui.row(c);
        head.setMinimumHeight(Ui.dp(c, 32));
        head.setPadding(Ui.dp(c, 6), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2));
        head.setBackground(Ui.press(c, null, Ui.dp(c, 10)));
        LinearLayout.LayoutParams lp = Ui.fill();
        lp.leftMargin = -Ui.dp(c, 6);
        head.setLayoutParams(lp);
        if (boxed) {
            FrameLayout frame = new FrameLayout(c);
            frame.setBackground(Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 8), Ui.dp(c, 1), Ui.color(c, R.color.border)));
            ImageView i = Ui.icon(c, icon, 16, R.color.text2);
            frame.addView(i, new FrameLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16), Gravity.CENTER));
            head.addView(frame, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
        } else if (icon != 0) {
            head.addView(Ui.icon(c, icon, 17, R.color.text2));
        }
        return head;
    }

    private ImageView chevron(boolean open) {
        ImageView v = Ui.icon(c, R.drawable.ic_chevron_right, 16, R.color.text3);
        v.setRotation(open ? 90 : 0);
        Ui.margins(v, c, 6, 0, 0, 0);
        return v;
    }

    /** The row being opened: its body grows out of its head once built (grow). */
    private Chat.Block growing;
    /**
     * The row opened last (its box): the list keeps it in sight as it grows (opening, a picture or
     * a result coming in) until the list is moved otherwise (MainActivity lets go of it).
     */
    View opened;
    /** A row moving open or closed: taps on it meanwhile wait. */
    private final java.util.Set<Chat.Block> moving = new java.util.HashSet<>();

    /** Whether a row is growing open or shrinking shut just now (its height is not its own); for a status row, the thinking it opens. */
    boolean moving(RecyclerView.ViewHolder h) {
        if (!(h instanceof Holder)) return false;
        Holder r = (Holder) h;
        Chat.Block b = statusRows.contains(r) ? streamingThinking() : r.item != null ? r.item.block : null;
        return b != null && moving.contains(b);
    }

    /** Opens or closes a row: its height moves between the head alone and the whole, the chevron turns. */
    private void toggle(Chat.Block b, View row) {
        if (moving.contains(b)) return;
        opened = null; // a row opened is followed from when it grows (grow), not one before it
        if (!b.open) {
            growing = b;
            b.open = true;
            changed.accept(row);
            return;
        }
        ViewGroup box = (ViewGroup) row;
        View head = box.getChildAt(0);
        int from = box.getHeight(), to = head.getBottom() + box.getPaddingBottom();
        turn(head, 90, 0);
        if (from <= to) {
            b.open = false;
            changed.accept(row);
            return;
        }
        moving.add(b);
        resize(box, from, to, () -> {
            moving.remove(b);
            b.open = false;
            changed.accept(row);
        });
    }

    /** A row just opened: built whole, it is drawn from its head's height down to that. */
    private void grow(LinearLayout box, Chat.Block b) {
        if (box.getChildCount() == 0) return;
        View head = box.getChildAt(0);
        turn(head, 0, 90);
        moving.add(b);
        box.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                box.getViewTreeObserver().removeOnPreDrawListener(this);
                opened = box;
                int to = box.getHeight(), from = head.getBottom() + box.getPaddingBottom();
                if (to <= from) { moving.remove(b); return true; }
                for (int i = 1; i < box.getChildCount(); i++) {
                    View v = box.getChildAt(i);
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(Motion.PAGE).setInterpolator(Motion.EMPHASIZED).start();
                }
                resize(box, from, to, () -> {
                    moving.remove(b);
                    changed.accept(box); // where it ends: the list follows it as an opened row
                });
                return false; // this frame was whole: the next starts at the head's height
            }
        });
    }

    /** The chevron at the head's end, turned from one angle to another. */
    private static void turn(View head, float from, float to) {
        if (!(head instanceof ViewGroup) || ((ViewGroup) head).getChildCount() == 0) return;
        View chevron = ((ViewGroup) head).getChildAt(((ViewGroup) head).getChildCount() - 1);
        if (!(chevron instanceof ImageView)) return;
        chevron.setRotation(from);
        chevron.animate().rotation(to).setDuration(250).setInterpolator(Motion.EMPHASIZED).start();
    }

    /** A box's height moved from one to another, then back to its own (wrap_content). */
    private static void resize(View box, int from, int to, Runnable end) {
        ViewGroup.LayoutParams p = box.getLayoutParams();
        p.height = from;
        box.setLayoutParams(p);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(from, to);
        a.setDuration(Math.min(Motion.PAGE, 200 + Math.abs(to - from) / 4));
        a.setInterpolator(Motion.EMPHASIZED);
        a.addUpdateListener(x -> {
            p.height = (int) x.getAnimatedValue();
            box.setLayoutParams(p);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                box.setLayoutParams(p);
                end.run();
            }
        });
        a.start();
    }

    private static String thinkingLabel(Chat.Block b) {
        return b.streaming ? "思考中…" : b.millis > 0 ? "已思考 " + Math.max(1, Math.round(b.millis / 1000f)) + " 秒" : "已思考";
    }

    /** The model ends its thinking with blank lines; shown, they leave an empty tail under the words. */
    private static String thinkingText(Chat.Block b) {
        return b.text.replaceAll("\\s+$", "");
    }

    private void thinking(Holder h, Chat.Block b) {
        LinearLayout box = h.box;
        // The words and the chevron, no icon.
        LinearLayout head = head(0, false);
        TextView t = Ui.text(c, thinkingLabel(b), 14.5f, R.color.text2);
        h.thinkLabel = t;
        h.thinkOpen = b.open;
        h.thinkBody = null;
        h.thinkScroll = null;
        head.addView(t, Ui.weight());
        head.addView(chevron(b.open));
        head.setOnClickListener(v -> toggle(b, (View) v.getParent()));
        box.addView(head);
        if (!b.open) return;
        MaxScroll scroll = new MaxScroll(c, Ui.dp(c, 300));
        TextView body = Ui.text(c, thinkingText(b), 14.5f, R.color.text2);
        body.setTypeface(Fonts.body(c));
        body.setLineSpacing(0, 1.2f);
        body.setTextIsSelectable(true);
        body.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu m) {
                if (translate != null) m.add(android.view.Menu.NONE, TRANSLATE, 100, "翻译");
                return true;
            }

            @Override public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu m) { return false; }

            @Override public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                if (item.getItemId() != TRANSLATE) return false;
                int a = Math.min(body.getSelectionStart(), body.getSelectionEnd()), z = Math.max(body.getSelectionStart(), body.getSelectionEnd());
                if (a >= 0 && a < z) translate.accept(body.getText().subSequence(a, z).toString());
                mode.finish();
                return true;
            }

            @Override public void onDestroyActionMode(android.view.ActionMode mode) {}
        });
        scroll.addView(body);
        h.thinkBody = body;
        h.thinkScroll = scroll;
        LinearLayout wrap = Ui.row(c);
        View bar = new View(c);
        bar.setBackgroundColor(Ui.color(c, R.color.border));
        wrap.addView(bar, new LinearLayout.LayoutParams(Ui.dp(c, 2), -1));
        wrap.addView(scroll, new LinearLayout.LayoutParams(0, -2, 1));
        scroll.setPadding(Ui.dp(c, 14), Ui.dp(c, 2), Ui.dp(c, 6), Ui.dp(c, 2));
        box.addView(wrap, Ui.fill());
        Ui.margins(wrap, c, 3, 4, 0, 2);
        scroll.follow = b.streaming;
    }

    /** {icon, tool name, what it acts on}: as Claude Code's terminal shows a step. */
    static String[] toolInfo(Chat.Block b) {
        JSONObject i = b.input;
        String name = b.name == null || b.name.isEmpty() ? "Tool" : b.name;
        switch (name) {
            case "Bash": {
                String summary = i.optString("description");
                return new String[]{"terminal", name, summary.isEmpty() ? i.optString("command").trim().split("\n")[0] : summary};
            }
            case "Read": return new String[]{"file", name, Ui.base(i.optString("file_path"))};
            case "Write": return new String[]{"filePlus", name, Ui.base(i.optString("file_path"))};
            case "Edit": return new String[]{"pencil", name, Ui.base(i.optString("file_path"))};
            case "MultiEdit": return new String[]{"editLines", name, Ui.base(i.optString("file_path"))};
            case "NotebookEdit": return new String[]{"book", name, Ui.base(i.optString("notebook_path"))};
            case "Glob": return new String[]{"folder", name, i.optString("pattern")};
            case "Grep": return new String[]{"search", name, i.optString("pattern")};
            case "WebFetch": return new String[]{"link", name, i.optString("url")};
            case "WebSearch": return new String[]{"globe", name, i.optString("query")};
            case "Task": case "Agent": return new String[]{"bot", name, i.optString("description")};
            case "TodoWrite": return new String[]{"list", "TodoWrite", "Update Todos"};
            case "Skill": return new String[]{"spark", name, i.optString("skill", i.optString("command"))};
            case "AskUserQuestion": {
                JSONArray qs = i.optJSONArray("questions");
                JSONObject first = qs == null ? null : qs.optJSONObject(0);
                return new String[]{"list", "提问", first == null ? "提问" : first.optString("question")};
            }
            default: {
                // An MCP tool (mcp__server__tool) or any other: its own name and what it was given first.
                int cut = name.startsWith("mcp__") ? name.indexOf("__", 5) : -1;
                String shown = cut > 0 ? name.substring(cut + 2) : name;
                String arg = firstArg(i);
                return new String[]{"tool", name, arg.isEmpty() ? shown : shown + " · " + arg};
            }
        }
    }

    /** The first text a tool was given (a web address without its scheme), on one line. */
    private static String firstArg(JSONObject input) {
        for (java.util.Iterator<String> it = input.keys(); it.hasNext(); ) {
            Object v = input.opt(it.next());
            if (v instanceof JSONArray && ((JSONArray) v).length() > 0) v = ((JSONArray) v).opt(0);
            if (!(v instanceof String) || ((String) v).trim().isEmpty()) continue;
            String t = ((String) v).trim().split("\n")[0];
            return t.replaceFirst("^https?://", "");
        }
        return "";
    }

    private static int iconRes(String name) {
        switch (name) {
            case "terminal": return R.drawable.ic_terminal;
            case "file": return R.drawable.ic_file;
            case "filePlus": return R.drawable.ic_file_plus;
            case "pencil": return R.drawable.ic_pencil;
            case "search": return R.drawable.ic_search;
            case "globe": return R.drawable.ic_globe;
            case "spark": return R.drawable.ic_spark;
            case "list": return R.drawable.ic_list;
            case "editLines": return R.drawable.ic_edit_lines;
            case "book": return R.drawable.ic_book;
            case "folder": return R.drawable.ic_folder;
            case "link": return R.drawable.ic_link;
            case "bot": return R.drawable.ic_bot;
            default: return R.drawable.ic_tool;
        }
    }

    private void tool(LinearLayout box, Chat.Block b) {
        String[] info = toolInfo(b);
        // The row shows what the step does; opened, the tool's name heads the command, as in
        // Claude Code's terminal.
        LinearLayout head = head(iconRes(info[0]), true);
        TextView detail = Ui.text(c, info[2], 14.5f, R.color.text);
        if (!b.open) { // opened, it is shown whole
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
        }
        detail.setPadding(Ui.dp(c, 9), 0, 0, 0);
        head.addView(detail, Ui.weight());
        View state;
        if (b.running) {
            ProgressBar spin = new ProgressBar(c);
            spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.color(c, R.color.accent)));
            state = spin;
            state.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 15), Ui.dp(c, 15)));
        } else {
            state = Ui.icon(c, b.error ? R.drawable.ic_x : R.drawable.ic_check, 15, b.error ? R.color.danger : R.color.ok);
        }
        if (!b.images.isEmpty() && !b.open) {
            // A picture it read: shown small at the end of the row, whole when opened.
            String src = b.images.get(0);
            ImageView thumb = new ImageView(c);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 8), Ui.dp(c, 1), Ui.color(c, R.color.border)));
            thumb.setClipToOutline(true);
            Images.load(thumb, src, null, Ui.dp(c, 56), null);
            thumb.setOnClickListener(v -> Images.show(c, src, null));
            head.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
            Ui.margins(thumb, c, 8, 0, 0, 0);
        }
        Ui.margins(state, c, 8, 0, 0, 0);
        head.addView(state);
        head.addView(chevron(b.open));
        head.setOnClickListener(v -> toggle(b, (View) v.getParent()));
        box.addView(head);
        if ("TodoWrite".equals(b.name)) todos(box, b);
        // Claude Code asks the user something: its questions to answer, not whether it may run.
        if ("AskUserQuestion".equals(b.name) && b.ask != null) {
            questions(box, b);
            return;
        }
        // Asked whether it may run: what it would do is shown, and the answer.
        if (b.open || b.ask != null) toolBody(box, b);
        if (b.ask != null) ask(box, b);
    }

    /**
     * Claude Code's questions to the user (AskUserQuestion): each with its options to pick (one, or
     * several where it says so), a line for an answer of one's own (其他), then 跳过 or 提交. What is
     * picked stays on the block, so the row drawn again keeps it.
     */
    private void questions(LinearLayout box, Chat.Block b) {
        JSONArray qs = b.input.optJSONArray("questions");
        if (qs == null || qs.length() == 0) { ask(box, b); return; }
        LinearLayout all = Ui.column(c);
        List<Runnable> repaint = new ArrayList<>();
        for (int qi = 0; qi < qs.length(); qi++) {
            JSONObject q = qs.optJSONObject(qi);
            if (q == null) continue;
            int index = qi;
            boolean multi = q.optBoolean("multiSelect");
            java.util.LinkedHashSet<String> picked = b.picked.computeIfAbsent(index, k -> new java.util.LinkedHashSet<>());
            String header = q.optString("header");
            if (!header.isEmpty()) {
                TextView h = Ui.text(c, header, 12.5f, R.color.text3);
                all.addView(h);
                Ui.margins(h, c, 0, qi == 0 ? 0 : 16, 0, 2);
            }
            String asked = q.optString("question");
            // Several may be picked: said so, unless the question says it already.
            TextView question = Ui.text(c, asked + (multi && !asked.contains("多选") ? "（可多选）" : ""), 15.5f, R.color.text);
            all.addView(question);
            if (header.isEmpty() && qi > 0) Ui.margins(question, c, 0, 16, 0, 0);
            EditText other = new EditText(c);
            JSONArray opts = q.optJSONArray("options");
            for (int oi = 0; opts != null && oi < opts.length(); oi++) {
                JSONObject o = opts.optJSONObject(oi);
                if (o == null) continue;
                String label = o.optString("label");
                LinearLayout opt = Ui.column(c);
                opt.setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 9));
                opt.addView(Ui.text(c, label, 15, R.color.text));
                String desc = o.optString("description");
                if (!desc.isEmpty()) {
                    TextView d = Ui.text(c, desc, 13, R.color.text2);
                    d.setPadding(0, Ui.dp(c, 2), 0, 0);
                    opt.addView(d);
                }
                Runnable paint = () -> {
                    boolean on = picked.contains(label);
                    opt.setBackground(Ui.press(c, Ui.round(Ui.color(c, on ? R.color.active : R.color.surface), Ui.dp(c, 10),
                            Ui.dp(c, on ? 2 : 1), Ui.color(c, on ? R.color.accent : R.color.border)), Ui.dp(c, 10)));
                };
                paint.run();
                repaint.add(paint);
                opt.setOnClickListener(v -> {
                    if (multi) {
                        if (!picked.remove(label)) picked.add(label);
                    } else {
                        boolean was = picked.contains(label);
                        picked.clear();
                        if (!was) picked.add(label);
                        if (!was) other.setText(""); // one answer: an option instead of what was written
                    }
                    for (Runnable r : repaint) r.run();
                });
                all.addView(opt, Ui.fill());
                Ui.margins(opt, c, 0, 6, 0, 0);
            }
            other.setHint("其他（自己填写）");
            other.setText(b.other.containsKey(index) ? b.other.get(index) : "");
            other.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            other.setTextColor(Ui.color(c, R.color.text));
            other.setHintTextColor(Ui.color(c, R.color.text3));
            other.setPadding(Ui.dp(c, 12), Ui.dp(c, 9), Ui.dp(c, 12), Ui.dp(c, 9));
            other.setBackground(Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border)));
            other.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int n, int k) {}
                @Override public void onTextChanged(CharSequence s, int a, int n, int k) {}
                @Override public void afterTextChanged(Editable s) {
                    String t = s.toString();
                    if (t.trim().isEmpty()) b.other.remove(index);
                    else {
                        b.other.put(index, t);
                        if (!multi) picked.clear(); // one answer: what is written instead of an option
                    }
                    for (Runnable r : repaint) r.run();
                }
            });
            all.addView(other, Ui.fill());
            Ui.margins(other, c, 0, 6, 0, 0);
        }
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        for (boolean submit : new boolean[]{false, true}) {
            TextView t = Ui.text(c, submit ? "提交" : "跳过", 14.5f, submit ? R.color.bg : R.color.text);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
            t.setMinHeight(Ui.dp(c, 36));
            t.setBackground(Ui.press(c, submit ? Ui.round(Ui.color(c, R.color.text), Ui.dp(c, 10))
                    : Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.borderStrong)), Ui.dp(c, 10)));
            if (submit) {
                // Ready once every question has an answer.
                Runnable ready = () -> {
                    boolean all_ = true;
                    for (int k = 0; k < qs.length(); k++) {
                        java.util.Set<String> p = b.picked.get(k);
                        if ((p == null || p.isEmpty()) && !b.other.containsKey(k)) all_ = false;
                    }
                    t.setEnabled(all_);
                    t.setAlpha(all_ ? 1f : 0.4f);
                };
                ready.run();
                repaint.add(ready);
                t.setOnClickListener(v -> {
                    JSONObject answers = new JSONObject();
                    try {
                        for (int k = 0; k < qs.length(); k++) {
                            JSONObject q = qs.optJSONObject(k);
                            if (q == null) continue;
                            List<String> parts = new ArrayList<>();
                            if (b.picked.containsKey(k)) parts.addAll(b.picked.get(k));
                            if (b.other.containsKey(k)) parts.add(b.other.get(k).trim());
                            answers.put(q.optString("question"), TextUtils.join(", ", parts));
                        }
                    } catch (org.json.JSONException ignored) {
                        // keys are strings
                    }
                    if (asks != null) asks.answer(chat, b, true, answers);
                });
            } else {
                t.setOnClickListener(v -> { if (asks != null) asks.answer(chat, b, false, null); });
            }
            row.addView(t, Ui.wrap());
            if (submit) Ui.margins(t, c, 8, 0, 0, 0);
        }
        all.addView(row, Ui.fill());
        Ui.margins(row, c, 0, 12, 0, 0);
        box.addView(all, Ui.fill());
        Ui.margins(all, c, 32, 8, 0, 6);
    }

    /** What the user answered to a question, from AskUserQuestion's result ("question"="answer"); "" if none. */
    private static String answerOf(String result, String question) {
        if (result == null) return "";
        String key = "\"" + question + "\"=\"";
        int at = result.indexOf(key);
        if (at < 0) return "";
        int from = at + key.length(), to = result.indexOf('"', from);
        return to < 0 ? "" : result.substring(from, to);
    }

    /** Allow or deny, for Claude Code's question whether this tool may run. */
    private void ask(LinearLayout box, Chat.Block b) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        for (boolean allow : new boolean[]{false, true}) {
            TextView t = Ui.text(c, allow ? "允许" : "拒绝", 14.5f, allow ? R.color.bg : R.color.text);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(c, 18), 0, Ui.dp(c, 18), 0);
            t.setMinHeight(Ui.dp(c, 36));
            t.setBackground(Ui.press(c, allow ? Ui.round(Ui.color(c, R.color.text), Ui.dp(c, 10))
                    : Ui.round(Ui.color(c, R.color.surface), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.borderStrong)), Ui.dp(c, 10)));
            t.setOnClickListener(v -> { if (asks != null) asks.answer(chat, b, allow, null); });
            row.addView(t, Ui.wrap());
            if (allow) Ui.margins(t, c, 8, 0, 0, 0);
        }
        box.addView(row, Ui.fill());
        Ui.margins(row, c, 32, 8, 0, 6);
    }

    private void toolBody(LinearLayout box, Chat.Block b) {
        LinearLayout body = Ui.column(c);
        JSONObject i = b.input;
        // Its input still coming: each box keeps its end in view as it grows.
        boolean streaming = b.partial.length() > 0;
        switch (b.name == null ? "" : b.name) {
            case "Bash": {
                MaxScroll shown = io(b.name, i.optString("command"), R.color.text);
                shown.follow = streaming;
                body.addView(shown);
                break;
            }
            case "Read": body.addView(io(b.name, i.optString("file_path"), R.color.text)); break;
            case "Edit": {
                body.addView(io(b.name, i.optString("file_path"), R.color.text));
                MaxScroll shown = diff(i.optString("old_string"), i.optString("new_string"));
                shown.follow = streaming;
                body.addView(shown);
                break;
            }
            case "MultiEdit": {
                body.addView(io(b.name, i.optString("file_path"), R.color.text));
                JSONArray edits = i.optJSONArray("edits");
                for (int k = 0; edits != null && k < edits.length(); k++) {
                    JSONObject e = edits.optJSONObject(k);
                    if (e == null) continue;
                    MaxScroll shown = diff(e.optString("old_string"), e.optString("new_string"));
                    shown.follow = streaming && k == edits.length() - 1;
                    body.addView(shown);
                }
                break;
            }
            case "Write": {
                String text = i.optString("content");
                // Still being written: only its end (a whole long file drawn every 300 ms is slow).
                if (streaming && text.length() > 4000) {
                    int cut = text.indexOf('\n', text.length() - 4000);
                    text = "…\n" + text.substring(cut < 0 ? text.length() - 4000 : cut + 1);
                }
                MaxScroll shown = io(b.name, i.optString("file_path") + "\n\n" + text, R.color.text);
                shown.follow = streaming;
                body.addView(shown);
                break;
            }
            case "TodoWrite": break;
            case "AskUserQuestion": {
                // Each question with what was answered (read from the result).
                JSONArray qs = i.optJSONArray("questions");
                boolean answered = false;
                for (int k = 0; qs != null && k < qs.length(); k++) {
                    JSONObject q = qs.optJSONObject(k);
                    if (q == null) continue;
                    String a = answerOf(b.result, q.optString("question"));
                    answered |= !a.isEmpty();
                    body.addView(io(q.optString("question"), a.isEmpty() ? "—" : a, R.color.text));
                }
                if (answered) { box.addView(body, Ui.fill()); Ui.margins(body, c, 32, 6, 0, 8); return; }
                break;
            }
            default: body.addView(io(b.name, pretty(i), R.color.text2));
        }
        for (String src : b.images) body.addView(picture(src));
        String result = b.result == null ? "" : b.images.isEmpty() ? b.result : b.result.replace("[图片]", "").trim();
        if (!result.isEmpty()) body.addView(io(null, result, b.error ? R.color.danger : R.color.text2));
        for (int k = 1; k < body.getChildCount(); k++) Ui.margins(body.getChildAt(k), c, 0, 8, 0, 0);
        box.addView(body, Ui.fill());
        Ui.margins(body, c, 32, 6, 0, 8);
    }

    /** A picture across the row, at most a screen's half high; a tap shows it whole. */
    private View picture(String src) {
        ImageView img = new ImageView(c);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_START);
        img.setMaxHeight(c.getResources().getDisplayMetrics().heightPixels / 2);
        img.setBackground(Ui.round(Ui.color(c, R.color.surface2), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border)));
        img.setClipToOutline(true);
        img.setLayoutParams(Ui.wrap());
        Images.load(img, src, null, c.getResources().getDisplayMetrics().widthPixels, null);
        img.setOnClickListener(v -> Images.show(c, src, null));
        return img;
    }

    private static String pretty(JSONObject o) {
        // JSONObject writes / as \/: shown as typed.
        try { return o.toString(2).replace("\\/", "/"); } catch (Exception e) { return o.toString().replace("\\/", "/"); }
    }

    /** A box of monospace text, headed by the tool's name when given. */
    private MaxScroll io(String label, String text, int colorRes) {
        String body = text == null ? "" : text;
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (label != null) {
            s.append(label);
            s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.accentPress)), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!body.isEmpty()) s.append('\n');
        }
        s.append(body);
        MaxScroll scroll = new MaxScroll(c, Ui.dp(c, 320));
        scroll.setBackground(Ui.round(Ui.color(c, R.color.codeBg), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border)));
        TextView t = Ui.text(c, s, 12.5f, colorRes);
        t.setTypeface(Ui.MONO);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        t.setTextIsSelectable(true);
        scroll.addView(t);
        scroll.setLayoutParams(Ui.fill());
        return scroll;
    }

    private MaxScroll diff(String before, String after) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        int del = Ui.color(c, R.color.diffDel), delBg = Ui.color(c, R.color.diffDelBg);
        int add = Ui.color(c, R.color.diffAdd), addBg = Ui.color(c, R.color.diffAddBg);
        int lines = 0;
        for (String l : before.split("\n", -1)) {
            if (lines++ > 300) break;
            int st = s.length();
            s.append("- ").append(l).append('\n');
            s.setSpan(new ForegroundColorSpan(del), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new BackgroundColorSpan(delBg), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (String l : after.split("\n", -1)) {
            if (lines++ > 600) break;
            int st = s.length();
            s.append("+ ").append(l).append('\n');
            s.setSpan(new ForegroundColorSpan(add), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new BackgroundColorSpan(addBg), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        MaxScroll scroll = new MaxScroll(c, Ui.dp(c, 320));
        scroll.setBackground(Ui.round(Ui.color(c, R.color.codeBg), Ui.dp(c, 10), Ui.dp(c, 1), Ui.color(c, R.color.border)));
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTypeface(Ui.MONO);
        t.setTextColor(Ui.color(c, R.color.text));
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        t.setTextIsSelectable(true);
        scroll.addView(t);
        scroll.setLayoutParams(Ui.fill());
        return scroll;
    }

    private void todos(LinearLayout box, Chat.Block b) {
        JSONArray list = b.input.optJSONArray("todos");
        if (list == null || list.length() == 0) return;
        LinearLayout col = Ui.column(c);
        for (int k = 0; k < list.length(); k++) {
            JSONObject t = list.optJSONObject(k);
            if (t == null) continue;
            String status = t.optString("status");
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.TOP);
            FrameLayout check = new FrameLayout(c);
            boolean done = "completed".equals(status), now = "in_progress".equals(status);
            check.setBackground(done ? Ui.round(Ui.color(c, R.color.ok), Ui.dp(c, 5))
                    : Ui.round(0, Ui.dp(c, 5), Ui.dp(c, 1.5f), Ui.color(c, now ? R.color.accent : R.color.borderStrong)));
            if (done) check.addView(Ui.icon(c, R.drawable.ic_check, 12, android.R.color.white), new FrameLayout.LayoutParams(Ui.dp(c, 12), Ui.dp(c, 12), Gravity.CENTER));
            if (now) {
                View dot = new View(c);
                dot.setBackground(Ui.round(Ui.color(c, R.color.accent), Ui.dp(c, 2)));
                check.addView(dot, new FrameLayout.LayoutParams(Ui.dp(c, 6), Ui.dp(c, 6), Gravity.CENTER));
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16));
            cp.topMargin = Ui.dp(c, 3);
            row.addView(check, cp);
            TextView label = Ui.text(c, t.optString("content"), 14.5f, done ? R.color.text3 : now ? R.color.text : R.color.text2);
            label.setIncludeFontPadding(true);
            if (done) label.setPaintFlags(label.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            if (now) label.setTypeface(Ui.MEDIUM);
            label.setPadding(Ui.dp(c, 9), 0, 0, 0);
            row.addView(label, Ui.weight());
            col.addView(row, Ui.fill());
            Ui.margins(row, c, 0, 3, 0, 2);
        }
        box.addView(col, Ui.fill());
        Ui.margins(col, c, 32, 4, 0, 4);
    }

    private void error(LinearLayout box, Chat.Block b) {
        LinearLayout card = Ui.column(c);
        card.setPadding(Ui.dp(c, 14), Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11));
        card.setBackground(Ui.round(Ui.color(c, R.color.dangerSoft), Ui.dp(c, 12)));
        TextView title = Ui.text(c, b.title, 14, R.color.danger);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);
        if (!b.text.isEmpty()) {
            TextView t = Ui.text(c, b.text, 14, R.color.danger);
            t.setLineSpacing(0, 1.2f);
            t.setTextIsSelectable(true);
            card.addView(t);
            Ui.margins(t, c, 0, 4, 0, 0);
        }
        box.addView(card, Ui.fill());
    }

    private void status(Holder h) {
        LinearLayout row = Ui.row(c);
        h.glyph = Ui.text(c, GLYPHS[0], 19, R.color.accent);
        h.glyph.setTypeface(Ui.MONO);
        h.glyph.setGravity(Gravity.CENTER);
        h.glyph.setIncludeFontPadding(false);
        // A fixed box: the glyphs come from fonts of different heights, and a row changing height
        // every frame shakes the whole list while it keeps the bottom in view.
        row.addView(h.glyph, new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 24)));
        h.verb = Ui.text(c, "", 14.5f, R.color.text2);
        h.verb.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        row.addView(h.verb);
        h.elapsed = Ui.text(c, "", 13, R.color.text3);
        h.elapsed.setFontFeatureSettings("tnum");
        row.addView(h.elapsed);
        h.box.addView(row);
        h.detail = Ui.text(c, "", 12.5f, R.color.text3);
        h.detail.setPadding(Ui.dp(c, 30), Ui.dp(c, 4), 0, 0);
        h.box.addView(h.detail);
        h.box.setOnClickListener(v -> {
            Chat.Block b = streamingThinking();
            if (b != null) toggle(b, h.box);
        });
        statusRows.add(h);
        tick(h);
    }

    /** Animates the status rows: glyph, verb, elapsed time, retries. */
    void tick() { for (Holder h : statusRows) tick(h); }

    private void tick(Holder h) {
        if (chat == null || h.glyph == null) return;
        long now = System.currentTimeMillis();
        h.glyph.setText(GLYPHS[(int) (now / 120 % GLYPHS.length)]);
        h.verb.setText(chat.verb + "…");
        long secs = (now - (chat.startedAt == 0 ? now : chat.startedAt)) / 1000;
        h.elapsed.setText(secs > 0 ? secs + "s" : "");
        String detail = chat.detail; // retries
        h.detail.setText(detail);
        h.detail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        h.detail.setTextColor(Ui.color(c, detail.isEmpty() ? R.color.text3 : R.color.warn));
    }

    private Chat.Block streamingThinking() {
        if (chat == null || chat.messages.isEmpty()) return null;
        for (Chat.Block b : chat.messages.get(chat.messages.size() - 1).blocks)
            if (b.kind == Chat.Block.THINKING && b.streaming) return b;
        return null;
    }

    /** A long press on a message's text (each of its views) opens its menu, instead of selecting. */
    private void pressable(View v, Chat.Msg m) {
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) pressable(((ViewGroup) v).getChildAt(i), m);
        } else if (v instanceof TextView) {
            v.setOnLongClickListener(x -> {
                if (menu != null) menu.open(m);
                return true;
            });
        }
    }

    /** A vertical scroll box with a maximum height, scrolling inside the list (ThreadList): what it does not use goes on to the list. */
    static final class MaxScroll extends NestedScrollView {
        private final int max;
        /** Keeps the end in view as the text grows; scrolling up by hand stops it, back at the end resumes it. */
        boolean follow;
        private boolean touching;

        MaxScroll(Context c, int max) {
            super(c);
            this.max = max;
            setFillViewport(false);
        }

        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (follow && getChildCount() > 0) {
                int end = getChildAt(0).getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom());
                if (end > 0 && getScrollY() != end) scrollTo(0, end);
            }
        }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (touching) follow = !canScrollVertically(1);
            else if (!canScrollVertically(1)) follow = true;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) touching = true;
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false;
            boolean handled = super.dispatchTouchEvent(e);
            // A text's selection keeps the end of the touch from this box's own handling: its
            // scrolling with the list ends here all the same.
            if (!touching) stopNestedScroll(androidx.core.view.ViewCompat.TYPE_TOUCH);
            return handled;
        }
    }
}
