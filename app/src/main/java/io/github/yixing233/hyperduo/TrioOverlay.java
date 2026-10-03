package io.github.yixing233.hyperduo;

import android.content.Context;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
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

    /** {@code TYPE_APPLICATION_OVERLAY}: the fallback when the hidden type is absent. */
    private static final int TYPE_FALLBACK = 2038;

    /** One overlay per glyph host: the status bar's battery view gets exactly one. */
    private static final WeakHashMap<View, TrioOverlay> LIVE =
            new WeakHashMap<View, TrioOverlay>();

    private static int sWindowType;

    /**
     * The one host whose window is live.
     *
     * <p>The bar can hold more than one battery view - MIUI's icon row is not the
     * only place the same class is inflated - and two windows on the same spot
     * draw the glyph twice, a pixel apart, which reads as a blur. The first host
     * to ask keeps the window; the others stay on the in-view path.
     */
    private static volatile View sOwner;

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
            release(host);
            return null;
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

    /** {@code Host<Parent<...}}, truncated: the log line is a diagnosis, not a dump. */
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

    /** True while some host owns the glyph window. */
    static boolean windowOwned() {
        return sOwner != null;
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
     * Follows the host: same visibility, same centre, and a repaint whenever the
     * host repaints. Runs inside the host's draw pass, so nothing here may
     * schedule layout on the host.
     */
    void sync() {
        final boolean hostVisible = host.isShown()
                && host.getWindowVisibility() == View.VISIBLE
                && host.getAlpha() > 0f;
        // The bar is what is on screen, not this view: MIUI fades the bar's
        // contents during a shade pull, hides them outright when the shade is
        // open or a full-screen app takes the screen, and the window has to go
        // with it rather than outlive it. Tying visibility and alpha to the bar
        // (and not just to the host) is what keeps the two in step.
        final View bar = TrioHooks.statusBarView();
        final boolean barVisible = bar == null
                || (bar.isShown() && bar.getWindowVisibility() == View.VISIBLE
                    && bar.getAlpha() > 0f);
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
        final boolean visible = hostVisible && barVisible && onScreen;
        if (visible != shown) {
            shown = visible;
            glyph.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
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
        // Alpha rides with the bar's own: the pull-down fades the status bar
        // rather than hiding it, and a window that stays opaque through that
        // reads as a glyph glued to the shade.
        final float alpha = (bar == null) ? host.getAlpha()
                : Math.min(host.getAlpha(), bar.getAlpha());
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
     * {@code TYPE_STATUS_BAR_ADDITIONAL} is a hidden constant, so it is read
     * reflectively and cached. The fallback is the public overlay type, which
     * needs no windowing permission the system UI does not already hold.
     */
    private static int windowType() {
        if (sWindowType == 0) {
            int type = TYPE_FALLBACK;
            try {
                final Field field = WindowManager.LayoutParams.class
                        .getField("TYPE_STATUS_BAR_ADDITIONAL");
                type = field.getInt(null);
            } catch (Throwable ignored) {
                // Older platform: the public overlay type is close enough.
            }
            sWindowType = type;
        }
        return sWindowType;
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
