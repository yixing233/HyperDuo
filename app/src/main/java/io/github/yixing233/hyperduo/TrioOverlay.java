package io.github.yixing233.hyperduo;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

/**
 * Draws the trio glyph in a window of its own, so it is no longer bounded by the
 * status bar's own height.
 *
 * <p>The status bar is a real window with a real height: on this device it is
 * {@code ty=STATUS_BAR}, {@code (0,0)(fillx121)} - 121px, about 43dp, i.e.
 * {@code status_bar_height}. The glyph wants roughly 55dp drawn whole, and
 * everything past that rectangle is clipped by the window itself, not by the
 * view tree: enlarging the icon view's layout, handing it a taller
 * {@code LayoutParams}, or turning {@code clipChildren} off on the way up
 * cannot recover a single pixel of it.
 *
 * <p>Nothing about the status bar is changed here. A second window is added
 * instead, using {@code TYPE_STATUS_BAR_ADDITIONAL} - the type the platform
 * keeps for extra status bar surfaces - sized to the glyph, never touchable,
 * placed above the bar. The left half of the status bar - and anything else the
 * user has arranged there - shares neither layout nor window with it.
 *
 * <p>The window is pinned to the top of the screen: {@code y = 0} is the hard
 * edge, so the extra height can only grow downwards. Side by side with the bar,
 * the glyph therefore reads a few dp low - the cost of asking for more height
 * than the bar has, not a placement bug.
 *
 * <p>Every failure path hands the caller back to drawing in the host view: an
 * overlay that cannot be attached must never leave a glyph-shaped hole where
 * the battery icon used to be.
 */
final class TrioOverlay {

    /** How tall the glyph is drawn, in dp. The bar itself gives it about 43dp. */
    static final int GLYPH_HEIGHT_DP = 55;

    /** Window title, only ever visible in {@code dumpsys window}. */
    private static final String TITLE = "HyperDuo glyph";

    /** How often the window re-checks the bar it belongs to, in ms. */
    private static final long WATCH_INTERVAL_MS = 100L;

    /**
     * How long a host counts as "on screen" after its last draw, in ms. Long
     * enough to cover a bar that simply has nothing new to draw, short enough that
     * a bar hidden behind a full-screen app stops counting almost at once.
     */
    private static final long DRAW_GRACE_MS = 2000L;

    /** {@code TYPE_APPLICATION_OVERLAY}: the fallback when the hidden type is absent. */
    private static final int TYPE_FALLBACK = 2038;

    /** True once the half-second re-check has been posted. */
    private static volatile boolean sTicking;

    /**
     * Re-asks whether the window belongs on screen twice a second.
     *
     * <p>Every other path into that decision runs inside the host's draw pass,
     * and a bar that is standing still does not draw - so a state that changes
     * without the bar repainting (an app going full screen, the bar coming
     * back) would otherwise be noticed only when something else forced a pass.
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            // A window that is completely covered is not drawn, so a bar that
            // answers this nudge is a bar that is really on screen.
            TrioHooks.nudgeHosts();
            for (TrioOverlay overlay : LIVE.values()) {
                try {
                    overlay.sync();
                } catch (Throwable ignored) {
                    // A host that went away mid-pass is not this task's problem.
                }
            }
            BUSY_HANDLER.postDelayed(this, 500L);
        }
    };

    /** Scratch rectangle for the visible-rect test; UI thread only. */
    private static final android.graphics.Rect VISIBLE_RECT =
            new android.graphics.Rect();

    /** Last sync trace logged, so the same one is not written twice. */
    private static volatile String sLastSyncNote;

    /** True while the bar is not standing still. */
    private static volatile boolean sShadeBusy;

    /** Every signal that sets the flag arrives on the UI thread. */
    private static final android.os.Handler BUSY_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Lets the bar draw again once nothing has moved for a moment. Both signals
     * are pulses rather than states: the panel reports its height on the frames
     * it changes and the launcher reports its gesture while it runs, so a hold
     * that is refreshed by every report and expires on its own is the one shape
     * that cannot get stuck when the closing report never comes.
     */
    private static final Runnable CLEAR_BUSY = new Runnable() {
        @Override
        public void run() {
            setBusy(false, "settled");
        }
    };

    /** How long the bar stays stood down after the last report. */
    private static final long HOLD_MS = 800L;

