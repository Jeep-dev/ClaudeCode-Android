package com.jeep.claudecode

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/** The conversation page: the list of messages with the bar above it and the box below, the jumps, and the page shown while there is nothing yet. */
class ChatScreen(app: MainActivity) : Part(app) {
    @JvmField var stick = true
    /** A jump to the end (its button, the title) is on its way: the list is still taken as at the end. */
    private var toEnd = false
    lateinit var title: TextView
    lateinit var banner: TextView
    lateinit var list: ThreadList
    // where the list was last touched, on the screen: a message's menu opens there
    @JvmField var touchX = 0f
    @JvmField var touchY = 0f
    lateinit var adapter: ThreadAdapter
    lateinit var emptyView: ScrollView
    private lateinit var toTop: View
    private lateinit var toBottom: View
    private var jumpLowest = 0 // the jumps' least bottom margin: clear of the box
    /** The jump to the top and to the end: shown while the list is scrolled by hand, then hidden. */
    private lateinit var jump: LinearLayout
    /** The immersive layout (a setting): the conversation under the status bar and floating glass. */
    @JvmField var immersive = false
    var host: Glass.Host? = null
    private var composer: View? = null
    private lateinit var fadeTop: View
    private lateinit var fadeBottom: View
    private lateinit var topButtons: LinearLayout
    /** The status bar's height and what covers the bottom (navigation bar or keyboard), immersive only. */
    private var insetTop = 0
    private var insetBottom = 0
    private var keyboard = false
    /** What the empty page last showed: rebuilt only when that changes, so what moves in it is not cut off. */
    private var emptyShown: String? = null
    /** The conversation the greeting last came up for: shown again for it, it stays still. */
    private var heroFor: Chat? = null

