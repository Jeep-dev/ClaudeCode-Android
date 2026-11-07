package com.jeep.claudecode;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;

/** The conversation page: the list of messages with the bar above it and the box below, the jumps, and the page shown while there is nothing yet. */
final class ChatScreen extends Part {
    boolean stick = true;
    /** A jump to the end (its button, the title) is on its way: the list is still taken as at the end. */
    private boolean toEnd;
    TextView title, banner;
    ThreadList list;
    float touchX, touchY; // where the list was last touched, on the screen: a message's menu opens there
    ThreadAdapter adapter;
    ScrollView emptyView;
    private View toTop, toBottom;
    private int jumpLowest; // the jumps' least bottom margin: clear of the box
    /** The jump to the top and to the end: shown while the list is scrolled by hand, then hidden. */
    private LinearLayout jump;
    /** The immersive layout (a setting): the conversation under the status bar and floating glass. */
    boolean immersive;
    Glass.Host host;
    private View stage, fadeTop, fadeBottom, composer;
    private LinearLayout topButtons;
    /** The status bar's height and what covers the bottom (navigation bar or keyboard), immersive only. */
    private int insetTop, insetBottom;
    private boolean keyboard;
    /** What the empty page last showed: rebuilt only when that changes, so what moves in it is not cut off. */
    private String emptyShown;
    /** The conversation the greeting last came up for: shown again for it, it stays still. */
    private Chat heroFor;

    ChatScreen(MainActivity app) { super(app); }