    private static void pulse(String why) {
        setBusy(true, why);
        BUSY_HANDLER.removeCallbacks(CLEAR_BUSY);
        BUSY_HANDLER.postDelayed(CLEAR_BUSY, HOLD_MS);
    }

    /**
     * Called from the panel's own expansion step, on every frame of a drag and
     * of the fling after it. Anything above zero means the shade is moving, so
     * the window leaves on the first frame instead of after the animation.
     */
    static void onShadeHeight(float height) {
        if (height > 0.5f) {
            pulse("shade height=" + height);
        } else {
            setBusy(false, "shade closed");
        }
    }

    /**
     * Called while the launcher reports an overview (recents) gesture or the
     * animation that follows it. Recents scales what it covers, and a window of
     * its own cannot follow that, so for the duration the glyph goes back to
     * being painted by the bar itself.
     */
    static void onOverviewPulse() {
        pulse("overview");
    }

    private static void setBusy(boolean busy, String why) {
        if (busy == sShadeBusy) {
            return;
        }
        sShadeBusy = busy;
        TrioHooks.log(TrioHooks.LOG_INFO, "busy=" + busy + " (" + why
                + ") windows=" + LIVE.size());
        if (busy) {
            BUSY_HANDLER.removeCallbacks(CLEAR_BUSY);
            for (TrioOverlay overlay : LIVE.values()) {
                overlay.hideNow();
            }
        } else {
            for (TrioOverlay overlay : LIVE.values()) {
                overlay.showAgain();
            }
        }
        // Either way the row has to draw again: with the window gone it paints
        // the glyph itself, and with the window back it steps aside for it.
        TrioHooks.invalidateHosts();
    }

    /**
     * Takes the glyph off the screen now, without waiting for the host to draw
     * again.
     *
     * <p>The window itself stays up and stays visible to the window manager: it
     * is the glyph inside it that is made transparent. Hiding the window's only
     * view would make the window itself report as not visible, and putting it
     * back would then have to wait for a draw pass that a bar standing still
     * never performs - the state the glyph exists for.
     */
    void hideNow() {
        shown = false;
        glyph.setAlpha(0f);
    }

    /**
     * Puts the window back without waiting for the host to draw again.
     *
     * <p>A bar that is standing still does not draw, so waiting for its next
     * draw pass to bring the window back would leave it hidden for as long as
     * nothing changed on screen - which is exactly the state the glyph is for.
     * The window is where the last sync left it and the host has not moved, so
     * it can be shown again here; the same visibility rules are re-checked so a
     * bar that is off screen or covered keeps it hidden.
     */
    void showAgain() {
        if (shown) {
            return;
        }
        // The window's own visibility is not asked about here: the window being
        // hidden is the very thing this call is undoing, and its visibility
        // follows the views inside it. Only the view's own state is read.
        final boolean hostVisible = host.isShown() && effectiveAlpha(host) > 0f;
        host.getLocationOnScreen(location);
        final DisplayMetrics metrics = host.getResources().getDisplayMetrics();
        final boolean onScreen = location[0] + host.getWidth() > 0
                && location[0] < metrics.widthPixels
                && location[1] + host.getHeight() > 0
                && location[1] < metrics.heightPixels;
        TrioHooks.log(TrioHooks.LOG_INFO, "showAgain: host=" + hostVisible
                + " onScreen=" + onScreen + " size=" + host.getWidth() + "x"
                + host.getHeight() + " at " + location[0] + "," + location[1]);
        if (!hostVisible || !onScreen) {
            return;
        }
        shown = true;
        glyph.setAlpha(1f);
    }

    private static final WeakHashMap<View, TrioOverlay> LIVE =
            new WeakHashMap<View, TrioOverlay>();

    private static int sWindowType;

    /** PRIVATE_FLAG_TRUSTED_OVERLAY, or 0 on a build that has no such flag. */
    private static int sTrustedOverlay;

    /**
     * The one host whose window is live.
     *
     * <p>The bar can hold more than one battery view - MIUI's icon row is not the
     * only place the same class is inflated - and two windows on the same spot
     * draw the glyph twice, a pixel apart, which reads as a blur. The first host
     * to ask keeps the window; the others stay on the in-view path.
     */
    private static volatile View sOwner;