    /** The page, built (again after a restyle) for the layout in use. */
    fun build(): View {
        immersive = Themes.immersive(app)
        applyWindow()

        title = Ui.text(app, "新对话", 14.5f, R.color.text)
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        title.setPadding(dp(6), 0, dp(6), 0)
        title.setOnClickListener { scrollToEnd(true) }

        banner = Ui.text(app, "与 Claude Code 的连接断开了，正在重连…", 13.5f, R.color.danger)
        banner.setPadding(dp(12), dp(9), dp(12), dp(9))
        banner.background = Ui.round(col(R.color.dangerSoft), dp(10))
        banner.visibility = View.GONE

        list = ThreadList(app)
        list.layoutManager = object : LinearLayoutManager(app) {
            // A long press that starts selecting gives the message's text focus, and the list would
            // then scroll that whole text into view: to its top, when it is taller than the screen.
            // The list stays put; dragging a handle to the edge still scrolls (another path).
            override fun onRequestChildFocus(parent: RecyclerView, state: RecyclerView.State, child: View, focused: View?): Boolean = true
        }
        list.clipToPadding = false
        list.setPadding(dp(22), dp(2), dp(22), dp(24))
        list.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        list.itemAnimator = null // rows change in place; animated moves overlap neighbours
        adapter = ThreadAdapter(app.talkContext(), app.md) {
            // A row opened or closed. One opened is followed as it grows (ThreadList): its bottom
            // brought up from its first frame, its head kept on screen. From here the list follows
            // the end if it is there, which a row grown open may have taken out of reach.
            stick = !list.canScrollVertically(1)
            app.renderNow()
        }
        adapter.queue = app.sender.queueActions
        adapter.asks = app.sender::answer
        adapter.marker = app.finder::mark
        adapter.menu = app.messages::messageMenu
        adapter.translate = app.messages::translate
        list.adapter = adapter
        list.follow = object : ThreadList.Follow {
            override fun atEnd(): Boolean = stick
            override fun opened(): View? = adapter.opened
            override fun moving(row: View): Boolean = adapter.moving(list.getChildViewHolder(row) as ThreadAdapter.Holder)
        }
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchX = e.rawX
                    touchY = e.rawY
                }
                return false
            }
        })
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE && dy == 0) return
                if (toEnd) return // on its way down: still at the end, as the reader asked
                // Only scrolling up by hand lets go of the bottom. The list moving by itself (following
                // the end or a row opened, a layout change) never does: a conversation just opened is
                // laid out from its top, and goes to its end a screen at a time. Reaching the end, by
                // hand or not, holds on to it. A jump away (to the top, to a place found) lets go itself.
                if (!list.byHand()) {
                    if (!rv.canScrollVertically(1)) stick = true
                    return
                }
                adapter.opened = null // the reader moves it: the row opened last stays where it is
                stick = dy >= 0 && !rv.canScrollVertically(1)
                showJump()
            }

            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (!toEnd) return
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    toEnd = false // the reader took the list
                } else if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    // While a reply runs, rows come below the one it was going to: the rest at once.
                    toEnd = false
                    if (stick && rv.canScrollVertically(1)) scrollToEnd(false)
                }
            }
        })
        list.addOnLayoutChangeListener { _, _, _, _, b, _, _, _, ob ->
            if (stick && b != ob && list.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) scrollToEnd(false)
        }
        emptyView = ScrollView(app)
        emptyView.isFillViewport = true
        jump = Ui.column(app)
        jump.visibility = View.GONE
        host = if (immersive) Glass.Host(app) else null
        toTop = jumpButton(R.drawable.ic_arrow_up)
        toTop.setOnClickListener { if (app.finder.finding()) app.finder.findGo(app.finder.findAt - 1) else scrollToTop() }
        toBottom = jumpButton(R.drawable.ic_arrow_down)
        toBottom.setOnClickListener { if (app.finder.finding()) app.finder.findGo(app.finder.findAt + 1) else scrollToEnd(true) }
        jump.addView(toTop, LinearLayout.LayoutParams(dp(36), dp(36)))
        val down = LinearLayout.LayoutParams(dp(36), dp(36))
        down.topMargin = dp(10)
        jump.addView(toBottom, down)
        val tb = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        jumpLowest = dp(12)
        tb.bottomMargin = jumpLowest
        tb.rightMargin = dp(8)

        composer = null
        val main = if (immersive) buildImmersive(tb) else buildPlain(tb)
        (jump.parent as View).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.post(::placeJump) }
        return main
    }

    /** The usual layout: a bar with the title on top, the conversation, the box below it. */
    private fun buildPlain(jumpAt: FrameLayout.LayoutParams): View {
        val main = Ui.column(app)
        main.setBackgroundColor(col(R.color.bg))
        val top = Ui.row(app)
        top.setPadding(dp(6), 0, dp(6), 0)
        val menu = Ui.iconButton(app, R.drawable.ic_sidebar, 44, 20, R.color.text2)
        menu.setOnClickListener { app.frame.open() }
        top.addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(title, Ui.weight())
        if (!Themes.showTitle(app)) title.visibility = View.INVISIBLE
        val more = Ui.iconButton(app, R.drawable.ic_more, 44, 20, R.color.text2)
        more.setOnClickListener(::moreMenu)
        top.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        main.addView(top, LinearLayout.LayoutParams(-1, dp(52)))
        main.addView(banner, Ui.fill())
        Ui.margins(banner, app, 12, 4, 12, 0)
        val content = FrameLayout(app)
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        content.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        // The conversation fades out under the bar instead of being cut at its edge; at its top
        // it starts below the fade.
        list.setPadding(dp(22), dp(24), dp(22), dp(24))
        list.clipToPadding = false
        val bg = col(R.color.bg)
        val fade = View(app)
        fade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(bg, Glass.alpha(bg, 0.6f), Glass.alpha(bg, 0f)))
        content.addView(fade, FrameLayout.LayoutParams(-1, dp(28), Gravity.TOP))
        content.addView(jump, jumpAt)
        main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val box = app.box.buildComposer()
        composer = box
        main.addView(box, Ui.fill())
        return main
    }

    /**
     * The immersive layout: the conversation fills the screen, under the status bar and the
     * navigation bar; the two buttons and the box float over it as frosted glass, and the
     * conversation fades out under the status bar and the box.
     */
    private fun buildImmersive(jumpAt: FrameLayout.LayoutParams): View {
        val host = host!!
        host.setBackgroundColor(col(R.color.bg))
        val behind = FrameLayout(app)
        behind.addView(list, FrameLayout.LayoutParams(-1, -1))
        behind.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        list.clipToPadding = false
        emptyView.clipToPadding = false
        host.source = behind
        host.addView(behind, FrameLayout.LayoutParams(-1, -1))
        // Anything that moves behind the glass draws the glass again.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = host.invalidate()
        })
        emptyView.setOnScrollChangeListener { _, _, _, _, _ -> host.invalidate() }

        val bg = col(R.color.bg)
        fadeTop = View(app)
        fadeTop.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Glass.alpha(bg, 0.72f), Glass.alpha(bg, 0.45f), Glass.alpha(bg, 0f)))
        host.addView(fadeTop, FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP))
        fadeBottom = View(app)
        fadeBottom.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Glass.alpha(bg, 0f), Glass.alpha(bg, 0.55f), Glass.alpha(bg, 0.85f)))
        host.addView(fadeBottom, FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM))

        topButtons = Ui.row(app)
        topButtons.setPadding(dp(14), 0, dp(14), 0)
        val menu = glassButton(R.drawable.ic_sidebar, 21)
        menu.setOnClickListener { app.frame.open() }
        topButtons.addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)))
        topButtons.addView(View(app), LinearLayout.LayoutParams(0, 1, 1f))
        if (Themes.showTitle(app)) {
            val pill = Glass.Pane(app, host, -1f, col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
                Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border))
            pill.gravity = Gravity.CENTER
            pill.setPadding(dp(16), 0, dp(16), 0)
            title.setPadding(0, 0, 0, 0)
            title.maxWidth = (app.resources.displayMetrics.widthPixels * 0.55f).toInt()
            pill.addView(title)
            topButtons.addView(pill, LinearLayout.LayoutParams(-2, dp(44)))
            topButtons.addView(View(app), LinearLayout.LayoutParams(0, 1, 1f))
        }
        val more = glassButton(R.drawable.ic_more, 21)
        more.setOnClickListener(::moreMenu)
        topButtons.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
        host.addView(topButtons, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        host.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        host.addView(jump, jumpAt)
        val box = app.box.buildComposer()
        composer = box
        host.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // The conversation keeps clear of the box as it grows (more lines, files, the command menu).
        box.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) placeImmersive()
        }
        host.setOnApplyWindowInsetsListener { _, insets ->
            insetTop = insets.systemWindowInsetTop
            insetBottom = insets.systemWindowInsetBottom
            keyboard = insetBottom > app.resources.displayMetrics.heightPixels / 6
            if (Build.VERSION.SDK_INT >= 30) {
                insetBottom = max(insetBottom, insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.navigationBars()).bottom)
                keyboard = insets.isVisible(WindowInsets.Type.ime())
            }
            placeImmersive()
            insets
        }
        placeImmersive()
        return host
    }

    private fun glassButton(icon: Int, iconDp: Int): Glass.Pane {
        val b = Glass.Pane(app, host!!, -1f, col(R.color.bg), Glass.alpha(col(R.color.surface), 0.55f),
            Glass.alpha(col(R.color.surface), 0.94f), col(R.color.border))
        b.gravity = Gravity.CENTER
        b.addView(Ui.icon(app, icon, iconDp, R.color.text2))
        return b
    }

    /** Places what floats, and keeps the conversation clear of it, for the bars' sizes now. */
    fun placeImmersive() {
        val composer = composer
        if (!immersive || composer == null) return
        // The buttons just under the status bar; the box where the usual layout has it, as far above the
        // navigation bar (none, with gestures), or just above the keyboard. Not as far from the bottom as
        // the buttons from the top: a tall status bar put it higher than in the usual layout.
        val below = insetBottom + dp(if (keyboard) 8 else 16)
        composer.setPadding(dp(16), dp(4), dp(16), below)
        app.drawer.drawerPanel?.setPadding(0, insetTop, 0, if (keyboard) 0 else insetBottom)
        (topButtons.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop
        (banner.layoutParams as FrameLayout.LayoutParams).setMargins(dp(12), insetTop + dp(52), dp(12), 0)
        val box = if (composer.height > 0) composer.height else below + dp(58)
        fadeTop.layoutParams.height = insetTop + dp(68)
        fadeBottom.layoutParams.height = box + dp(24)
        jumpLowest = box + dp(8)
        placeJump()
        val top = insetTop + dp(50)
        if (list.paddingTop != top || list.paddingBottom != box + dp(12)) {
            list.setPadding(dp(22), top, dp(22), box + dp(12))
            emptyView.setPadding(0, top, 0, box)
            if (stick) scrollToEnd(false)
        }
        host?.requestLayout()
    }

    /** The window for the layout: edge to edge (immersive) or below the status bar. */
    private fun applyWindow() {
        val w = app.window
        Themes.window(w, app)
        val d = w.decorView
        val edge = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val flags = d.systemUiVisibility
        d.systemUiVisibility = if (immersive) flags or edge else flags and edge.inv()
        if (immersive) {
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                w.isStatusBarContrastEnforced = false
                w.isNavigationBarContrastEnforced = false
            }
        }
        d.requestApplyInsets()
    }

    private fun jumpButton(icon: Int): View {
        if (immersive) return glassButton(icon, 18)
        val b = Ui.iconButton(app, icon, 36, 18, R.color.text2)
        b.background = Ui.press(app, Ui.round(col(R.color.surface), dp(18), dp(1), col(R.color.border)), dp(18))
        b.elevation = dp(3).toFloat()
        return b
    }

    /** The jumps sit where a right thumb rests, two thirds down the screen; never down on the box. */
    private fun placeJump() {
        val parent = jump.parent as View?
        val screen = app.window.decorView.height
        if (parent == null || parent.height == 0 || screen == 0) return
        val at = IntArray(2)
        parent.getLocationInWindow(at)
        val m = max(jumpLowest, at[1] + parent.height - (screen * 0.67f).roundToInt())
        val lp = jump.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin == m) return
        lp.bottomMargin = m
        jump.layoutParams = lp
    }

    private val hideJump = Runnable {
        jump.animate().alpha(0f).setDuration(200).withEndAction { jump.visibility = View.GONE }.start()
    }

    /** Shows the jumps that lead somewhere; they go again a moment after the scrolling stops. */
    fun showJump() {
        // While /find looks for something they go between the places found, and stay.
        val finding = app.finder.finding()
        toTop.visibility = if (finding || list.canScrollVertically(-1)) View.VISIBLE else View.GONE
        toBottom.visibility = if (finding || list.canScrollVertically(1)) View.VISIBLE else View.GONE
        ui.removeCallbacks(hideJump)
        if (toTop.visibility != View.VISIBLE && toBottom.visibility != View.VISIBLE) {
            jump.visibility = View.GONE
            return
        }
        jump.animate().cancel()
        if (jump.visibility != View.VISIBLE) {
            jump.alpha = 0f
            jump.visibility = View.VISIBLE
        }
        jump.animate().alpha(1f).setDuration(120).start()
        if (!finding) ui.postDelayed(hideJump, 1500)
    }

    private fun scrollToTop() {
        stick = false
        adapter.opened = null
        list.stopScroll()
        val lm = list.layoutManager as LinearLayoutManager
        // From far down, most of the way at once: a smooth scroll through it all takes too long.
        if (lm.findFirstVisibleItemPosition() > 6) lm.scrollToPosition(6)
        list.post { list.smoothScrollToPosition(0) }
    }

    fun scrollToEnd(smooth: Boolean) {
        stick = true
        list.post {
            // The reader may have taken the list meanwhile: never pull it back down.
            if (!stick || list.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) return@post
            val count = adapter.itemCount
            if (count == 0) return@post
            val lm = list.layoutManager as LinearLayoutManager
            if (!smooth) {
                // A jump down under way, overtaken by new rows: there at once.
                if (toEnd) {
                    toEnd = false
                    list.stopScroll()
                }
                lm.scrollToPositionWithOffset(count - 1, -100000)
                return@post
            }
            // From far up, most of the way at once (as to the top): a smooth scroll through it all takes too long.
            if (lm.findLastVisibleItemPosition() < count - 7) lm.scrollToPosition(count - 7)
            list.post {
                val last = adapter.itemCount - 1
                if (last < 0) return@post
                toEnd = true
                // The last row on screen already: down by what is left of it. Brought into view, a row
                // taller than the screen would be aligned by its top, up first and then down at once.
                val lastRow = lm.findViewByPosition(last)
                if (lastRow != null) list.smoothScrollBy(0, max(0,
                    lm.getDecoratedBottom(lastRow) + (lastRow.layoutParams as RecyclerView.LayoutParams).bottomMargin -
                        (list.height - list.paddingBottom)))
                else list.smoothScrollToPosition(last)
                // Nothing to go (the end in sight already, or stopped before its first frame): no jump under way.
                list.postOnAnimation { if (list.scrollState == RecyclerView.SCROLL_STATE_IDLE) toEnd = false }
            }
        }
    }

    /** Shows the open conversation's title above, and the box below as it now stands. */
    fun updateChrome() {
        title.text = app.current.title.ifEmpty { "新对话" }
        app.box.update()
    }

    fun fillEmpty() {
        val link = app.link
        val folder = app.current.cwd.ifEmpty { claude.defaultCwd }
        val shown = link.setup + "|" + link.installStatus + "|" + link.installError + "|" + link.linked + "|" + folder + "|" +
            System.identityHashCode(app.current) + "|" + System.identityHashCode(emptyView)
        if (shown == emptyShown && emptyView.childCount > 0) return
        emptyShown = shown
        val box = Ui.column(app)
        box.gravity = Gravity.CENTER
        box.setPadding(dp(22), dp(20), dp(22), dp(40))
        if (link.setup != null) buildSetup(box) else buildHero(box)
        emptyView.removeAllViews()
        emptyView.addView(box, ViewGroup.LayoutParams(-1, -1))
    }

    private fun buildHero(box: LinearLayout) {
        // The crab, in the style Settings has for it: it plays one of its moves when the greeting comes up, and another when tapped.
        val crab = Crab(app, Themes.crabStyle(app), false, 5)
        box.addView(crab, LinearLayout.LayoutParams(-2, -2))
        // The folder it will work in, which can be changed only until the first message: tapped, it is picked (/folder).
        val pick = app.link.linked && app.current.sessionId == null
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = if (h < 5) "夜深了" else if (h < 11) "早上好" else if (h < 13) "中午好" else if (h < 18) "下午好" else "晚上好"
        val big = Ui.text(app, hello, 30, R.color.text)
        big.typeface = Ui.SERIF
        big.setPadding(0, dp(4), 0, dp(if (pick) 2 else 10)) // the line under it is 8dp in its own round
        big.gravity = Gravity.CENTER
        box.addView(big, Ui.fill())
        val folder = Ui.base(app.current.cwd.ifEmpty { claude.defaultCwd })
        val sub = SpannableStringBuilder()
        if (app.link.linked) {
            sub.append("在 ")
            val s = sub.length
            sub.append(folder.ifEmpty { "~" })
            sub.setSpan(StyleSpan(Typeface.BOLD), s, sub.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (pick) sub.setSpan(ForegroundColorSpan(col(R.color.accent)), s, sub.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sub.append(" 里，想做点什么？")
        } else {
            sub.append("正在启动 Claude Code…")
        }
        val p = Ui.text(app, sub, 15, R.color.text2)
        p.gravity = Gravity.CENTER
        if (pick) {
            p.setPadding(dp(16), dp(8), dp(16), dp(8))
            p.background = Ui.press(app, null, dp(20))
            p.setOnClickListener { app.pickers.folderSheet(null) }
        }
        box.addView(p, Ui.wrap())
        // A new conversation's: one after another up into place. Built again on every render, it stays still then.
        if (heroFor === app.current) return
        heroFor = app.current
        crab.enter()
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(40L * i).setDuration(Motion.PAGE)
                .setInterpolator(Motion.EMPHASIZED).start()
        }
    }

    private fun buildSetup(box: LinearLayout) {
        val link = app.link
        box.gravity = Gravity.START
        val mark = ImageView(app)
        mark.setImageResource(R.drawable.mascot)
        box.addView(mark, LinearLayout.LayoutParams(dp(54), dp(33)))
        val busy = link.setup == "install"
        val failed = link.setup == "installFailed"
        val h = Ui.text(app, if (busy) "正在准备运行环境" else if (failed) "运行环境没准备好" else "Claude Code 没能启动", 26, R.color.text)
        h.typeface = Ui.SERIF
        h.setPadding(0, dp(16), 0, dp(8))
        box.addView(h)
        val why = when {
            busy -> link.installStatus.ifEmpty { "…" }
            failed -> link.installError
            else -> "后台 30 秒内没有起来。可以重试，或者在设置里看运行日志。"
        }
        val p = Ui.text(app, why, 15, R.color.text2)
        p.setLineSpacing(0f, 1.25f)
        p.setPadding(0, 0, 0, dp(12))
        box.addView(p)
        if (busy) return
        val actions = Ui.row(app)
        val retry = sheets.button("重试", true) { link.connect(true) }
        retry.background = Ui.press(app, Ui.round(col(R.color.text), dp(10)), dp(10))
        retry.setTextColor(col(R.color.bg))
        actions.addView(retry)
        val logs = sheets.button("运行日志", false) { app.appPage.logSheet() }
        actions.addView(logs)
        Ui.margins(logs, app, 8, 0, 0, 0)
        box.addView(actions)
        Ui.margins(actions, app, 0, 18, 0, 0)
    }

    /**
     * The top bar's menu, as the claude.ai app's card: what the app adds (the terminal, searching the
     * conversation) rather than more buttons.
     */
    private fun moreMenu(anchor: View) {
        val shade = dp(24) // room around the card for its whole shadow (less, and the shadow is cut square)
        val room = FrameLayout(app)
        room.setPadding(shade, shade, shade, shade)
        val card = sheets.menuCard()
        room.addView(card, FrameLayout.LayoutParams(-2, -2))
        val pop = PopupWindow(room, -2, -2, true)
        pop.setBackgroundDrawable(ColorDrawable(0))
        pop.isOutsideTouchable = true
        pop.elevation = 0f // the window's own shadow is square: only the card's, round, is drawn
        pop.isClippingEnabled = false // the shadow's room may pass the screen's edge
        sheets.menuItem(card, pop, R.drawable.ic_chart, "用量", null) { app.usage.show() }
        sheets.menuItem(card, pop, R.drawable.ic_folder, "文件", null) { app.files.open() }
        sheets.menuItem(card, pop, R.drawable.ic_terminal, "终端", null) { app.openTerminal() }
        sheets.menuItem(card, pop, R.drawable.ic_search, "搜索对话", null) {
            app.box.input.setText("/find ")
            app.box.input.setSelection(app.box.input.length())
            app.box.input.requestFocus()
            app.box.showKeyboard()
        }
        // As wide as its rows need; its right edge near the screen's, just under the button; it grows out of that corner.
        room.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = room.measuredWidth - 2 * shade
        pop.width = width + 2 * shade
        val at = IntArray(2)
        anchor.getLocationOnScreen(at)
        val screenW = app.resources.displayMetrics.widthPixels
        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, screenW - dp(6) - width - shade, at[1] + anchor.height + dp(2) - shade)
        card.pivotX = width.toFloat()
        card.pivotY = 0f
        card.alpha = 0f
        card.scaleX = 0.8f
        card.scaleY = 0.8f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
            .setInterpolator(Motion.EMPHASIZED).start()
    }
}
