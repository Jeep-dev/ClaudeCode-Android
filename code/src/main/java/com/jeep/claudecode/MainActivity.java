package com.jeep.claudecode;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Claude Code front end: native interface for Claude Code running in this app's own userland behind the bridge. */
public final class MainActivity extends Activity implements Bridge.Events {
    private static final int PICK_FILES = 1, PICK_FONT = 2;
    private static final int ASK_STORAGE = 2;
    private static final int SHORT_TITLE = 16; // as the bridge's: a first message this short names its conversation until it has a name
    private static final String STORAGE = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

    private Bridge bridge;
    private Userland userland;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Md md;

    // state
    private boolean linked, everLinked, connecting, resumed;
    /** When the bridge in use started; another value means a new bridge and lost turns. */
    private double bridgeStarted;
    private String setup; // why the bridge cannot be reached ("install" while the userland unpacks), or null
    private boolean installing;
    /** How far preparing the userland is (downloading, unpacking). */
    private String installStatus = "";
    private String installError = "";
    /** Claude Code has an API key or login to use; assumed until the bridge says otherwise. */
    private String claudeVersion = "";
    /** From Claude Code itself (its --help and settings.json): thinking levels, permission modes, the level set. */
    private final List<String> efforts = new ArrayList<>(), permissionModes = new ArrayList<>();
    private String configEffort = "";
    private String home = "", defaultCwd = "", cwd, model, effort, defaultModel = "";
    private final List<String> models = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();
    private JSONArray sessions = new JSONArray();
    private final Map<String, Chat> chats = new HashMap<>();
    private final Map<String, String> bySession = new HashMap<>();
    private Chat current;
    private boolean stick = true;
    /** A jump to the end (its button, the title) is on its way: the list is still taken as at the end. */
    private boolean toEnd;
    private String search = "";
    /** For the search in the drawer: sessions whose conversation has the words, with that text. */
    private final Map<String, String> hits = new HashMap<>();
    private final Runnable searchText = this::searchText;