    /** When a host last drew, and which host it was. */
    private static volatile long sLastDraw;
    private static volatile View sLastDrawHost;

    private final View host;
    private final TrioState state;
    private final GlyphView glyph;
    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final int[] location = new int[2];

    /** {@code true} once {@code addView} has succeeded. */
    private boolean attached;
    /** Last applied visibility, so a steady frame does not touch the window. */
    private boolean shown = true;

    /**
     * Keeps the window honest between draws of the host.
     *
     * <p>sync() only ran when the host drew, and a bar that has just been hidden -
     * a full-screen app, an immersive game, a collapsed shade - stops drawing
     * first: the window would keep the glyph on screen over whatever is
     * underneath until something else happened to repaint the bar. This asks the
     * host directly, a few times a second, and hides the window the moment the
     * bar is gone.
     */
    private final Runnable mWatch = new Runnable() {
        @Override
        public void run() {
            if (!attached) {
                return;
            }
            sync();
            glyph.postDelayed(this, WATCH_INTERVAL_MS);
        }
    };

    /**
     * The last reason the window was not used. The decision runs on every frame
     * of the host, so only a change of reason is worth a log line - but a change
     * has to produce one, or a switch that quietly does nothing looks exactly
     * like a switch that is not wired up.
     */
    private static String sLastNote;

    private TrioOverlay(View host, TrioState state) {
        this.host = host;
        this.state = state;
        final Context context = host.getContext();
        this.windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        this.params = new WindowManager.LayoutParams();
        params.type = windowType();
        params.gravity = Gravity.TOP | Gravity.LEFT;
        // Sized from the host on every sync - see sync(). One pixel keeps the
        // first frame honest until that happens.
        params.width = 1;
        params.height = 1;
        params.format = PixelFormat.TRANSLUCENT;
        // Not focusable and not touchable: the window exists to be looked at, so
        // it must never take a touch that belongs to what is below it. NO_LIMITS
        // keeps the platform from insetting a window that is deliberately flush
        // with the top edge, and LAYOUT_IN_SCREEN makes x/y screen coordinates.
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        // Marked trusted so the platform does not read this window as a
        // tap-jacking overlay - see trustedOverlayFlag(). privateFlags is a
        // hidden field, so it is set reflectively like the flag itself.
        final int trusted = trustedOverlayFlag();
        if (trusted != 0) {
            try {
                final Field field = WindowManager.LayoutParams.class
                        .getField("privateFlags");
                field.setInt(params, field.getInt(params) | trusted);
            } catch (Throwable ignored) {
                // Hidden field: nothing to mark on this build.
            }
        }
        params.setTitle(TITLE);
        params.alpha = 1f;
        params.windowAnimations = 0;

        this.glyph = new GlyphView(context);
        this.glyph.setVisibility(View.VISIBLE);
    }

    /**
     * The overlay that should be used for {@code host}, or {@code null} when the
     * caller must keep drawing in the host view.
     *
     * <p>Called from the host's draw pass, so it may create and attach the window
     * - both are cheap and idempotent - but it must never throw.
     */
    static TrioOverlay active(View host, TrioState state) {
        final boolean cfg = TrioConfig.get().overlayGlyph;
        final boolean statusBar = TrioHooks.isStatusBarHost(host);
        if (host == null || state == null || !cfg || !statusBar) {
            // Either the switch just went off or this host is not the status
            // bar's. A window this host already owns must not survive that: it
            // would keep drawing a second glyph next to the one the host view
            // goes back to painting.
            note(cfg, statusBar, state != null, host != null, host);
            release(host);
            return null;
        }
        final View owner = sOwner;
        if (owner != null && owner != host) {
            if (owner.isAttachedToWindow() && owner.isShown()) {
                // One window per bar: MIUI inflates more than one battery view
                // into the same row, and two windows in the same spot draw the
                // glyph twice.
                release(host);
                return null;
            }
            // The owner has left the tree - MIUI rebuilds the bar's row on its
            // own schedule - and the window went with it. Holding the slot for a
            // view that is gone is what left the glyph missing for good: no host
            // could ever take the window over, so every one of them fell back to
            // drawing into a row that had been told to stay blank.
            release(owner);
        }
        sOwner = host;
        TrioOverlay overlay = LIVE.get(host);
        if (overlay == null) {
            try {
                overlay = new TrioOverlay(host, state);
            } catch (Throwable t) {
                TrioHooks.log(TrioHooks.LOG_WARN, "overlay: cannot be built: " + t);
                return null;
            }
            LIVE.put(host, overlay);
        }
        if (!overlay.attach()) {
            LIVE.remove(host);
            if (sOwner == host) {
                sOwner = null;
            }
            return null;
        }
        return overlay;
    }