    /** The page, built (again after a restyle) for the layout in use. */
    View build() {
        immersive = Themes.immersive(app);
        applyWindow();

        title = Ui.text(app, "新对话", 14.5f, R.color.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(6), 0, dp(6), 0);
        title.setOnClickListener(v -> scrollToEnd(true));

        banner = Ui.text(app, "与 Claude Code 的连接断开了，正在重连…", 13.5f, R.color.danger);
        banner.setPadding(dp(12), dp(9), dp(12), dp(9));
        banner.setBackground(Ui.round(col(R.color.dangerSoft), dp(10)));
        banner.setVisibility(View.GONE);

        list = new ThreadList(app);
        list.setLayoutManager(new LinearLayoutManager(app) {
            // A long press that starts selecting gives the message's text focus, and the list would
            // then scroll that whole text into view: to its top, when it is taller than the screen.
            // The list stays put; dragging a handle to the edge still scrolls (another path).
            @Override public boolean onRequestChildFocus(RecyclerView parent, RecyclerView.State state,
                                                         View child, View focused) {
                return true;
            }
        });
        list.setClipToPadding(false);
        list.setPadding(dp(22), dp(2), dp(22), dp(24));
        list.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        list.setItemAnimator(null); // rows change in place; animated moves overlap neighbours
        adapter = new ThreadAdapter(app.talkContext(), app.md, row -> {
            // A row opened or closed. One opened is followed as it grows (ThreadList): its bottom
            // brought up from its first frame, its head kept on screen. From here the list follows
            // the end if it is there, which a row grown open may have taken out of reach.
            stick = !list.canScrollVertically(1);
            app.renderNow();
        });
        adapter.queue = app.sender.queueActions;
        adapter.asks = app.sender::answer;
        adapter.marker = app.finder::mark;
        adapter.menu = app.messages::messageMenu;
        adapter.translate = app.messages::translate;
        list.setAdapter(adapter);
        list.follow = new ThreadList.Follow() {
            @Override public boolean atEnd() { return stick; }
            @Override public View opened() { return adapter.opened; }
            @Override public boolean moving(View row) { return adapter.moving(list.getChildViewHolder(row)); }
        };
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { touchX = e.getRawX(); touchY = e.getRawY(); }
                return false;
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (rv.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && dy == 0) return;
                if (toEnd) return; // on its way down: still at the end, as the reader asked
                // Only scrolling up by hand lets go of the bottom. The list moving by itself (following
                // the end or a row opened, a layout change) never does: a conversation just opened is
                // laid out from its top, and goes to its end a screen at a time. Reaching the end, by
                // hand or not, holds on to it. A jump away (to the top, to a place found) lets go itself.
                if (!list.byHand()) {
                    if (!rv.canScrollVertically(1)) stick = true;
                    return;
                }
                adapter.opened = null; // the reader moves it: the row opened last stays where it is
                stick = dy >= 0 && !rv.canScrollVertically(1);
                showJump();
            }

            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (!toEnd) return;
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) toEnd = false; // the reader took the list
                else if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    // While a reply runs, rows come below the one it was going to: the rest at once.
                    toEnd = false;
                    if (stick && rv.canScrollVertically(1)) scrollToEnd(false);
                }
            }
        });
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (stick && b != ob && list.getScrollState() != RecyclerView.SCROLL_STATE_DRAGGING) scrollToEnd(false);
        });
        emptyView = new ScrollView(app);
        emptyView.setFillViewport(true);
        jump = Ui.column(app);
        jump.setVisibility(View.GONE);
        if (immersive) host = new Glass.Host(app);
        toTop = jumpButton(R.drawable.ic_arrow_up);
        toTop.setOnClickListener(v -> { if (app.finder.finding()) app.finder.findGo(app.finder.findAt - 1); else scrollToTop(); });
        toBottom = jumpButton(R.drawable.ic_arrow_down);
        toBottom.setOnClickListener(v -> { if (app.finder.finding()) app.finder.findGo(app.finder.findAt + 1); else scrollToEnd(true); });
        jump.addView(toTop, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams down = new LinearLayout.LayoutParams(dp(36), dp(36));
        down.topMargin = dp(10);
        jump.addView(toBottom, down);
        FrameLayout.LayoutParams tb = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        tb.bottomMargin = jumpLowest = dp(12);
        tb.rightMargin = dp(8);

        View main = immersive ? buildImmersive(tb) : buildPlain(tb);
        ((View) jump.getParent()).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeJump));
        return main;
    }

    /** The usual layout: a bar with the title on top, the conversation, the box below it. */
    private View buildPlain(FrameLayout.LayoutParams jumpAt) {
        LinearLayout main = Ui.column(app);
        main.setBackgroundColor(col(R.color.bg));
        LinearLayout top = Ui.row(app);
        top.setPadding(dp(6), 0, dp(6), 0);
        ImageView menu = Ui.iconButton(app, R.drawable.ic_sidebar, 44, 20, R.color.text2);
        menu.setOnClickListener(v -> app.frame.open());
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        top.addView(title, Ui.weight());
        if (!Themes.showTitle(app)) title.setVisibility(View.INVISIBLE);
        ImageView more = Ui.iconButton(app, R.drawable.ic_more, 44, 20, R.color.text2);
        more.setOnClickListener(this::moreMenu);
        top.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        main.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));
        main.addView(banner, Ui.fill());
        Ui.margins(banner, app, 12, 4, 12, 0);
        FrameLayout content = new FrameLayout(app);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        // The conversation fades out under the bar instead of being cut at its edge; at its top
        // it starts below the fade.
        list.setPadding(dp(22), dp(24), dp(22), dp(24));
        list.setClipToPadding(false);
        int bg = col(R.color.bg);
        View fade = new View(app);
        fade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{bg, Glass.alpha(bg, 0.6f), Glass.alpha(bg, 0f)}));
        content.addView(fade, new FrameLayout.LayoutParams(-1, dp(28), Gravity.TOP));
        content.addView(jump, jumpAt);
        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        composer = app.box.buildComposer();
        main.addView(composer, Ui.fill());
        return main;
    }

    /**
     * The immersive layout: the conversation fills the screen, under the status bar and the
     * navigation bar; the two buttons and the box float over it as frosted glass, and the
     * conversation fades out under the status bar and the box.
     */
    private View buildImmersive(FrameLayout.LayoutParams jumpAt) {
        host.setBackgroundColor(col(R.color.bg));
        FrameLayout behind = new FrameLayout(app);
        behind.addView(list, new FrameLayout.LayoutParams(-1, -1));
        behind.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        list.setClipToPadding(false);
        emptyView.setClipToPadding(false);
        stage = behind;
        host.source = behind;
        host.addView(behind, new FrameLayout.LayoutParams(-1, -1));
        // Anything that moves behind the glass draws the glass again.
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { host.invalidate(); }
        });
        emptyView.setOnScrollChangeListener((v, x, y, ox, oy) -> host.invalidate());

        int bg = col(R.color.bg);
        fadeTop = new View(app);
        fadeTop.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Glass.alpha(bg, 0.72f), Glass.alpha(bg, 0.45f), Glass.alpha(bg, 0f)}));
        host.addView(fadeTop, new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP));
        fadeBottom = new View(app);
        fadeBottom.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Glass.alpha(bg, 0f), Glass.alpha(bg, 0.55f), Glass.alpha(bg, 0.85f)}));
        host.addView(fadeBottom, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));

        topButtons = Ui.row(app);
        topButtons.setPadding(dp(14), 0, dp(14), 0);
        Glass.Pane menu = glassButton(R.drawable.ic_sidebar, 21);
        menu.setOnClickListener(v -> app.frame.open());
        topButtons.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        topButtons.addView(new View(app), new LinearLayout.LayoutParams(0, 1, 1));
        if (Themes.showTitle(app)) {
            Glass.Pane pill = new Glass.Pane(app, host, -1, col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                    Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border));
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(16), 0, dp(16), 0);
            title.setPadding(0, 0, 0, 0);
            title.setMaxWidth((int) (app.getResources().getDisplayMetrics().widthPixels * 0.55f));
            pill.addView(title);
            topButtons.addView(pill, new LinearLayout.LayoutParams(-2, dp(44)));
            topButtons.addView(new View(app), new LinearLayout.LayoutParams(0, 1, 1));
        }
        Glass.Pane more = glassButton(R.drawable.ic_more, 21);
        more.setOnClickListener(this::moreMenu);
        topButtons.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        host.addView(topButtons, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        host.addView(banner, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        host.addView(jump, jumpAt);
        composer = app.box.buildComposer();
        host.addView(composer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // The conversation keeps clear of the box as it grows (more lines, files, the command menu).
        composer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) placeImmersive();
        });
        host.setOnApplyWindowInsetsListener((v, insets) -> {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            keyboard = insetBottom > app.getResources().getDisplayMetrics().heightPixels / 6;
            if (Build.VERSION.SDK_INT >= 30) {
                insetBottom = Math.max(insetBottom, insets.getInsets(android.view.WindowInsets.Type.ime()
                        | android.view.WindowInsets.Type.navigationBars()).bottom);
                keyboard = insets.isVisible(android.view.WindowInsets.Type.ime());
            }
            placeImmersive();
            return insets;
        });
        placeImmersive();
        return host;
    }

    private Glass.Pane glassButton(int icon, int iconDp) {
        Glass.Pane b = new Glass.Pane(app, host, -1, col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border));
        b.setGravity(Gravity.CENTER);
        b.addView(Ui.icon(app, icon, iconDp, R.color.text2));
        return b;
    }

    /** Places what floats, and keeps the conversation clear of it, for the bars' sizes now. */
    void placeImmersive() {
        if (!immersive || composer == null) return;
        // The buttons just under the status bar, and the box about as far from the bottom (or
        // just above the keyboard).
        int below = keyboard ? insetBottom + dp(8) : Math.max(insetBottom + dp(8), insetTop - dp(12));
        composer.setPadding(dp(16), dp(4), dp(16), below);
        if (app.drawer.drawerPanel != null) app.drawer.drawerPanel.setPadding(0, insetTop, 0, keyboard ? 0 : insetBottom);
        ((FrameLayout.LayoutParams) topButtons.getLayoutParams()).topMargin = insetTop;
        ((FrameLayout.LayoutParams) banner.getLayoutParams()).setMargins(dp(12), insetTop + dp(52), dp(12), 0);
        int box = composer.getHeight() > 0 ? composer.getHeight() : below + dp(58);
        fadeTop.getLayoutParams().height = insetTop + dp(68);
        fadeBottom.getLayoutParams().height = box + dp(24);
        jumpLowest = box + dp(8);
        placeJump();
        int top = insetTop + dp(50);
        if (list.getPaddingTop() != top || list.getPaddingBottom() != box + dp(12)) {
            list.setPadding(dp(22), top, dp(22), box + dp(12));
            emptyView.setPadding(0, top, 0, box);
            if (stick) scrollToEnd(false);
        }
        host.requestLayout();
    }

    /** The window for the layout: edge to edge (immersive) or below the status bar. */
    private void applyWindow() {
        Window w = app.getWindow();
        Themes.window(w, app);
        View d = w.getDecorView();
        int edge = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        int flags = d.getSystemUiVisibility();
        d.setSystemUiVisibility(immersive ? flags | edge : flags & ~edge);
        if (immersive) {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setStatusBarContrastEnforced(false);
                w.setNavigationBarContrastEnforced(false);
            }
        }
        d.requestApplyInsets();
    }

    private View jumpButton(int icon) {
        if (immersive) return glassButton(icon, 18);
        ImageView b = Ui.iconButton(app, icon, 36, 18, R.color.text2);
        b.setBackground(Ui.press(app, Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.border)), dp(18)));
        b.setElevation(dp(3));
        return b;
    }

    /** The jumps sit where a right thumb rests, two thirds down the screen; never down on the box. */
    private void placeJump() {
        View parent = (View) jump.getParent();
        int screen = app.getWindow().getDecorView().getHeight();
        if (parent == null || parent.getHeight() == 0 || screen == 0) return;
        int[] at = new int[2];
        parent.getLocationInWindow(at);
        int m = Math.max(jumpLowest, at[1] + parent.getHeight() - Math.round(screen * 0.67f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) jump.getLayoutParams();
        if (lp.bottomMargin == m) return;
        lp.bottomMargin = m;
        jump.setLayoutParams(lp);
    }

    private final Runnable hideJump = () -> jump.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> jump.setVisibility(View.GONE)).start();

    /** Shows the jumps that lead somewhere; they go again a moment after the scrolling stops. */
    void showJump() {
        // While /find looks for something they go between the places found, and stay.
        boolean finding = app.finder.finding();
        toTop.setVisibility(finding || list.canScrollVertically(-1) ? View.VISIBLE : View.GONE);
        toBottom.setVisibility(finding || list.canScrollVertically(1) ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideJump);
        if (toTop.getVisibility() != View.VISIBLE && toBottom.getVisibility() != View.VISIBLE) {
            jump.setVisibility(View.GONE);
            return;
        }
        jump.animate().cancel();
        if (jump.getVisibility() != View.VISIBLE) {
            jump.setAlpha(0f);
            jump.setVisibility(View.VISIBLE);
        }
        jump.animate().alpha(1f).setDuration(120).start();
        if (!finding) ui.postDelayed(hideJump, 1500);
    }

    private void scrollToTop() {
        stick = false;
        adapter.opened = null;
        list.stopScroll();
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        // From far down, most of the way at once: a smooth scroll through it all takes too long.
        if (lm.findFirstVisibleItemPosition() > 6) lm.scrollToPosition(6);
        list.post(() -> list.smoothScrollToPosition(0));
    }

    void scrollToEnd(boolean smooth) {
        stick = true;
        list.post(() -> {
            // The reader may have taken the list meanwhile: never pull it back down.
            if (!stick || list.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING) return;
            int count = adapter.getItemCount();
            if (count == 0) return;
            LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
            if (!smooth) {
                // A jump down under way, overtaken by new rows: there at once.
                if (toEnd) { toEnd = false; list.stopScroll(); }
                lm.scrollToPositionWithOffset(count - 1, -100000);
                return;
            }
            // From far up, most of the way at once (as to the top): a smooth scroll through it all takes too long.
            if (lm.findLastVisibleItemPosition() < count - 7) lm.scrollToPosition(count - 7);
            list.post(() -> {
                int last = adapter.getItemCount() - 1;
                if (last < 0) return;
                toEnd = true;
                // The last row on screen already: down by what is left of it. Brought into view, a row
                // taller than the screen would be aligned by its top, up first and then down at once.
                View lastRow = lm.findViewByPosition(last);
                if (lastRow != null) list.smoothScrollBy(0, Math.max(0,
                        lm.getDecoratedBottom(lastRow) + ((RecyclerView.LayoutParams) lastRow.getLayoutParams()).bottomMargin
                        - (list.getHeight() - list.getPaddingBottom())));
                else list.smoothScrollToPosition(last);
                // Nothing to go (the end in sight already, or stopped before its first frame): no jump under way.
                list.postOnAnimation(() -> { if (list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) toEnd = false; });
            });
        });
    }

    /** Shows the open conversation's title above, and the box below as it now stands. */
    void updateChrome() {
        title.setText(app.current.title.isEmpty() ? "新对话" : app.current.title);
        app.box.update();
    }

    void fillEmpty() {
        String folder = app.current.cwd == null || app.current.cwd.isEmpty() ? claude.defaultCwd : app.current.cwd;
        String shown = app.link.setup + "|" + app.link.installStatus + "|" + app.link.installError + "|" + app.link.linked + "|" + folder + "|"
                + System.identityHashCode(app.current) + "|" + System.identityHashCode(emptyView);
        if (shown.equals(emptyShown) && emptyView.getChildCount() > 0) return;
        emptyShown = shown;
        LinearLayout box = Ui.column(app);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(22), dp(20), dp(22), dp(40));
        if (app.link.setup != null) buildSetup(box);
        else buildHero(box);
        emptyView.removeAllViews();
        emptyView.addView(box, new ViewGroup.LayoutParams(-1, -1));
    }

    private void buildHero(LinearLayout box) {
        // The crab, in the style Settings has for it: it plays one of its moves when the greeting comes up, and another when tapped.
        Crab crab = new Crab(app, Themes.crabStyle(app), false, 5);
        box.addView(crab, new LinearLayout.LayoutParams(-2, -2));
        // The folder it will work in, which can be changed only until the first message: tapped, it is picked (/folder).
        boolean pick = app.link.linked && app.current.sessionId == null;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = h < 5 ? "夜深了" : h < 11 ? "早上好" : h < 13 ? "中午好" : h < 18 ? "下午好" : "晚上好";
        TextView big = Ui.text(app, hello, 30, R.color.text);
        big.setTypeface(Ui.SERIF);
        big.setPadding(0, dp(4), 0, dp(pick ? 2 : 10)); // the line under it is 8dp in its own round
        big.setGravity(Gravity.CENTER);
        box.addView(big, Ui.fill());
        String folder = Ui.base(app.current.cwd == null || app.current.cwd.isEmpty() ? claude.defaultCwd : app.current.cwd);
        SpannableStringBuilder sub = new SpannableStringBuilder();
        if (app.link.linked) {
            sub.append("在 ");
            int s = sub.length();
            sub.append(folder.isEmpty() ? "~" : folder);
            sub.setSpan(new StyleSpan(Typeface.BOLD), s, sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (pick) sub.setSpan(new ForegroundColorSpan(col(R.color.accent)), s, sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sub.append(" 里，想做点什么？");
        } else {
            sub.append("正在启动 Claude Code…");
        }
        TextView p = Ui.text(app, sub, 15, R.color.text2);
        p.setGravity(Gravity.CENTER);
        if (pick) {
            p.setPadding(dp(16), dp(8), dp(16), dp(8));
            p.setBackground(Ui.press(app, null, dp(20)));
            p.setOnClickListener(v -> app.pickers.folderSheet(null));
        }
        box.addView(p, Ui.wrap());
        // A new conversation's: one after another up into place. Built again on every render, it stays still then.
        if (heroFor == app.current) return;
        heroFor = app.current;
        crab.enter();
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(18));
            v.animate().alpha(1f).translationY(0).setStartDelay(40L * i).setDuration(Motion.PAGE)
                    .setInterpolator(Motion.EMPHASIZED).start();
        }
    }

    private void buildSetup(LinearLayout box) {
        box.setGravity(Gravity.START);
        ImageView mark = new ImageView(app);
        mark.setImageResource(R.drawable.mascot);
        box.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(33)));
        boolean busy = "install".equals(app.link.setup);
        TextView h = Ui.text(app, busy ? "正在准备运行环境" : "installFailed".equals(app.link.setup) ? "运行环境没准备好" : "Claude Code 没能启动", 26, R.color.text);
        h.setTypeface(Ui.SERIF);
        h.setPadding(0, dp(16), 0, dp(8));
        box.addView(h);
        String why;
        if (busy) why = app.link.installStatus.isEmpty() ? "…" : app.link.installStatus;
        else if ("installFailed".equals(app.link.setup)) why = app.link.installError;
        else why = "后台 30 秒内没有起来。可以重试，或者在设置里看运行日志。";
        TextView p = Ui.text(app, why, 15, R.color.text2);
        p.setLineSpacing(0, 1.25f);
        p.setPadding(0, 0, 0, dp(12));
        box.addView(p);
        if (busy) return;
        LinearLayout actions = Ui.row(app);
        TextView retry = sheets.button("重试", true, v -> app.link.connect(true));
        retry.setBackground(Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10)));
        retry.setTextColor(col(R.color.bg));
        actions.addView(retry);
        TextView logs = sheets.button("运行日志", false, v -> app.appPage.logSheet());
        actions.addView(logs);
        Ui.margins(logs, app, 8, 0, 0, 0);
        box.addView(actions);
        Ui.margins(actions, app, 0, 18, 0, 0);
    }

    /**
     * The top bar's menu, as the claude.ai app's card: what the app adds (the terminal, searching the
     * conversation) rather than more buttons.
     */
    private void moreMenu(View anchor) {
        int shade = dp(24); // room around the card for its whole shadow (less, and the shadow is cut square)
        FrameLayout room = new FrameLayout(app);
        room.setPadding(shade, shade, shade, shade);
        LinearLayout card = sheets.menuCard();
        room.addView(card, new FrameLayout.LayoutParams(-2, -2));
        android.widget.PopupWindow pop = new android.widget.PopupWindow(room, -2, -2, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setOutsideTouchable(true);
        pop.setClippingEnabled(false); // the shadow's room may pass the screen's edge
        sheets.menuItem(card, pop, R.drawable.ic_chart, "用量", null, app.usage::show);
        sheets.menuItem(card, pop, R.drawable.ic_terminal, "终端", null, app::openTerminal);
        sheets.menuItem(card, pop, R.drawable.ic_search, "搜索对话", null, () -> {
            app.box.input.setText("/find ");
            app.box.input.setSelection(app.box.input.length());
            app.box.input.requestFocus();
            app.box.showKeyboard();
        });
        // As wide as its rows need; its right edge near the screen's, just under the button; it grows out of that corner.
        room.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = room.getMeasuredWidth() - 2 * shade;
        pop.setWidth(width + 2 * shade);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int screenW = app.getResources().getDisplayMetrics().widthPixels;
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, screenW - dp(6) - width - shade, at[1] + anchor.getHeight() + dp(2) - shade);
        card.setPivotX(width);
        card.setPivotY(0);
        card.setAlpha(0f);
        card.setScaleX(0.8f);
        card.setScaleY(0.8f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
                .setInterpolator(Motion.EMPHASIZED).start();
    }
}