    // views
    private DrawerFrame frame;
    private TextView title, banner, linkText;
    private View linkDot;
    private ThreadList list;
    private float touchX, touchY; // where the list was last touched, on the screen: a message's menu opens there
    private ThreadAdapter adapter;
    private ScrollView emptyView;
    private View toTop, toBottom;
    private int jumpLowest; // the jumps' least bottom margin: clear of the box
    private ImageView sendBtn;
    /** The jump to the top and to the end: shown while the list is scrolled by hand, then hidden. */
    private LinearLayout jump;
    private EditText input;
    private LinearLayout attachments, slashMenu, sessionsBox;
    private HorizontalScrollView attachScroll;
    private ScrollView slashScroll;
    private boolean renderQueued;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        cwd = prefs.getString("cwd", "");
        model = prefs.getString("model", "");
        effort = prefs.getString("effort", "");
        try {
            fill(efforts, new JSONArray(prefs.getString("efforts", "[]")));
            fill(permissionModes, new JSONArray(prefs.getString("permissionModes", "[]")));
            claudeModels = new JSONArray(prefs.getString("claudeModels", "[]"));
        } catch (JSONException ignored) {
            // none saved
        }
        try {
            JSONArray saved = new JSONArray(prefs.getString("commands", "[]"));
            for (int i = 0; i < saved.length(); i++) commands.add(saved.optString(i));
        } catch (JSONException ignored) {
            // none saved
        }
        bridge = new Bridge(this, this);
        Images.saved = bridge::image;
        userland = new Userland(this);
        md = new Md(talkContext(), this::copy, this::openUrl, this::userFile);
        // The keyboard opens when the box is tapped, never by itself (coming back from the background).
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        build();
        switchTo(newChat());
        renderSessions();
        work.execute(() -> {
            JSONArray kept = keptSessions();
            ui.post(() -> {
                if (sessionsRead || kept.length() == 0) return; // the bridge's own list came first
                sessions = kept;
                renderSessions();
            });
        });
        connect(false);
        ui.postDelayed(ticker, 120);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (adapter != null) adapter.tick();
            updateLink();
            ui.postDelayed(this, 120);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!linked && !connecting && setup == null) connect(false);
        else if (linked) opened(current);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
    }

    /** The link stayed down: start the bridge again if it is gone (the service also restarts one that died). */
    private final Runnable recover = () -> {
        if (!linked && !connecting && resumed) connect(false);
    };

    @Override protected void onDestroy() {
        ui.removeCallbacks(ticker);
        bridge.close();
        work.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (slashScroll.getVisibility() == View.VISIBLE) { hideMenu(); return; }
        if (frame.isOpen()) { frame.close(); return; }
        moveTaskToBack(true);
    }

    private int dp(float v) { return Ui.dp(this, v); }
    private int col(int res) { return Ui.color(this, res); }

    // ================= layout =================

    /** The immersive layout (a setting): the conversation under the status bar and floating glass. */
    private boolean immersive;
    private Glass.Host host;
    private View stage, fadeTop, fadeBottom, composer;
    private LinearLayout topButtons, drawerPanel;
    /** The status bar's height and what covers the bottom (navigation bar or keyboard), immersive only. */
    private int insetTop, insetBottom;
    private boolean keyboard;

    private void build() {
        shownLink = null; // a new drawer: its state is shown again
        drawerPanel = null;
        immersive = Themes.immersive(this);
        applyWindow();

        title = Ui.text(this, "新对话", 14.5f, R.color.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(6), 0, dp(6), 0);
        title.setOnClickListener(v -> scrollToEnd(true));

        banner = Ui.text(this, "与 Claude Code 的连接断开了，正在重连…", 13.5f, R.color.danger);
        banner.setPadding(dp(12), dp(9), dp(12), dp(9));
        banner.setBackground(Ui.round(col(R.color.dangerSoft), dp(10)));
        banner.setVisibility(View.GONE);

        list = new ThreadList(this);
        list.setLayoutManager(new LinearLayoutManager(this) {
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
        adapter = new ThreadAdapter(talkContext(), md, row -> {
            // A row opened or closed. One opened is followed as it grows (ThreadList): its bottom
            // brought up from its first frame, its head kept on screen. From here the list follows
            // the end if it is there, which a row grown open may have taken out of reach.
            stick = !list.canScrollVertically(1);
            renderNow();
        });
        adapter.queue = queueActions;
        adapter.asks = this::answer;
        adapter.marker = this::mark;
        adapter.menu = this::messageMenu;
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
                // Scrolling up by hand lets go of the bottom; a layout change at the bottom does not.
                boolean byHand = list.byHand();
                if (byHand) adapter.opened = null; // the reader moves it: the row opened last stays where it is
                stick = !(byHand && dy < 0) && !rv.canScrollVertically(1);
                if (byHand) showJump();
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
        emptyView = new ScrollView(this);
        emptyView.setFillViewport(true);
        jump = Ui.column(this);
        jump.setVisibility(View.GONE);
        if (immersive) host = new Glass.Host(this);
        toTop = jumpButton(R.drawable.ic_arrow_up);
        toTop.setOnClickListener(v -> { if (finding()) findGo(findAt - 1); else scrollToTop(); });
        toBottom = jumpButton(R.drawable.ic_arrow_down);
        toBottom.setOnClickListener(v -> { if (finding()) findGo(findAt + 1); else scrollToEnd(true); });
        jump.addView(toTop, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout.LayoutParams down = new LinearLayout.LayoutParams(dp(36), dp(36));
        down.topMargin = dp(10);
        jump.addView(toBottom, down);
        FrameLayout.LayoutParams tb = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        tb.bottomMargin = jumpLowest = dp(12);
        tb.rightMargin = dp(8);

        View main = immersive ? buildImmersive(tb) : buildPlain(tb);
        ((View) jump.getParent()).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::placeJump));
        frame = new DrawerFrame(this, main, buildDrawer());
        placeImmersive(); // the drawer too, now that it is there
        frame.setListener(() -> {
            hideKeyboard();
            loadSessions();
        });
        setContentView(frame);
    }

    /** The usual layout: a bar with the title on top, the conversation, the box below it. */
    private View buildPlain(FrameLayout.LayoutParams jumpAt) {
        LinearLayout main = Ui.column(this);
        main.setBackgroundColor(col(R.color.bg));
        LinearLayout top = Ui.row(this);
        top.setPadding(dp(6), 0, dp(6), 0);
        ImageView menu = Ui.iconButton(this, R.drawable.ic_sidebar, 44, 20, R.color.text2);
        menu.setOnClickListener(v -> frame.open());
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        top.addView(title, Ui.weight());
        if (!Themes.showTitle(this)) title.setVisibility(View.INVISIBLE);
        ImageView more = Ui.iconButton(this, R.drawable.ic_more, 44, 20, R.color.text2);
        more.setOnClickListener(this::moreMenu);
        top.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        main.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));
        main.addView(banner, Ui.fill());
        Ui.margins(banner, this, 12, 4, 12, 0);
        FrameLayout content = new FrameLayout(this);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
        // The conversation fades out under the bar instead of being cut at its edge; at its top
        // it starts below the fade.
        list.setPadding(dp(22), dp(24), dp(22), dp(24));
        list.setClipToPadding(false);
        int bg = col(R.color.bg);
        View fade = new View(this);
        fade.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{bg, Glass.alpha(bg, 0.6f), Glass.alpha(bg, 0f)}));
        content.addView(fade, new FrameLayout.LayoutParams(-1, dp(28), Gravity.TOP));
        content.addView(jump, jumpAt);
        main.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        composer = buildComposer();
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
        FrameLayout behind = new FrameLayout(this);
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
        fadeTop = new View(this);
        fadeTop.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Glass.alpha(bg, 0.72f), Glass.alpha(bg, 0.45f), Glass.alpha(bg, 0f)}));
        host.addView(fadeTop, new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP));
        fadeBottom = new View(this);
        fadeBottom.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Glass.alpha(bg, 0f), Glass.alpha(bg, 0.55f), Glass.alpha(bg, 0.85f)}));
        host.addView(fadeBottom, new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM));

        topButtons = Ui.row(this);
        topButtons.setPadding(dp(14), 0, dp(14), 0);
        Glass.Pane menu = glassButton(R.drawable.ic_sidebar, 21);
        menu.setOnClickListener(v -> frame.open());
        topButtons.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        topButtons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (Themes.showTitle(this)) {
            Glass.Pane pill = new Glass.Pane(this, host, -1, col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                    Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border));
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(16), 0, dp(16), 0);
            title.setPadding(0, 0, 0, 0);
            title.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.55f));
            pill.addView(title);
            topButtons.addView(pill, new LinearLayout.LayoutParams(-2, dp(44)));
            topButtons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
        Glass.Pane more = glassButton(R.drawable.ic_more, 21);
        more.setOnClickListener(this::moreMenu);
        topButtons.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        host.addView(topButtons, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        host.addView(banner, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        host.addView(jump, jumpAt);
        composer = buildComposer();
        host.addView(composer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // The conversation keeps clear of the box as it grows (more lines, files, the command menu).
        composer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) placeImmersive();
        });
        host.setOnApplyWindowInsetsListener((v, insets) -> {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
            keyboard = insetBottom > getResources().getDisplayMetrics().heightPixels / 6;
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
        Glass.Pane b = new Glass.Pane(this, host, -1, col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border));
        b.setGravity(Gravity.CENTER);
        b.addView(Ui.icon(this, icon, iconDp, R.color.text2));
        return b;
    }

    /** Places what floats, and keeps the conversation clear of it, for the bars' sizes now. */
    private void placeImmersive() {
        if (!immersive || composer == null) return;
        // The buttons just under the status bar, and the box about as far from the bottom (or
        // just above the keyboard).
        int below = keyboard ? insetBottom + dp(8) : Math.max(insetBottom + dp(8), insetTop - dp(12));
        composer.setPadding(dp(32), dp(4), dp(32), below);
        if (drawerPanel != null) drawerPanel.setPadding(0, insetTop, 0, keyboard ? 0 : insetBottom);
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
        Window w = getWindow();
        Themes.window(w, this);
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

    private View buildComposer() {
        LinearLayout wrap = Ui.column(this);
        wrap.setPadding(dp(32), dp(4), dp(32), dp(18));
        LinearLayout card;
        if (immersive) {
            card = new Glass.Pane(this, host, dp(26), col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                    Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border));
            card.setOrientation(LinearLayout.VERTICAL);
        } else {
            card = Ui.column(this);
            card.setBackground(Ui.round(col(R.color.surface), dp(26), dp(1), col(R.color.border)));
            card.setElevation(dp(2));
        }
        card.setPadding(dp(6), dp(4), dp(6), dp(4));

        slashScroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.min(dp(300),
                        (int) (getResources().getDisplayMetrics().heightPixels * 0.4f)), MeasureSpec.AT_MOST));
            }
        };
        slashMenu = Ui.column(this);
        slashScroll.addView(slashMenu);
        slashScroll.setVisibility(View.GONE);
        card.addView(slashScroll, Ui.fill());

        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachments = Ui.row(this);
        attachments.setPadding(dp(6), dp(4), dp(6), dp(4));
        attachScroll.addView(attachments);
        attachScroll.setVisibility(View.GONE);
        card.addView(attachScroll, Ui.fill());

        // One line: + , the text, send (or stop while a reply runs and nothing is typed).
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.BOTTOM);
        ImageView attach = Ui.iconButton(this, R.drawable.ic_plus, 40, 22, R.color.text2);
        attach.setOnClickListener(v -> pickFiles());
        row.addView(attach, new LinearLayout.LayoutParams(dp(40), dp(40)));

        input = new EditText(this);
        input.setBackground(null);
        input.setHintTextColor(col(R.color.text3));
        input.setTextColor(col(R.color.text));
        input.setTextSize(16);
        input.setPadding(dp(4), dp(9), dp(4), dp(9));
        input.setMinHeight(dp(40));
        input.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.34f));
        // The keyboard's Enter sends (its key shows Send); the text still wraps over several lines.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(Integer.MAX_VALUE);
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_NULL) {
                if (event == null || event.getAction() == android.view.KeyEvent.ACTION_DOWN) send();
                return true;
            }
            return false;
        });
        // A physical Enter sends too; Shift+Enter makes a new line.
        input.setOnKeyListener((v, code, event) -> {
            if (code != android.view.KeyEvent.KEYCODE_ENTER || event.isShiftPressed()) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) send();
            return true;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                // The /find line stays in the box while it looks; changed or cleared, it stops.
                if (finding() && !s.toString().trim().equals(findLine)) findEnd();
                showMenu();
                updateChrome();
                if (s.length() > 0) warm();
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        sendBtn = new ImageView(this);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendBtn.setOnClickListener(v -> send());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(34), dp(34));
        sp.setMargins(dp(2), 0, dp(2), dp(3));
        row.addView(sendBtn, sp);
        card.addView(row, Ui.fill());
        wrap.addView(card, Ui.fill());

        return wrap;
    }

    private View buildDrawer() {
        LinearLayout d = Ui.column(this);
        drawerPanel = d;
        d.setBackgroundColor(col(R.color.bg)); // full screen: one colour with the status bar
        d.setClickable(true);

        // One line down the side: the title and the conversations' words from 24dp; icons centred
        // in a column 28dp wide from 24dp, their words from 64dp. What can be tapped is round, as elsewhere.
        LinearLayout head = Ui.row(this);
        head.setPadding(dp(24), dp(14), dp(24), dp(14));
        TextView brand = Ui.text(this, "Claude Code", 26, R.color.text);
        brand.setTypeface(Ui.SERIF); // as Settings' titles
        head.addView(brand);
        d.addView(head, Ui.fill());

        LinearLayout newChat = Ui.row(this);
        newChat.setPadding(dp(12), 0, dp(12), 0);
        newChat.setMinimumHeight(dp(48));
        newChat.setBackground(Ui.press(this, null, dp(24)));
        // As Settings' icons: the accent on a pale round of it.
        FrameLayout plus = drawerSlot(Ui.icon(this, R.drawable.ic_plus, 18, R.color.accent), 18, 18);
        plus.setBackground(Ui.round(col(R.color.accentSoft), dp(14)));
        newChat.addView(plus);
        TextView nl = Ui.text(this, "新对话", 16, R.color.text);
        nl.setTypeface(Ui.MEDIUM);
        nl.setPadding(dp(12), 0, 0, 0);
        newChat.addView(nl);
        newChat.setOnClickListener(v -> { frame.close(); switchTo(newChat()); });
        d.addView(newChat, Ui.fill());
        Ui.margins(newChat, this, 12, 0, 12, 8);

        LinearLayout searchBox = Ui.row(this);
        searchBox.setPadding(dp(12), 0, dp(16), 0);
        // White with a hairline, round at its ends: as the message box.
        searchBox.setBackground(Ui.round(col(R.color.surface), dp(22), dp(1), col(R.color.border)));
        searchBox.addView(drawerSlot(Ui.icon(this, R.drawable.ic_search, 18, R.color.text3), 18, 18));
        EditText searchInput = new EditText(this);
        searchInput.setBackground(null);
        searchInput.setHint("搜索对话");
        searchInput.setHintTextColor(col(R.color.text3));
        searchInput.setTextColor(col(R.color.text));
        searchInput.setTextSize(15.5f);
        searchInput.setSingleLine(true);
        searchInput.setPadding(dp(12), 0, 0, 0);
        // Built again after a restyle: what was being looked for is still in the box.
        searchInput.setText(search);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                search = s.toString();
                hits.clear();
                renderSessions();
                ui.removeCallbacks(searchText);
                if (!search.trim().isEmpty()) ui.postDelayed(searchText, 250);
            }
        });
        searchBox.addView(searchInput, Ui.weight());
        d.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(44)));
        Ui.margins(searchBox, this, 12, 0, 12, 4);

        // The list fades out at its ends, as the conversation does under its bar, instead of
        // being cut by a line.
        FrameLayout listArea = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        sessionsBox = Ui.column(this);
        sessionsBox.setPadding(dp(12), dp(8), dp(12), dp(24));
        scroll.addView(sessionsBox);
        listArea.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        int bg = col(R.color.bg);
        View fadeIn = new View(this);
        fadeIn.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg, Glass.alpha(bg, 0f)}));
        listArea.addView(fadeIn, new FrameLayout.LayoutParams(-1, dp(12), Gravity.TOP));
        View fadeOut = new View(this);
        fadeOut.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{bg, Glass.alpha(bg, 0f)}));
        listArea.addView(fadeOut, new FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM));
        d.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));

        // The whole line is Settings' button: the words (from the conversations' line) and the gear.
        LinearLayout foot = Ui.row(this);
        foot.setPadding(dp(12), 0, 0, 0);
        foot.setMinimumHeight(dp(44));
        foot.setBackground(Ui.press(this, null, dp(22)));
        foot.setOnClickListener(v -> { if (linked) settingsSheet(); else connect(true); });
        LinearLayout link = Ui.row(this);
        linkDot = new View(this);
        link.addView(linkDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        linkText = Ui.text(this, "正在连接…", 15, R.color.text);
        linkText.setTypeface(Ui.MEDIUM);
        linkText.setSingleLine(true);
        linkText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        linkText.setPadding(dp(9), 0, 0, 0);
        link.addView(linkText);
        foot.addView(link, Ui.weight());
        // Round, white with a hairline: as the conversation's own buttons; a tap on it is the line's.
        ImageView gear = Ui.iconButton(this, R.drawable.ic_gear, 44, 20, R.color.text2);
        gear.setBackground(Ui.round(col(R.color.surface), dp(22), dp(1), col(R.color.border)));
        gear.setClickable(false);
        gear.setFocusable(false);
        foot.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        d.addView(foot, Ui.fill());
        Ui.margins(foot, this, 12, 4, 12, 12);
        return d;
    }

    /** The drawer's column of icons: v (w×h dp) in the middle of a 28dp square. */
    private FrameLayout drawerSlot(View v, int w, int h) {
        FrameLayout slot = new FrameLayout(this);
        slot.addView(v, new FrameLayout.LayoutParams(dp(w), dp(h), Gravity.CENTER));
        slot.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        return slot;
    }

    // ================= chats & rendering =================

    private Chat newChat() {
        Chat chat = new Chat(Chat.newKey());
        chat.cwd = cwd.isEmpty() ? defaultCwd : cwd;
        chats.put(chat.key, chat);
        return chat;
    }

    private void switchTo(Chat chat) {
        if (finding()) findEnd();
        if (current != null) current.draft = input.getText().toString();
        current = chat;
        input.setText(chat.draft);
        input.setSelection(input.length());
        stick = true;
        adapter.show(chat, true);
        renderNow();
        renderSessions();
    }

    /** Coalesces updates to one per frame. */
    private void render() {
        if (renderQueued) return;
        renderQueued = true;
        list.postOnAnimation(() -> { renderQueued = false; renderNow(); });
    }

    private void renderNow() {
        adapter.show(current, false);
        boolean empty = current.messages.isEmpty() && !current.running;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) fillEmpty();
        if (stick) scrollToEnd(false);
        updateChrome();
        updateLink();
    }

    /** A path as Claude Code means it (absolute, ~/…, or from this conversation's folder) as a file here. */
    private java.io.File userFile(String path) {
        String p = Uri.decode(path);
        if (p.startsWith("~/")) p = Userland.HOME + p.substring(1);
        else if (!p.startsWith("/")) p = (current.cwd == null || current.cwd.isEmpty() ? defaultCwd : current.cwd) + "/" + p;
        return userland.file(p);
    }

    private View jumpButton(int icon) {
        if (immersive) return glassButton(icon, 18);
        ImageView b = Ui.iconButton(this, icon, 36, 18, R.color.text2);
        b.setBackground(Ui.press(this, Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.border)), dp(18)));
        b.setElevation(dp(3));
        return b;
    }

    /** The jumps sit where a right thumb rests, two thirds down the screen; never down on the box. */
    private void placeJump() {
        View parent = (View) jump.getParent();
        int screen = getWindow().getDecorView().getHeight();
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
    private void showJump() {
        // While /find looks for something they go between the places found, and stay.
        boolean finding = finding();
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

    private void scrollToEnd(boolean smooth) {
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
                list.smoothScrollToPosition(last);
                // Nothing to go (the end in sight already, or stopped before its first frame): no jump under way.
                list.postOnAnimation(() -> { if (list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) toEnd = false; });
            });
        });
    }

    private void updateChrome() {
        title.setText(current.title.isEmpty() ? "新对话" : current.title);
        boolean typed = input.getText().toString().trim().length() > 0 || !current.attachments.isEmpty();
        boolean canSend = linked && typed;
        // Plain icons, no filled button: Enter to send, a ringed square to stop the reply.
        boolean stop = current.running && !typed;
        sendBtn.setImageResource(stop ? R.drawable.ic_stop_circle : R.drawable.ic_enter);
        sendBtn.setImageTintList(android.content.res.ColorStateList.valueOf(col(R.color.text)));
        sendBtn.setBackground(Ui.press(this, null, dp(18)));
        sendBtn.setAlpha(stop || canSend ? 1f : 0.35f);
        sendBtn.setEnabled(stop || canSend);
        sendBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        renderAttachments();
    }

    private static String shortModel(String m) {
        return m == null || m.isEmpty() ? "默认模型" : m.replaceAll("(?i)\\[1m\\]$", "");
    }

    /** What the empty page last showed: rebuilt only when that changes, so what moves in it is not cut off. */
    private String emptyShown;

    private void fillEmpty() {
        String folder = current.cwd == null || current.cwd.isEmpty() ? defaultCwd : current.cwd;
        String shown = setup + "|" + installStatus + "|" + installError + "|" + linked + "|" + folder + "|"
                + System.identityHashCode(current) + "|" + System.identityHashCode(emptyView);
        if (shown.equals(emptyShown) && emptyView.getChildCount() > 0) return;
        emptyShown = shown;
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(22), dp(20), dp(22), dp(40));
        if (setup != null) buildSetup(box);
        else buildHero(box);
        emptyView.removeAllViews();
        emptyView.addView(box, new ViewGroup.LayoutParams(-1, -1));
    }

    private void buildHero(LinearLayout box) {
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.mascot);
        box.addView(mark, new LinearLayout.LayoutParams(dp(65), dp(40))); // 5dp to each of its 13×8 pixels
        // The folder it will work in, which can be changed only until the first message: tapped, it is picked (/folder).
        boolean pick = linked && current.sessionId == null;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = h < 5 ? "夜深了" : h < 11 ? "早上好" : h < 13 ? "中午好" : h < 18 ? "下午好" : "晚上好";
        TextView big = Ui.text(this, hello, 30, R.color.text);
        big.setTypeface(Ui.SERIF);
        big.setPadding(0, dp(18), 0, dp(pick ? 2 : 10)); // the line under it is 8dp in its own round
        big.setGravity(Gravity.CENTER);
        box.addView(big, Ui.fill());
        String folder = Ui.base(current.cwd == null || current.cwd.isEmpty() ? defaultCwd : current.cwd);
        SpannableStringBuilder sub = new SpannableStringBuilder();
        if (linked) {
            sub.append("在 ");
            int s = sub.length();
            sub.append(folder.isEmpty() ? "~" : folder);
            sub.setSpan(new StyleSpan(Typeface.BOLD), s, sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (pick) sub.setSpan(new ForegroundColorSpan(col(R.color.accent)), s, sub.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sub.append(" 里，想做点什么？");
        } else {
            sub.append("正在启动 Claude Code…");
        }
        TextView p = Ui.text(this, sub, 15, R.color.text2);
        p.setGravity(Gravity.CENTER);
        if (pick) {
            p.setPadding(dp(16), dp(8), dp(16), dp(8));
            p.setBackground(Ui.press(this, null, dp(20)));
            p.setOnClickListener(v -> folderSheet(null));
        }
        box.addView(p, Ui.wrap());
        // A new conversation's: one after another up into place. Built again on every render, it stays still then.
        if (heroFor == current) return;
        heroFor = current;
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(18));
            v.animate().alpha(1f).translationY(0).setStartDelay(40L * i).setDuration(Motion.PAGE)
                    .setInterpolator(Motion.EMPHASIZED).start();
        }
    }

    /** The conversation the greeting last came up for: shown again for it, it stays still. */
    private Chat heroFor;

    private void buildSetup(LinearLayout box) {
        box.setGravity(Gravity.START);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.mascot);
        box.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(33)));
        boolean busy = "install".equals(setup);
        TextView h = Ui.text(this, busy ? "正在准备运行环境" : "installFailed".equals(setup) ? "运行环境没准备好" : "Claude Code 没能启动", 26, R.color.text);
        h.setTypeface(Ui.SERIF);
        h.setPadding(0, dp(16), 0, dp(8));
        box.addView(h);
        String why;
        if (busy) why = installStatus.isEmpty() ? "…" : installStatus;
        else if ("installFailed".equals(setup)) why = installError;
        else why = "后台 30 秒内没有起来。可以重试，或者在设置里看运行日志。";
        TextView p = Ui.text(this, why, 15, R.color.text2);
        p.setLineSpacing(0, 1.25f);
        p.setPadding(0, 0, 0, dp(12));
        box.addView(p);
        if (busy) return;
        LinearLayout actions = Ui.row(this);
        TextView retry = button("重试", true, v -> connect(true));
        retry.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        retry.setTextColor(col(R.color.bg));
        actions.addView(retry);
        TextView logs = button("运行日志", false, v -> logSheet());
        actions.addView(logs);
        Ui.margins(logs, this, 8, 0, 0, 0);
        box.addView(actions);
        Ui.margins(actions, this, 0, 18, 0, 0);
    }

    /** Unpacks the userland (first launch, or an update that brings a new one), then connects. */
    private void install() {
        if (installing) return;
        installing = true;
        setup = "install";
        installStatus = "";
        linked = false;
        renderNow();
        work.execute(() -> {
            String error = null;
            try {
                userland.install(p -> ui.post(() -> {
                    installStatus = p;
                    if ("install".equals(setup) && emptyView.getVisibility() == View.VISIBLE) fillEmpty();
                }));
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String err = error;
            ui.post(() -> {
                installing = false;
                if (err != null) {
                    setup = "installFailed";
                    installError = err;
                    renderNow();
                    return;
                }
                setup = null;
                connect(false);
                if (!prefs.getBoolean("askedStorage", false)) {
                    prefs.edit().putBoolean("askedStorage", true).apply();
                    requestStorage();
                }
            });
        });
    }

    private TextView button(String label, boolean accent, View.OnClickListener click) {
        TextView b = Ui.text(this, label, 14.5f, accent ? R.color.onAccent : R.color.text);
        b.setTypeface(Ui.MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(38));
        b.setBackground(Ui.press(this, accent ? Ui.round(col(R.color.accent), dp(10))
                : Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)), dp(10)));
        b.setOnClickListener(click);
        b.setLayoutParams(Ui.wrap());
        return b;
    }

    // ================= sessions =================

    private void loadSessions() {
        if (!linked) { renderSessions(); return; }
        bridge.get("/sessions", (status, data) -> {
            if (status == 200 && data.optJSONArray("sessions") != null) keepSessions(data.optJSONArray("sessions"));
            renderSessions();
        });
    }

    /** The conversation list as last read, kept so it shows at once on the next start. */
    private java.io.File sessionsFile() { return new java.io.File(getFilesDir(), "sessions.json"); }

    /** The list was read from the bridge (or changed here) in this run: the kept one is older. */
    private boolean sessionsRead;

    private JSONArray keptSessions() {
        try {
            JSONArray kept = new JSONArray(Userland.read(new java.io.FileInputStream(sessionsFile())));
            for (int i = 0; i < kept.length(); i++) {
                JSONObject s = kept.optJSONObject(i);
                if (s != null) s.remove("running"); // a new start: nothing runs yet
            }
            return kept;
        } catch (java.io.IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private void keepSessions(JSONArray next) {
        sessions = next;
        sessionsRead = true;
        String text = next.toString();
        try {
            work.execute(() -> {
                // Written aside, then moved in: a write cut short leaves the last list whole.
                java.io.File f = sessionsFile(), part = new java.io.File(f.getPath() + ".tmp");
                try {
                    Userland.write(part, text);
                    if (!part.renameTo(f)) part.delete();
                } catch (java.io.IOException ignored) {
                    // read from the bridge again next time
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closing
        }
    }

    /** Asks the bridge which conversations contain the words being searched for. */
    private void searchText() {
        String q = search.trim();
        if (q.isEmpty() || !linked) return;
        bridge.get("/search?q=" + Uri.encode(q), (status, data) -> {
            if (!q.equals(search.trim()) || status != 200) return;
            hits.clear();
            JSONArray a = data.optJSONArray("results");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null) hits.put(r.optString("id"), r.optString("snippet"));
            }
            renderSessions();
        });
    }

    /** Takes what the bridge read from Claude Code and its settings. */
    /** Claude Code's own model list (as its /model shows it): value, name. */
    private JSONArray claudeModels = new JSONArray();

    /** Whether Claude Code's default (no model chosen) is this model. */
    private boolean defaultResolved(String resolved) {
        for (int i = 0; i < claudeModels.length(); i++) {
            JSONObject cm = claudeModels.optJSONObject(i);
            if (cm != null && cm.optString("value").equals("default")) return !resolved.isEmpty() && resolved.equals(cm.optString("resolvedModel"));
        }
        return false;
    }

    private void applyConfig(JSONObject data) {
        fill(models, data.optJSONArray("models"));
        JSONArray cm = data.optJSONArray("claudeModels");
        if (cm != null && cm.length() > 0) {
            claudeModels = cm;
            prefs.edit().putString("claudeModels", cm.toString()).apply();
        }
        defaultModel = data.optString("defaultModel");
        claudeVersion = data.optString("claudeVersion");
        configEffort = data.optString("effort").toLowerCase(Locale.ROOT);
        // Found a moment after the bridge starts: until then, the last ones it gave.
        if (data.optJSONArray("efforts") != null && data.optJSONArray("efforts").length() > 0) {
            fill(efforts, data.optJSONArray("efforts"));
            fill(permissionModes, data.optJSONArray("permissionModes"));
            prefs.edit().putString("efforts", data.optJSONArray("efforts").toString())
                    .putString("permissionModes", String.valueOf(data.optJSONArray("permissionModes"))).apply();
        }
        updateChrome();
    }

    private static void fill(List<String> to, JSONArray from) {
        to.clear();
        for (int i = 0; from != null && i < from.length(); i++) to.add(from.optString(i));
    }

    /** How long ago a conversation last changed (its last message), short: now, 5m, 1h, 3d, 2w,
     *  4mo, 1y. Read again each time the drawer opens. */
    private static String ago(double mtime) {
        if (Double.isNaN(mtime)) return "";
        long s = Math.max(0, System.currentTimeMillis() / 1000 - (long) mtime), day = 86_400;
        if (s < 60) return "now";
        if (s < 3600) return s / 60 + "m";
        if (s < day) return s / 3600 + "h";
        if (s < 7 * day) return s / day + "d";
        if (s < 30 * day) return s / (7 * day) + "w";
        if (s < 365 * day) return s / (30 * day) + "mo";
        return s / (365 * day) + "y";
    }

    private static String group(double mtime) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        long t = (long) (mtime * 1000), s = start.getTimeInMillis(), day = 86_400_000L;
        if (t >= s) return "今天";
        if (t >= s - day) return "昨天";
        if (t >= s - 7 * day) return "过去 7 天";
        if (t >= s - 30 * day) return "过去 30 天";
        return "更早";
    }

    private void renderSessions() {
        if (sessionsBox == null) return;
        drawnState = drawerState();
        sessionsBox.removeAllViews();
        String q = search.trim().toLowerCase(Locale.ROOT);
        String group = "";
        int shown = 0;
        // Only the working folder's (Settings → 外观 → 只显示当前目录): new conversations' folder, not its subfolders.
        String here = onlyHere() ? workFolder().replaceAll("/+$", "") : "";
        // Pinned ones first, under their own heading.
        java.util.Set<String> pins = pinned();
        List<JSONObject> ordered = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.optJSONObject(i);
                if (s != null && pins.contains(s.optString("id")) == (pass == 0)) ordered.add(s);
            }
        }
        for (JSONObject s : ordered) {
            String titleText = s.optString("title").isEmpty() ? "新对话" : s.optString("title"), folder = s.optString("cwd"), id = s.optString("id");
            if (!here.isEmpty() && !folder.replaceAll("/+$", "").equals(here)) continue;
            boolean byName = titleText.toLowerCase(Locale.ROOT).contains(q) || folder.toLowerCase(Locale.ROOT).contains(q);
            if (!q.isEmpty() && !byName && !hits.containsKey(id)) continue;
            String g = pins.contains(id) ? "置顶" : group(s.optDouble("mtime"));
            if (!g.equals(group)) {
                group = g;
                // As Settings' headings: the accent, close under the search, apart from a group above.
                TextView label = Ui.text(this, g, 14, R.color.accent);
                label.setTypeface(Ui.MEDIUM);
                label.setPadding(dp(12), dp(sessionsBox.getChildCount() == 0 ? 8 : 20), dp(12), dp(8));
                sessionsBox.addView(label);
            }
            Chat chat = chats.get(bySession.containsKey(id) ? bySession.get(id) : id);
            boolean live = chat != null ? chat.running : s.optBoolean("running");
            LinearLayout item = Ui.column(this);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            boolean active = id.equals(current.sessionId);
            // The open one on a pale round of the accent, as what is chosen in Settings.
            item.setBackground(Ui.press(this, active ? Ui.round(col(R.color.accentSoft), dp(20)) : null, dp(20)));
            LinearLayout line = Ui.row(this);
            if (live) {
                View dot = new View(this);
                dot.setBackground(Ui.round(col(R.color.accent), dp(4)));
                line.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
                Ui.margins(dot, this, 0, 0, 6, 0);
            }
            TextView t = Ui.text(this, titleText, 15, R.color.text);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(t, Ui.weight());
            item.addView(line, Ui.fill());
            TextView meta = Ui.text(this, Ui.base(folder), 12.5f, R.color.text3);
            meta.setPadding(0, dp(3), 0, 0);
            meta.setSingleLine(true);
            if (!q.isEmpty() && !byName) { // found in what was said: show where
                String snip = hits.get(id);
                SpannableStringBuilder sb = new SpannableStringBuilder(snip);
                int at = snip.toLowerCase(Locale.ROOT).indexOf(q);
                if (at >= 0) sb.setSpan(new ForegroundColorSpan(col(R.color.text)), at, at + q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                meta.setText(sb);
                meta.setEllipsize(TextUtils.TruncateAt.END);
            }
            // How long ago, in a column of one width at the end of the folder's line, its text from the
            // column's start: 55m, 1h and 3d begin at one place.
            // Shown unless turned off (Settings → 外观 → 对话时间).
            LinearLayout under = Ui.row(this);
            under.addView(meta, Ui.weight());
            if (prefs.getBoolean("drawerTimes", true)) {
                TextView when = Ui.text(this, ago(s.optDouble("mtime")), 11.5f, R.color.text3);
                when.setFontFeatureSettings("tnum");
                when.setSingleLine(true);
                when.setPadding(0, dp(3), 0, 0);
                int column = (int) Math.ceil(when.getPaint().measureText("88mo"));
                under.addView(when, new LinearLayout.LayoutParams(column + dp(2), -2));
                Ui.margins(when, this, 10, 0, 0, 0);
            }
            item.addView(under, Ui.fill());
            item.setOnClickListener(v -> openSession(id, titleText, folder));
            item.setOnLongClickListener(v -> { sessionActions(id, titleText, folder); return true; });
            sessionsBox.addView(item, Ui.fill());
            shown++;
        }
        if (shown == 0) {
            String none = !q.isEmpty() ? "没有匹配的对话" : here.isEmpty() ? "还没有对话" : Ui.base(here) + " 里还没有对话";
            LinearLayout empty = Ui.column(this);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(16), dp(48), dp(16), dp(28));
            TextView words = Ui.text(this, linked ? none : "连接后显示历史对话", 14, R.color.text3);
            words.setGravity(Gravity.CENTER);
            empty.addView(words);
            sessionsBox.addView(empty, Ui.fill());
        }
    }

    private void openSession(String id, String titleText, String folder) {
        frame.close();
        String key = bySession.containsKey(id) ? bySession.get(id) : id;
        Chat chat = chats.get(key);
        if (chat == null) {
            chat = new Chat(key);
            chat.sessionId = id;
            chat.cwd = folder;
            chat.loaded = false;
            chats.put(key, chat);
            bySession.put(id, key);
        }
        if (chat.title.isEmpty()) chat.title = titleText;
        switchTo(chat);
        loadHistory(chat);
        if (linked) opened(chat);
    }

    /** A new conversation's Claude Code (the bridge's spare) is made ready, so its first message
     *  does not wait for one to start. A saved conversation's starts only when a message is sent. */
    private void opened(Chat chat) {
        if (chat == null || chat.sessionId != null) return;
        warmedAt = android.os.SystemClock.uptimeMillis();
        bridge.post("/open", obj("chat", chat.key, "sessionId", chat.sessionId, "cwd", chat.cwd,
                "model", model.isEmpty() ? null : model, "effort", effort.isEmpty() ? null : effort,
                "restart", restartIdle()), null);
    }

    /** When a new conversation was last opened (or written in): see warm. */
    private long warmedAt;

    /** Something is being written in a new conversation: its Claude Code gets ready meanwhile
     *  (opened), at most every 20 s. */
    private void warm() {
        if (!linked || current == null || current.running) return;
        if (android.os.SystemClock.uptimeMillis() - warmedAt < 20_000) return;
        opened(current);
    }

    /** The drawer lists only the working folder's conversations (off by default; Settings → 外观). */
    private boolean onlyHere() {
        return prefs.getBoolean("drawerOnlyHere", false);
    }

    /** The folder new conversations start in (Settings → 对话), Claude Code's default until one is picked. */
    private String workFolder() {
        return cwd.isEmpty() ? defaultCwd : cwd;
    }

    /** An idle Claude Code is started again before its next message (on by default; Settings → 后台). */
    private boolean restartIdle() {
        return prefs.getBoolean("restartIdle", true);
    }

    /** Reads a saved conversation once; a failed read is tried again on the next link or tap. */
    private void loadHistory(Chat target) {
        if (target.loaded || target.sessionId == null || !linked) return;
        target.loaded = true;
        bridge.get("/history?id=" + Uri.encode(target.sessionId), (status, data) -> {
            if (status != 200) {
                target.loaded = false;
                toast(status == -2 ? "读取对话失败：桥接 30 秒没有回应" : status == -1 || status == Bridge.REFUSED ? "读取对话失败：连不上桥接" : "读取对话失败：" + data.optString("error", "HTTP " + status));
                return;
            }
            target.load(data);
            if (!data.optString("cwd").isEmpty()) target.cwd = data.optString("cwd");
            if (current == target) { adapter.show(target, true); stick = true; renderNow(); }
        });
    }

    private java.util.Set<String> pinned() {
        java.util.Set<String> out = new java.util.HashSet<>();
        JSONArray a = savedArray("pinned");
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private void sessionActions(String id, String titleText, String folder) {
        sessionsBox.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        Dialog sheet = sheet(titleText, null);
        LinearLayout card = card(sheet);
        boolean pin = pinned().contains(id);
        option(card, R.drawable.ic_pin, pin ? "取消置顶" : "置顶", null, false, () -> {
            sheet.dismiss();
            java.util.Set<String> pins = pinned();
            if (pin) pins.remove(id); else pins.add(id);
            prefs.edit().putString("pinned", new JSONArray(pins).toString()).apply();
            renderSessions();
        });
        option(card, R.drawable.ic_pencil, "重命名", null, false, () -> {
            sheet.dismiss();
            textSheet("重命名", titleText, "", false, (t, done) -> {
                String name = t.trim();
                if (name.isEmpty()) return;
                bridge.post("/title", obj("id", id, "title", name), (status, data) -> {
                    if (status != 200) { toast("重命名失败"); return; }
                    Chat ch = chats.get(bySession.containsKey(id) ? bySession.get(id) : id);
                    if (ch != null) ch.title = name;
                    done.run();
                    updateChrome();
                    loadSessions();
                });
            }, null);
        });
        option(card, R.drawable.ic_trash, "删除对话", null, true, () -> {
            sheet.dismiss();
            bridge.post("/delete", obj("id", id), (status, data) -> {
                if (status != 200) { toast("删除失败"); return; }
                JSONArray next = new JSONArray();
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject s = sessions.optJSONObject(i);
                    if (s != null && !id.equals(s.optString("id"))) next.put(s);
                }
                keepSessions(next);
                String key = bySession.containsKey(id) ? bySession.get(id) : id;
                if (current.key.equals(key)) switchTo(newChat());
                chats.remove(key);
                renderSessions();
                toast("已删除");
            });
        });
        sheet.show();
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        } catch (JSONException ignored) {
            // keys are strings
        }
        return o;
    }

    // ================= bridge events =================

    @Override public void events(List<JSONObject> batch) {
        for (JSONObject ev : batch) onEvent(ev);
        for (Chat ch : new ArrayList<>(chats.values())) sendQueued(ch);
        render();
        // Events come many times a second while a reply streams: the list is built again only when
        // what it shows of the live conversations changed.
        if (frame.isOpen() && !drawerState().equals(drawnState)) renderSessions();
    }

    /** What the drawer's list was last built with of the live conversations (see drawerState). */
    private String drawnState = "";

    /** The open conversation and those running: all the list shows that events change. */
    private String drawerState() {
        StringBuilder s = new StringBuilder(current == null ? "" : String.valueOf(current.sessionId));
        for (Chat ch : chats.values()) if (ch.running && ch.sessionId != null) s.append(' ').append(ch.sessionId);
        return s.toString();
    }

    private Chat chatFor(JSONObject ev) {
        String key = ev.optString("chat");
        Chat chat = chats.get(key);
        if (chat != null) return chat;
        JSONObject data = ev.optJSONObject("data");
        String sid = data != null && data.has("session_id") ? data.optString("session_id") : key.startsWith("n-") ? null : key;
        if (sid != null && bySession.containsKey(sid)) return chats.get(bySession.get(sid));
        // Started before this app was: its history loads from the transcript when opened.
        chat = new Chat(key);
        chat.sessionId = sid;
        chat.cwd = defaultCwd;
        chat.loaded = false;
        chats.put(key, chat);
        if (sid != null) bySession.put(sid, key);
        return chat;
    }

    private void onEvent(JSONObject ev) {
        if ("gap".equals(ev.optString("kind"))) { gap(); return; }
        Chat chat = chatFor(ev);
        chat.lastEvent = System.currentTimeMillis();
        String kind = ev.optString("kind");
        JSONObject data = ev.optJSONObject("data");
        if ("cc".equals(kind) && data != null) {
            if ("system".equals(data.optString("type")) && "init".equals(data.optString("subtype"))) {
                SharedPreferences.Editor e = prefs.edit();
                if (data.optJSONArray("tools") != null) e.putString("tools", data.optJSONArray("tools").toString());
                if (data.optJSONArray("mcp_servers") != null) e.putString("mcpServers", data.optJSONArray("mcp_servers").toString());
                if (data.optJSONArray("skills") != null) e.putString("skills", data.optJSONArray("skills").toString());
                if (data.optJSONArray("agents") != null) e.putString("agents", data.optJSONArray("agents").toString());
                if (data.optJSONArray("slash_commands") != null) e.putString("slashCommands", data.optJSONArray("slash_commands").toString());
                e.apply();
                JSONArray cmds = data.optJSONArray("slash_commands");
                if (cmds != null && cmds.length() > 0) {
                    commands.clear();
                    for (int i = 0; i < cmds.length(); i++) if (!cmds.optString(i).startsWith("_")) commands.add(cmds.optString(i));
                    prefs.edit().putString("commands", new JSONArray(commands).toString()).apply();
                }
            }
            if (chat.apply(data)) {
                loadSessions();
                loadHistory(chat); // one not read yet (or since a gap) is read now that the turn is whole
            }
            if (chat.sessionId != null) bySession.put(chat.sessionId, chat.key);
        } else if ("title".equals(kind)) {
            if (data != null && !data.optString("title").isEmpty()) {
                chat.title = data.optString("title"); // a short name made after the first turn
                updateChrome();
                loadSessions();
            }
        } else if ("state".equals(kind)) {
            if (data != null && data.optBoolean("running") && !chat.running) {
                chat.running = true;
                chat.startedAt = System.currentTimeMillis();
            }
        } else if ("exit".equals(kind)) {
            if (data != null && data.optBoolean("running")) {
                Chat.Msg m = chat.reply(true);
                Chat.Block b = new Chat.Block(Chat.Block.ERROR);
                b.title = "Claude Code 意外退出";
                int code = data.optInt("code");
                // Popen gives a negative code for a signal; 9 is the system killing it.
                String how = code == -9 ? "被系统强制结束（SIGKILL）"
                        : code < 0 ? "被信号 " + (-code) + " 结束" : "退出码 " + code;
                StringBuilder err = new StringBuilder();
                for (String line : data.optString("stderr").split("\n")) {
                    // Claude Code notes models it does not know (DeepSeek's) on every run: not the cause.
                    if (line.trim().isEmpty() || line.contains("unrecognized_model")) continue;
                    err.append('\n').append(line);
                }
                b.text = how + err;
                m.blocks.add(b);
                m.done = true;
                m.touch();
                chat.stopped = true; // Claude Code died: what is queued waits to be sent by hand
            }
            chat.unhand(); // what it held waits here again
            chat.running = false;
        }
    }

    @Override public void link(boolean up) {
        if (up == linked) return;
        if (up) afterLink();
        else {
            linked = false;
            updateLink();
            render();
            ui.removeCallbacks(recover);
            ui.postDelayed(recover, 2000);
        }
    }

    private int connectAttempt;

    /** Connects to the bridge; a restart is never refused, and only the latest attempt counts. */
    private void connect(boolean restart) {
        if (connecting && !restart) return;
        connecting = true;
        int attempt = ++connectAttempt;
        updateLink();
        bridge.connect(restart, result -> {
            if (attempt != connectAttempt) return;
            connecting = false;
            if ("ok".equals(result)) {
                setup = null;
                afterLink();
            } else if ("install".equals(result)) {
                install();
            } else {
                setup = result;
                linked = false;
                renderNow();
            }
        });
    }

    private void afterLink() {
        linked = true;
        everLinked = true;
        setup = null;
        bridge.get("/health", (status, data) -> {
            if (status != 200) return;
            double started = data.optDouble("started", 0);
            if (bridgeStarted != 0 && started != bridgeStarted) {
                // A new bridge: the old one's turns ended with it.
                for (Chat ch : chats.values()) {
                    if (ch.running) fail(ch, "后台重启了", "这一轮被中断，发消息即可接着这个对话继续。");
                    ch.unhand(); // what the old one held waits here again
                }
            }
            bridgeStarted = started;
            home = data.optString("home");
            defaultCwd = data.optString("defaultCwd");
            for (Chat ch : chats.values()) if (ch.cwd == null || ch.cwd.isEmpty()) ch.cwd = cwd.isEmpty() ? defaultCwd : cwd;
            renderNow();
        });
        bridge.get("/config", (status, data) -> {
            if (status != 200) return;
            applyConfig(data);
            if (current.messages.isEmpty()) renderNow();
        });
        renderNow();
        loadSessions();
        loadHistory(current);
    }

    private String shownLink;

    private void updateLink() {
        if (linkDot == null) return;
        // Linked: the user's own words (a setting), nothing when they wrote none.
        String label = linked ? prefs.getString("footer", "") : installing ? "正在准备…"
                : connecting ? "正在启动…" : "未运行 · 点此重试";
        int bannerShown = !linked && !connecting && setup == null && everLinked ? View.VISIBLE : View.GONE;
        if (banner.getVisibility() != bannerShown) banner.setVisibility(bannerShown);
        if (label.equals(shownLink)) return;
        shownLink = label;
        int dot = linked ? R.color.ok : connecting ? R.color.text3 : R.color.danger;
        linkDot.setBackground(Ui.round(col(dot), dp(4)));
        linkDot.setVisibility(linked ? View.GONE : View.VISIBLE);
        linkText.setPadding(linked ? 0 : dp(9), 0, 0, 0);
        linkText.setText(label);
        ((View) linkText.getParent()).setVisibility(label.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    private boolean hasStorage() { return checkSelfPermission(STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED; }

    private void requestStorage() {
        requestPermissions(new String[]{STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, ASK_STORAGE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != ASK_STORAGE) return;
        refreshSettings();
        if (!hasStorage()) { toast("没有存储权限：Claude Code 看不到手机里的文件"); return; }
        // /sdcard is shared into the userland when the bridge starts.
        if (!installing) connect(true);
    }

    // ================= sending =================

    private void send() {
        Chat chat = current;
        String text = input.getText().toString().replaceAll("\\s+$", "");
        if (text.trim().isEmpty() && chat.attachments.isEmpty()) {
            if (chat.running) stop(chat);
            return;
        }
        if (text.matches("^/\\S[^\\n]*$") && localCommand(text)) {
            if (!text.startsWith("/find ")) input.setText(""); // /find's words stay in the box
            hideMenu();
            updateChrome();
            return;
        }
        if (!linked) {
            toast(installing ? "运行环境还在准备，稍后再发" : "Claude Code 正在启动，稍后再发");
            if (!connecting) connect(false);
            return;
        }
        if (finding()) findEnd();
        List<Chat.Attachment> attached = new ArrayList<>(chat.attachments);
        chat.attachments.clear();
        input.setText("");
        hideMenu();
        if (chat.running) {
            // Written while a reply runs: it waits (edited, taken back or sent now meanwhile), and goes
            // when the reply runs a tool (插入) or when it ends (排队): sendQueued.
            chat.queue.add(new Chat.Queued(text, attached));
            stick = true;
            sendQueued(chat);
            renderNow();
            return;
        }
        hideKeyboard();
        submit(chat, text, attached);
    }

    /** What is written while a reply runs: 插入 (sent when the reply runs a tool, the model gets it
     *  with the tool's result; the default) or 排队 (sent when the reply ends). Settings → 对话. */
    private boolean insertWhileRunning() {
        return prefs.getBoolean("insertWhileRunning", true);
    }

    /** Hands a message to Claude Code while a reply runs: the model gets it with its next request,
     *  or Claude Code starts a turn for it when this one ends. It waits as it was (edited, taken back
     *  or sent now) until the model has it (Chat.apply, command_lifecycle). */
    private void insert(Chat chat, Chat.Queued q) {
        q.inserted = true;
        q.uuid = java.util.UUID.randomUUID().toString();
        upload(chat, q.attachments, 0, new ArrayList<>(), q.text, q);
    }

    /** An inserted message could not be sent: it waits for the reply to end instead. */
    private void notInserted(Chat chat, Chat.Queued q, String why) {
        if (!q.inserted) return;
        q.inserted = false;
        q.uuid = null;
        q.notInserted = true; // not tried again: it goes when the reply ends
        toast("没能插入，改为回复结束后发送：" + why);
        if (!chat.running) sendQueued(chat);
        render();
    }

    /** Sends a message as the chat's next turn. */
    private void submit(Chat chat, String text, List<Chat.Attachment> attached) {
        Chat.Msg user = new Chat.Msg(Chat.Msg.USER);
        user.text = text;
        for (Chat.Attachment a : attached) {
            if (a.image) user.images.add(a.shown);
            else user.files.add(a.name);
        }
        chat.messages.add(user);
        if (chat.title.isEmpty()) {
            // A short message is the name until one is made (Claude Code makes none for those); a long one is not shown.
            String t = text.trim().replaceAll("\\s+", " ");
            if (!t.isEmpty() && t.length() <= SHORT_TITLE) chat.title = t;
        }
        if (chat.cwd == null || chat.cwd.isEmpty()) chat.cwd = cwd.isEmpty() ? defaultCwd : cwd;
        chat.running = true;
        chat.stopped = false;
        chat.startedAt = System.currentTimeMillis();
        chat.verb = chat.randomVerb();
        chat.detail = "";
        stick = true;
        renderNow();
        upload(chat, attached, 0, new ArrayList<>(), text, null);
    }

    /** The reply ended: the next queued message goes, unless the reply was stopped by hand. */
    private void sendQueued(Chat chat) {
        if (chat.queue.isEmpty() || !linked) return;
        if (chat.running) {
            // 插入: while the reply runs a tool, what waits goes now and the model gets it with the
            // tool's result, without the reply ending (a stop asked for keeps it waiting).
            if (!insertWhileRunning() || chat.stopping || !chat.toolRunning()) return;
            for (Chat.Queued q : new ArrayList<>(chat.queue)) if (!q.inserted && !q.now && !q.notInserted) insert(chat, q);
            return;
        }
        Chat.Queued q = null;
        for (Chat.Queued w : chat.queue) if (!w.inserted) { q = w; break; } // Claude Code has the others
        if (q == null || (chat.stopped && !q.now)) return;
        chat.queue.remove(q);
        submit(chat, q.text, q.attachments);
    }

    /** Takes a waiting message back from Claude Code, if it was handed over, then goes on; when the
     *  model has got it meanwhile, it is a message of the conversation and nothing is done. */
    private void takeBack(Chat chat, Chat.Queued q, Runnable then) {
        if (!q.inserted) { then.run(); return; }
        bridge.post("/cancel", obj("chat", chat.key, "sessionId", chat.sessionId, "uuid", q.uuid), (status, data) -> {
            if (status == 200 && data.optBoolean("cancelled")) {
                q.inserted = false;
                q.uuid = null;
                then.run();
            } else if (status == 200) {
                toast("模型已经读到这条消息了");
            } else {
                toast("没能撤回：" + data.optString("error", "HTTP " + status));
            }
        });
    }

    private final ThreadAdapter.Queue queueActions = new ThreadAdapter.Queue() {
        @Override public void edit(Chat.Queued q) {
            Chat chat = current;
            takeBack(chat, q, () -> {
                if (!chat.queue.remove(q) || current != chat) return;
                String typed = input.getText().toString();
                String text = typed.trim().isEmpty() ? q.text : q.text + "\n" + typed;
                current.attachments.addAll(0, q.attachments);
                input.setText(text);
                input.setSelection(input.length());
                input.requestFocus();
                showKeyboard();
                renderNow();
            });
        }

        @Override public void cancel(Chat.Queued q) {
            Chat chat = current;
            takeBack(chat, q, () -> {
                chat.queue.remove(q);
                renderNow();
            });
        }

        @Override public void sendNow(Chat.Queued q) {
            Chat chat = current;
            // With Claude Code already: stopping the reply is enough, it then starts a turn for it.
            if (q.inserted) { if (chat.running) stop(chat); renderNow(); return; }
            chat.queue.remove(q);
            chat.queue.add(0, q);
            q.now = true;
            if (chat.running) stop(chat);
            else sendQueued(chat);
            renderNow();
        }
    };

    /** Saves non-picture files into the working folder one by one, then sends. */
    private void upload(Chat chat, List<Chat.Attachment> attached, int from, List<String> saved, String text, Chat.Queued inserted) {
        int i = from;
        while (i < attached.size() && attached.get(i).image) i++;
        if (i >= attached.size()) { deliver(chat, attached, saved, text, inserted); return; }
        Chat.Attachment f = attached.get(i);
        int next = i + 1;
        bridge.upload(chat.cwd, f.name, f.uri, (status, data) -> {
            if (status != 200) {
                String why = f.name + " 没能存到 " + Ui.base(chat.cwd) + "：" + data.optString("error", String.valueOf(status));
                if (inserted != null) notInserted(chat, inserted, why);
                else fail(chat, "发送失败", why);
                return;
            }
            saved.add(data.optString("path"));
            upload(chat, attached, next, saved, text, inserted);
        });
    }

    private void deliver(Chat chat, List<Chat.Attachment> attached, List<String> saved, String text, Chat.Queued inserted) {
        JSONArray content = new JSONArray();
        for (Chat.Attachment a : attached) {
            if (!a.image) continue;
            content.put(obj("type", "image", "source", obj("type", "base64", "media_type", a.type, "data", a.data)));
        }
        StringBuilder full = new StringBuilder(text.trim().isEmpty() ? "" : text);
        if (!saved.isEmpty()) {
            if (full.length() > 0) full.append("\n\n");
            full.append(saved.size() > 1 ? "附件已保存到：" : "附件已保存为：");
            for (String p : saved) full.append("\n- ").append(p);
        }
        if (full.length() > 0) content.put(obj("type", "text", "text", full.toString()));
        long sentAt = System.currentTimeMillis();
        JSONObject body = obj("chat", chat.key, "sessionId", chat.sessionId, "cwd", chat.cwd,
                "model", model.isEmpty() ? null : model, "effort", effort.isEmpty() ? null : effort,
                "content", content, "mid", chat.key + ":" + sentAt, "restart", restartIdle(),
                "uuid", inserted != null ? inserted.uuid : null);
        sendWithRetry(chat, body, sentAt, 0, inserted);
    }

    /** The answer may be lost with the message delivered: the bridge ignores a repeated mid. */
    private void sendWithRetry(Chat chat, JSONObject body, long sentAt, int attempt, Chat.Queued inserted) {
        bridge.post("/send", body, (status, data) -> {
            if (status == 200) return;
            if (status < 0 && attempt < 2) {
                ui.postDelayed(() -> sendWithRetry(chat, body, sentAt, attempt + 1, inserted), 1200);
                return;
            }
            if (inserted != null) {
                // Refused for sure: it waits for the reply instead. No answer: it may have gone (the
                // reply runs on regardless), so it is not sent twice.
                if (status >= 400 || status == Bridge.REFUSED) notInserted(chat, inserted, data.optString("error", String.valueOf(status)));
                return;
            }
            if (chat.lastEvent > sentAt) return; // it is running after all
            fail(chat, "发送失败", status == -2 ? "后台没有回应。" : status == -1 || status == Bridge.REFUSED ? "连不上后台。" : data.optString("error", String.valueOf(status)));
        });
    }

    private void fail(Chat chat, String titleText, String text) {
        chat.running = false;
        Chat.Msg m = chat.reply(true);
        Chat.Block b = new Chat.Block(Chat.Block.ERROR);
        b.title = titleText;
        b.text = text == null ? "" : text;
        m.blocks.add(b);
        m.done = true;
        m.touch();
        render();
    }

    /**
     * Events were dropped while the app was away (the bridge keeps only the last ones): the
     * conversations are read again from their transcripts, a running one when its turn ends.
     */
    private void gap() {
        for (Chat ch : chats.values()) if (ch.sessionId != null) ch.loaded = false;
        if (!current.running) loadHistory(current);
    }

    /** Answers Claude Code's question whether a tool may run. */
    /** answers: for AskUserQuestion, what the user chose (question -> answer), sent with the allow. */
    private void answer(Chat chat, Chat.Block b, boolean allow, JSONObject answers) {
        String request = b.ask;
        if (request == null) return;
        b.ask = null;
        b.touch();
        renderNow();
        JSONObject body = obj("chat", chat.key, "sessionId", chat.sessionId, "requestId", request, "allow", allow);
        if (answers != null) {
            try { body.put("answers", answers); } catch (org.json.JSONException ignored) { /* a JSONObject */ }
        }
        bridge.post("/permission", body, (status, data) -> {
            if (status == 200 || status == 409) return; // 409: not asked any more (the turn ended)
            b.ask = request;
            b.touch();
            render();
            toast("没能回复：" + data.optString("error", String.valueOf(status)));
        });
    }

    private void stop(Chat chat) {
        chat.stopping = true;
        bridge.post("/stop", obj("chat", chat.key, "sessionId", chat.sessionId), null);
    }

    // ================= slash commands =================

    private static final String[][] LOCAL = {
            {"help", "", "所有命令"}, {"new", "", "新对话"}, {"clear", "", "新对话"},
            {"effort", "", "思考级别"},
            {"model", "", "模型"}, {"folder", "", "工作目录"},
            {"find", "<词>", "在本对话里搜索，↑↓ 换一处，不带词结束"},
            {"tree", "", "回到之前的某条消息，重新编辑"}, {"rewind", "", "同 /tree"},
            {"stop", "", "停止"}};
    /** name -> {args, description, "local" when the app handles it} */
    private LinkedHashMap<String, String[]> commandList() {
        LinkedHashMap<String, String[]> all = new LinkedHashMap<>();
        for (String[] c : LOCAL) all.put(c[0], new String[]{c[1], c[2], "local"});
        for (String c : commands) if (!all.containsKey(c)) all.put(c, new String[]{"", "", ""});
        return all;
    }

    private void note(CharSequence text) {
        Chat.Msg m = new Chat.Msg(Chat.Msg.NOTE);
        m.note = text;
        current.messages.add(m);
        stick = true;
        renderNow();
    }

    /** Text from parts: strings as they are, {"b", text} bold, {"c", text} command. */
    private CharSequence styled(Object... parts) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Object p : parts) {
            if (p instanceof String[]) {
                String[] t = (String[]) p;
                int st = s.length();
                s.append(t[1]);
                if ("b".equals(t[0])) {
                    s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(col(R.color.text)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    s.setSpan(new TypefaceSpan("monospace"), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new ForegroundColorSpan(col(R.color.accentPress)), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                s.append(String.valueOf(p));
            }
        }
        return s;
    }

    private boolean localCommand(String text) {
        String t = text.trim().substring(1);
        String name = t.split("\\s+", 2)[0];
        String arg = t.contains(" ") ? t.substring(t.indexOf(' ') + 1).trim() : "";
        switch (name) {
            case "help": {
                List<Object> parts = new ArrayList<>();
                parts.add(new String[]{"b", "命令"});
                for (Map.Entry<String, String[]> e : commandList().entrySet()) {
                    parts.add("\n");
                    parts.add(new String[]{"c", "/" + e.getKey() + (e.getValue()[0].isEmpty() ? "" : " " + e.getValue()[0])});
                    parts.add("  " + e.getValue()[1]);
                }
                note(styled(parts.toArray()));
                return true;
            }
            case "new": case "clear":
                switchTo(newChat());
                return true;
            case "stop":
                if (current.running) stop(current); else toast("现在没有在运行");
                return true;
            case "find":
                find(arg);
                return true;
            case "tree": case "rewind":
                tree();
                return true;
            case "folder":
                if (current.sessionId != null) toast("已有对话的目录不能更改，新对话可以选");
                else folderSheet(null);
                return true;
            case "model":
                if (arg.isEmpty()) { modelSheet(); return true; }
                model = arg;
                prefs.edit().putString("model", model).apply();
                updateChrome();
                return true;
            case "effort": {
                String a = arg.toLowerCase(Locale.ROOT);
                if (a.isEmpty() || (!efforts.isEmpty() && !efforts.contains(a))) { modelSheet(); return true; }
                effort = a;
                prefs.edit().putString("effort", effort).apply();
                updateChrome();
                return true;
            }
            default:
                return false; // Claude Code's own command or skill
        }
    }

    // ================= /tree =================

    /**
     * /tree: the user's messages in this conversation, the last first. One picked, the conversation
     * goes back to before it (the bridge's /rewind cuts the transcript there: the model never sees
     * what was after, a message its safeguards refused included) and its text is in the box again.
     */
    private void tree() {
        Chat chat = current;
        if (chat.sessionId == null) { toast("这个对话还没有可以回退的消息"); return; }
        if (chat.running) { toast("回复结束后才能回退"); return; }
        if (!linked) { toast("Claude Code 正在启动，稍后再试"); return; }
        // Read again: a message sent from here knows its uuid only from the transcript.
        bridge.get("/history?id=" + Uri.encode(chat.sessionId), (status, data) -> {
            if (status != 200) { toast("读取对话失败：" + data.optString("error", "HTTP " + status)); return; }
            chat.load(data);
            chat.loaded = true;
            if (current == chat) { adapter.show(chat, true); renderNow(); }
            List<Chat.Msg> users = new ArrayList<>();
            for (Chat.Msg m : chat.messages) if (m.kind == Chat.Msg.USER && m.uuid != null) users.add(m);
            if (users.isEmpty()) { toast("没有可以回退的消息"); return; }
            Dialog sheet = sheet("回到哪条消息之前", "选中的消息和它之后的对话都会删掉，它的文字放回输入框");
            LinearLayout card = card(sheet);
            for (int i = users.size() - 1; i >= 0; i--) {
                Chat.Msg m = users.get(i);
                option(card, 0, (i + 1) + ". " + preview(m), null, false, () -> {
                    sheet.dismiss();
                    rewindAsk(chat, m);
                });
            }
            sheet.show();
        });
    }

    private static String preview(Chat.Msg m) {
        String t = m.text.trim().replaceAll("\\s+", " ");
        return !t.isEmpty() ? t : !m.images.isEmpty() ? "[图片]" : "（空）";
    }

    private void rewindAsk(Chat chat, Chat.Msg m) {
        Dialog sheet = sheet("回到这条消息之前？", preview(m) + "\n\n它和之后的对话会删掉，不能恢复。");
        LinearLayout actions = Ui.row(this);
        TextView cancel = button("取消", false, v -> sheet.dismiss());
        TextView ok = button("回退", true, v -> { sheet.dismiss(); rewind(chat, m); });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        sheet.show();
    }

    private void rewind(Chat chat, Chat.Msg m) {
        String id = chat.sessionId;
        bridge.post("/rewind", obj("chat", chat.key, "sessionId", id, "uuid", m.uuid), (status, data) -> {
            if (status != 200) { toast("回退失败：" + data.optString("error", "HTTP " + status)); return; }
            if (data.optInt("left") == 0) {
                gone(chat, id, m.text); // nothing was before it: a new conversation starts with its text
            } else {
                int at = chat.messages.indexOf(m);
                if (at >= 0) chat.messages.subList(at, chat.messages.size()).clear();
                if (current == chat) {
                    input.setText(m.text);
                    input.setSelection(input.length());
                    stick = true;
                    adapter.show(chat, true);
                    renderNow();
                } else {
                    chat.draft = m.text;
                }
            }
            if (!m.images.isEmpty()) toast("已回退，图片要重新添加");
            else toast("已回退");
            loadSessions();
        });
    }

    /** The conversation's transcript is gone (nothing left in it): a new one takes its place, with this draft. */
    private void gone(Chat chat, String id, String draft) {
        JSONArray next = new JSONArray();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s != null && !id.equals(s.optString("id"))) next.put(s);
        }
        keepSessions(next);
        chats.remove(chat.key);
        bySession.remove(id);
        Chat fresh = newChat();
        fresh.cwd = chat.cwd;
        fresh.draft = draft;
        if (current == chat) switchTo(fresh);
    }

    // ================= A message's menu =================

    /** A message pressed long: a small card where the finger is, in place of selecting its text. */
    private void messageMenu(Chat.Msg m) {
        list.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        Chat chat = current;
        boolean user = m.kind == Chat.Msg.USER;
        int width = dp(196), shade = dp(14);
        FrameLayout room = new FrameLayout(this);
        room.setPadding(shade, shade, shade, shade);
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.borderStrong)));
        card.setElevation(dp(8));
        room.addView(card, new FrameLayout.LayoutParams(width, -2));
        android.widget.PopupWindow pop = new android.widget.PopupWindow(room, width + 2 * shade, -2, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setOutsideTouchable(true);
        if (user) {
            menuItem(card, pop, R.drawable.ic_copy, "复制", null, () -> copy(m.text));
        } else {
            menuItem(card, pop, R.drawable.ic_copy, "复制文字", null, () -> copy(replyText(m, true)));
            menuItem(card, pop, R.drawable.ic_file, "复制 Markdown", null, () -> copy(replyText(m, false)));
        }
        menuItem(card, pop, R.drawable.ic_edit_lines, "选择文字", null, () -> selectSheet(user ? m.text : replyText(m, true)));
        if (user) menuItem(card, pop, R.drawable.ic_pencil, "编辑重发", null, () -> {
            if (canChange(chat, "回退")) withUuid(chat, m, found -> rewindAsk(chat, found));
        });
        menuItem(card, pop, R.drawable.ic_trash, "删除", null, () -> {
            if (canChange(chat, "删除")) withUuid(chat, m, found -> deleteAsk(chat, found));
        });
        // Where the finger was, kept on the screen: above it when there is no room below.
        room.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = room.getMeasuredWidth(), h = room.getMeasuredHeight();
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        int x = Math.max(0, Math.min((int) touchX - w / 2, screenW - w));
        int y = (int) touchY + h > screenH - dp(24) ? (int) touchY - h : (int) touchY;
        pop.showAtLocation(list, Gravity.NO_GRAVITY, x, Math.max(0, y));
        // It grows out of the finger.
        card.setPivotX(Math.max(0, Math.min(width, touchX - x - shade)));
        card.setPivotY(Math.max(0, Math.min(h - 2 * shade, touchY - Math.max(0, y) - shade)));
        card.setAlpha(0f);
        card.setScaleX(0.8f);
        card.setScaleY(0.8f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
                .setInterpolator(Motion.EMPHASIZED).start();
    }

    /** A reply's text blocks: as shown (plain) or as written (Markdown). */
    private String replyText(Chat.Msg m, boolean plain) {
        StringBuilder s = new StringBuilder();
        for (Chat.Block b : m.blocks) {
            if (b.kind != Chat.Block.TEXT) continue;
            if (s.length() > 0) s.append("\n\n");
            s.append(plain ? md.plain(b.text) : b.text);
        }
        return s.toString();
    }

    /** The text in a sheet, to select a part of it. */
    private void selectSheet(String text) {
        Dialog sheet = sheet("选择文字", null);
        TextView t = Ui.text(this, text, 15.5f, R.color.text);
        t.setLineSpacing(0, 1.1f);
        t.setTextIsSelectable(true);
        content(sheet).addView(t, Ui.fill());
        sheet.show();
    }

    /** Whether the conversation's transcript may be changed now; says why not. */
    private boolean canChange(Chat chat, String what) {
        if (chat.sessionId == null) { toast("这条消息还没存进对话记录"); return false; }
        if (chat.running) { toast("回复结束后才能" + what); return false; }
        if (!linked) { toast("Claude Code 正在启动，稍后再试"); return false; }
        return true;
    }

    /**
     * The message with its uuid. One sent or received here knows it only from the transcript: that
     * is read again, and the message found there by its place among those of its kind and its text.
     */
    private void withUuid(Chat chat, Chat.Msg m, java.util.function.Consumer<Chat.Msg> then) {
        if (m.uuid != null) { then.accept(m); return; }
        int nth = 0;
        for (Chat.Msg o : chat.messages) {
            if (o == m) break;
            if (o.kind == m.kind) nth++;
        }
        int place = nth;
        String text = m.kind == Chat.Msg.USER ? m.text : replyText(m, false);
        bridge.get("/history?id=" + Uri.encode(chat.sessionId), (status, data) -> {
            if (status != 200) { toast("读取对话失败：" + data.optString("error", "HTTP " + status)); return; }
            chat.load(data);
            chat.loaded = true;
            if (current == chat) { adapter.show(chat, true); renderNow(); }
            int k = 0;
            for (Chat.Msg o : chat.messages) {
                if (o.kind != m.kind || k++ != place) continue;
                if (o.uuid != null && text.equals(o.kind == Chat.Msg.USER ? o.text : replyText(o, false))) then.accept(o);
                else toast("对话记录里找不到这条消息");
                return;
            }
            toast("对话记录里找不到这条消息");
        });
    }

    private void deleteAsk(Chat chat, Chat.Msg m) {
        String what = m.kind == Chat.Msg.USER ? preview(m) : replyText(m, true).trim().replaceAll("\\s+", " ");
        Dialog sheet = sheet("删除这条消息？", (what.length() > 80 ? what.substring(0, 80) + "…" : what)
                + "\n\n" + (m.kind == Chat.Msg.USER ? "" : "这条回复里的工具调用也一起删掉。")
                + "Claude Code 的对话记录里也会删掉，不能恢复。");
        LinearLayout actions = Ui.row(this);
        TextView cancel = button("取消", false, v -> sheet.dismiss());
        TextView ok = button("删除", true, v -> { sheet.dismiss(); deleteMessage(chat, m); });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        sheet.show();
    }

    /**
     * Takes the message out of the transcript (the bridge's /delete), then reads it again: what was
     * on its two sides is shown as the transcript now has it, and pictures are named anew.
     */
    private void deleteMessage(Chat chat, Chat.Msg m) {
        String id = chat.sessionId;
        String role = m.kind == Chat.Msg.USER ? "user" : "assistant";
        bridge.post("/delete-message", obj("chat", chat.key, "sessionId", id, "uuid", m.uuid, "role", role), (status, data) -> {
            if (status != 200 || !data.has("left")) { toast("删除失败：" + data.optString("error", "HTTP " + status)); return; }
            toast("已删除");
            loadSessions();
            if (data.optInt("left") == 0) { gone(chat, id, ""); return; }
            Images.forget();
            bridge.get("/history?id=" + Uri.encode(id), (st, history) -> {
                if (st != 200) return;
                chat.load(history);
                chat.loaded = true;
                if (current == chat) { adapter.show(chat, true); renderNow(); }
            });
        });
    }

    // ================= /find =================

    private String findQuery = ""; // lower case; empty when not looking
    private String findLine = ""; // the /find line as sent, kept in the box while it looks
    private final List<int[]> findHits = new ArrayList<>(); // {row, which place in the row}
    private int findAt;
    private Toast findToast;

    private static final class Found extends android.text.style.BackgroundColorSpan {
        Found(int color) { super(color); }
    }

    private boolean finding() { return !findHits.isEmpty(); }

    /**
     * /find: the places in this conversation's messages and replies where the words are, marked,
     * from the last; ↑↓ go between them. With no words it stops looking.
     */
    private void find(String words) {
        findQuery = words.toLowerCase(Locale.ROOT);
        findHits.clear();
        unCache();
        for (int p = 0; !findQuery.isEmpty() && p < adapter.getItemCount(); p++) {
            String s = adapter.searchText(p);
            if (s == null) continue;
            s = s.toLowerCase(Locale.ROOT);
            for (int i = s.indexOf(findQuery), k = 0; i >= 0; i = s.indexOf(findQuery, i + findQuery.length()), k++) {
                findHits.add(new int[]{p, k});
            }
        }
        if (findHits.isEmpty()) {
            findEnd();
            return;
        }
        findLine = input.getText().toString().trim();
        hideKeyboard();
        findGo(findHits.size() - 1);
    }

    private void findEnd() {
        findQuery = "";
        findHits.clear();
        unCache();
        for (int i = 0; i < list.getChildCount(); i++) mark(list.getChildAt(i), list.getChildAdapterPosition(list.getChildAt(i)));
        showJump();
    }

    /** Rows kept aside off screen come back without being bound again: they must not keep old marks. */
    private void unCache() {
        list.setItemViewCacheSize(0);
        list.setItemViewCacheSize(2); // RecyclerView's default
    }

    /** Goes to a place found: its line a third of the way down the screen. */
    private void findGo(int i) {
        findAt = Math.max(0, Math.min(findHits.size() - 1, i));
        int[] at = findHits.get(findAt);
        stick = false;
        adapter.opened = null;
        list.stopScroll();
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        lm.scrollToPositionWithOffset(at[0], list.getHeight() / 4);
        list.post(() -> {
            if (!finding()) return;
            for (int k = 0; k < list.getChildCount(); k++) mark(list.getChildAt(k), list.getChildAdapterPosition(list.getChildAt(k)));
            View row = lm.findViewByPosition(at[0]);
            int y = row == null ? -1 : findSpot(row, at[1]);
            if (y >= 0) list.scrollBy(0, y - list.getHeight() / 3);
            showJump();
        });
        if (findToast != null) findToast.cancel();
        findToast = Toast.makeText(this, (findAt + 1) + " / " + findHits.size(), Toast.LENGTH_SHORT);
        findToast.show();
    }

    /** The text a row shows that /find looks in: a message's own (tagged findable), not labels such as 复制. */
    private static void findTexts(View v, List<TextView> out) {
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
                list.getLocationInWindow(b);
                return a[1] - b[1] + t.getTotalPaddingTop() + layout.getLineTop(layout.getLineForOffset(i));
            }
        }
        return -1;
    }

    /** Marks the places found in a row, the one gone to darker; or takes the marks away. */
    private void mark(View row, int position) {
        boolean marked = !findQuery.isEmpty() && adapter.searchText(position) != null;
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

    private void showMenu() {
        String v = input.getText().toString();
        if (!v.matches("^/\\S*$")) { hideMenu(); return; }
        String q = v.substring(1).toLowerCase(Locale.ROOT);
        List<Map.Entry<String, String[]>> hits = new ArrayList<>(), rest = new ArrayList<>();
        for (Map.Entry<String, String[]> e : commandList().entrySet()) {
            String n = e.getKey().toLowerCase(Locale.ROOT);
            if (n.startsWith(q)) hits.add(e);
            else if (n.contains(q)) rest.add(e);
        }
        hits.addAll(rest);
        if (hits.isEmpty()) { hideMenu(); return; }
        slashMenu.removeAllViews();
        for (Map.Entry<String, String[]> e : hits) {
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackground(Ui.press(this, null, dp(10)));
            TextView n = Ui.text(this, "/" + e.getKey(), 13.5f, R.color.accentPress);
            n.setTypeface(Ui.MONO);
            row.addView(n);
            TextView d = Ui.text(this, e.getValue()[1], 13.5f, R.color.text2);
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
                if (!args && local) send();
            });
            slashMenu.addView(row, Ui.fill());
        }
        slashScroll.setVisibility(View.VISIBLE);
        slashScroll.scrollTo(0, 0);
    }

    private void hideMenu() { slashScroll.setVisibility(View.GONE); }

    // ================= attachments =================

    private void pickFiles() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, PICK_FILES);
        } catch (android.content.ActivityNotFoundException e) {
            toast("没有可用的文件选择器");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK_FONT && result == RESULT_OK && data != null && data.getData() != null) {
            addFont(data.getData());
            return;
        }
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        Chat chat = current;
        for (Uri uri : uris.subList(0, Math.min(8, uris.size()))) {
            work.execute(() -> {
                Chat.Attachment a = readAttachment(uri);
                ui.post(() -> {
                    if (a == null) return;
                    chat.attachments.add(a);
                    updateChrome();
                });
            });
        }
    }

    private Chat.Attachment readAttachment(Uri uri) {
        String name = "file";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && c.getString(n) != null) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (RuntimeException ignored) {
            // name stays "file"
        }
        String type = getContentResolver().getType(uri);
        try {
            if (type != null && type.matches("image/(png|jpe?g|gif|webp|bmp|heic|heif|avif)")) {
                // Claude Code scales a picture for the model itself and keeps the original, whose path,
                // size and scale it gives the model: one it reads goes as it is, up to 10 MB (held in
                // memory as it is sent). HEIC, HEIF and AVIF, which it cannot read, and a larger one go
                // as JPEG of up to 4096 px.
                byte[] raw = type.matches("image/(heic|heif|avif)") ? null : readUpTo(uri, 10 << 20);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                int longer = Math.max(o.outWidth, o.outHeight), sample = 1;
                // Decoded only as large as needed: for the thread's copy (2000 px), or for the JPEG sent (4096 px).
                if (raw != null) while (longer / (sample * 2) >= 2000) sample *= 2;
                else while (longer / sample > 4096) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o); }
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
            try (InputStream in = getContentResolver().openInputStream(uri)) {
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
        try (InputStream in = getContentResolver().openInputStream(uri)) {
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

    private void renderAttachments() {
        List<Chat.Attachment> items = current.attachments;
        attachScroll.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        String key = items.isEmpty() ? "" : System.identityHashCode(items) + ":" + items.size() + ":" + System.identityHashCode(items.get(items.size() - 1));
        if (key.equals(attachments.getTag())) return;
        attachments.setTag(key);
        attachments.removeAllViews();
        for (Chat.Attachment a : new ArrayList<>(items)) {
            FrameLayout cell = new FrameLayout(this);
            cell.setPadding(0, dp(7), dp(7), 0);
            if (a.image) {
                ImageView img = new ImageView(this);
                img.setImageBitmap(a.thumb);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border)));
                img.setClipToOutline(true);
                cell.addView(img, new FrameLayout.LayoutParams(dp(64), dp(64)));
            } else {
                LinearLayout f = Ui.column(this);
                f.setGravity(Gravity.CENTER_VERTICAL);
                f.setPadding(dp(10), dp(8), dp(10), dp(8));
                f.setBackground(Ui.round(col(R.color.surface2), dp(12), dp(1), col(R.color.border)));
                f.addView(Ui.icon(this, R.drawable.ic_file, 16, R.color.text2));
                TextView n = Ui.text(this, a.name, 12.5f, R.color.text);
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                n.setPadding(0, dp(4), 0, dp(2));
                f.addView(n);
                f.addView(Ui.text(this, a.size >= 0 ? Ui.size(a.size) : "", 11, R.color.text3));
                cell.addView(f, new FrameLayout.LayoutParams(dp(150), dp(64)));
            }
            ImageView remove = Ui.icon(this, R.drawable.ic_x, 22, R.color.bg);
            remove.setPadding(dp(5), dp(5), dp(5), dp(5));
            remove.setBackground(Ui.round(col(R.color.text), dp(11), dp(2), col(R.color.surface)));
            remove.setOnClickListener(v -> { current.attachments.remove(a); attachments.setTag(null); updateChrome(); });
            cell.addView(remove, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END | Gravity.TOP));
            ((FrameLayout.LayoutParams) remove.getLayoutParams()).setMargins(0, -dp(7), -dp(7), 0);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            attachments.addView(cell);
            Ui.margins(cell, this, 0, 0, 4, 0);
        }
    }

    // ================= sheets =================

    private Dialog sheet(String titleText, String sub) {
        SheetBox box = new SheetBox(this);
        Dialog d = new Dialog(this, R.style.Sheet) {
            private boolean leaving;

            /** Up from the bottom edge, the dim coming with it. */
            @Override public void show() {
                super.show();
                Window w = getWindow();
                box.setTranslationY(getResources().getDisplayMetrics().heightPixels);
                box.post(() -> {
                    box.setTranslationY(box.getHeight());
                    box.animate().translationY(0).setDuration(Motion.PAGE).setInterpolator(Motion.EMPHASIZED).start();
                    dim(w, 0f, SHEET_DIM);
                });
            }

            /** Down out of sight, the dim going, and only then away: however it is closed. */
            @Override public void dismiss() {
                if (leaving) return;
                if (!isShowing() || isFinishing() || isDestroyed() || box.getHeight() == 0) { super.dismiss(); return; }
                leaving = true;
                dim(getWindow(), SHEET_DIM, 0f);
                box.animate().translationY(box.getHeight()).setDuration(250).setInterpolator(Motion.EMPHASIZED)
                        .withEndAction(super::dismiss).start();
            }
        };
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        box.close = d::cancel;
        android.graphics.drawable.GradientDrawable bg = Ui.round(col(R.color.bg), 0);
        float r = dp(22);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(dp(18), dp(8), dp(18), dp(18));
        View grab = new View(this);
        grab.setBackground(Ui.round(col(R.color.borderStrong), dp(2)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(38), dp(4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(14);
        box.addView(grab, gp);
        TextView t = Ui.text(this, titleText, 21, R.color.text);
        t.setTypeface(Ui.SERIF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Ui.text(this, sub, 14, R.color.text2);
            s.setLineSpacing(0, 1.25f);
            s.setPadding(0, dp(8), 0, 0);
            box.addView(s);
        }
        ScrollView scroll = new ScrollView(this) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) (getResources().getDisplayMetrics().heightPixels * 0.62f), MeasureSpec.AT_MOST));
            }
        };
        LinearLayout content = Ui.column(this);
        content.setPadding(0, dp(12), 0, 0);
        scroll.addView(content);
        box.addView(scroll, Ui.fill());
        box.setTag(content);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            // It moves itself (show, dismiss); the dim follows it.
            w.setWindowAnimations(0);
            w.setDimAmount(0f);
        }
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    /** The dim behind a sheet (its style's backgroundDimAmount). */
    private static final float SHEET_DIM = 0.34f;

    private static void dim(Window w, float from, float to) {
        if (w == null) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, to);
        a.setDuration(to > from ? Motion.PAGE : 250);
        a.setInterpolator(Motion.EMPHASIZED);
        a.addUpdateListener(x -> w.setDimAmount((float) x.getAnimatedValue()));
        a.start();
    }

    private LinearLayout content(Dialog d) {
        ViewGroup root = d.findViewById(android.R.id.content);
        return (LinearLayout) root.getChildAt(0).getTag();
    }

    private LinearLayout card(Dialog d) {
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border)));
        content(d).addView(card, Ui.fill());
        return card;
    }

    /** A row: icon, label, and its value on the right. */
    private void option(LinearLayout card, int iconRes, String label, String value, boolean danger, Runnable click) {
        if (tiled) { tileOption(card, iconRes, label, value, danger, click); return; }
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setMinimumHeight(dp(50));
        if (click != null) row.setBackground(Ui.press(this, null, dp(12)));
        if (iconRes != 0) {
            row.addView(Ui.icon(this, iconRes, 19, danger ? R.color.danger : R.color.text2));
            row.addView(new View(this), new LinearLayout.LayoutParams(dp(14), 1));
        }
        TextView l = Ui.text(this, label, 15.5f, danger ? R.color.danger : R.color.text);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null && !value.isEmpty()) {
            TextView v = Ui.text(this, value, 14, R.color.text3);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            v.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.45f));
            v.setPadding(dp(12), 0, 0, 0);
            row.addView(v);
        }
        if (click != null) row.setOnClickListener(v -> click.run());
        card.addView(row, Ui.fill());
    }

    /** A row of Settings: its icon in a tinted circle, the label with the value under it. */
    private void tileOption(LinearLayout card, int iconRes, String label, String value, boolean danger, Runnable click) {
        LinearLayout row = tileRow(card, iconRes, label, value, danger);
        if (click != null) row.setOnClickListener(v -> click.run());
    }

    /** A tile's row: icon, label and what is under it; whatever goes on its right is added after. */
    private LinearLayout tileRow(LinearLayout card, int iconRes, String label, String value, boolean danger) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(64));
        if (iconRes != 0) {
            ImageView icon = Ui.icon(this, iconRes, 44, danger ? R.color.danger : R.color.accent);
            icon.setPadding(dp(11), dp(11), dp(11), dp(11));
            icon.setBackground(Ui.round(col(danger ? R.color.dangerSoft : R.color.accentSoft), dp(22)));
            row.addView(icon);
            Ui.margins(icon, this, 0, 0, 16, 0);
        }
        LinearLayout words = Ui.column(this);
        TextView l = Ui.text(this, label, 16.5f, danger ? R.color.danger : R.color.text);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(l);
        if (value != null && !value.isEmpty()) {
            TextView v = Ui.text(this, value, 13.5f, R.color.text2);
            v.setMaxLines(2);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setLineSpacing(0, 1.15f);
            v.setPadding(0, dp(5), 0, 0);
            words.addView(v);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row, Ui.fill());
        return row;
    }

    /** A setting that is on or off: the whole row flips it. */
    private void toggle(LinearLayout card, String label, String about, boolean on, java.util.function.Consumer<Boolean> set) {
        LinearLayout row = tileRow(card, 0, label, about, false);
        Toggle t = new Toggle(this, on);
        row.addView(t);
        Ui.margins(t, this, 16, 0, 0, 0);
        row.setOnClickListener(v -> {
            t.flip();
            // Redrawn by what it sets: after the thumb has moved.
            ui.postDelayed(() -> set.accept(t.isOn()), 260);
        });
    }

    /** A choice of a few: its label, the buttons across the tile under it, the chosen one filled. */
    private void choice(LinearLayout card, String label, String[] labels, int chosen, java.util.function.IntConsumer pick) {
        LinearLayout tile = Ui.column(this);
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        tile.addView(Ui.text(this, label, 16.5f, R.color.text));
        LinearLayout bar = Ui.row(this);
        int n = labels.length;
        TextView[] buttons = new TextView[n];
        GradientDrawable[] shapes = new GradientDrawable[n];
        boolean[] busy = {false};
        for (int i = 0; i < n; i++) {
            int index = i;
            TextView b = Ui.text(this, labels[i], 15, R.color.text);
            b.setGravity(Gravity.CENTER);
            shapes[i] = new GradientDrawable();
            choiceLook(b, shapes[i], i, n, i == chosen ? 1 : 0);
            b.setBackground(Ui.press(this, shapes[i], dp(10)));
            b.setOnClickListener(v -> {
                if (index == chosen || busy[0]) return;
                busy[0] = true;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                // The fill and the round ends move from the chosen one to this, then it is set.
                android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(250);
                a.setInterpolator(Motion.EMPHASIZED);
                a.addUpdateListener(x -> {
                    float f = (float) x.getAnimatedValue();
                    choiceLook(buttons[chosen], shapes[chosen], chosen, n, 1 - f);
                    choiceLook(buttons[index], shapes[index], index, n, f);
                });
                a.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator x) { pick.accept(index); }
                });
                a.start();
            });
            buttons[i] = b;
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
            if (i > 0) Ui.margins(b, this, 4, 0, 0, 0);
        }
        tile.addView(bar, Ui.fill());
        Ui.margins(bar, this, 0, 14, 0, 0);
        card.addView(tile, Ui.fill());
    }

    /** A choice's button {@code on} of the way to chosen: filled, in the colour on the accent, round at both ends; else only the row's ends round. */
    private void choiceLook(TextView b, GradientDrawable shape, int i, int n, float on) {
        float big = dp(24), small = dp(10);
        float l = i == 0 ? big : small + (big - small) * on, r = i == n - 1 ? big : small + (big - small) * on;
        shape.setCornerRadii(new float[]{l, l, r, r, r, r, l, l});
        shape.setColor(blend(col(R.color.surface2), col(R.color.accent), on));
        b.setTextColor(blend(col(R.color.text), col(R.color.onAccent), on));
        b.setTypeface(on > 0.5f ? Ui.MEDIUM : Typeface.DEFAULT);
    }

    private static int blend(int a, int b, float t) {
        return (int) new android.animation.ArgbEvaluator().evaluate(t, a, b);
    }

    private void check(LinearLayout card) {
        LinearLayout last = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
        View tick = Ui.icon(this, R.drawable.ic_check, 19, R.color.accent);
        last.addView(tick);
        Ui.margins(tick, this, 12, 0, 0, 0);
    }

    private String tilde(String p) {
        if (p == null) return "";
        return !home.isEmpty() && p.startsWith(home) ? "~" + p.substring(home.length()) : p;
    }

    private void folderSheet(String path) {
        String at = path != null ? path : !cwd.isEmpty() ? cwd : defaultCwd;
        bridge.get("/dirs?path=" + Uri.encode(at), (status, d) -> {
            if (status != 200) { toast("读取目录失败"); return; }
            String here = d.optString("path");
            Dialog sheet = sheet("工作目录", tilde(here));
            LinearLayout card = card(sheet);
            String parent = d.isNull("parent") ? "" : d.optString("parent");
            if (!parent.isEmpty()) option(card, R.drawable.ic_up, "上一级", null, false, () -> { sheet.dismiss(); folderSheet(parent); });
            JSONArray dirs = d.optJSONArray("dirs");
            for (int i = 0; dirs != null && i < dirs.length(); i++) {
                String name = dirs.optString(i);
                option(card, R.drawable.ic_folder, name, null, false, () -> { sheet.dismiss(); folderSheet(here + "/" + name); });
            }
            if (dirs == null || dirs.length() == 0) {
                TextView none = Ui.text(this, "没有子目录", 14, R.color.text3);
                none.setGravity(Gravity.CENTER);
                none.setPadding(0, dp(18), 0, dp(18));
                card.addView(none, Ui.fill());
            }
            LinearLayout field = Ui.row(this);
            EditText name = new EditText(this);
            name.setHint("新建目录名");
            name.setSingleLine(true);
            name.setTextSize(15);
            name.setTextColor(col(R.color.text));
            name.setHintTextColor(col(R.color.text3));
            name.setPadding(dp(12), 0, dp(12), 0);
            name.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
            field.addView(name, new LinearLayout.LayoutParams(0, dp(40), 1));
            TextView make = button("新建", false, v -> {
                String n = name.getText().toString().trim();
                if (n.isEmpty() || n.contains("/")) { toast("目录名不能为空，也不能带 /"); return; }
                bridge.post("/mkdir", obj("path", here + "/" + n), (s2, r2) -> {
                    if (s2 == 200) { sheet.dismiss(); folderSheet(here + "/" + n); } else toast("新建失败");
                });
            });
            field.addView(make);
            Ui.margins(make, this, 8, 0, 0, 0);
            content(sheet).addView(field, Ui.fill());
            Ui.margins(field, this, 0, 10, 0, 0);
            LinearLayout actions = Ui.row(this);
            TextView cancel = button("取消", false, v -> sheet.dismiss());
            TextView use = button("使用这个目录", true, v -> {
                cwd = here;
                prefs.edit().putString("cwd", cwd).apply();
                if (current.sessionId == null) current.cwd = cwd;
                sheet.dismiss();
                renderNow();
                renderSessions(); // the drawer may list only this folder's
                refreshSettings();
            });
            use.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
            use.setTextColor(col(R.color.bg));
            actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
            actions.addView(use, new LinearLayout.LayoutParams(0, dp(42), 1));
            Ui.margins(use, this, 10, 0, 0, 0);
            content(sheet).addView(actions, Ui.fill());
            Ui.margins(actions, this, 0, 14, 0, 0);
            sheet.show();
        });
    }

    /** The thinking level in use: picked here, else Claude Code's own setting. */
    private String effortInUse() {
        return !effort.isEmpty() ? effort : configEffort;
    }

    private void modelSheet() {
        Dialog sheet = sheet("模型", null);
        // Thinking: one segmented bar.
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        bar.setBackground(Ui.round(col(R.color.surface2), dp(12)));
        String level = effortInUse();
        for (String e : efforts) {
            boolean on = e.equals(level);
            TextView b = Ui.text(this, e, 13.5f, on ? R.color.text : R.color.text2);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            if (on) {
                b.setTypeface(Ui.MEDIUM);
                b.setBackground(Ui.round(col(R.color.bg), dp(9), dp(1), col(R.color.border)));
            }
            b.setOnClickListener(v -> {
                effort = e;
                prefs.edit().putString("effort", effort).apply();
                sheet.dismiss();
                updateChrome();
            });
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(34), 1));
        }
        if (!efforts.isEmpty()) {
            content(sheet).addView(bar, Ui.fill());
            Ui.margins(bar, this, 0, 0, 0, 12);
        }
        // Claude Code's own list first ("default": no --model, its own choice), then ones from the
        // settings and typed here; each once.
        LinearLayout own = null;
        List<String> listed = new ArrayList<>();
        for (int i = 0; i < claudeModels.length(); i++) {
            JSONObject cm = claudeModels.optJSONObject(i);
            if (cm == null) continue;
            String value = cm.optString("value");
            String resolved = cm.optString("resolvedModel");
            listed.add(value);
            listed.add(resolved);
            // "default" is one of the others under another name: not listed twice.
            if (value.equals("default")) continue;
            String pick = value;
            if (own == null) own = card(sheet);
            // Its full name with the version, as Claude Code describes it ("Opus 5.5 · …").
            String desc = cm.optString("description");
            String name = desc.contains(" · ") ? desc.substring(0, desc.indexOf(" · ")) : cm.optString("displayName", value);
            option(own, 0, name, null, false, () -> {
                model = pick;
                prefs.edit().putString("model", model).apply();
                sheet.dismiss();
                updateChrome();
            });
            if (model.equals(pick) || (model.isEmpty() && defaultResolved(resolved))) check(own);
        }
        if (own != null) Ui.margins(own, this, 0, 0, 0, 10);
        List<String> all = new ArrayList<>();
        if (!defaultModel.isEmpty()) all.add(defaultModel);
        for (String m : models) if (!all.contains(m)) all.add(m);
        for (String m : usedModels()) if (!all.contains(m)) all.add(m);
        all.removeAll(listed);
        String inUse = model.isEmpty() ? defaultModel : model;
        LinearLayout card = card(sheet);
        for (String m : all) {
            boolean big = m.toLowerCase(Locale.ROOT).endsWith("[1m]");
            option(card, 0, shortModel(m), big ? "1M" : null, false, () -> {
                model = m;
                prefs.edit().putString("model", model).apply();
                sheet.dismiss();
                updateChrome();
            });
            if (m.equals(inUse)) check(card);
        }
        option(card, R.drawable.ic_plus, "其他模型", null, false, () -> {
            sheet.dismiss();
            textSheet("其他模型", "", "模型名", false, (t, done) -> {
                String name = t.trim();
                if (name.isEmpty()) return;
                List<String> used = usedModels();
                used.remove(name);
                used.add(0, name);
                prefs.edit().putString("usedModels", new JSONArray(used.subList(0, Math.min(8, used.size()))).toString()).apply();
                model = name;
                prefs.edit().putString("model", model).apply();
                updateChrome();
                done.run();
            }, null);
        });
        sheet.show();
    }

    private List<String> usedModels() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("usedModels", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (org.json.JSONException ignored) { /* none */ }
        return out;
    }

    private Dialog settingsPage;
    private LinearLayout settingsBox;
    private TextView settingsTitle;
    /** The page of Settings shown: null for the list of pages, else one of them. */
    private String settingsAt;
    /** The launch options last read from the bridge, shown while they are read again. */
    private JSONObject launchCache;

    private static final String[][] PAGES = {
            {"look", "外观", "主题、显示和字体"}, {"talk", "对话", "工作目录、权限、系统提示和 CLAUDE.md"},
            {"model", "模型", "API Key"},
            {"claude", "Claude Code", "settings.json 和环境变量"}, {"ext", "扩展", "技能、插件、MCP、子代理和 Hooks"},
            {"storage", "存储", "占用的空间和清理"}, {"keep", "后台", "闲置重启、电池优化和保活"},
            {"about", "关于", "版本、日志和终端"},
    };

    /** Opens Settings, a full-screen page (or refreshes it when open). */
    private void settingsSheet() {
        if (settingsPage == null) {
            Dialog d = new Dialog(this, R.style.Page);
            // Its pages, one over another while they move.
            settingsStage = new FrameLayout(this);
            settingsStage.addView(settingsView());
            d.setContentView(settingsStage);
            Themes.window(d.getWindow(), this);
            View under = findViewById(android.R.id.content);
            if (d.getWindow() != null) {
                d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                d.getWindow().setStatusBarColor(col(R.color.sidebar));
                d.getWindow().setNavigationBarColor(col(R.color.sidebar));
                // It moves itself in (Motion), over the conversation seen through it meanwhile.
                d.getWindow().setWindowAnimations(0);
                d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            }
            d.setOnKeyListener((x, code, e) -> {
                if (code != android.view.KeyEvent.KEYCODE_BACK) return false;
                if (e.getAction() == android.view.KeyEvent.ACTION_UP && !e.isCanceled()) settingsBack();
                return true;
            });
            d.setOnDismissListener(x -> {
                // Told after restyle has opened the next one already: that one stays as it is.
                if (settingsPage != d) return;
                settingsPage = null;
                settingsAt = null;
                Motion.reset(under);
            });
            settingsPage = d;
            fillSettings(linked ? launchCache : null);
            d.show();
            if (!settingsInstant) {
                settingsMoving();
                Motion.push(settingsStage, under, null);
            }
        }
        refreshSettings();
    }

    /** A page of Settings: the round back button (with the name beside it on the list of pages), its contents scrolling under. */
    private View settingsView() {
        LinearLayout root = Ui.column(this);
        // Tiles a shade lighter than the page under them.
        root.setBackgroundColor(col(R.color.sidebar));
        root.setClickable(true); // touches stay on the page in front while two are shown
        LinearLayout top = Ui.row(this);
        top.setPadding(dp(14), dp(8), dp(14), dp(4));
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, 48, 22, R.color.text);
        back.setBackground(Ui.press(this, Ui.round(col(R.color.surface), dp(24)), dp(24)));
        back.setOnClickListener(v -> settingsBack());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        settingsTitle = Ui.text(this, "设置", 26, R.color.text);
        settingsTitle.setTypeface(Ui.SERIF);
        settingsTitle.setPadding(dp(14), 0, 0, 0);
        top.addView(settingsTitle);
        root.addView(top, Ui.fill());
        ScrollView scroll = new ScrollView(this);
        settingsBox = Ui.column(this);
        settingsBox.setPadding(dp(16), dp(4), dp(16), dp(32));
        scroll.addView(settingsBox);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private FrameLayout settingsStage;
    /** Reopened at once (the colours or text size changed), not moved in. */
    private boolean settingsInstant;
    private long settingsStill;

    /** A move has begun: taps meanwhile (a second back, a second page) wait for it to end. */
    private boolean settingsMoving() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now < settingsStill) return false;
        settingsStill = now + Motion.PAGE;
        return true;
    }

    /** Back: from a page to the list of pages, from the list out of Settings. */
    private void settingsBack() {
        if (settingsPage == null || !settingsMoving()) return;
        if (settingsAt != null) { settingsGo(null); return; }
        Dialog d = settingsPage;
        Motion.pop(findViewById(android.R.id.content), settingsStage, d::dismiss);
    }

    private void settingsOpen(String page) {
        if (settingsPage == null || !settingsMoving()) return;
        settingsGo(page);
    }

    private void settingsGo(String page) {
        View old = settingsStage.getChildAt(settingsStage.getChildCount() - 1);
        settingsAt = page;
        View fresh = settingsView();
        // A page comes in over the list; the list comes back from under the page.
        if (page != null) settingsStage.addView(fresh);
        else settingsStage.addView(fresh, 0);
        fillSettings(linked ? launchCache : null);
        if (page != null) Motion.push(fresh, old, () -> settingsStage.removeView(old));
        else Motion.pop(fresh, old, () -> settingsStage.removeView(old));
        if (page != null && page.equals("claude")) loadSchema(() -> { });
        if (page != null && page.equals("storage")) measureStorage();
    }

    /** Reads the options again and redraws Settings, if open. */
    private void refreshSettings() {
        if (settingsPage == null) return;
        if (!linked) { fillSettings(null); return; }
        // Claude Code's version is found a moment after the bridge starts.
        bridge.get("/config", (st, config) -> {
            if (st == 200) applyConfig(config);
            bridge.get("/launch", (status, launch) -> {
                if (status == 200) launchCache = launch;
                fillSettings(status == 200 ? launch : null);
            });
        });
    }

    private LinearLayout card(LinearLayout box) {
        LinearLayout card = Ui.column(this);
        if (tiled) {
            // Its rows become tiles once Settings is filled (tile).
            card.setTag(TILES);
            box.addView(card, Ui.fill());
            return card;
        }
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(Ui.round(col(R.color.surface), dp(14), dp(1), col(R.color.border)));
        box.addView(card, Ui.fill());
        return card;
    }

    /** Settings' contents; the launch options only with the bridge running (null launch). */
    private void fillSettings(JSONObject launch) {
        if (settingsPage == null) return;
        LinearLayout box = settingsBox;
        box.removeAllViews();
        String name = "设置";
        for (String[] p : PAGES) if (p[0].equals(settingsAt)) name = p[1];
        // The list of pages has its name beside the back button; a page, big above its contents.
        settingsTitle.setText(settingsAt == null ? name : "");
        if (settingsAt != null) {
            TextView big = Ui.text(this, name, 34, R.color.text);
            big.setTypeface(Ui.SERIF);
            big.setPadding(dp(6), dp(10), 0, dp(14));
            box.addView(big);
        }
        tiled = true;
        try {
            if (settingsAt == null) fillPages(box);
            else switch (settingsAt) {
                case "look": fillLook(box); break;
                case "talk": fillTalk(box, launch); break;
                case "model": fillModel(box); break;
                case "claude": fillClaude(box); break;
                case "ext": fillExt(box, launch); break;
                case "storage": fillStorage(box); break;
                case "keep": fillKeep(box); break;
                default: fillAbout(box); break;
            }
        } finally {
            tiled = false;
        }
        tile(box);
    }

    private static final String TILES = "tiles";
    /** Settings is being filled: cards and options are made as its tiles, not as in a sheet. */
    private boolean tiled;

    /** Each card's rows as tiles one under another: the group's outer corners round, its inner ones nearly square. */
    private void tile(LinearLayout box) {
        float big = dp(24), small = dp(5);
        for (int i = 0; i < box.getChildCount(); i++) {
            View g = box.getChildAt(i);
            if (!TILES.equals(g.getTag())) continue;
            LinearLayout card = (LinearLayout) g;
            int n = card.getChildCount();
            for (int j = 0; j < n; j++) {
                View row = card.getChildAt(j);
                float t = j == 0 ? big : small, b = j == n - 1 ? big : small;
                float[] radii = {t, t, t, t, b, b, b, b};
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(col(R.color.surface));
                shape.setCornerRadii(radii);
                if (row.isClickable()) {
                    GradientDrawable mask = new GradientDrawable();
                    mask.setColor(0xFF000000);
                    mask.setCornerRadii(radii);
                    row.setBackground(new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(col(R.color.active)), shape, mask));
                } else {
                    row.setBackground(shape);
                }
                if (j > 0) Ui.margins(row, this, 0, 2, 0, 0);
            }
        }
    }

    /** The first level: a row for each page. */
    private void fillPages(LinearLayout box) {
        section(box, "通用");
        LinearLayout a = card(box);
        page(a, R.drawable.ic_image, "look");
        page(a, R.drawable.ic_compose, "talk");
        page(a, R.drawable.ic_link, "model");
        section(box, "Claude Code");
        LinearLayout b = card(box);
        page(b, R.drawable.ic_gear, "claude");
        page(b, R.drawable.ic_tool, "ext");
        section(box, "应用");
        LinearLayout c = card(box);
        page(c, R.drawable.ic_folder, "storage");
        page(c, R.drawable.ic_refresh, "keep");
        page(c, R.drawable.ic_book, "about");
    }

    private void page(LinearLayout card, int icon, String id) {
        String label = id, what = null;
        for (String[] p : PAGES) if (p[0].equals(id)) { label = p[1]; what = p[2]; }
        option(card, icon, label, what, false, () -> settingsOpen(id));
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---- 外观 ----

    private void fillLook(LinearLayout box) {
        section(box, "主题");
        LinearLayout themes = card(box);
        // As RikkaHub has them: four to a row, all as wide, the last row's gaps kept.
        LinearLayout grid = Ui.column(this);
        grid.setPadding(dp(8), dp(12), dp(8), dp(12));
        String now = Themes.current(this).id;
        boolean dark = Themes.dark(this);
        LinearLayout row = null;
        for (int i = 0; i < Themes.ALL.length; i++) {
            if (i % 4 == 0) {
                row = Ui.row(this);
                row.setGravity(Gravity.TOP);
                grid.addView(row, Ui.fill());
                if (i > 0) Ui.margins(row, this, 0, 8, 0, 0);
            }
            Themes.Theme t = Themes.ALL[i];
            row.addView(swatch(t, t.id.equals(now), dark), new LinearLayout.LayoutParams(0, -2, 1));
            if (i % 4 > 0) Ui.margins(row.getChildAt(row.getChildCount() - 1), this, 4, 0, 0, 0);
        }
        for (int i = Themes.ALL.length % 4; i > 0 && i < 4; i++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            Ui.margins(row.getChildAt(row.getChildCount() - 1), this, 4, 0, 0, 0);
        }
        themes.addView(grid, Ui.fill());
        choice(themes, "深色模式", Themes.MODES, Themes.mode(this), m -> {
            Themes.setMode(this, m);
            restyle();
        });
        toggle(themes, "纯黑背景", "深色时页面用纯黑", Themes.pureBlack(this), on -> {
            Themes.setPureBlack(this, on);
            if (Themes.dark(this)) restyle();
        });

        section(box, "显示");
        LinearLayout show = card(box);
        toggle(show, "沉浸式", "对话延伸到状态栏下，按钮和输入框是玻璃效果", Themes.immersive(this), on -> {
            Themes.setImmersive(this, on);
            restyle();
        });
        toggle(show, "标题", "在对话上方显示它的标题", Themes.showTitle(this), on -> {
            Themes.setShowTitle(this, on);
            restyle();
        });
        toggle(show, "思考", "显示模型的思考过程", Themes.showThinking(this), on -> {
            Themes.setShowThinking(this, on);
            fillSettings(launchCache);
            renderNow();
        });
        toggle(show, "对话时间", "侧栏里显示每个对话多久以前用过", prefs.getBoolean("drawerTimes", true), on -> {
            prefs.edit().putBoolean("drawerTimes", on).apply();
            fillSettings(launchCache);
            renderSessions();
        });
        toggle(show, "只显示当前目录", "侧栏只列出当前工作目录里的对话", onlyHere(), on -> {
            prefs.edit().putBoolean("drawerOnlyHere", on).apply();
            fillSettings(launchCache);
            renderSessions();
        });
        String footer = prefs.getString("footer", "");
        option(show, 0, "底部文字", footer.isEmpty() ? "不显示" : footer, false, () -> textSheet("底部文字", footer, "", false, (t, done) -> {
            prefs.edit().putString("footer", t.trim()).apply();
            shownLink = null;
            updateLink();
            done.run();
        }, this::refreshSettings));

        section(box, "文字");
        LinearLayout text = card(box);
        choice(text, "大小", Fonts.SIZES, Fonts.size(this), i -> {
            Fonts.setSize(this, i);
            restyle();
        });
        option(text, 0, "中文字体", Fonts.name(Fonts.cjk(this)), false, () -> fontSheet(true));
        option(text, 0, "英文字体", Fonts.name(Fonts.latin(this)), false, () -> fontSheet(false));
    }

    /**
     * A palette as RikkaHub draws it, in the light or dark in use: a round of its primary's
     * container (left half), the secondary's (top right) and the tertiary's (bottom right), its
     * primary in the middle, larger and ticked when chosen; its name below in its primary.
     */
    private View swatch(Themes.Theme t, boolean chosen, boolean dark) {
        int[] c = t.swatch(dark); // primary, its container, the secondary's, the tertiary's, on primary
        LinearLayout cell = Ui.column(this);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(8), 0, dp(8));
        cell.setBackground(Ui.press(this, null, dp(16)));
        FrameLayout disc = new FrameLayout(this);
        View circle = new View(this) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            final android.graphics.RectF oval = new android.graphics.RectF();

            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), r = w / 2f;
                oval.set(0, 0, w, w);
                p.setColor(c[1]);
                canvas.drawArc(oval, 90, 180, true, p);
                p.setColor(c[2]);
                canvas.drawArc(oval, 270, 90, true, p);
                p.setColor(c[3]);
                canvas.drawArc(oval, 0, 90, true, p);
                p.setColor(c[0]);
                canvas.drawCircle(r, r, dp(chosen ? 12 : 8), p);
            }
        };
        disc.addView(circle, new FrameLayout.LayoutParams(dp(48), dp(48)));
        if (chosen) {
            ImageView tick = new ImageView(this);
            tick.setImageResource(R.drawable.ic_check);
            tick.setImageTintList(android.content.res.ColorStateList.valueOf(c[4]));
            disc.addView(tick, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        }
        cell.addView(disc, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = Ui.text(this, t.name, 12.5f, R.color.text);
        name.setTextColor(c[0]);
        name.setTypeface(Ui.MEDIUM);
        name.setPadding(0, dp(6), 0, 0);
        name.setSingleLine(true);
        cell.addView(name);
        cell.setOnClickListener(v -> {
            if (chosen) return;
            Themes.set(this, t.id);
            restyle();
        });
        return cell;
    }

    // ---- 对话 ----

    private void fillTalk(LinearLayout box, JSONObject launch) {
        LinearLayout talk = card(box);
        choice(talk, "回复中发消息", new String[]{"排队", "插入"}, insertWhileRunning() ? 1 : 0, i -> {
            prefs.edit().putBoolean("insertWhileRunning", i == 1).apply();
            fillSettings(launchCache); // shown chosen
        });
        String folderNow = cwd.isEmpty() ? defaultCwd : cwd;
        option(talk, R.drawable.ic_folder, "工作目录", folderNow.isEmpty() ? "~/work" : tilde(folderNow), false, () -> {
            if (!linked) { toast("Claude Code 还没启动好"); return; }
            folderSheet(null);
        });
        if (launch == null) return;
        String mode = launch.optString("permissionMode", "bypassPermissions");
        String prompt = launch.optString("appendSystemPrompt");
        String extra = launch.optString("extraArgs");
        option(talk, R.drawable.ic_tool, "权限", mode, false, () -> permissionSheet(mode));
        option(talk, R.drawable.ic_spark, "系统提示", oneLine(prompt), false,
                () -> textSheet("系统提示", prompt, "", true, (t, done) -> saveLaunch("appendSystemPrompt", t, done), this::refreshSettings));
        option(talk, R.drawable.ic_edit_lines, "启动参数", extra.trim(), false,
                () -> textSheet("启动参数", extra, "--max-turns 50", false, (t, done) -> saveLaunch("extraArgs", t, done), this::refreshSettings));
        section(box, "CLAUDE.md");
        LinearLayout memory = card(box);
        String folder = current.cwd == null || current.cwd.isEmpty() ? folderNow : current.cwd;
        option(memory, R.drawable.ic_book, "全局", "~/.claude", false, () -> memorySheet("global", null, "CLAUDE.md"));
        option(memory, R.drawable.ic_book, Ui.base(folder), tilde(folder), false,
                () -> memorySheet("folder", folder, "CLAUDE.md · " + Ui.base(folder)));
    }

    // ---- 模型 ----

    private void fillModel(LinearLayout box) {
        LinearLayout api = card(box);
        option(api, R.drawable.ic_link, "API Key", null, false, this::apiSheet);
    }

    // ---- Claude Code: its settings.json, as its own schema describes it ----

    /** The settings Claude Code knows (key, type, desc, choices), read from it by the bridge; null until read. */
    private JSONArray schema;

    private void loadSchema(Runnable then) {
        if (schema != null || !linked) return;
        bridge.get("/settings-schema", (status, data) -> {
            if (status != 200) return;
            schema = data.optJSONArray("settings");
            if (schema == null) schema = new JSONArray();
            then.run();
        });
    }

    private JSONObject schemaOf(String key) {
        for (int i = 0; schema != null && i < schema.length(); i++) {
            JSONObject s = schema.optJSONObject(i);
            if (s != null && key.equals(s.optString("key"))) return s;
        }
        return null;
    }

    private void fillClaude(LinearLayout box) {
        JSONObject json = settingsJson();
        JSONObject env = json.optJSONObject("env");
        LinearLayout tools = card(box);
        option(tools, R.drawable.ic_plus, "添加设置", null, false, this::addSettingSheet);
        option(tools, R.drawable.ic_list, "环境变量", env == null || env.length() == 0 ? null : String.valueOf(env.length()), false, this::envSheet);
        option(tools, R.drawable.ic_file, "settings.json", null, false, this::rawSettingsSheet);
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!k.equals("env")) keys.add(k);
        }
        if (keys.isEmpty()) return;
        java.util.Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        section(box, "已设置");
        LinearLayout set = card(box);
        for (String k : keys) option(set, 0, k, summary(json.opt(k)), false, () -> settingSheet(k));
    }

    private static String summary(Object v) {
        if (v instanceof Boolean) return (Boolean) v ? "开" : "关";
        if (v instanceof JSONObject) return ((JSONObject) v).length() == 0 ? "{ }" : "{ " + ((JSONObject) v).length() + " }";
        if (v instanceof JSONArray) return "[ " + ((JSONArray) v).length() + " ]";
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    /** One setting: its description, and a way to set it fitting its type; or to remove it. */
    private void settingSheet(String key) {
        JSONObject json = settingsJson();
        Object value = json.opt(key);
        JSONObject spec = schemaOf(key);
        String type = spec != null ? spec.optString("type")
                : value instanceof Boolean ? "bool" : value instanceof Number ? "number"
                : value instanceof String ? "string" : "json";
        Dialog sheet = sheet(key, spec != null ? spec.optString("desc") : null);
        java.util.function.Consumer<Object> save = v -> {
            JSONObject now = settingsJson();
            try {
                if (v == null) now.remove(key); else now.put(key, v);
            } catch (JSONException e) {
                return;
            }
            if (saveSettings(now)) { toast("已保存"); sheet.dismiss(); refreshSettings(); }
        };
        if (type.equals("bool") || type.equals("enum")) {
            LinearLayout card = card(sheet);
            List<Object> values = new ArrayList<>();
            List<String> names = new ArrayList<>();
            if (type.equals("bool")) {
                values.add(true); names.add("开");
                values.add(false); names.add("关");
            } else {
                JSONArray choices = spec.optJSONArray("choices");
                for (int i = 0; choices != null && i < choices.length(); i++) {
                    values.add(choices.opt(i));
                    names.add(String.valueOf(choices.opt(i)));
                }
                if (value != null && !values.contains(value)) { values.add(value); names.add(String.valueOf(value)); }
            }
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i);
                option(card, 0, names.get(i), null, false, () -> save.accept(v));
                if (v.equals(value)) check(card);
            }
            if (value != null) {
                LinearLayout reset = card(sheet);
                Ui.margins(reset, this, 0, 10, 0, 0);
                option(reset, R.drawable.ic_trash, "移除", null, true, () -> save.accept(null));
            }
            sheet.show();
            return;
        }
        EditText field = new EditText(this);
        boolean multi = type.equals("json") || (value instanceof String && ((String) value).contains("\n"));
        String text = "";
        if (value instanceof JSONObject || value instanceof JSONArray) {
            try { text = value instanceof JSONObject ? ((JSONObject) value).toString(2) : ((JSONArray) value).toString(2); } catch (JSONException ignored) { /* as is */ }
        } else if (value != null && value != JSONObject.NULL) {
            text = String.valueOf(value);
        }
        field.setText(text);
        field.setTextSize(14);
        field.setTypeface(Ui.MONO);
        field.setTextColor(col(R.color.text));
        field.setHintTextColor(col(R.color.text3));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        if (multi) {
            field.setMinLines(5);
            field.setMaxLines(14);
            field.setHint("JSON");
        } else {
            field.setSingleLine(true);
            if (type.equals("number")) field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        content(sheet).addView(field, Ui.fill());
        LinearLayout actions = Ui.row(this);
        TextView remove = button(value == null ? "取消" : "移除", false, v -> {
            if (value == null) sheet.dismiss(); else save.accept(null);
        });
        TextView ok = button("保存", true, v -> {
            String t = field.getText().toString().trim();
            if (t.isEmpty()) { save.accept(null); return; }
            try {
                if (type.equals("number")) save.accept(t.matches("-?\\d+") ? (Object) Long.parseLong(t) : (Object) Double.parseDouble(t));
                else if (type.equals("json")) save.accept(new org.json.JSONTokener(t).nextValue());
                else save.accept(field.getText().toString());
            } catch (NumberFormatException | JSONException e) {
                toast(type.equals("number") ? "不是数字" : "不是有效的 JSON：" + e.getMessage());
            }
        });
        ok.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(remove, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    /** Every setting Claude Code knows and settings.json lacks, found by name or description. */
    private void addSettingSheet() {
        if (schema == null) {
            if (!linked) { toast("Claude Code 还没启动好"); return; }
            loadSchema(this::addSettingSheet);
            return;
        }
        Dialog sheet = sheet("添加设置", null);
        EditText search = new EditText(this);
        search.setHint("搜索");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(col(R.color.text));
        search.setHintTextColor(col(R.color.text3));
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(Ui.round(col(R.color.surface2), dp(10)));
        content(sheet).addView(search, new LinearLayout.LayoutParams(-1, dp(42)));
        LinearLayout list = card(sheet);
        Ui.margins(list, this, 0, 10, 0, 0);
        JSONObject json = settingsJson();
        List<View> rows = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < schema.length(); i++) {
            JSONObject s = schema.optJSONObject(i);
            if (s == null || json.has(s.optString("key"))) continue;
            String key = s.optString("key"), desc = s.optString("desc");
            LinearLayout row = Ui.column(this);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(Ui.press(this, null, dp(12)));
            row.addView(Ui.text(this, key, 15, R.color.text));
            TextView d = Ui.text(this, desc, 12.5f, R.color.text3);
            d.setMaxLines(2);
            d.setEllipsize(TextUtils.TruncateAt.END);
            d.setPadding(0, dp(2), 0, 0);
            row.addView(d);
            row.setOnClickListener(v -> { sheet.dismiss(); settingSheet(key); });
            list.addView(row, Ui.fill());
            rows.add(row);
            texts.add((key + " " + desc).toLowerCase(Locale.ROOT));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < rows.size(); i++) rows.get(i).setVisibility(q.isEmpty() || texts.get(i).contains(q) ? View.VISIBLE : View.GONE);
            }
        });
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    /** settings.json's env: Claude Code's environment variables. */
    private void envSheet() {
        Dialog sheet = sheet("环境变量", null);
        JSONObject env = settingsEnv();
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = env.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys);
        if (!keys.isEmpty()) {
            LinearLayout card = card(sheet);
            for (String k : keys) option(card, 0, k, env.optString(k), false, () -> { sheet.dismiss(); envEdit(k); });
            Ui.margins(card, this, 0, 0, 0, 10);
        }
        LinearLayout add = card(sheet);
        option(add, R.drawable.ic_plus, "添加变量", null, false, () -> { sheet.dismiss(); envEdit(null); });
        sheet.show();
    }

    private void envEdit(String key) {
        Dialog sheet = sheet(key == null ? "添加变量" : key, null);
        EditText name = field(sheet, "名称", key == null ? "" : key, "NAME");
        EditText value = field(sheet, "值", key == null ? "" : settingsEnv().optString(key), "");
        LinearLayout actions = Ui.row(this);
        TextView remove = button(key == null ? "取消" : "删除", false, v -> {
            if (key == null) { sheet.dismiss(); envSheet(); return; }
            JSONObject json = settingsJson();
            JSONObject env = json.optJSONObject("env");
            if (env != null) env.remove(key);
            if (saveSettings(json)) { toast("已删除"); sheet.dismiss(); envSheet(); refreshSettings(); }
        });
        TextView ok = button("保存", true, v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { toast("名称不能为空"); return; }
            JSONObject json = settingsJson();
            JSONObject env = json.optJSONObject("env");
            if (env == null) env = new JSONObject();
            if (key != null) env.remove(key);
            try {
                env.put(n, value.getText().toString());
                json.put("env", env);
            } catch (JSONException e) {
                return;
            }
            if (saveSettings(json)) { toast("已保存"); sheet.dismiss(); envSheet(); refreshSettings(); }
        });
        ok.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(remove, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    private void rawSettingsSheet() {
        String text;
        JSONObject now = settingsJson();
        try { text = settingsBroken ? readFile(settingsFile()) : now.toString(2); } catch (JSONException e) { text = "{}"; }
        textSheet("settings.json", text, "{ }", true, (t, done) -> {
            JSONObject json;
            try { json = new JSONObject(t.trim().isEmpty() ? "{}" : t); } catch (JSONException e) { toast("不是有效的 JSON：" + e.getMessage()); return; }
            settingsBroken = false; // replaced whole
            if (saveSettings(json)) { toast("已保存"); done.run(); }
        }, this::refreshSettings);
    }

    // ---- 扩展 ----

    /** The 扩展 page's counts (skills, MCP servers, agents, commands, hooks), read off the main thread; null until read. */
    private int[] extCounts;

    private void fillExt(LinearLayout box, JSONObject launch) {
        int[] n = extCounts;
        extCounts = null; // read again next time
        if (n == null) {
            work.execute(() -> {
                JSONObject hooks = readJson(settingsFile()).optJSONObject("hooks");
                int[] read = {skillList().size(), mcpList().size(), fileItems(AGENTS).size(), fileItems(COMMANDS).size(),
                        hooks == null ? 0 : hooks.length()};
                ui.post(() -> {
                    if (!"ext".equals(settingsAt) || settingsPage == null) return;
                    extCounts = read;
                    fillSettings(linked ? launchCache : null);
                });
            });
        }
        LinearLayout ext = card(box);
        option(ext, R.drawable.ic_spark, "技能", count(n, 0), false, this::skillsSheet);
        option(ext, R.drawable.ic_file_plus, "插件", null, false, this::pluginsSheet);
        option(ext, R.drawable.ic_globe, "MCP", count(n, 1), false, this::mcpSheet);
        option(ext, R.drawable.ic_bot, "子代理", count(n, 2), false, () -> itemsSheet(AGENTS));
        option(ext, R.drawable.ic_edit_lines, "命令", count(n, 3), false, () -> itemsSheet(COMMANDS));
        if (launch != null) {
            int off = disallowed(launch).size();
            option(ext, R.drawable.ic_tool, "工具", off == 0 ? null : "关闭 " + off, false, () -> toolsSheet(launch));
        }
        option(ext, R.drawable.ic_refresh, "Hooks", count(n, 4), false, this::hooksSheet);
    }

    private static String count(int[] n, int i) { return n == null || n[i] == 0 ? null : String.valueOf(n[i]); }

    // ---- 插件: Claude Code's own `claude plugin` ----

    private void pluginsSheet() {
        if (!linked) { toast("Claude Code 还没启动好"); return; }
        Dialog sheet = sheet("插件", null);
        LinearLayout card = card(sheet);
        TextView wait = Ui.text(this, "…", 14, R.color.text3);
        wait.setGravity(Gravity.CENTER);
        wait.setPadding(0, dp(18), 0, dp(18));
        card.addView(wait, Ui.fill());
        LinearLayout more = card(sheet);
        Ui.margins(more, this, 0, 10, 0, 0);
        option(more, R.drawable.ic_plus, "安装", null, false, () -> {
            sheet.dismiss();
            textSheet("安装插件", "", "名称 或 名称@市场", false, (t, done) -> pluginDo("install", t.trim(), done), this::pluginsSheet);
        });
        option(more, R.drawable.ic_globe, "市场", null, false, () -> { sheet.dismiss(); marketsSheet(); });
        sheet.show();
        bridge.get("/plugins", (status, data) -> {
            card.removeAllViews();
            JSONArray list = status == 200 ? data.optJSONArray("plugins") : null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                if (p == null) continue;
                String id = p.optString("id");
                String name = id.contains("@") ? id.substring(0, id.indexOf('@')) : id;
                boolean on = p.optBoolean("enabled", true);
                option(card, R.drawable.ic_file_plus, name, on ? p.optString("version") : "已停用", false, () -> { sheet.dismiss(); pluginActions(id, on); });
            }
            if (list == null || list.length() == 0) {
                TextView none = Ui.text(this, status == 200 ? "还没有" : "读取失败", 14, R.color.text3);
                none.setGravity(Gravity.CENTER);
                none.setPadding(0, dp(18), 0, dp(18));
                card.addView(none, Ui.fill());
            }
        });
    }

    private void pluginActions(String id, boolean on) {
        Dialog sheet = sheet(id, null);
        LinearLayout card = card(sheet);
        option(card, on ? R.drawable.ic_stop_circle : R.drawable.ic_check, on ? "停用" : "启用", null, false,
                () -> { sheet.dismiss(); pluginDo(on ? "disable" : "enable", id, null); });
        option(card, R.drawable.ic_refresh, "更新", null, false, () -> { sheet.dismiss(); pluginDo("update", id, null); });
        option(card, R.drawable.ic_trash, "卸载", null, true, () -> { sheet.dismiss(); pluginDo("uninstall", id, null); });
        sheet.show();
    }

    /** One `claude plugin` change; its own message on failure. */
    private void pluginDo(String action, String name, Runnable done) {
        if (name.isEmpty()) return;
        toast("正在处理…");
        bridge.post("/plugin", obj("action", action, "name", name), (status, data) -> {
            if (status != 200) { textSheetMessage("没有成功", data.optString("error")); return; }
            toast("完成");
            if (done != null) done.run();
            refreshSettings();
            if (action.startsWith("market")) marketsSheet(); else pluginsSheet();
        });
    }

    private void marketsSheet() {
        Dialog sheet = sheet("市场", null);
        LinearLayout card = card(sheet);
        TextView wait = Ui.text(this, "…", 14, R.color.text3);
        wait.setGravity(Gravity.CENTER);
        wait.setPadding(0, dp(18), 0, dp(18));
        card.addView(wait, Ui.fill());
        LinearLayout more = card(sheet);
        Ui.margins(more, this, 0, 10, 0, 0);
        option(more, R.drawable.ic_plus, "添加", null, false, () -> {
            sheet.dismiss();
            textSheet("添加市场", "", "GitHub 仓库（owner/repo）或网址", false, (t, done) -> pluginDo("market-add", t.trim(), done), this::marketsSheet);
        });
        sheet.show();
        bridge.get("/plugins", (status, data) -> {
            card.removeAllViews();
            JSONArray list = status == 200 ? data.optJSONArray("marketplaces") : null;
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                String name = m == null ? "" : m.optString("name");
                if (name.isEmpty()) continue;
                option(card, R.drawable.ic_globe, name, null, false, () -> {
                    sheet.dismiss();
                    Dialog ask = sheet(name, null);
                    LinearLayout c2 = card(ask);
                    option(c2, R.drawable.ic_trash, "删除", null, true, () -> { ask.dismiss(); pluginDo("market-remove", name, null); });
                    ask.show();
                });
            }
            if (card.getChildCount() == 0) {
                TextView none = Ui.text(this, status == 200 ? "还没有" : "读取失败", 14, R.color.text3);
                none.setGravity(Gravity.CENTER);
                none.setPadding(0, dp(18), 0, dp(18));
                card.addView(none, Ui.fill());
            }
        });
    }

    /** A message to read, such as what Claude Code said when something did not work. */
    private void textSheetMessage(String title, String text) {
        Dialog sheet = sheet(title, null);
        TextView t = Ui.text(this, text, 13, R.color.text2);
        t.setTypeface(Ui.MONO);
        t.setTextIsSelectable(true);
        content(sheet).addView(t, Ui.fill());
        sheet.show();
    }

    // ---- 子代理 and 命令: Markdown files Claude Code reads, and those it reported ----

    /** agents: subagents; commands: slash commands. Each a .md file under .claude/<kind>. */
    private static final String AGENTS = "agents", COMMANDS = "commands";

    /** A file extension: its name, its file when the user's or this folder's (editable), else where it comes from. */
    private static final class Item {
        final String name, from;
        final java.io.File file;

        Item(String name, java.io.File file, String from) {
            this.name = name;
            this.file = file;
            this.from = from;
        }
    }

    private java.io.File itemsDir(String kind) { return userland.file(Userland.HOME + "/.claude/" + kind); }

    /**
     * The user's and this folder's files (commands in subfolders are dir:name), then those Claude
     * Code reported when a conversation last started: for subagents its own and plugins'; for
     * commands only plugins' (its own commands are not extensions; skills have their own page).
     */
    private List<Item> fileItems(String kind) {
        List<Item> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.io.File here = userland.file(mcpFolder() + "/.claude/" + kind);
        addItems(out, seen, itemsDir(kind), itemsDir(kind), null);
        if (!here.equals(itemsDir(kind))) addItems(out, seen, here, here, "本目录");
        JSONArray reported = savedArray(kind.equals(AGENTS) ? "agents" : "slashCommands");
        java.util.Set<String> skills = new java.util.HashSet<>();
        JSONArray sk = savedArray("skills");
        for (int i = 0; i < sk.length(); i++) skills.add(sk.optString(i));
        for (int i = 0; i < reported.length(); i++) {
            String n = reported.optString(i);
            int colon = n.indexOf(':');
            if (n.isEmpty() || seen.contains(n) || skills.contains(n)) continue;
            if (kind.equals(COMMANDS) && colon <= 0) continue;
            seen.add(n);
            out.add(colon > 0 ? new Item(n.substring(colon + 1), null, n.substring(0, colon)) : new Item(n, null, "内置"));
        }
        return out;
    }

    private static void addItems(List<Item> out, java.util.Set<String> seen, java.io.File root, java.io.File dir, String from) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (java.io.File f : files) {
            if (f.isDirectory()) { addItems(out, seen, root, f, from); continue; }
            if (!f.getName().endsWith(".md")) continue;
            String rel = root.toURI().relativize(f.toURI()).getPath();
            String n = rel.substring(0, rel.length() - 3).replace('/', ':');
            if (seen.add(n)) out.add(new Item(n, f, from));
        }
    }

    private void itemsSheet(String kind) {
        boolean agents = kind.equals(AGENTS);
        Dialog sheet = sheet(agents ? "子代理" : "命令", null);
        LinearLayout card = card(sheet);
        List<Item> items = fileItems(kind);
        for (Item it : items) {
            option(card, agents ? R.drawable.ic_bot : R.drawable.ic_edit_lines, agents ? it.name : "/" + it.name, it.from, false,
                    it.file == null ? null : () -> { sheet.dismiss(); itemActions(kind, it.file); });
        }
        if (items.isEmpty()) {
            TextView none = Ui.text(this, "还没有", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(18));
            card.addView(none, Ui.fill());
        }
        LinearLayout add = card(sheet);
        Ui.margins(add, this, 0, 10, 0, 0);
        option(add, R.drawable.ic_plus, "新建", null, false, () -> {
            sheet.dismiss();
            textSheet(agents ? "新建子代理" : "新建命令", "", "名称：小写字母、数字和 -", false, (t, done) -> {
                String n = t.trim();
                if (!n.matches("[a-z0-9][a-z0-9-]*")) { toast("名称只能用小写字母、数字和 -"); return; }
                java.io.File f = new java.io.File(itemsDir(kind), n + ".md");
                if (f.exists()) { toast("已经有了"); return; }
                String body = agents
                        ? "---\nname: " + n + "\ndescription: 一句话写清楚什么时候该交给这个子代理\n---\n\n在这里写给它的指示。\n"
                        : "在这里写 /" + n + " 要 Claude 做的事。$ARGUMENTS 是命令后面跟的内容。\n";
                try {
                    Userland.write(f, body);
                } catch (java.io.IOException e) {
                    toast("新建失败：" + e.getMessage());
                    return;
                }
                done.run();
                editItem(kind, f);
            }, null);
        });
        sheet.show();
    }

    private void itemActions(String kind, java.io.File f) {
        Dialog sheet = sheet(f.getName().replaceAll("\\.md$", ""), null);
        LinearLayout card = card(sheet);
        option(card, R.drawable.ic_pencil, "编辑", null, false, () -> { sheet.dismiss(); editItem(kind, f); });
        option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            f.delete();
            relaunch();
            toast("已删除");
            refreshSettings();
            itemsSheet(kind);
        });
        sheet.show();
    }

    private void editItem(String kind, java.io.File f) {
        textSheet(f.getName(), readFile(f), "", true, (t, done) -> {
            try {
                Userland.write(f, t);
            } catch (java.io.IOException e) {
                toast("保存失败：" + e.getMessage());
                return;
            }
            relaunch();
            toast("已保存");
            done.run();
            refreshSettings();
        }, () -> itemsSheet(kind));
    }

    // ---- Hooks: settings.json's hooks ----

    /** settings.json's hooks as JSON; an example rule to start from when there are none. */
    private void hooksEdit() {
        JSONObject hooks = settingsJson().optJSONObject("hooks");
        String text;
        try {
            text = hooks != null && hooks.length() > 0 ? hooks.toString(2).replace("\\/", "/")
                    : "{\n  \"PostToolUse\": [\n    {\n      \"matcher\": \"Edit|Write\",\n      \"hooks\": [\n        { \"type\": \"command\", \"command\": \"要运行的命令\" }\n      ]\n    }\n  ]\n}";
        } catch (JSONException e) {
            text = "{}";
        }
        textSheet("Hooks", text, "{ }", true, (t, done) -> {
            JSONObject now = settingsJson();
            try {
                JSONObject h = t.trim().isEmpty() ? new JSONObject() : new JSONObject(t);
                if (h.length() == 0) now.remove("hooks"); else now.put("hooks", h);
            } catch (JSONException e) {
                toast("不是有效的 JSON：" + e.getMessage());
                return;
            }
            if (saveSettings(now)) { toast("已保存"); done.run(); refreshSettings(); }
        }, this::hooksSheet);
    }

    private void hooksSheet() {
        Dialog sheet = sheet("Hooks", null);
        JSONObject json = settingsJson();
        JSONObject hooks = json.optJSONObject("hooks");
        LinearLayout card = card(sheet);
        // Each event's rules: what they match, and the command they run.
        for (java.util.Iterator<String> it = hooks == null ? null : hooks.keys(); it != null && it.hasNext(); ) {
            String event = it.next();
            JSONArray rules = hooks.optJSONArray(event);
            for (int i = 0; rules != null && i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                JSONArray hs = rule == null ? null : rule.optJSONArray("hooks");
                for (int k = 0; hs != null && k < hs.length(); k++) {
                    JSONObject h = hs.optJSONObject(k);
                    String what = h == null ? "" : h.optString("command", h.optString("url", h.optString("prompt", h.optString("type"))));
                    String matcher = rule.optString("matcher");
                    option(card, R.drawable.ic_refresh, matcher.isEmpty() ? event : event + " · " + matcher, what, false,
                            () -> { sheet.dismiss(); hooksEdit(); });
                }
            }
        }
        if (card.getChildCount() == 0) {
            TextView none = Ui.text(this, "还没有", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(18));
            card.addView(none, Ui.fill());
        }
        LinearLayout more = card(sheet);
        Ui.margins(more, this, 0, 10, 0, 0);
        option(more, R.drawable.ic_pencil, "编辑", null, false, () -> { sheet.dismiss(); hooksEdit(); });
        boolean off = json.optBoolean("disableAllHooks");
        option(more, off ? R.drawable.ic_check : R.drawable.ic_stop_circle, off ? "全部启用" : "全部停用", null, false, () -> {
            JSONObject now = settingsJson();
            try {
                if (off) now.remove("disableAllHooks"); else now.put("disableAllHooks", true);
            } catch (JSONException e) {
                return;
            }
            if (saveSettings(now)) { toast("已保存"); sheet.dismiss(); refreshSettings(); hooksSheet(); }
        });
        sheet.show();
    }

    // ---- 存储 ----

    /** Sizes in the userland, measured when the page opens: conversations, work, temporary files, all. */
    private long[] storage;

    private void measureStorage() {
        storage = null;
        work.execute(() -> {
            long[] s = {
                    Userland.size(userland.file(Userland.HOME + "/.claude/projects")),
                    Userland.size(userland.file(Userland.HOME + "/work")),
                    Userland.size(userland.file("/tmp")) + Userland.size(userland.file(Userland.HOME + "/.cache")),
                    Userland.size(userland.file("/")),
            };
            ui.post(() -> {
                storage = s;
                if ("storage".equals(settingsAt)) fillSettings(launchCache);
            });
        });
    }

    private void fillStorage(LinearLayout box) {
        long[] s = storage;
        LinearLayout sizes = card(box);
        option(sizes, R.drawable.ic_compose, "对话记录", s == null ? "…" : Ui.size(s[0]), false, null);
        option(sizes, R.drawable.ic_folder, "工作目录", s == null ? "…" : Ui.size(s[1]), false, null);
        option(sizes, R.drawable.ic_file, "临时文件", s == null ? "…" : Ui.size(s[2]), false, null);
        option(sizes, R.drawable.ic_terminal, "运行环境", s == null ? "…" : Ui.size(Math.max(0, s[3] - s[0] - s[1] - s[2])), false, null);
        LinearLayout clean = card(box);
        Ui.margins(clean, this, 0, 12, 0, 0);
        option(clean, R.drawable.ic_trash, "清理临时文件", null, false, () -> confirm("清理临时文件", "删除 /tmp 和 ~/.cache 里的文件", false, () -> {
            clear(userland.file("/tmp"));
            clear(userland.file(Userland.HOME + "/.cache"));
        }));
        option(clean, R.drawable.ic_trash, "清空对话记录", null, true, () -> confirm("清空对话记录", "所有对话都会被删除，不能恢复", true, () -> {
            clear(userland.file(Userland.HOME + "/.claude/projects"));
            ui.post(() -> {
                keepSessions(new JSONArray());
                loadSessions();
            });
        }));
    }

    private void clear(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) Userland.delete(f);
    }

    /** Asks before doing something that cannot be undone; does it off the main thread. */
    private void confirm(String title, String what, boolean danger, Runnable action) {
        Dialog sheet = sheet(title, what);
        LinearLayout actions = Ui.row(this);
        TextView cancel = button("取消", false, v -> sheet.dismiss());
        TextView ok = button("确定", true, v -> {
            sheet.dismiss();
            work.execute(() -> {
                action.run();
                ui.post(() -> { toast("完成"); measureStorage(); });
            });
        });
        if (danger) ok.setBackground(Ui.press(this, Ui.round(col(R.color.danger), dp(10)), dp(10)));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        sheet.show();
    }

    // ---- 后台 ----

    private void fillKeep(LinearLayout box) {
        LinearLayout idle = card(box);
        toggle(idle, "闲置后重启 Claude Code", "闲着的 Claude Code 重新启动，不等在断掉的连接上", restartIdle(), on -> {
            prefs.edit().putBoolean("restartIdle", on).apply();
            fillSettings(launchCache);
        });
        LinearLayout keep = card(box);
        Ui.margins(keep, this, 0, 12, 0, 0);
        if (!hasStorage()) option(keep, R.drawable.ic_folder, "允许访问存储", null, false, this::requestStorage);
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            option(keep, R.drawable.ic_check, "关闭电池优化", null, false, () -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException e) {
                    toast("系统不支持这个设置");
                }
            });
        }
        option(keep, R.drawable.ic_gear, "应用信息", null, false, () -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                toast("打不开应用信息");
            }
        });
        // Android 12+ kills an app's child processes beyond a limit, or busy ones in the background.
        String rish = "rish -c 'dumpsys deviceidle whitelist +" + getPackageName()
                + "; cmd appops set " + getPackageName() + " RUN_ANY_IN_BACKGROUND allow"
                + "; settings put global settings_enable_monitor_phantom_procs false"
                + "; device_config put activity_manager max_phantom_processes 2147483647'";
        option(keep, R.drawable.ic_copy, "复制保活命令", "rish", false, () -> copy(rish));
    }

    // ---- 关于 ----

    private void fillAbout(LinearLayout box) {
        LinearLayout about = card(box);
        option(about, 0, "应用", "v" + appVersion(), false, null);
        option(about, 0, "Claude Code", claudeVersion, false, null);
        LinearLayout tools = card(box);
        Ui.margins(tools, this, 0, 12, 0, 0);
        option(tools, R.drawable.ic_list, "日志", null, false, this::logSheet);
        option(tools, R.drawable.ic_terminal, "终端", null, false, () -> openTerminal());
    }

    /** The colours or text size changed: the screen is built again around the same conversations. */
    private void restyle() {
        String at = settingsAt;
        boolean open = settingsPage != null;
        int scrolled = open ? settingsScroll().getScrollY() : 0;
        if (open) {
            // Its dismissal is told later: Settings is let go of now, to be opened again below.
            settingsPage.dismiss();
            settingsPage = null;
        }
        Chat chat = current;
        chat.draft = input.getText().toString();
        current = null;
        md = new Md(talkContext(), this::copy, this::openUrl, this::userFile);
        build();
        switchTo(chat);
        renderSessions();
        updateLink();
        if (open) {
            settingsAt = at;
            settingsInstant = true;
            settingsSheet();
            settingsInstant = false;
            // Where it was, not back at its top.
            ScrollView again = settingsScroll();
            again.post(() -> again.scrollTo(0, scrolled));
        }
    }

    /** The scrolling of the Settings page in front. */
    private ScrollView settingsScroll() {
        return (ScrollView) settingsBox.getParent();
    }

    /** The context conversation text is made in: the chosen text size over the system's. */
    private android.content.Context talkContext() {
        float k = Fonts.scale(this);
        if (k == 1f) return this;
        android.content.res.Configuration o = new android.content.res.Configuration();
        o.fontScale = getResources().getConfiguration().fontScale * k;
        android.view.ContextThemeWrapper w = new android.view.ContextThemeWrapper(this, getTheme());
        w.applyOverrideConfiguration(o);
        return w;
    }

    /** The system's light or dark when the screen was built, to follow it when it changes. */
    private int night;

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        int now = config.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (now == night) return;
        night = now;
        if (Themes.mode(this) == 0) restyle();
    }

    // ---- fonts ----

    /** Which sheet a font file is imported from, to return to it. */
    private boolean fontCjk = true;

    private void fontSheet(boolean cjk) {
        fontCjk = cjk;
        Dialog sheet = sheet(cjk ? "中文字体" : "英文字体", null);
        LinearLayout card = card(sheet);
        String now = cjk ? Fonts.cjk(this) : Fonts.latin(this);
        TextView wait = Ui.text(this, "…", 14, R.color.text3);
        wait.setGravity(Gravity.CENTER);
        wait.setPadding(0, dp(18), 0, dp(18));
        card.addView(wait, Ui.fill());
        LinearLayout add = card(sheet);
        Ui.margins(add, this, 0, 10, 0, 0);
        option(add, R.drawable.ic_plus, "导入字体文件", "ttf / otf", false, () -> {
            sheet.dismiss();
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try {
                startActivityForResult(pick, PICK_FONT);
            } catch (android.content.ActivityNotFoundException e) {
                toast("没有可用的文件选择器");
            }
        });
        sheet.show();
        // Reading the phone's fonts takes a moment: off the main thread.
        work.execute(() -> {
            List<Fonts.Choice> all = Fonts.choices(this, cjk);
            ui.post(() -> {
                card.removeAllViews();
                String sample = cjk ? "  永字八法" : "  Aa";
                for (Fonts.Choice f : all) {
                    option(card, 0, f.name + sample, null, false, () -> {
                        Fonts.set(this, cjk, f.id);
                        md.fontChanged();
                        sheet.dismiss();
                        adapter.notifyDataSetChanged();
                        refreshSettings();
                    });
                    LinearLayout row = (LinearLayout) card.getChildAt(card.getChildCount() - 1);
                    ((TextView) row.getChildAt(0)).setTypeface(f.preview);
                    if (f.id.equals(now)) check(card);
                }
            });
        });
    }

    /** Copies a picked font file into files/fonts, where it is offered with the others. */
    private void addFont(Uri uri) {
        work.execute(() -> {
            String name = "font.ttf";
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (n >= 0 && c.getString(n) != null) name = c.getString(n);
                }
            } catch (RuntimeException ignored) {
                // default name
            }
            String safe = name.replaceAll("[/\\\\]", "_");
            if (!safe.toLowerCase(Locale.ROOT).matches(".*\\.(ttf|otf|ttc)$")) {
                ui.post(() -> toast("只支持 ttf、otf、ttc"));
                return;
            }
            java.io.File out = new java.io.File(Fonts.dir(this), safe);
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("读不到文件");
                Fonts.dir(this).mkdirs();
                try (java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                    Userland.copy(in, o);
                }
                if (Typeface.createFromFile(out) == Typeface.DEFAULT) throw new java.io.IOException("不是可用的字体");
            } catch (Exception e) {
                out.delete();
                error = e.getMessage();
            }
            String err = error;
            ui.post(() -> {
                if (err != null) { toast("导入失败：" + err); return; }
                toast("已导入");
                fontSheet(fontCjk);
            });
        });
    }

    // ---- extensions: skills, tools, MCP servers ----

    private java.io.File skillsDir() { return userland.file(Userland.HOME + "/.claude/skills"); }

    private List<String> skillNames() {
        List<String> out = new ArrayList<>();
        java.io.File[] dirs = skillsDir().listFiles(java.io.File::isDirectory);
        if (dirs != null) for (java.io.File d : dirs) if (new java.io.File(d, "SKILL.md").isFile()) out.add(d.getName());
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** A skill: its name, its folder when it is the user's or this folder's (editable), else where it comes from. */
    private static final class Skill {
        final String name, from;
        final java.io.File dir;

        Skill(String name, java.io.File dir, String from) {
            this.name = name;
            this.dir = dir;
            this.from = from;
        }
    }

    /**
     * The skills Claude Code has: the user's and this folder's (their files, editable), then the
     * rest it reported when a conversation last started (its own, a plugin's), shown only.
     */
    private List<Skill> skillList() {
        List<Skill> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String n : skillNames()) {
            out.add(new Skill(n, new java.io.File(skillsDir(), n), null));
            seen.add(n);
        }
        java.io.File here = userland.file(mcpFolder() + "/.claude/skills");
        java.io.File[] dirs = here.equals(skillsDir()) ? null : here.listFiles(java.io.File::isDirectory);
        if (dirs != null) {
            java.util.Arrays.sort(dirs);
            for (java.io.File d : dirs) {
                if (!new java.io.File(d, "SKILL.md").isFile() || !seen.add(d.getName())) continue;
                out.add(new Skill(d.getName(), d, "本目录"));
            }
        }
        JSONArray reported = savedArray("skills");
        for (int i = 0; i < reported.length(); i++) {
            String n = reported.optString(i);
            if (n.isEmpty() || !seen.add(n)) continue;
            int colon = n.indexOf(':');
            // A plugin's skill is plugin:skill: its name, and the plugin it comes from.
            out.add(colon > 0 ? new Skill(n.substring(colon + 1), null, n.substring(0, colon)) : new Skill(n, null, "内置"));
        }
        return out;
    }

    private String readFile(java.io.File f) {
        try {
            return Userland.read(new java.io.FileInputStream(f));
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** A change Claude Code reads when it starts: idle conversations restart with it. */
    private void relaunch() {
        if (linked) bridge.post("/launch", new JSONObject(), null);
    }

    private void skillsSheet() {
        Dialog sheet = sheet("技能", null);
        LinearLayout card = card(sheet);
        List<Skill> skills = skillList();
        for (Skill k : skills) {
            option(card, R.drawable.ic_spark, k.name, k.from, false, k.dir == null ? null : () -> { sheet.dismiss(); skillActions(k.dir); });
        }
        if (skills.isEmpty()) {
            TextView none = Ui.text(this, "还没有", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(18));
            card.addView(none, Ui.fill());
        }
        LinearLayout add = card(sheet);
        Ui.margins(add, this, 0, 10, 0, 0);
        option(add, R.drawable.ic_plus, "新建", null, false, () -> {
            sheet.dismiss();
            textSheet("新建技能", "", "名称：小写字母、数字和 -", false, (t, done) -> {
                String n = t.trim();
                if (!n.matches("[a-z0-9][a-z0-9-]*")) { toast("名称只能用小写字母、数字和 -"); return; }
                java.io.File f = new java.io.File(skillsDir(), n + "/SKILL.md");
                if (f.exists()) { toast("已经有这个技能了"); return; }
                try {
                    Userland.write(f, "---\nname: " + n + "\ndescription: 一句话写清楚什么时候该用这个技能\n---\n\n在这里写给 Claude 的做法和步骤。\n");
                } catch (java.io.IOException e) {
                    toast("新建失败：" + e.getMessage());
                    return;
                }
                done.run();
                editSkill(f.getParentFile());
            }, null);
        });
        sheet.show();
    }

    private void skillActions(java.io.File dir) {
        Dialog sheet = sheet(dir.getName(), null);
        LinearLayout card = card(sheet);
        option(card, R.drawable.ic_pencil, "编辑", null, false, () -> { sheet.dismiss(); editSkill(dir); });
        option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            Userland.delete(dir);
            relaunch();
            toast("已删除");
            refreshSettings();
            skillsSheet();
        });
        sheet.show();
    }

    private void editSkill(java.io.File dir) {
        java.io.File f = new java.io.File(dir, "SKILL.md");
        textSheet("技能 · " + dir.getName(), readFile(f), "SKILL.md", true, (t, done) -> {
            try {
                Userland.write(f, t);
            } catch (java.io.IOException e) {
                toast("保存失败：" + e.getMessage());
                return;
            }
            relaunch();
            toast("已保存");
            done.run();
            refreshSettings();
        }, this::skillsSheet);
    }

    /** The tools Claude Code reported when a conversation last started, and MCP servers' state. */
    private JSONArray savedArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static List<String> disallowed(JSONObject launch) {
        List<String> out = new ArrayList<>();
        for (String t : launch.optString("disallowedTools").replace(',', ' ').trim().split("\\s+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private void toolsSheet(JSONObject launch) {
        Dialog sheet = sheet("工具", null);
        LinearLayout card = card(sheet);
        JSONArray tools = savedArray("tools");
        List<String> off = disallowed(launch);
        List<String> all = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) all.add(tools.optString(i));
        for (String t : off) if (!all.contains(t)) all.add(t);
        for (String t : all) {
            boolean on = !off.contains(t);
            String sub = t.startsWith("mcp__") ? t.split("__")[1] : null;
            String label = t.startsWith("mcp__") && t.split("__").length > 2 ? t.substring(t.indexOf("__", 5) + 2) : t;
            option(card, 0, label, sub, false, () -> {
                List<String> next = new ArrayList<>(off);
                if (on) next.add(t); else next.remove(t);
                saveLaunch("disallowedTools", TextUtils.join(" ", next), () -> {
                    sheet.dismiss();
                    refreshSettings();
                    bridge.get("/launch", (status, l) -> { if (status == 200) { launchCache = l; toolsSheet(l); } });
                });
            });
            if (on) check(card);
        }
        if (all.isEmpty()) {
            TextView none = Ui.text(this, "发一条消息后显示", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(dp(12), dp(18), dp(12), dp(18));
            card.addView(none, Ui.fill());
        }
        sheet.show();
    }

    private java.io.File mcpFile() { return userland.file(Userland.HOME + "/.ccapp/mcp.json"); }

    /** The MCP servers added here, by name. */
    private JSONObject mcpServers() {
        try {
            JSONObject s = new JSONObject(readFile(mcpFile())).optJSONObject("mcpServers");
            return s != null ? s : new JSONObject();
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private boolean saveMcp(JSONObject servers) {
        try {
            Userland.replace(mcpFile(), new JSONObject().put("mcpServers", servers).toString(2) + "\n");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
            return false;
        }
        relaunch();
        refreshSettings();
        return true;
    }

    /** An MCP server: its name, where it is kept, and its settings. */
    private static final class Mcp {
        /** user, local (this folder), project (the folder's .mcp.json): Claude Code's; app: added here before. */
        final String name, scope;
        final JSONObject config;

        Mcp(String name, String scope, JSONObject config) {
            this.name = name;
            this.scope = scope;
            this.config = config;
        }
    }

    private String mcpFolder() {
        String f = current != null && current.cwd != null && !current.cwd.isEmpty() ? current.cwd : cwd;
        return f.isEmpty() ? defaultCwd : f;
    }

    private JSONObject readJson(java.io.File f) {
        try {
            return new JSONObject(readFile(f));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Every MCP server Claude Code has for this folder (as `claude mcp` keeps them), and those added here before. */
    private List<Mcp> mcpList() {
        List<Mcp> out = new ArrayList<>();
        String folder = mcpFolder();
        JSONObject cc = readJson(userland.file(Userland.HOME + "/.claude.json"));
        JSONObject projects = cc.optJSONObject("projects");
        JSONObject here = projects == null ? null : projects.optJSONObject(folder);
        addMcp(out, cc.optJSONObject("mcpServers"), "user");
        addMcp(out, here == null ? null : here.optJSONObject("mcpServers"), "local");
        addMcp(out, readJson(userland.file(folder + "/.mcp.json")).optJSONObject("mcpServers"), "project");
        addMcp(out, mcpServers(), "app");
        return out;
    }

    private static void addMcp(List<Mcp> out, JSONObject servers, String scope) {
        if (servers == null) return;
        for (java.util.Iterator<String> it = servers.keys(); it.hasNext(); ) {
            String n = it.next();
            JSONObject cfg = servers.optJSONObject(n);
            if (cfg != null) out.add(new Mcp(n, scope, cfg));
        }
    }

    /** Has Claude Code itself add, replace or remove one of its servers; then shows the list again. */
    private void mcpChange(String action, Mcp m, JSONObject config, Runnable done) {
        if (!linked) { toast("Claude Code 还没启动好"); return; }
        toast("正在保存…");
        JSONObject body = obj("action", action, "name", m.name, "scope", m.scope, "cwd", mcpFolder());
        try { if (config != null) body.put("config", config); } catch (JSONException ignored) { /* string key */ }
        bridge.post("/mcp", body, (status, data) -> {
            if (status != 200) { toast("失败：" + data.optString("error")); return; }
            toast("remove".equals(action) ? "已删除" : "已保存");
            if (done != null) done.run();
            refreshSettings();
            mcpSheet();
        });
    }

    private void mcpSheet() {
        Dialog sheet = sheet("MCP", null);
        LinearLayout card = card(sheet);
        // Their state, from when a conversation last started.
        Map<String, String> state = new HashMap<>();
        JSONArray st = savedArray("mcpServers");
        for (int i = 0; i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            if (o != null) state.put(o.optString("name"), o.optString("status"));
        }
        List<Mcp> servers = mcpList();
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (Mcp m : servers) {
            String s = state.get(m.name);
            shown.add(m.name);
            option(card, R.drawable.ic_globe, m.name, s == null ? null : mcpState(s), false, () -> { sheet.dismiss(); mcpActions(m); });
        }
        // Servers Claude Code reported but keeps elsewhere (plugins, claude.ai): shown, not changed here.
        for (Map.Entry<String, String> e : state.entrySet()) {
            if (shown.contains(e.getKey())) continue;
            option(card, R.drawable.ic_globe, e.getKey(), mcpState(e.getValue()), false, null);
        }
        if (servers.isEmpty() && state.isEmpty()) {
            TextView none = Ui.text(this, "还没有", 14, R.color.text3);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(18), 0, dp(18));
            card.addView(none, Ui.fill());
        }
        LinearLayout add = card(sheet);
        Ui.margins(add, this, 0, 10, 0, 0);
        option(add, R.drawable.ic_plus, "添加", null, false, () -> {
            sheet.dismiss();
            textSheet("添加 MCP", "", "{\n  \"名称\": {\n    \"type\": \"http\",\n    \"url\": \"https://…\"\n  }\n}", true, (t, done) -> {
                JSONObject in;
                try {
                    in = new JSONObject(t.trim());
                } catch (JSONException e) {
                    toast("不是有效的 JSON：" + e.getMessage());
                    return;
                }
                // Docs give {"mcpServers": {...}}, or just the servers.
                JSONObject add2 = in.optJSONObject("mcpServers") != null ? in.optJSONObject("mcpServers") : in;
                List<Mcp> adding = new ArrayList<>();
                for (java.util.Iterator<String> keys = add2.keys(); keys.hasNext(); ) {
                    String k = keys.next();
                    if (add2.optJSONObject(k) == null) { toast(k + " 的配置不是一个对象"); return; }
                    adding.add(new Mcp(k, "user", add2.optJSONObject(k)));
                }
                if (adding.isEmpty()) { toast("没有找到服务器配置"); return; }
                // Into Claude Code's own list (user scope), one after another.
                Runnable[] next = new Runnable[1];
                int[] at = {0};
                next[0] = () -> {
                    if (at[0] >= adding.size()) { done.run(); return; }
                    Mcp m = adding.get(at[0]++);
                    mcpChange("set", m, m.config, next[0]);
                };
                next[0].run();
            }, this::mcpSheet);
        });
        sheet.show();
    }

    private static String mcpState(String s) {
        switch (s) {
            case "connected": return "已连接";
            case "failed": return "连接失败";
            case "pending": return "连接中";
            case "needs-auth": return "需要授权";
            default: return s;
        }
    }

    private void mcpActions(Mcp m) {
        Dialog sheet = sheet(m.name, null);
        LinearLayout card = card(sheet);
        option(card, R.drawable.ic_pencil, "编辑", null, false, () -> {
            sheet.dismiss();
            String text;
            try { text = m.config.toString(2).replace("\\/", "/"); } catch (JSONException e) { text = "{}"; }
            textSheet("MCP · " + m.name, text, "{ }", true, (t, done) -> {
                JSONObject cfg;
                try { cfg = new JSONObject(t.trim()); } catch (JSONException e) { toast("不是有效的 JSON：" + e.getMessage()); return; }
                if (!m.scope.equals("app")) { mcpChange("set", m, cfg, done); return; }
                JSONObject all = mcpServers();
                try { all.put(m.name, cfg); } catch (JSONException ignored) { /* string key */ }
                if (saveMcp(all)) { toast("已保存"); done.run(); }
            }, this::mcpSheet);
        });
        option(card, R.drawable.ic_trash, "删除", null, true, () -> {
            sheet.dismiss();
            if (!m.scope.equals("app")) { mcpChange("remove", m, null, null); return; }
            JSONObject all = mcpServers();
            all.remove(m.name);
            if (saveMcp(all)) toast("已删除");
            mcpSheet();
        });
        sheet.show();
    }

    // ---- Claude Code's settings.json, read and written here directly ----

    /**
     * The top bar's menu: a small card under its button, holding what the app adds (the terminal,
     * searching the conversation) rather than more buttons; each also a / command where it has one.
     */
    private void moreMenu(View anchor) {
        int width = dp(212), shade = dp(14); // room around the card for its shadow
        FrameLayout room = new FrameLayout(this);
        room.setPadding(shade, shade, shade, shade);
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackground(Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.border)));
        card.setElevation(dp(8));
        room.addView(card, new FrameLayout.LayoutParams(width, -2));
        android.widget.PopupWindow pop = new android.widget.PopupWindow(room, width + 2 * shade, -2, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setOutsideTouchable(true);
        menuItem(card, pop, R.drawable.ic_terminal, "终端", null, this::openTerminal);
        menuItem(card, pop, R.drawable.ic_search, "搜索对话", "/find", () -> {
            input.setText("/find ");
            input.setSelection(input.length());
            input.requestFocus();
            showKeyboard();
        });
        // Its right edge on the button's, a little below it; it grows out of that corner.
        pop.showAsDropDown(anchor, shade, dp(6) - shade, Gravity.END);
        card.setPivotX(width);
        card.setPivotY(0);
        card.setAlpha(0f);
        card.setScaleX(0.8f);
        card.setScaleY(0.8f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
                .setInterpolator(Motion.EMPHASIZED).start();
    }

    private void menuItem(LinearLayout card, android.widget.PopupWindow pop, int icon, String label, String command, Runnable click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setMinimumHeight(dp(46));
        row.setBackground(Ui.press(this, null, dp(12)));
        row.addView(Ui.icon(this, icon, 19, R.color.text2));
        TextView l = Ui.text(this, label, 15, R.color.text);
        l.setPadding(dp(12), 0, 0, 0);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (command != null) {
            TextView c = Ui.text(this, command, 12.5f, R.color.text3);
            c.setTypeface(Ui.MONO);
            row.addView(c);
        }
        row.setOnClickListener(v -> {
            pop.dismiss();
            click.run();
        });
        card.addView(row, Ui.fill());
    }

    private void openTerminal() {
        if (!userland.ready()) { toast("运行环境还没准备好"); return; }
        startActivity(new Intent(this, TerminalActivity.class));
        overridePendingTransition(R.anim.page_in, R.anim.page_under_out);
    }

    private java.io.File settingsFile() { return userland.file(Userland.HOME + "/.claude/settings.json"); }

    /** settings.json is there but did not read as JSON: a change saved over it would lose it. */
    private boolean settingsBroken;

    private JSONObject settingsJson() {
        settingsBroken = false;
        java.io.File f = settingsFile();
        if (!f.exists()) return new JSONObject();
        try {
            return new JSONObject(Userland.read(new java.io.FileInputStream(f)));
        } catch (Exception e) {
            settingsBroken = true;
            return new JSONObject();
        }
    }

    private JSONObject settingsEnv() {
        JSONObject env = settingsJson().optJSONObject("env");
        return env != null ? env : new JSONObject();
    }

    /** Writes settings.json; running conversations pick it up on their next message. */
    private boolean saveSettings(JSONObject json) {
        if (settingsBroken) {
            toast("settings.json 格式有错，没有保存");
            return false;
        }
        try {
            Userland.replace(settingsFile(), json.toString(2) + "\n");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
            return false;
        }
        if (linked) {
            bridge.post("/launch", new JSONObject(), null); // restarts idle conversations with it
            bridge.get("/config", (status, data) -> {
                if (status != 200) return;
                applyConfig(data);
                renderNow();
            });
        }
        return true;
    }

    private void apiSheet() {
        Dialog sheet = sheet("API", null);
        JSONObject env = settingsEnv();
        String key = env.optString("ANTHROPIC_AUTH_TOKEN", env.optString("ANTHROPIC_API_KEY"));
        EditText url = field(sheet, "地址", env.optString("ANTHROPIC_BASE_URL"), "https://api.anthropic.com");
        EditText token = field(sheet, "API Key", key, "sk-…");
        EditText name = field(sheet, "模型", env.optString("ANTHROPIC_MODEL"), "");
        LinearLayout actions = Ui.row(this);
        TextView raw = button("settings.json", false, v -> {
            sheet.dismiss();
            rawSettingsSheet();
        });
        TextView ok = button("保存", true, v -> {
            JSONObject json = settingsJson();
            JSONObject e = json.optJSONObject("env");
            if (e == null) e = new JSONObject();
            put(e, "ANTHROPIC_BASE_URL", url.getText().toString().trim());
            String k = token.getText().toString().trim();
            // Anthropic's own API takes a key as an API key (x-api-key); other providers take theirs
            // as a bearer token.
            boolean anthropic = url.getText().toString().trim().isEmpty();
            e.remove("ANTHROPIC_API_KEY");
            e.remove("ANTHROPIC_AUTH_TOKEN");
            put(e, anthropic ? "ANTHROPIC_API_KEY" : "ANTHROPIC_AUTH_TOKEN", k);
            put(e, "ANTHROPIC_MODEL", name.getText().toString().trim());
            try { json.put("env", e); } catch (JSONException ignored) { /* string key */ }
            if (saveSettings(json)) { toast("已保存"); sheet.dismiss(); refreshSettings(); }
        });
        ok.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(raw, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
    }

    private static void put(JSONObject o, String key, String value) {
        if (value.isEmpty()) { o.remove(key); return; }
        try { o.put(key, value); } catch (JSONException ignored) { /* string key */ }
    }

    private EditText field(Dialog sheet, String label, String value, String hint) {
        TextView l = Ui.text(this, label, 13, R.color.text2);
        l.setPadding(dp(2), dp(8), 0, dp(6));
        content(sheet).addView(l);
        EditText f = new EditText(this);
        f.setText(value);
        f.setHint(hint);
        f.setSingleLine(true);
        f.setTextSize(14.5f);
        f.setTypeface(Ui.MONO);
        f.setTextColor(col(R.color.text));
        f.setHintTextColor(col(R.color.text3));
        f.setPadding(dp(12), 0, dp(12), 0);
        f.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        content(sheet).addView(f, new LinearLayout.LayoutParams(-1, dp(44)));
        return f;
    }

    private void logSheet() {
        String bridgeLog = Userland.tail(userland.file(Userland.HOME + "/.ccapp/bridge.log"), 16_000);
        String runLog = Userland.tail(userland.log, 8_000);
        String text = "== bridge.log ==\n" + (bridgeLog.isEmpty() ? "（空）\n" : bridgeLog) + "\n== 启动 ==\n" + (runLog.isEmpty() ? "（空）\n" : runLog);
        Dialog sheet = sheet("日志", null);
        TextView t = Ui.text(this, text, 11.5f, R.color.text);
        t.setTypeface(Ui.MONO);
        t.setTextIsSelectable(true);
        content(sheet).addView(t, Ui.fill());
        TextView cp = button("复制全部", false, v -> copy(text));
        content(sheet).addView(cp);
        Ui.margins(cp, this, 0, 12, 0, 0);
        sheet.show();
        ScrollView scroll = (ScrollView) content(sheet).getParent();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String oneLine(String text) {
        String t = text.trim().replace('\n', ' ');
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private void saveLaunch(String key, String value, Runnable done) {
        bridge.post("/launch", obj(key, value), (status, data) -> {
            if (status == 200) {
                toast("已保存");
                done.run();
            } else {
                toast(status == 400 ? "参数有误：" + data.optString("error") : "保存失败");
            }
        });
    }

    private void permissionSheet(String mode) {
        Dialog sheet = sheet("权限模式", null);
        LinearLayout card = card(sheet);
        List<String> modes = new ArrayList<>(permissionModes);
        if (!modes.contains(mode)) modes.add(0, mode);
        for (String m : modes) {
            option(card, 0, m, null, false, () -> saveLaunch("permissionMode", m, () -> {
                sheet.dismiss();
                refreshSettings();
            }));
            if (m.equals(mode)) check(card);
        }
        sheet.show();
    }

    private void memorySheet(String scope, String folder, String title) {
        String q = "/memory?scope=" + scope + (folder == null ? "" : "&cwd=" + Uri.encode(folder));
        bridge.get(q, (status, data) -> {
            if (status != 200) { toast("读取 CLAUDE.md 失败"); return; }
            textSheet(title, data.optString("text"), "", true, (t, done) -> {
                JSONObject body = obj("scope", scope, "text", t);
                if (folder != null) body = obj("scope", scope, "cwd", folder, "text", t);
                bridge.post("/memory", body, (s2, r2) -> {
                    if (s2 == 200) { toast("已保存"); done.run(); } else toast("保存失败");
                });
            }, this::refreshSettings);
        });
    }

    private void textSheet(String title, String value, String hint, boolean multiLine,
                           java.util.function.BiConsumer<String, Runnable> save, Runnable back) {
        Dialog sheet = sheet(title, null);
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(col(R.color.text));
        field.setHintTextColor(col(R.color.text3));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(Ui.round(col(R.color.surface), dp(10), dp(1), col(R.color.borderStrong)));
        if (multiLine) {
            field.setMinLines(6);
            field.setMaxLines(14);
            field.setTextSize(14);
        } else {
            field.setSingleLine(true);
            field.setTypeface(Ui.MONO);
        }
        content(sheet).addView(field, Ui.fill());
        LinearLayout actions = Ui.row(this);
        TextView cancel = button("取消", false, v -> { sheet.dismiss(); if (back != null) back.run(); });
        TextView ok = button("保存", true, v -> save.accept(field.getText().toString(), () -> {
            sheet.dismiss();
            if (back != null) back.run();
        }));
        ok.setBackground(Ui.press(this, Ui.round(col(R.color.text), dp(10)), dp(10)));
        ok.setTextColor(col(R.color.bg));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, this, 10, 0, 0, 0);
        content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, this, 0, 14, 0, 0);
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        sheet.show();
        field.requestFocus();
    }

    private void section(LinearLayout box, String label) {
        TextView t = Ui.text(this, label, 14, R.color.accent);
        t.setTypeface(Ui.MEDIUM);
        // Close under the page's title, apart from a group above.
        boolean afterGroup = box.getChildCount() > 0 && TILES.equals(box.getChildAt(box.getChildCount() - 1).getTag());
        t.setPadding(dp(12), dp(afterGroup ? 22 : 4), 0, dp(10));
        box.addView(t);
    }

    // ================= misc =================

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Claude Code", text));
        toast("已复制");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast(url);
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, 0);
    }
}
