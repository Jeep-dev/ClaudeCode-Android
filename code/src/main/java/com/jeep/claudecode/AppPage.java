package com.jeep.claudecode;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

/** Settings → 存储, 后台 and 关于: what is taken up, keeping the app running, the version and the logs. */
final class AppPage extends Part {
    /** Sizes in the userland, measured when the page opens: conversations, work, temporary files, all. */
    private long[] storage;
    /** How keeping alive was found (KeepAlive's states), UNKNOWN until checked in this run. */
    static final int UNKNOWN = -1;
    int keepAlive = UNKNOWN;
    private boolean keepChecking;

    AppPage(MainActivity app) { super(app); }

    // ---- 存储 ----

    void measureStorage() {
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
                if ("storage".equals(app.settings.settingsAt)) app.settings.fillSettings(app.settings.launchCache);
            });
        });
    }

    void fillStorage(LinearLayout box) {
        long[] s = storage;
        LinearLayout sizes = app.settings.card(box);
        sheets.option(sizes, R.drawable.ic_compose, "对话记录", s == null ? "…" : Ui.size(s[0]), false, null);
        sheets.option(sizes, R.drawable.ic_folder, "工作目录", s == null ? "…" : Ui.size(s[1]), false, null);
        sheets.option(sizes, R.drawable.ic_file, "临时文件", s == null ? "…" : Ui.size(s[2]), false, null);
        sheets.option(sizes, R.drawable.ic_terminal, "运行环境", s == null ? "…" : Ui.size(Math.max(0, s[3] - s[0] - s[1] - s[2])), false, null);
        LinearLayout clean = app.settings.card(box);
        Ui.margins(clean, app, 0, 12, 0, 0);
        sheets.option(clean, R.drawable.ic_trash, "清理临时文件", null, false, () -> confirm("清理临时文件", "删除 /tmp 和 ~/.cache 里的文件", false, () -> {
            clear(userland.file("/tmp"));
            clear(userland.file(Userland.HOME + "/.cache"));
        }));
        sheets.option(clean, R.drawable.ic_trash, "清空对话记录", null, true, () -> confirm("清空对话记录", "所有对话都会被删除，不能恢复", true, () -> {
            clear(userland.file(Userland.HOME + "/.claude/projects"));
            ui.post(() -> {
                app.drawer.keepSessions(new JSONArray());
                app.drawer.loadSessions();
            });
        }));
    }

    private void clear(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) Userland.delete(f);
    }

    /** Asks before doing something that cannot be undone; does it off the main thread. */
    private void confirm(String title, String what, boolean danger, Runnable action) {
        Dialog sheet = sheets.sheet(title, what);
        LinearLayout actions = Ui.row(app);
        TextView cancel = sheets.button("取消", false, v -> sheet.dismiss());
        TextView ok = sheets.button("确定", true, v -> {
            sheet.dismiss();
            work.execute(() -> {
                action.run();
                ui.post(() -> { toast("完成"); measureStorage(); });
            });
        });
        if (danger) ok.setBackground(Ui.press(app, Ui.round(col(R.color.danger), dp(10)), dp(10)));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(42), 1));
        Ui.margins(ok, app, 10, 0, 0, 0);
        sheets.content(sheet).addView(actions, Ui.fill());
        Ui.margins(actions, app, 0, 14, 0, 0);
        sheet.show();
    }

    // ---- 后台 ----

    /** Checks keeping alive and sets what is not set, through rish; asked: by a tap, which is told how it went. */
    void keepAlive(boolean asked) {
        if (keepChecking) return;
        keepChecking = true;
        if ("keep".equals(app.settings.settingsAt)) app.settings.fillSettings(app.settings.launchCache);
        String pkg = app.getPackageName();
        // A thread of its own: rish may take long, and work reads the files being attached.
        new Thread(() -> {
            int state = KeepAlive.ensure(userland, pkg);
            ui.post(() -> {
                keepChecking = false;
                keepAlive = state;
                if (state == KeepAlive.SET) toast("已开启后台保活");
                else if (asked) toast(state == KeepAlive.ON ? "后台保活已经开着" : state == KeepAlive.OFF
                        ? "没能开启后台保活" : "rish 没有运行：请确认 Shizuku 在运行并已授权本应用");
                if ("keep".equals(app.settings.settingsAt)) app.settings.fillSettings(app.settings.launchCache);
            });
        }, "keep-alive").start();
    }

    void fillKeep(LinearLayout box) {
        LinearLayout keep = app.settings.card(box);
        if (!app.hasStorage()) sheets.option(keep, R.drawable.ic_folder, "允许访问存储", null, false, app::requestStorage);
        android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(app.POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(app.getPackageName())) {
            sheets.option(keep, R.drawable.ic_check, "关闭电池优化", null, false, () -> {
                try {
                    app.startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + app.getPackageName())));
                } catch (RuntimeException e) {
                    toast("系统不支持这个设置");
                }
            });
        }
        sheets.option(keep, R.drawable.ic_gear, "应用信息", null, false, () -> {
            try {
                app.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.getPackageName())));
            } catch (RuntimeException e) {
                toast("打不开应用信息");
            }
        });
        // Android 12+ kills an app's child processes beyond a limit, or busy ones in the background:
        // the app checks and sets that itself (KeepAlive); a tap checks again.
        String state = keepChecking ? "检查中…" : keepAlive == UNKNOWN ? "未检查"
                : keepAlive == KeepAlive.ON || keepAlive == KeepAlive.SET ? "已开启"
                : keepAlive == KeepAlive.OFF ? "没能开启，点此重试" : "需要 Shizuku，点此重试";
        sheets.option(keep, R.drawable.ic_check, "后台保活", state, false, () -> keepAlive(true));
    }

    // ---- 关于 ----

    private String appVersion() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    void fillAbout(LinearLayout box) {
        LinearLayout about = app.settings.card(box);
        sheets.option(about, 0, "应用", "v" + appVersion(), false, null);
        sheets.option(about, 0, "Claude Code", claude.claudeVersion, false, null);
        LinearLayout tools = app.settings.card(box);
        Ui.margins(tools, app, 0, 12, 0, 0);
        sheets.option(tools, R.drawable.ic_list, "日志", null, false, this::logSheet);
        sheets.option(tools, R.drawable.ic_terminal, "终端", null, false, () -> app.openTerminal());
    }

    void logSheet() {
        String bridgeLog = Userland.tail(userland.file(Userland.HOME + "/.ccapp/bridge.log"), 16_000);
        String runLog = Userland.tail(userland.log, 8_000);
        String text = "== bridge.log ==\n" + (bridgeLog.isEmpty() ? "（空）\n" : bridgeLog) + "\n== 启动 ==\n" + (runLog.isEmpty() ? "（空）\n" : runLog);
        Dialog sheet = sheets.sheet("日志", null);
        TextView t = Ui.text(app, text, 11.5f, R.color.text);
        t.setTypeface(Ui.MONO);
        t.setTextIsSelectable(true);
        sheets.content(sheet).addView(t, Ui.fill());
        TextView cp = sheets.button("复制全部", false, v -> app.copy(text));
        sheets.content(sheet).addView(cp);
        Ui.margins(cp, app, 0, 12, 0, 0);
        sheet.show();
        ScrollView scroll = (ScrollView) sheets.content(sheet).getParent();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