    /** Logs a changed reason for keeping the glyph in the host view. */
    private static void note(boolean cfg, boolean statusBar, boolean hasState, boolean hasHost,
                             View host) {
        final Object container = TrioHooks.statusIconContainer();
        final String note = "overlay: in-view (cfg=" + cfg + " statusBar=" + statusBar
                + " state=" + hasState + " host=" + hasHost
                + " container=" + (container != null)
                + " root=" + (container instanceof View && host != null
                        && host.getRootView() == ((View) container).getRootView())
                + ") chain=" + chainOf(host);
        if (!note.equals(sLastNote)) {
            sLastNote = note;
            TrioHooks.log(TrioHooks.LOG_INFO, note);
        }
    }

    /**
     * The alpha a view really shows at: its own, times every ancestor's.
     *
     * <p>MIUI fades the bar out for a full-screen app by fading the row's
     * container, not the battery view inside it, so asking the host alone is
     * asking the wrong view - it still reports itself fully opaque while
     * nothing of it is on screen.
     */
    private static float effectiveAlpha(View view) {
        float alpha = 1f;
        for (View current = view; current != null; ) {
            alpha *= current.getAlpha();
            if (alpha <= 0f) {
                return 0f;
            }
            final ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
        }
        return alpha;
    }

    /** The alpha of the window a view lives in - not the view's own. */
    private static float windowAlpha(View view) {
        if (view == null) {
            return 1f;
        }
        final View root = view.getRootView();
        final ViewGroup.LayoutParams lp = (root == null) ? null : root.getLayoutParams();
        return (lp instanceof WindowManager.LayoutParams)
                ? ((WindowManager.LayoutParams) lp).alpha : 1f;
    }

