package com.jeep.claudecode;

import android.view.View;

/** The link to the bridge, and what comes before it: unpacking the userland the bridge runs in. */
final class Link extends Part {
    boolean linked, everLinked, connecting, resumed;
    /** When the bridge in use started; another value means a new bridge and lost turns. */
    private double bridgeStarted;
    /** Why the bridge cannot be reached ("install" while the userland unpacks), or null. */
    String setup;
    boolean installing;
    /** How far preparing the userland is (downloading, unpacking). */
    String installStatus = "";
    String installError = "";
    private int connectAttempt;

    Link(MainActivity app) { super(app); }

    /** The app came to the front: the link is made if it is not there, or the open conversation is looked at again. */
    void resume() {
        resumed = true;
        if (!linked && !connecting && setup == null) connect(false);
        else if (linked) app.sender.opened(app.current);
    }

    void pause() { resumed = false; }

    /** The link stayed down: start the bridge again if it is gone (the service also restarts one that died). */
    private final Runnable recover = () -> {
        if (!linked && !connecting && resumed) connect(false);
    };

    /** The bridge says its link went up or down. */
    void changed(boolean up) {
        if (up == linked) return;
        if (up) afterLink();
        else {
            linked = false;
            app.drawer.updateLink();
            app.render();
            ui.removeCallbacks(recover);
            ui.postDelayed(recover, 2000);
        }
    }

    /** Connects to the bridge; a restart is never refused, and only the latest attempt counts. */
    void connect(boolean restart) {
        if (connecting && !restart) return;
        connecting = true;
        int attempt = ++connectAttempt;
        app.drawer.updateLink();
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
                app.renderNow();
            }
        });
    }

    /** Unpacks the userland (first launch, or an update that brings a new one), then connects. */
    private void install() {
        if (installing) return;
        installing = true;
        setup = "install";
        installStatus = "";
        linked = false;
        app.renderNow();
        work.execute(() -> {
            String error = null;
            try {
                userland.install(p -> ui.post(() -> {
                    installStatus = p;
                    if ("install".equals(setup) && app.screen.emptyView.getVisibility() == View.VISIBLE) app.screen.fillEmpty();
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
                    app.renderNow();
                    return;
                }
                setup = null;
                connect(false);
                if (!prefs.getBoolean("askedStorage", false)) {
                    prefs.edit().putBoolean("askedStorage", true).apply();
                    app.requestStorage();
                }
            });
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
                for (Chat ch : app.chats.values()) {
                    if (ch.running) app.sender.fail(ch, "后台重启了", "这一轮被中断，发消息即可接着这个对话继续。");
                    ch.unhand(); // what the old one held waits here again
                }
            }
            bridgeStarted = started;
            claude.home = data.optString("home");
            claude.defaultCwd = data.optString("defaultCwd");
            for (Chat ch : app.chats.values()) if (ch.cwd == null || ch.cwd.isEmpty()) ch.cwd = claude.cwd.isEmpty() ? claude.defaultCwd : claude.cwd;
            app.renderNow();
        });
        bridge.get("/config", (status, data) -> {
            if (status != 200) return;
            claude.applyConfig(data);
            if (app.current.messages.isEmpty()) app.renderNow();
        });
        app.renderNow();
        app.drawer.loadSessions();
        app.loadHistory(app.current);
        if (app.appPage.keepAlive == AppPage.UNKNOWN) app.appPage.keepAlive(false); // once a run: the userland is there now
    }
}