    /**
     * Whether the system still tells this window that the status bar is visible.
     *
     * <p>A full-screen app hides the bar by asking the system to, and the first
     * place that lands is the insets - before any view in the bar has changed.
     */
    private static boolean insetsShowBar(View view) {
        if (view == null) {
            return true;
        }
        try {
            final WindowInsets insets = view.getRootWindowInsets();
            if (insets == null) {
                return true;
            }
            // Two questions, because a full-screen app answers them differently
            // on different builds: the bar can still be reported visible while
            // taking no room at all, which is exactly the state the window must
            // not open in.
            return insets.isVisible(WindowInsets.Type.statusBars())
                    && insets.getInsets(WindowInsets.Type.statusBars()).top > 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** {@code Host<Parent<...>}, truncated: the log line is a diagnosis, not a dump. */
    private static String chainOf(View host) {
        if (host == null) {
            return "-";
        }
        final StringBuilder sb = new StringBuilder(host.getClass().getSimpleName());
        for (ViewParent p = host.getParent(); p != null && sb.length() < 220;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            sb.append('<').append(p.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /**
     * Whether a view is really on screen right now: attached, in a visible window,
     * in a window that is not faded out, told by the system that the bar is
     * visible, and inside the screen's rectangle.
     */
    private static boolean onScreenNow(View view) {
        if (view == null || !view.isShown()
                || view.getWindowVisibility() != View.VISIBLE
                || view.getAlpha() <= 0f || windowAlpha(view) <= 0f) {
            return false;
        }
        // A host that drew a moment ago is on screen, whatever the insets think:
        // the shade's copy of the bar keeps drawing while the panel is open over a
        // full-screen app, and the insets still say the bar is hidden then.
        if (view == sLastDrawHost
                && SystemClock.uptimeMillis() - sLastDraw < DRAW_GRACE_MS) {
            return true;
        }
        final int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        final DisplayMetrics metrics = view.getResources().getDisplayMetrics();
        return insetsShowBar(view)
                && loc[0] + view.getWidth() > 0 && loc[0] < metrics.widthPixels
                && loc[1] + view.getHeight() > 0 && loc[1] < metrics.heightPixels;
    }

    /** Records that a host drew a frame; called from the hooked draw pass. */
    static void noteDrawn(View host) {
        sLastDraw = SystemClock.uptimeMillis();
        sLastDrawHost = host;
    }

    /**
     * True while the window is open *and* showing the glyph.
     *
     * <p>The row steps aside for the window only in that case. A window that is
     * open but hidden - the bar sliding out from under it, the panel coming down -
     * must not blank the row: that is how the glyph disappeared from both the bar
     * and the panel at once, with the window invisible and every host politely
     * clearing its canvas.
     */
    static boolean coveringBar() {
        final View owner = sOwner;
        if (owner == null) {
            return false;
        }
        final TrioOverlay overlay = LIVE.get(owner);
        return overlay != null && overlay.covering();
    }

    /** True while this window is open and painting the glyph. */
    boolean covering() {
        return attached && shown;
    }

    /** Drops the window for a host that is going away. Safe to call repeatedly. */
    static void release(View host) {
        final TrioOverlay overlay = LIVE.remove(host);
        if (overlay != null) {
            overlay.detach();
        }
        if (sOwner == host) {
            sOwner = null;
        }
    }

    /**
     * Repaints every live glyph window.
     *
     * <p>The window only repaints when something invalidates it, and while it is
     * up the bar's own view draws nothing - this module clears it - so a change
     * that reaches only the tint never repaints the glyph: the bar is re-tinted,
     * its own view is not redrawn because the module owns the pixels, and
     * sync() - which is the call that invalidates this view - is not run either.
     * The glyph then keeps the ink of the background it was last drawn on, which
     * is a white glyph on a light bar.
     *
     * <p>The tint hooks call this as the bar's ink changes, so the glyph follows
     * it. Invalidating a view that is already up to date costs nothing, so this
     * does not need to know whether anything really moved.
     */
    static void redrawAll() {
        for (TrioOverlay overlay : LIVE.values()) {
            try {
                overlay.redraw();
            } catch (Throwable ignored) {
                // A host that went away mid-pass is not this call's problem.
            }
        }
    }

    /** Invalidates this window's view. UI thread only. */
    private void redraw() {
        glyph.invalidate();
    }

    /**
     * Files {@code colour} as the ink of every live host's row.
     *
     * <p>The caller is the hook on the icon tint: it knows the colour the bar is
     * painting its own icons in, but it cannot name the row, because the icon it
     * hooks sits in the icon container rather than in a battery container. The
     * rows these windows serve are the ones to file it against.
     */
    static void fileInk(int colour) {
        for (View host : LIVE.keySet()) {
            try {
                TrioHooks.fileRowInk(TrioHooks.rowOf(host), colour);
            } catch (Throwable ignored) {
                // A host that went away mid-pass is not this call's problem.
            }
        }
    }

    /**
     * Follows the host: same visibility, same centre, and a repaint whenever the
     * host repaints. Runs inside the host's draw pass, so nothing here may
     * schedule layout on the host.
     */
    void sync() {
        if (!sTicking) {
            sTicking = true;
            BUSY_HANDLER.postDelayed(TICK, 500L);
        }
        final boolean hostVisible = host.isShown()
                && host.getWindowVisibility() == View.VISIBLE
                && effectiveAlpha(host) > 0f;
        // The bar is what is on screen, not this view: MIUI fades the bar's
        // contents during a shade pull, hides them outright when the shade is
        // open or a full-screen app takes the screen, and the window has to go
        // with it rather than outlive it. Tying visibility and alpha to the bar
        // (and not just to the host) is what keeps the two in step.
        final View bar = TrioHooks.statusBarView();
        final boolean barVisible = bar == null
                || (bar.isShown() && bar.getWindowVisibility() == View.VISIBLE
                    && effectiveAlpha(bar) > 0f);
        // A bar that hides by sliding off the screen leaves every view reporting
        // itself as shown - the window is still visible, the views are still
        // attached - so the host's rectangle on screen is part of the test. That
        // is the case a full-screen app produces: the glyph stayed behind because
        // nothing in the view tree had changed.
        host.getLocationOnScreen(location);
        final DisplayMetrics metrics = host.getResources().getDisplayMetrics();
        final boolean onScreen = location[0] + host.getWidth() > 0
                && location[0] < metrics.widthPixels
                && location[1] + host.getHeight() > 0
                && location[1] < metrics.heightPixels;
        // Two more ways a bar goes away that no view reports:
        //
        // - MIUI fades the bar's *window*, not the view, so getAlpha() stays 1
        //   while the window is already invisible. The window's own layout params
        //   are what carries that alpha.
        // - a full-screen app asks the system to hide the status bar, which shows
        //   up in the window insets before it shows up anywhere in the view tree.
        final boolean windowsOpaque = windowAlpha(host) > 0f && windowAlpha(bar) > 0f;
        final boolean drewRecently = host == sLastDrawHost
                && SystemClock.uptimeMillis() - sLastDraw < DRAW_GRACE_MS;
        final boolean insetsAgree = insetsShowBar(host);
        // The one test that sees a full-screen app: a window that is completely
        // covered reports no visible rectangle, and the status bar's window is
        // exactly that while an app like the camera owns the screen. The insets
        // cannot see it - they describe the bar as the bar sees itself.
        final boolean unclipped = host.getGlobalVisibleRect(VISIBLE_RECT);
        // Drawing wins over what the insets claim: a bar that is painting itself is
        // on screen by definition, and a full-screen app's hidden bar stops painting
        // long before anything else notices.
        // The insets have the last word: a full-screen app takes the bar away
        // without any view in it changing, and the bar's own row keeps drawing
        // through the transition, so drawing alone cannot be read as "on
        // screen". Nothing is lost when the insets say no - the row paints the
        // glyph itself in that case.
        final boolean visible = hostVisible && barVisible && windowsOpaque && onScreen
                && insetsAgree && unclipped && !sShadeBusy
                && !TrioHooks.barCovered()
                && !TrioHooks.islandHideBattery();
        if (host == sOwner) {
            final String trace = "sync: hShown=" + host.isShown()
                    + " hWin=" + host.getWindowVisibility()
                    + " hA=" + effectiveAlpha(host)
                    + " bShown=" + (bar != null && bar.isShown())
                    + " bWin=" + (bar == null ? -9 : bar.getWindowVisibility())
                    + " bA=" + (bar == null ? -9f : effectiveAlpha(bar))
                    + " bWinA=" + windowAlpha(bar)
                    + " onScreen=" + onScreen
                    + " insets=" + insetsAgree + " unclipped=" + unclipped
                    + " busy=" + sShadeBusy
                    + " island=" + TrioHooks.islandHideBattery()
                    + " -> " + visible;
            if (!trace.equals(sLastSyncNote)) {
                sLastSyncNote = trace;
                TrioHooks.log(TrioHooks.LOG_INFO, trace);
            }
        }
        if (visible != shown) {
            shown = visible;
            // Alpha, not visibility: see hideNow().
            glyph.setAlpha(visible ? 1f : 0f);
        }
        if (!visible) {
            return;
        }
        final int hostWidth = host.getWidth();
        final int hostHeight = host.getHeight();
        if (hostWidth <= 0 || hostHeight <= 0) {
            return;
        }
        // The glyph is drawn at the size MIUI gives the battery icon: the shorter
        // side of that box is what the drawing scales against, so the window is
        // the square that side implies - never a fixed figure, and never larger
        // than what the bar already had. The window exists to show the glyph
        // whole, not to blow it up.
        final int side = Math.min(hostWidth, hostHeight);
        final int width = Math.round(side * TrioGeometry.INK_W / TrioGeometry.INK_H);
        final int height = side;
        // Centred on the host, wherever the host is: this is what makes the
        // placement work in landscape, where the bar is not at the top of the
        // screen and "flush with the top edge" is simply wrong.
        // location was read above, together with the visibility test
        final int x = location[0] + hostWidth / 2 - width / 2;
        final int y = location[1] + hostHeight / 2 - height / 2;
        // Only the host's own alpha. The bar view's alpha is about the bar's own
        // window, and MIUI fades that out the moment the shade opens - following it
        // there made the window invisible exactly when the shade's copy of the
        // glyph was the one on screen.
        final float alpha = host.getAlpha();
        if (params.width != width || params.height != height
                || params.x != x || params.y != y || params.alpha != alpha) {
            params.width = width;
            params.height = height;
            params.x = x;
            params.y = y;
            params.alpha = alpha;
            try {
                windowManager.updateViewLayout(glyph, params);
            } catch (Throwable t) {
                TrioHooks.log(TrioHooks.LOG_WARN, "overlay: cannot be placed: " + t);
            }
        }
        glyph.invalidate();
    }

    private boolean attach() {
        if (attached) {
            return true;
        }
        if (windowManager == null) {
            return false;
        }
        try {
            windowManager.addView(glyph, params);
            attached = true;
            glyph.postDelayed(mWatch, WATCH_INTERVAL_MS);
            TrioHooks.log(TrioHooks.LOG_INFO, "overlay: added type=" + params.type
                    + " size=" + params.width + "x" + params.height);
            return true;
        } catch (Throwable t) {
            TrioHooks.log(TrioHooks.LOG_WARN, "overlay: addView failed: " + t);
            return false;
        }
    }

    private void detach() {
        if (!attached) {
            return;
        }
        attached = false;
        glyph.removeCallbacks(mWatch);
        try {
            windowManager.removeViewImmediate(glyph);
            TrioHooks.log(TrioHooks.LOG_INFO, "overlay: removed");
        } catch (Throwable ignored) {
            // The window is going away with the view tree anyway.
        }
    }

    /**
     * The window type, read reflectively because both candidates are hidden
     * constants. The bar's own type cannot be used: the platform allows only
     * one window of it, and the bar already owns it.
     *
     * <p>MIUI keeps a second status bar window - {@code StatusBar1},
     * {@code (0,0)(fillx121)} - which covers the whole bar and sits at the same
     * base layer ({@code 161000}) as {@code TYPE_STATUS_BAR_ADDITIONAL}. Same
     * layer means the window added later wins, and StatusBar1 is re-added on
     * config changes and on a SystemUI restart, so a glyph window of that type
     * ends up underneath it: the window is visible and reports
     * {@code HAS_DRAWN}, and the status bar simply paints over it - the glyph
     * is switched on and drawn, and it is not on screen.
     *
     * <p>{@code TYPE_STATUS_BAR_SUB_PANEL} is ordered above the bar's own
     * windows - the notification modal window in the same process uses it and
     * lands higher - so the glyph is not covered whichever order the bar's
     * windows happened to be added in. It is tried first, and
     * {@code TYPE_STATUS_BAR_ADDITIONAL} stays as the fallback. Both are
     * status-bar-owned types, so neither needs a windowing permission the
     * system UI does not already hold.
     */
    private static int windowType() {
        if (sWindowType == 0) {
            int type = TYPE_FALLBACK;
            // Tried in this order on purpose - see the note above.
            try {
                final Field field = WindowManager.LayoutParams.class
                        .getField("TYPE_STATUS_BAR_SUB_PANEL");
                type = field.getInt(null);
            } catch (Throwable ignored) {
                try {
                    final Field field = WindowManager.LayoutParams.class
                            .getField("TYPE_STATUS_BAR_ADDITIONAL");
                    type = field.getInt(null);
                } catch (Throwable ignored2) {
                    // Older platform: the public overlay type is close enough.
                }
            }
            sWindowType = type;
        }
        return sWindowType;
    }

    /**
     * {@code PRIVATE_FLAG_TRUSTED_OVERLAY}, read reflectively because it is a
     * hidden constant, or {@code 0} on a build that does not have it.
     *
     * <p>Without the mark the platform treats a window drawn over an app as a
     * possible tap-jacking attempt: a touch that lands under it is flagged as
     * obscured, and an app that checks for that - a module manager, say -
     * refuses to act on it. This window is not that kind of overlay. It is not
     * touchable, it is one icon wide and sits in the bar, and it belongs to the
     * system UI.
     */
    private static int trustedOverlayFlag() {
        if (sTrustedOverlay == 0) {
            int flag = 0;
            try {
                final Field field = WindowManager.LayoutParams.class
                        .getField("PRIVATE_FLAG_TRUSTED_OVERLAY");
                flag = field.getInt(null);
            } catch (Throwable ignored) {
                // Older platform: there is no such mark to set.
            }
            sTrustedOverlay = flag;
        }
        return sTrustedOverlay;
    }

    /** The glyph itself: the same drawing call the hooked view used to make. */
    private final class GlyphView extends View {

        GlyphView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            state.refresh();
            TrioRenderer.drawState(canvas, getWidth(), getHeight(), state, TrioConfig.get());
        }
    }
}
