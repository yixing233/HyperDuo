package io.github.yixing233.hyperduo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Installs every hook HyperDuo v1 needs and keeps the per-host {@link TrioState}
 * bookkeeping.
 *
 * <h3>Why the trio glyph is drawn from the battery icon view</h3>
 * {@code MiuiBatteryMeterIconView} is already measured 28dp x 20dp
 * ({@code battery_meter_width} x {@code status_bar_icon_height}) and its native
 * {@code onDraw} clears the canvas itself, so drawing after {@code proceed()}
 * takes the icon over completely without any view-tree surgery.
 *
 * <h3>Why {@code ignoredSlots} alone leaves a ghost, and what fixes it</h3>
 * {@code ModernStatusBarView.isIconVisible()} is driven by the binding and the anim
 * helper, never by {@code View.getVisibility()}, so hiding the native Wi-Fi / mobile
 * icons has to go through {@code ignoredSlots}: {@code MiuiStatusIconContainer.onMeasure}
 * skips every child whose slot is in that list. But the <em>layout</em> pass still
 * touches them: its first pass unconditionally runs
 * {@code childAt.layout(0, y, measuredWidth, ...)} for every child, and the later
 * right-to-left pass only repositions children that are visible, unblocked and not
 * ignored. So an ignored child keeps the left edge 0 of the pass. The status bar
 * hands its icon container exactly half the screen width (see
 * {@code MiuiNotificationStatusContainer.onMeasure}), which parks the orphaned
 * native mobile view - and the "5G" label inside it - on the screen centre line.
 *
 * <p>The fix therefore has to pair the slot suppression with a hard
 * {@code View.GONE} on the same children, re-applied on every layout pass so it
 * survives MIUI re-showing them. Hiding is restricted to containers that really
 * render the trio glyph (one that owns a live host), because a container whose
 * battery view never draws would otherwise lose its signal icons entirely.
 *
 * <h3>Why {@code addIgnoredSlots} itself is not hooked</h3>
 * The callers pass MIUI's shared static block lists
 * ({@code MiuiIconManagerUtils.RIGHT_BLOCK_LIST}); mutating the argument would
 * corrupt those lists process-wide. Instead the container's own
 * {@code addIgnoredSlots} is invoked with a private list, and only when one of our
 * slots is actually missing - {@code addIgnoredSlots} unconditionally ends in
 * {@code requestLayout()}, so calling it on every pass would loop forever.
 */
final class TrioHooks {

    private static final String TAG = "HyperDuo";
    static final int LOG_INFO = 4;
    static final int LOG_WARN = 5;
    static final int LOG_ERROR = 6;

    /**
     * Enables the view-tree dumps used to pin down which container holds a stray
     * native icon. Normally driven by the user's debug-log setting; force it on
     * here while matching a container that has not been identified yet.
     */
    static final boolean DEBUG_DUMP = false;

    /** True when the user asked for verbose logging. */
    static boolean debugLog() {
        return DEBUG_DUMP || TrioConfig.debugLog();
    }

    /**
     * {@code MiuiStatusBatteryContainer} — matched by name while walking the view
     * tree, because resolving it through the host's class loader never worked.
     */
    private static final String BATTERY_CONTAINER_CLASS =
            "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer";

    /** Module handle for logging from helpers that have no callback argument. */
    private static volatile XposedModule sModule;

    /**
     * Every slot the module knows how to fold, one group per sub-toggle.
     *
     * <p>"mobile" and "wifi" are the classic {@code ModernStatusBarMobileView} /
     * {@code ModernStatusBarWifiView} slots. HyperOS 4 replaced the visible mobile
     * indicator with a Compose one whose slot is "stacked_mobile"
     * ({@code SingleBindableStatusBarComposeIconView}); on a real device it is the
     * only mobile child left visible and the classic ones stay {@code a=0.0 v=8}.
     * Both mobile slots are managed together so whichever the build uses follows
     * the same sub-toggle.
     */
    private static final List<String> MANAGED_SLOTS =
            Collections.unmodifiableList(
                    Arrays.asList("wifi", "mobile", "stacked_mobile"));

    /** The "mobile" slot group, folded and restored as one. */
    private static final List<String> MOBILE_SLOTS =
            Collections.unmodifiableList(Arrays.asList("mobile", "stacked_mobile"));

    /** The Wi-Fi slot group; a single element list, kept for symmetry. */
    private static final List<String> WIFI_SLOTS =
            Collections.unmodifiableList(Arrays.asList("wifi"));

    /**
     * The battery style that makes MIUI measure, show and lay out its own
     * charging bolt. Read by {@code MiuiBatteryMeterView.onMeasure},
     * {@code updateChargeAndText} and {@code onLayout} - never by
     * {@code onBatteryStyleChanged}, which is the only method whose style-1 path
     * also hides {@code mBatteryIconView}, the view the glyph is drawn on.
     */
    private static final int BOLT_STYLE = 1;

    /**
     * The slots to fold for the settings in force right now.
     *
     * <p>This is what makes a sub-toggle mean something: while the module is on,
     * a slot is folded exactly when the glyph draws that element, so switching
     * one off hands its native icon back instead of leaving it stranded at the
     * container's left edge. The whole list is empty while the module is off,
     * which is what {@link #restoreNative} relies on to undo everything.
     *
     * <p>The bolt is not here: it is not a status-bar icon slot but a child of
     * the battery view, handed back through {@link #BOLT_STYLE}.
     */
    private static List<String> foldedSlots() {
        final TrioAppearance a = TrioConfig.appearance();
        if (!a.glyph) {
            return Collections.emptyList();
        }
        final List<String> slots = new ArrayList<>(MANAGED_SLOTS.size());
        if (a.wifi) {
            // The island is the one state that hands Wi-Fi back while the module
            // draws it: the battery is hidden, so the glyph cannot carry the
            // arcs and the user asked for the native icon to take over.
            if (!islandHideBattery()) {
                slots.addAll(WIFI_SLOTS);
            }
        }
        // The mobile slot is folded exactly while the module draws the signal
        // itself: as the glyph's dots, or as the out-of-ring reading. Leaving it
        // in place out of ring with the stacked switch off is what keeps MIUI's
        // own icon on screen, which is the whole difference the switch makes.
        // The island does not change this: what it changes is *where* the
        // module draws the signal (out of ring, since the glyph is gone), not
        // whether the user asked for it - so the fold condition is untouched and
        // the reading takes over in syncOutSignal.
        if (a.foldsMobile()) {
            slots.addAll(MOBILE_SLOTS);
        }
        return slots;
    }

    /**
     * Whether the module should be drawing the out-of-ring signal reading.
     *
     * <p>Two ways in: the user asked for it (out of ring + stacked), or the
     * charging island took the glyph away while the user still wants the signal
     * drawn in-ring - the reading is the only place left to put it. A user who
     * switched the mobile reading off entirely asked for no signal of ours
     * anywhere, so the island leaves the native icon in place instead.
     */
    private static boolean outSignalWanted() {
        final TrioAppearance a = TrioConfig.appearance();
        return a.stackedOut() || (islandHideBattery() && a.foldsMobile());
    }

    /**
     * Whether the charging super island currently has the battery hidden.
     *
     * <p>Sourced from {@link TrioState#sIslandHideBattery}, which the hook on
     * {@code MiuiStatusBatteryContainer.setIsHideBattery} keeps current; this
     * reads the flag rather than walking for the container, because the ask
     * happens in contexts ({@code foldedSlots} during a layout, the draw gate)
     * where no container is at hand - and the flag is global by nature: MIUI
     * hides the battery of every container at once. The glyph window of
     * {@link TrioOverlay} reads it for the same reason the draw gate does: a
     * battery that MIUI is not painting has no row the window may cover.
     */
    static boolean islandHideBattery() {
        return TrioState.sIslandHideBattery;
    }

    /** Hosts currently drawing the trio glyph. */
    private static final List<TrioState> HOSTS =
            Collections.synchronizedList(new ArrayList<TrioState>());

    /** The status bar's icon container, kept for {@link #resources()}. */
    private static volatile Object sStatusIconContainer;
    /**
     * The status bar view itself ({@code MiuiPhoneStatusBarView}), the row the
     * glyph shares with the clock, the notification icons and the system icons.
     * Kept so the module can tell the bar's own row from the same views the
     * keyguard and the control centre inflate - see {@link #isStatusBarHost}.
     */
    private static volatile View sStatusBarView;

    /** {@code MiuiStatusBatteryContainer}, resolved once. */
    private static volatile Class<?> sBatteryContainerClass;
    /** {@code MiuiStatusBatteryContainer.mStatusIcon}, resolved once. */
    private static volatile Field sStatusIconField;

    /** {@code MiuiBatteryMeterView.mStoreRealStyle}, resolved once. */
    private static volatile Field sStoreRealStyleField;
    /** {@code MiuiBatteryMeterView.updateChargeAndText}, resolved once. */
    private static volatile Method sUpdateChargeAndTextField;
    /** {@code MiuiBatteryMeterView.onBatteryStyleChanged(int)}, resolved once. */
    private static volatile Method sStyleChangedField;
    /** {@code MiuiBatteryMeterView.mBatteryChargingView}, resolved once. */
    private static volatile Field sChargingViewField;
    /** {@code MiuiBatteryMeterView.mBatteryPercentContainer}, resolved once. */
    private static volatile Field sPercentContainerField;
    /**
     * {@code MiuiBatteryMeterView.mBatteryStyle}, resolved once.
     *
     * <p>Written directly, never through {@code onBatteryStyleChanged}: that is
     * the only path that hides {@code mBatteryIconView}, and it only does so when
     * the style really changes, so passing the same value leaves the glyph host
     * alone. See {@link #BOLT_STYLE}.
     */
    private static volatile Field sBatteryStyleField;
    /** {@code MiuiBatteryMeterView.mBatteryIconView}, resolved once. */
    private static volatile Field sBatteryIconViewField;
    /** {@code MiuiBatteryMeterView.mHollowBatteryIconView}, resolved once. */
    private static volatile Field sHollowBatteryIconViewField;

    /**
     * Live {@code MiuiBatteryMeterView} instances, so a settings change can ask
     * them to re-apply the style MIUI last requested.
     */
    private static final List<WeakReference<View>> METERS = new ArrayList<>();

    /** {@code MiuiStatusIconContainer}, resolved once. */
    private static volatile Class<?> sIconContainerClass;
    /** {@code MiuiStatusIconContainer.ignoredSlots}, resolved once. */
    private static volatile Field sIgnoredSlotsField;

    /**
     * Icon containers that belong to a view actually drawing the trio glyph.
     * Native Wi-Fi / mobile children are only forced GONE in these, so a
     * container whose battery view stays hidden keeps its own signal icons.
     */
    private static final Map<Object, Boolean> OWNED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /** Containers already dumped to the log, to keep one diagnostic each. */
    private static final Map<Object, Boolean> DIAGNOSED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /**
     * Last {@code owned} value logged per container.
     *
     * <p>The first layout of a container normally runs before any glyph host has
     * drawn, so the first header legitimately reports {@code owned=false}; a
     * one-shot header would then keep reporting a stale value for the rest of
     * the session. Re-logging on a change keeps the log honest for two extra
     * lines at most per container.
     */
    private static final Map<Object, Boolean> DIAG_OWNED =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /**
     * Folded children already given a hard {@code GONE}. Kept so a firmware pass
     * that re-shows one of them is not answered with a new visibility flip on
     * every layout - the zero-size {@code layout()} in {@link #settle} already
     * keeps the child undrawn.
     */
    private static final Map<View, Boolean> COLLAPSED =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** Last child dump per container, so a steady layout logs at most once. */
    private static final Map<Object, String> DIAG_SIG =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    /**
     * Original {@code left}/{@code right} padding of an icon container, recorded
     * the first time the out-of-ring label reserves room in it.
     *
     * <p>Keyed on the <em>icon</em> container the padding is applied to; each value
     * is a bare {@code int[]}, so - unlike a {@code WeakHashMap<View, TextView>} -
     * it cannot reach back and keep its own key alive.
     */
    private static final Map<View, int[]> OUT_PAD_SAVED =
            Collections.synchronizedMap(new WeakHashMap<View, int[]>());

    /** Hard cap on total child dumps, so a layout loop cannot flood the log. */
    private static volatile int sDiagDumps;
    /** Hard cap on container headers, including {@code owned} flips. */
    private static volatile int sDiagHeaders;

    private TrioHooks() {
    }

    // ------------------------------------------------------------------ install

    static void install(XposedModule module, ClassLoader cl) {
        sModule = module;
        // Read the user's settings before anything is hooked: the very first hook
        // callback already needs to know whether the module is switched on.
        TrioConfig.install(module);
        TrioConfig.addListener(new TrioConfig.Listener() {
            @Override
            public void onConfigChanged() {
                applyConfigChange();
            }
        });
        int hooked = 0;
        hooked += group(module, cl, 1);
        hooked += group(module, cl, 2);
        hooked += group(module, cl, 3);
        hooked += group(module, cl, 4);
        hooked += group(module, cl, 5);
        hooked += group(module, cl, 6);
        hooked += group(module, cl, 7);
        hooked += group(module, cl, 8);
        hooked += group(module, cl, 9);
        hooked += group(module, cl, 10);
        hooked += group(module, cl, 11);
        hooked += group(module, cl, 12);
        hooked += group(module, cl, 13);
        hooked += group(module, cl, 14);
        hooked += group(module, cl, 15);
        hooked += group(module, cl, 16);
        hooked += group(module, cl, 17);
        hooked += group(module, cl, 18);
        log(module, "HyperDuo installed, hooks=" + hooked
                + " enabled=" + TrioConfig.get().enabled);
    }

    /**
     * Reacts to the settings app changing a value.
     *
     * <p>Runs on whatever thread delivered the change - the framework's Binder
     * thread, or the main looper for the settings app's reload broadcast - so
     * everything that touches the view tree is posted to the main looper.
     *
     * <p>A plain repaint is only enough when nothing about the suppression moved.
     * The transitions that need real work are:
     *
     * <p>Turning the module <em>off</em> has to undo the slot suppression and the
     * forced {@code GONE}, hand each meter back the battery style MIUI really
     * wants, and re-run {@code updateChargeAndText} - the native charging /
     * percentage views were hidden by our hook and MIUI only re-shows them from
     * that method, which it does not call on a settings change.
     *
     * <p>Turning it <em>on</em> has to redo the suppression (turning it off
     * dropped it), re-pin the battery style, re-hide the native charging and
     * percentage views (turning it off handed them back to MIUI), and collapse
     * the folded children again - see {@link #refoldContainers}, which applies
     * that state directly rather than waiting for the layout pass that
     * {@code invalidate()} would not trigger on its own.
     *
     * <p>The same work is needed for the sub-toggles, which is why this does not
     * branch on the master switch alone: {@code show_wifi} and {@code show_mobile}
     * decide which slots are suppressed, and {@code show_bolt} / {@code show_value}
     * decide whether the native charging bolt is ours to suppress. Turning a
     * sub-toggle off has to hand its native element back - see
     * {@link #foldedSlots} and {@link #applyMeterText} - and turning it on has to
     * take it away again.
     */
    private static void applyConfigChange() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // The framework delivers remote-preference changes on a Binder
            // thread, and the work below calls requestLayout on live views.
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    applyConfigChange();
                }
            });
            return;
        }
        final TrioAppearance a = TrioAppearance.of(TrioConfig.get());
        final boolean enabled = a.glyph;
        final List<TrioState> hosts;
        synchronized (HOSTS) {
            hosts = new ArrayList<TrioState>(HOSTS);
        }
        // Snapshot the meters before anything can drop them: the switch-off path
        // both re-applies their style and re-runs their text refresh, and the
        // weak list must not be emptied out from under the second step.
        final List<View> meters = liveMeters();
        if (debugLog()) {
            log(LOG_INFO, "config changed, hosts=" + hosts.size()
                    + " enabled=" + enabled
                    + " ring=" + a.ringStroke + " arc=" + a.arcStroke
                    + " size=" + a.valueSize);
        }
        for (int i = 0; i < hosts.size(); i++) {
            final View v = hosts.get(i).host;
            if (v == null) {
                continue;
            }
            v.post(new Runnable() {
                @Override
                public void run() {
                    if (enabled) {
                        foldHostContainer(v);
                        // Insurance on top of refoldContainers(), which applies
                        // the folded state directly: a layout pass is what MIUI
                        // itself uses to re-lay the icon container, and letting it
                        // happen keeps our result consistent with anything MIUI
                        // does in the same pass.
                        v.requestLayout();
                    }
                    // Either mounts or removes it, so this covers switching the
                    // network type to Out of ring, away from it, and off. Posted
                    // rather than called inline because it may add a view, which
                    // must never happen inside a layout pass - and this runnable
                    // is already the deferred slot the rest of the re-fold uses.
                    syncOutSignal(v);
                    syncOutTypeLabel(v);
                    v.invalidate();
                }
            });
        }
        // The styles are re-applied either way, but for different reasons, and it
        // has to happen before the meter text below: re-applying the style runs
        // onBatteryStyleChanged, which is what puts the battery views back the way
        // MIUI wants them - and the style hook re-forces the native bolt onto the
        // meter afterwards.
        restyleMeters(meters);
        // The slot suppression follows the sub-toggles, not just the master switch,
        // so this runs on every change while the module is on: turning show_wifi or
        // show_mobile off has to drop that slot from ignoredSlots and let MIUI lay
        // the native icon out again.
        if (enabled) {
            refoldContainers();
        } else {
            restoreNative();
        }
        // Always, whichever toggles moved: the native charging bolt has to follow
        // show_bolt / show_value, and the percentage container follows the master
        // switch.
        applyMeterText(meters);
        // A meter that was mid-island when the module switched off must still
        // receive MIUI's original replacement request - the replay re-drives
        // every declined island with its real values. Under the new enable the
        // same replay re-declines them, which is the state the hooks want.
        replayBatteryIslandRequests();
    }

    /**
     * Re-applies the slot suppression to every container we had claimed, and
     * both collapses the slots the sub-toggles still want folded and hands the
     * ones they no longer want back to MIUI.
     *
     * <p>{@link #restoreNative} drops both the suppression and the forced
     * {@code GONE}, but keeps the ownership record, so this is what makes a later
     * re-enable take effect without waiting for each container to be inflated
     * again.
     *
     * <p>Goes through {@link #syncSlots}, not the layout pass's
     * {@link #ensureFolded}: this runs once per settings change, never per layout,
     * so the suppression must be applied even when the slot list cannot be read
     * back, and {@code syncSlots} is idempotent whenever it can be.
     *
     * <p>{@link #settle} runs here as well, immediately after the suppression.
     * Normally {@code settle} is driven by the icon container's {@code onLayout},
     * and the re-enable path does request a layout so that it gets there - but
     * that makes correctness depend on the layout pass actually happening and on
     * MIUI not skipping it. Calling it directly applies the same state now; the
     * later layout pass is then a no-op, because
     * {@link #markCollapsed} has already recorded the children.
     */
    private static void refoldContainers() {
        final List<Object> containers;
        synchronized (OWNED) {
            containers = new ArrayList<Object>(OWNED.keySet());
        }
        for (int i = 0; i < containers.size(); i++) {
            foldAndSettle(containers.get(i));
        }
        foldAndSettle(sStatusIconContainer);
    }

    private static void foldAndSettle(Object container) {
        syncSlots(container);
        if (container instanceof ViewGroup) {
            settle((ViewGroup) container);
        }
    }

    /** Live meter views, dropping the ones the GC already took. */
    private static List<View> liveMeters() {
        final List<View> meters = new ArrayList<>();
        synchronized (METERS) {
            for (int i = METERS.size() - 1; i >= 0; i--) {
                final View v = METERS.get(i).get();
                if (v == null) {
                    METERS.remove(i);
                } else {
                    meters.add(v);
                }
            }
        }
        return meters;
    }

    /**
     * Brings MIUI's own charging bolt and percentage views in line with the
     * sub-toggles.
     *
     * <p>The charging bolt is the one native element this module draws itself, so
     * it is the one element with a rule: the stock bolt stays out of the way only
     * while the glyph actually draws its own, which
     * {@link TrioAppearance#hidesNativeBolt} states once for every caller. Turn
     * either toggle off and the stock bolt belongs to MIUI again.
     *
     * <p>Handing it back cannot just mean un-hiding it. {@code updateChargeAndText}
     * is MIUI's authority on whether the bolt belongs on screen - it hides it when
     * the battery is not charging - and it re-runs on every battery state change.
     * So the hand-back is: make the view visible, then let MIUI decide. That way a
     * battery change cannot leave the bolt in a state this module invented.
     *
     * <p>The percentage container has no such rule: MIUI only measures and shows
     * it at battery style 3, and the module never asks for style 3, so while the
     * module is on it can only ever be hidden. It is shown again whenever the
     * native text is wanted, so switching the module off leaves MIUI in charge.
     */
    private static void applyMeterText(List<View> meters) {
        final TrioAppearance a = TrioAppearance.of(TrioConfig.get());
        final boolean nativeBolt = !a.hidesNativeBolt();
        final boolean glyphOn = a.glyph;
        for (int i = 0; i < meters.size(); i++) {
            final View meter = meters.get(i);
            meter.post(new Runnable() {
                @Override
                public void run() {
                    final Object charging = Refl.get(sChargingViewField, meter);
                    if (nativeBolt) {
                        showIfHidden(charging);
                    } else {
                        hide(charging);
                    }
                    // The percentage text is ours to hide the whole time the
                    // module is on, whichever sub-toggles are set.
                    final Object percent = Refl.get(sPercentContainerField, meter);
                    if (glyphOn) {
                        hide(percent);
                    } else {
                        showIfHidden(percent);
                    }
                    // Let MIUI re-derive the bolt from the battery state. It only
                    // runs the text refresh on its own battery callbacks, and this
                    // is a settings change, so without the call the hand-back
                    // would wait for the next charge event.
                    Refl.invoke(sUpdateChargeAndTextField, meter);
                }
            });
        }
    }

    private static void showIfHidden(Object view) {
        if (view instanceof View) {
            final View v = (View) view;
            if (v.getVisibility() != View.VISIBLE) {
                v.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Asks every known {@code MiuiBatteryMeterView} to re-apply its real style. */
    private static void restyleMeters(List<View> meters) {
        for (int i = 0; i < meters.size(); i++) {
            final View meter = meters.get(i);
            meter.post(new Runnable() {
                @Override
                public void run() {
                    reapplyStyle(meter);
                }
            });
        }
    }

    private static void reapplyStyle(View meter) {
        final Object style = Refl.get(sStoreRealStyleField, meter);
        if (style instanceof Number) {
            Refl.invokeArgs(sStyleChangedField, meter,
                    new Object[]{Integer.valueOf(((Number) style).intValue())});
        }
    }

    /**
     * Puts the native icons back: drops our slots from {@code ignoredSlots} and
     * clears the forced {@code GONE}, so switching the module off leaves the
     * status bar exactly as MIUI would have drawn it.
     *
     * <p>Only the children recorded in {@code COLLAPSED} are made visible again.
     * Those are the ones this module hid; a child MIUI itself decided to hide -
     * no SIM, wifi off, airplane mode - was never recorded and is deliberately
     * left alone, because resurrecting it would show an icon the system state
     * does not call for.
     */
    private static void restoreNative() {
        List<Object> containers;
        synchronized (OWNED) {
            containers = new ArrayList<Object>(OWNED.keySet());
        }
        for (int i = 0; i < containers.size(); i++) {
            final Object container = containers.get(i);
            unfoldSlots(container);
            if (!(container instanceof ViewGroup)) {
                continue;
            }
            final ViewGroup group = (ViewGroup) container;
            final int count = group.getChildCount();
            for (int c = 0; c < count; c++) {
                final View child;
                try {
                    child = group.getChildAt(c);
                } catch (Throwable t) {
                    continue;
                }
                if (child == null) {
                    continue;
                }
                final String slot = slotOf(child);
                if (slot == null || !MANAGED_SLOTS.contains(slot)) {
                    continue;
                }
                if (!unmarkCollapsed(child)) {
                    continue;
                }
                try {
                    if (child.getVisibility() != View.VISIBLE) {
                        child.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {
                    // never let one child abort the pass
                }
            }
        }
        // The out-of-ring label is ours alone, so taking it back out needs no
        // state check - unlike the native slots above, MIUI has no opinion about
        // whether it should exist.
        for (int i = 0; i < containers.size(); i++) {
            final Object container = containers.get(i);
            if (container instanceof View) {
                removeOutTypeLabel(batteryContainerOf((View) container));
            }
        }
        for (int i = 0; i < containers.size(); i++) {
            final Object container = containers.get(i);
            if (container instanceof View) {
                // While folded, the children were laid out at 0x0 and hidden, so
                // the container may still hold the measurement taken then.
                // Making them visible again is not enough on its own: MIUI needs
                // one more pass before they reappear where they belong.
                ((View) container).requestLayout();
            }
        }
        for (int i = 0; i < containers.size(); i++) {
            final Object container = containers.get(i);
            if (container instanceof ViewGroup) {
                // The native type views were suppressed independently of the
                // slots, so they are handed back independently too. Runs before
                // COLLAPSED.clear() because the hand-back reads the same
                // once-only records the suppression wrote.
                suppressNativeTypeViews((ViewGroup) container);
            }
        }
        synchronized (COLLAPSED) {
            COLLAPSED.clear();
        }
        // METERS is deliberately kept: it holds only weak references (pruned by
        // liveMeters), and dropping it here would leave a later re-enable with no
        // meter to re-pin the battery style on.
    }

    /**
     * Runs one hook group, isolating any failure. Hook groups are independent:
     * a method that a given firmware build renamed must not take the others
     * down with it.
     */
    private static int group(XposedModule module, ClassLoader cl, int which) {
        try {
            switch (which) {
                case 1: return hookStatusBarViewCapture(module, cl);
                case 2: return hookBatteryIconView(module, cl);
                case 3: return hookBatteryMeterView(module, cl);
                case 4: return hookStatusBarView(module, cl);
                case 5: return hookSignalIcons(module, cl);
                case 6: return hookIconContainerLayout(module, cl);
                case 7: return hookMobileType(module, cl);
                case 8: return hookMobileTypeVisibility(module, cl);
                case 9: return hookMeterTint(module, cl);
                case 10: return hookIslandHide(module, cl);
                case 11: return hookShadeExpansion(module, cl);
                case 12: return hookOverviewGesture(module, cl);
                case 13: return hookOverviewProxy(module, cl);
                case 14: return hookOverviewProgress(module, cl);
                case 15: return hookLaunchAnimation(module, cl);
                case 16: return hookIconTint(module, cl);
                case 17: return hookKeyguardBar(module, cl);
                case 18: return hookIconAdded(module, cl);
                default: return 0;
            }
        } catch (Throwable t) {
            log(module, "hook group " + which + " failed: " + t);
            return 0;
        }
    }

    /**
     * Hooks one executable, tolerating a missing method (null) or a rejected
     * hook. Returns 1 when the hook was installed, 0 otherwise.
     */
    private static int hook(XposedModule module, Method m, String id,
                            XposedInterface.Hooker hooker) {
        if (m == null) {
            log(module, "skip " + id + ": method not found");
            return 0;
        }
        try {
            module.hook(m).setId(id).intercept(hooker);
            return 1;
        } catch (Throwable t) {
            log(module, "skip " + id + ": " + t);
            return 0;
        }
    }

    /**
     * Captures the status bar's icon container.
     *
     * <p>This deliberately hooks {@code MiuiPhoneStatusBarView}, not
     * {@code MiuiStatusBatteryContainer}: {@code system_icons.xml} is included by
     * seven layouts (status bar, keyguard, control center, both QS headers, ...),
     * so a container-level hook fires for every one of those instances and the
     * last to inflate would win, leaving us suppressing slots on the keyguard or
     * control-center container instead of the status bar's. Only
     * {@code status_bar.xml}'s root is a {@code MiuiPhoneStatusBarView}, and it
     * holds the authoritative {@code mStatusBarStatusIcons}.
     */
    private static int hookStatusBarViewCapture(XposedModule module, ClassLoader cl) {
        final Class<?> bar = Refl.cls(
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", cl);
        if (bar == null) {
            log(module, "MiuiPhoneStatusBarView missing (capture)");
            return 0;
        }
        final Field iconsField = Refl.field(bar, "mStatusBarStatusIcons");
        return hook(module, Refl.method(bar, "onFinishInflate"),
                "hyperduo-container", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        // mStatusBarStatusIcons is assigned inside proceed(), so it
                        // is only readable now.
                        final Object self = chain.getThisObject();
                        final Object icons = Refl.get(iconsField, self);
                        if (icons instanceof View) {
                            sStatusIconContainer = icons;
                            syncSlots(icons);
                        }
                        if (self instanceof View) {
                            sStatusBarView = (View) self;
                            // The only hook callback that runs early enough on a
                            // real SystemUI context. TrioConfig ignores repeat
                            // calls, so this stays a one-shot.
                            TrioConfig.installReceiver(((View) self).getContext());
                        }
                        return result;
                    }
                });
    }

    private static int hookBatteryIconView(XposedModule module, ClassLoader cl) {
        final Class<?> icon = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView", cl);
        if (icon == null) {
            log(module, "MiuiBatteryMeterIconView missing");
            return 0;
        }
        final int a = hook(module, Refl.method(icon, "onDraw", Canvas.class),
                "hyperduo-draw", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        final Object canvasArg = chain.getArg(0);
                        if (self instanceof View) {
                            // Idempotent, and the only chance to pick up a real
                            // SystemUI context when this generation was loaded
                            // after the view tree had already been inflated.
                            TrioConfig.installReceiver(((View) self).getContext());
                            // The per-SIM signal sampler needs the same context to
                            // reach telephony; it is the drawing path that needs it,
                            // so it is handed over here rather than at hook install
                            // time, when there may be no context at all.
                            TrioState.attachContext(((View) self).getContext());
                        }
                        // Just the master switch: whether to paint at all. The
                        // renderer resolves the rest of the switches itself -
                        // except the island: with the battery hidden there is no
                        // ring to draw into, and painting on would leave the
                        // glyph floating over the island's clearance.
                        if (self instanceof View && canvasArg instanceof Canvas
                                && TrioConfig.get().enabled && !islandHideBattery()) {
                            final View host = (View) self;
                            TrioState state = stateFor(host);
                            if (state == null) {
                                state = registerHost(host);
                            }
                            state.refresh();
                            // MIUI's dark-mode pass ends at this view's own
                            // invalidate() and nowhere else, so a tint change is
                            // visible here first - and the out-of-ring views are
                            // siblings that nothing repaints on their own.
                            final int ink = state.foreground();
                            if (state.outRingInk != ink) {
                                recolourOutRing(state, ink);
                            }
                            final Canvas canvas = (Canvas) canvasArg;
                            // Drawing is the one signal that cannot lie about
                            // whether this host is on screen: a hidden window is
                            // never drawn, and a bar that comes back - the shade
                            // over a full-screen app, for instance - starts
                            // drawing again. TrioOverlay uses the timestamp.
                            TrioOverlay.noteDrawn(host);
                            // The bar window is only status_bar_height tall and
                            // clips everything past it. Growing the row inside it
                            // was tried and abandoned: MIUI's own measure chain
                            // re-derives the size of every box on the way up, so
                            // each level fixed exposed the next one. The glyph is
                            // drawn in a window of its own instead; all that is
                            // left here is erasing MIUI's own battery drawing so
                            // the two cannot show at once.
                            final TrioOverlay overlay = TrioOverlay.active(host, state);
                            if (overlay != null) {
                                overlay.sync();
                                if (overlay.covering()) {
                                    // The window is painting the glyph, so this
                                    // row has to stay blank: painting here as
                                    // well is what put two glyphs on screen, a
                                    // few pixels apart.
                                    canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                                } else {
                                    // The window is not painting - the shade is
                                    // moving, the bar is off screen - so the row
                                    // draws the glyph itself and the trio stays
                                    // with the bar instead of blinking out.
                                    TrioRenderer.draw(canvas, host, state);
                                }
                            } else if (TrioOverlay.coveringBar() && isStatusBarHost(host)) {
                                // Another view on the bar's own row owns the glyph
                                // window (MIUI inflates more than one battery view
                                // there). This one has to stay blank: painting the
                                // glyph here as well is what put two of them on
                                // screen, a few pixels apart, whenever the bar was
                                // laid out again - a dark-mode switch, an app with
                                // its own bar colour, a configuration change.
                                //
                                // Gated on a window actually existing: with the
                                // switch off there is none, and blanking the row
                                // here is what made the glyph disappear entirely
                                // when the window route was turned off.
                                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                            } else {
                                TrioRenderer.draw(canvas, host, state);
                            }
                        } else if (self instanceof View) {
                            // The master switch is off. Any window this host opened
                            // earlier has to go with it, or the glyph would keep
                            // floating above a status bar that is back to stock.
                            TrioOverlay.release((View) self);
                        }
                        return result;
                    }
                });
        final int b = hook(module, Refl.method(icon, "onDetachedFromWindow"),
                "hyperduo-detach", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View) {
                            unregisterHost((View) self);
                        }
                        return result;
                    }
                });
        return a + b;
    }

    private static int hookBatteryMeterView(XposedModule module, ClassLoader cl) {
        final Class<?> meter = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiBatteryMeterView", cl);
        if (meter == null) {
            log(module, "MiuiBatteryMeterView missing");
            return 0;
        }
        // Pin the style to 0: MIUI then shows the digital battery view and hides
        // its hollow variant, and never measures its own charging icon or
        // percentage container. While the module is off the request is passed
        // through untouched, so MIUI is fully in charge again; while it is on the
        // style is always 0 and the native bolt is handed back on the side - see
        // the hook below.
        final Field storeRealStyle = Refl.field(meter, "mStoreRealStyle");
        sStoreRealStyleField = storeRealStyle;
        // Resolved through getDeclaredMethod rather than looked up by name on
        // every call: Refl.callByName uses getMethod, which cannot see a
        // non-public member, and the visibility of MIUI's private fields and
        // methods is not part of any contract we can rely on.
        final Method updateChargeAndText = Refl.method(meter, "updateChargeAndText");
        sUpdateChargeAndTextField = updateChargeAndText;
        sChargingViewField = Refl.field(meter, "mBatteryChargingView");
        sPercentContainerField = Refl.field(meter, "mBatteryPercentContainer");
        // Needed to hand MIUI its own charging bolt back: mBatteryStyle is the
        // switch its onMeasure, onLayout and updateChargeAndText all read, and
        // the two battery views have to be left the way a style-1 meter would
        // look, which is the opposite of the swap onBatteryStyleChanged performs.
        sBatteryStyleField = Refl.field(meter, "mBatteryStyle");
        sBatteryIconViewField = Refl.field(meter, "mBatteryIconView");
        sHollowBatteryIconViewField = Refl.field(meter, "mHollowBatteryIconView");
        final Method styleChanged = Refl.method(meter, "onBatteryStyleChanged", int.class);
        sStyleChangedField = styleChanged;
        final int a = hook(module, styleChanged,
                "hyperduo-style", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object self = chain.getThisObject();
                        final Object requested = chain.getArg(0);
                        if (self instanceof View) {
                            rememberMeter((View) self);
                        }
                        final TrioAppearance appearance = TrioConfig.appearance();
                        // The stock bolt is ours to suppress exactly while the
                        // glyph draws its own, which is one rule and lives in
                        // one place now.
                        final boolean glyphBolt = appearance.hidesNativeBolt();
                        if (!appearance.glyph) {
                            // Nothing of ours is involved: pass the real style
                            // through and leave MIUI's own bolt alone.
                            //
                            // The field is cleared first, though. While the module
                            // was on and the glyph was drawing no bolt of its own,
                            // handBackBolt wrote BOLT_STYLE into mBatteryStyle -
                            // but onBatteryStyleChanged leaves the two battery
                            // views the way *it* wants them, which for BOLT_STYLE
                            // is the hollow outline on and the normal icon off.
                            // MIUI guards that work on "the style really changed"
                            // and would therefore skip it, leaving the normal icon
                            // in place of the hollow one. The constructor's own
                            // value is the one state that makes the guard pass.
                            Refl.set(sBatteryStyleField, self, Integer.valueOf(-1));
                            return chain.proceed();
                        }
                        // Always ask for 0, even when MIUI's bolt is wanted. The
                        // style-1 branch of onBatteryStyleChanged hides
                        // mBatteryIconView - the view the glyph is drawn on - so
                        // MIUI must never be allowed to take that branch while the
                        // module is on. The bolt is handed back by writing the
                        // field below, which no other code path reads.
                        final Object result = chain.proceed(new Object[]{Integer.valueOf(0)});
                        // L574 assigns mStoreRealStyle = i *before* the style guard,
                        // so a forced style clobbers the real one. Keyguard reads it
                        // (KeyguardStatusBarViewControllerInject: "mStoreRealStyle != 3")
                        // to choose between its icon-container and battery alpha
                        // animations, so restore the caller's value.
                        Refl.set(storeRealStyle, self, requested);
                        if (!glyphBolt) {
                            handBackBolt(self);
                        }
                        return result;
                    }
                });
        // updateChargeAndText() re-shows those two views on every state change.
        final int b = hook(module, updateChargeAndText,
                "hyperduo-charge-text", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View) {
                            rememberMeter((View) self);
                        }
                        final TrioAppearance appearance = TrioConfig.appearance();
                        if (appearance.hidesNativeBolt()) {
                            // The glyph draws the bolt, so MIUI's own copy of it
                            // must not show up next to the ring.
                            hide(Refl.get(sChargingViewField, self));
                        }
                        // Otherwise MIUI's own decision stands: handBackBolt and
                        // the module's own hand-back leave the visibility to this
                        // method, and re-hiding here would undo them.
                        if (appearance.glyph) {
                            // The percentage container is only measured and shown
                            // at battery style 3, which the module never asks for,
                            // so it is ours to hide for as long as we are on.
                            hide(Refl.get(sPercentContainerField, self));
                        }
                        return result;
                    }
                });
        return a + b;
    }

    /**
     * Gives MIUI's own charging bolt back to the meter it belongs to.
     *
     * <p>Written as a field assignment instead of a
     * {@code onBatteryStyleChanged(BOLT_STYLE)} call on purpose: that method's
     * style-1 branch hides {@code mBatteryIconView}, which is exactly the view
     * the trio glyph is drawn on, so going through it would trade the native bolt
     * for the glyph. Nothing else in the firmware assigns {@code mBatteryStyle}
     * - only the constructor and that method - so the value survives, and the
     * three readers that matter all act on it: {@code onMeasure} measures the
     * bolt, {@code onLayout} puts it right after the battery, and
     * {@code updateChargeAndText} shows it when the battery is charging.
     *
     * <p>The two battery views are restored to their style-1 look first, to undo
     * MIUI's own swap: the glyph host stays visible and the hollow outline stays
     * gone. Then MIUI is asked to redo its charge text, so the bolt's visibility
     * comes from the battery state rather than from us.
     */
    private static void handBackBolt(Object meter) {
        Refl.set(sBatteryStyleField, meter, Integer.valueOf(BOLT_STYLE));
        showIfHidden(Refl.get(sBatteryIconViewField, meter));
        hide(Refl.get(sHollowBatteryIconViewField, meter));
        Refl.invoke(sUpdateChargeAndTextField, meter);
    }

    /** Records a meter view so a later settings change can re-apply its style. */
    private static void rememberMeter(View meter) {
        synchronized (METERS) {
            for (int i = 0; i < METERS.size(); i++) {
                if (METERS.get(i).get() == meter) {
                    return;
                }
            }
            METERS.add(new WeakReference<>(meter));
        }
    }

    /**
     * Re-applies our slots after the one call that clears them:
     * {@code MiuiPhoneStatusBarView.updateCutoutLocation} invokes
     * {@code setIgnoredSlots(RIGHT_BLOCK_LIST)}, which clears before adding.
     */
    private static int hookStatusBarView(XposedModule module, ClassLoader cl) {
        final Class<?> bar = Refl.cls(
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", cl);
        if (bar == null) {
            log(module, "MiuiPhoneStatusBarView missing");
            return 0;
        }
        return hook(module, Refl.method(bar, "updateCutoutLocation"),
                "hyperduo-cutout", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        syncSlots(sStatusIconContainer);
                        return result;
                    }
                });
    }

    /**
     * Learns the current Wi-Fi / mobile signal levels. Both binders call
     * {@code imageView.setTag(rawResId)} immediately before passing that same raw
     * id to {@code transformResId}, so the first argument is the signal icon being
     * applied right now.
     */
    private static int hookSignalIcons(XposedModule module, ClassLoader cl) {
        final Class<?> helper = Refl.cls(
                "com.android.systemui.statusbar.MiuiStatusBarIconViewHelper", cl);
        if (helper == null) {
            log(module, "MiuiStatusBarIconViewHelper missing");
            return 0;
        }
        return hook(module, Refl.method(helper, "transformResId",
                        int.class, boolean.class, boolean.class),
                "hyperduo-signal", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object raw = chain.getArg(0);
                        final int beforeWifi = TrioState.sWifiLevel;
                        final int beforeMobile = TrioState.sMobileLevel;
                        final Object result = chain.proceed();
                        if (raw instanceof Number) {
                            TrioState.noteSignalIcon(
                                    resources(), ((Number) raw).intValue());
                            // MIUI only re-applies an icon when the reading behind
                            // it changed, so this is the event that says the
                            // per-SIM levels are stale. The icons name no SIM, so
                            // telephony is asked directly - and only when a
                            // per-SIM reading is actually wanted: the glyph's
                            // second row, or the out-of-ring stacked reading.
                            final boolean simsMoved = (TrioConfig.get().dualSim
                                    || TrioConfig.appearance().stackedOut())
                                    && TrioState.pollSimsNow();
                            if (beforeWifi != TrioState.sWifiLevel
                                    || beforeMobile != TrioState.sMobileLevel
                                    || simsMoved) {
                                invalidateHosts();
                            }
                        }
                        return result;
                    }
                });
    }

    /**
     * Samples the mobile network type label ("5G", "5GA", "4G") that MIUI is
     * about to draw.
     *
     * <p>{@code MobileTypeDrawable.mMobileType} is owned by the
     * {@code R.id.mobile_type} image view, which has no stable global entry
     * point, so the string is sampled where it is used instead:
     * {@code measure()} runs in lockstep with MIUI's own refresh and is called
     * only when the label actually changed
     * ({@code MiuiMobileIconBinder} L1366-1373).
     *
     * <p>The read happens after {@code chain.proceed()} because {@code measure()}
     * is what normalises the label: it rewrites {@code "5G++"} into {@code "5G"}
     * plus a separate double-plus flag, so reading beforehand would surface a
     * string MIUI never paints.
     */
    private static int hookMobileType(XposedModule module, ClassLoader cl) {
        final Class<?> drawable = Refl.cls(
                "com.miui.systemui.statusbar.views.MobileTypeDrawable", cl);
        if (drawable == null) {
            log(module, "MobileTypeDrawable missing");
            return 0;
        }
        final Field type = Refl.field(drawable, "mMobileType");
        if (type == null) {
            log(module, "MobileTypeDrawable.mMobileType missing");
            return 0;
        }
        final int sampled = hook(module, Refl.method(drawable, "measure"),
                "hyperduo-mobile-type", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final String before = TrioState.sMobileType;
                        final Object result = chain.proceed();
                        final Object raw = Refl.get(type, chain.getThisObject());
                        if (raw instanceof String
                                && TrioState.setMobileType((String) raw)
                                && !before.equals(TrioState.sMobileType)) {
                            invalidateHosts();
                        }
                        return result;
                    }
                });
        // Draw-level suppression. The classic mobile_type ImageView is re-shown
        // by its binder's own flow, which does not go through setChildVisible -
        // and its drawable paints text without consulting the view's bounds, so
        // even the folded slot's 0x0 frame does not stop the ink (MIUI sets
        // clipChildren=false up the chain). Gate only this dedicated drawable's
        // draw, never View.draw: the sampling above stays intact either way, and
        // the module's replacement label reads the same string from it.
        final int drawn = hook(module, Refl.method(drawable, "draw", Canvas.class),
                "hyperduo-mobile-type-draw", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final TrioAppearance a = TrioConfig.appearance();
                        if (a.glyph && a.typeOutOfRing) {
                            return null;
                        }
                        return chain.proceed();
                    }
                });
        return sampled + drawn;
    }

    /**
     * Turns MIUI's own "show the network type" requests into "hide it" while the
     * module draws the type itself.
     *
     * <p>{@code MobileSignalAnimatorContainer.setChildVisible(View, boolean)} is
     * the one door every native type-show walks through:
     * {@code MiuiMobileIconBinder} collects a {@code mobileTypeSingleVisible}
     * flow and calls it for both the HyperOS {@code mobile_type_single} TextView
     * and the classic {@code mobile_signal_container} group. Flipping the
     * boolean here kills the show at the source - the appear animation never
     * starts, so there is no one-frame flash the way there would be if the
     * module only re-hid the view after every layout.
     *
     * <p>The same container also serves volte / vowifi / roaming, whose shows
     * must pass untouched: the flip is gated on the view actually being one of
     * the native type views, resolved once per view by id and remembered in
     * {@link #COLLAPSED}-adjacent fashion through the view's own tag slot is
     * deliberately avoided - a tag write could collide with MIUI's
     * {@code status_bar_view_state_tag}. The id check is cheap enough per call:
     * one memoised map look-up.
     *
     * <p>Only the {@code true} requests are touched, and only while the module
     * is drawing the type itself (out of ring). Every other call proceeds
     * unchanged, so a firmware rename or an unexpected caller costs nothing.
     */
    private static int hookMobileTypeVisibility(XposedModule module, ClassLoader cl) {
        final Class<?> container = Refl.cls(
                "com.android.systemui.statusbar.views.MobileSignalAnimatorContainer", cl);
        if (container == null) {
            log(module, "MobileSignalAnimatorContainer missing");
            return 0;
        }
        return hook(module, Refl.method(container, "setChildVisible",
                        View.class, boolean.class),
                "hyperduo-mobile-type-visible", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final TrioAppearance a = TrioConfig.appearance();
                        if (a.glyph && a.typeOutOfRing) {
                            final Object arg0 = chain.getArg(0);
                            final Object arg1 = chain.getArg(1);
                            if (arg0 instanceof View && isNativeTypeView((View) arg0)) {
                                // Both directions are suppressed, not just the
                                // show: a false request on a visible target
                                // makes the animator clone it into a transient
                                // disappearance view, which paints the type
                                // even after the slot has been folded away.
                                // Marking it INVISIBLE first still lets the
                                // call proceed, so MIUI cleans up any clone
                                // bookkeeping it already holds.
                                if (!Boolean.TRUE.equals(arg1)) {
                                    prepareNativeTypeHide((View) arg0);
                                }
                                return chain.proceed(new Object[]{arg0, Boolean.FALSE});
                            }
                        }
                        return chain.proceed();
                    }
                });
    }

    /**
     * Drops a native type view to INVISIBLE before MIUI animates it away, so
     * the disappearance clone starts from an invisible original. Recorded in
     * the collapse book, which is what the module's own restore path reads -
     * these are views this module hid.
     */
    private static void prepareNativeTypeHide(View view) {
        if (view.getVisibility() == View.VISIBLE && markCollapsed(view)) {
            try {
                view.setVisibility(View.INVISIBLE);
            } catch (Throwable ignored) {
                unmarkCollapsed(view);
            }
        }
    }

    /**
     * Keeps the trio glyph on the bar while the charging super island is up.
     *
     * <p>{@code MiuiBatteryMeterView.updateIslandChanged(boolean, boolean)} is
     * the one callee MIUI drives when the island appears or disappears; it
     * normally lands in {@code setIsHideBattery(true)}, which stops the battery
     * container reserving the meter's width - the native icon row slides right,
     * and the glyph the module paints into that meter would be left floating in
     * the vacated space. The module instead declines the replacement: the meter
     * keeps its place, the row never moves, and the glyph - plus the arcs and
     * dots - simply stay where they were while the island draws beside them.
     * On-device verification for this shape is PR #10's.
     *
     * <p>The real request is remembered per meter in
     * {@link #BATTERY_ISLAND_REQUESTS}, and {@link #replayBatteryIslandRequests}
     * re-drives every remembered meter when a settings change flips
     * {@code enabled}: a meter mid-island when the module switches off must get
     * the replacement MIUI asked for, or the bar would sit batteryless until the
     * next island event. {@code mStoreIsAddBatteryIsland} is restored after the
     * call for the same reason - it is the value other code reads back.
     *
     * <p>The {@code setIsHideBattery} hook stays as the fallback path: if this
     * meter hook is unavailable on some firmware, the old handover (hide the
     * glyph, hand Wi-Fi back, read the signal out of ring) still applies, driven
     * by the flag the setter records.
     */
    private static int hookIslandHide(XposedModule module, ClassLoader cl) {
        final Class<?> container = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiStatusBatteryContainer", cl);
        if (container == null) {
            log(module, "MiuiStatusBatteryContainer missing");
            return 0;
        }
        final Class<?> meter = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiBatteryMeterView", cl);
        int n = 0;
        if (meter != null) {
            n += hook(module, Refl.method(meter, "updateIslandChanged",
                            boolean.class, boolean.class),
                    "hyperduo-keep-island-battery", new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return keepBatteryDuringIsland(chain);
                        }
                    });
        }
        n += hook(module, Refl.method(container, "setIsHideBattery", Boolean.class),
                "hyperduo-island-hide", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object arg = chain.getArg(0);
                        final boolean hide = Boolean.TRUE.equals(arg);
                        final boolean changed = TrioState.sIslandHideBattery != hide;
                        final Object result = chain.proceed();
                        if (changed) {
                            TrioState.sIslandHideBattery = hide;
                            reactToIslandChange();
                        }
                        return result;
                    }
                });
        return n;
    }

    /** Real island requests per meter, so a mid-island disable can replay them. */
    private static final Map<Object, boolean[]> BATTERY_ISLAND_REQUESTS =
            new WeakHashMap<Object, boolean[]>();

    private static Object keepBatteryDuringIsland(XposedInterface.Chain chain) throws Throwable {
        final boolean requested = Boolean.TRUE.equals(chain.getArg(0));
        final boolean trigger = Boolean.TRUE.equals(chain.getArg(1));
        synchronized (BATTERY_ISLAND_REQUESTS) {
            BATTERY_ISLAND_REQUESTS.put(chain.getThisObject(),
                    new boolean[] {requested, trigger});
        }
        // Declining the replacement while the module owns the glyph: the flag
        // that stops the container from reserving the meter's width stays down,
        // so the bar keeps the glyph exactly where it was. The island window
        // and its clearance stay MIUI's - only the meter's participation moves.
        final Object result = chain.proceed(new Object[] {
                Boolean.valueOf(requested && !TrioConfig.get().enabled),
                Boolean.valueOf(trigger)});
        final Object meter = chain.getThisObject();
        Refl.set(Refl.field(meter.getClass(), "mStoreIsAddBatteryIsland"), meter,
                Boolean.valueOf(requested));
        return result;
    }

    /**
     * Re-drives every meter whose island request the module declined, with the
     * real values. Run from a settings change: a meter that was mid-island when
     * the module switched off must still receive MIUI's original request, or it
     * would keep painting a battery the island had replaced until the next
     * island event.
     */
    private static void replayBatteryIslandRequests() {
        final Map<Object, boolean[]> requests;
        synchronized (BATTERY_ISLAND_REQUESTS) {
            requests = new HashMap<Object, boolean[]>(BATTERY_ISLAND_REQUESTS);
        }
        for (Map.Entry<Object, boolean[]> entry : requests.entrySet()) {
            final boolean[] args = entry.getValue();
            Refl.callArgs(entry.getKey(), "updateIslandChanged",
                    new Class<?>[] {boolean.class, boolean.class},
                    new Object[] {Boolean.valueOf(args[0]), Boolean.valueOf(args[1])});
        }
    }

    /**
     * One re-fold and one resync after the island flag moved, posted to the
     * main looper - the same shape {@code applyConfigChange} gives a settings
     * change, minus the meter restyling the island does not touch.
     */
    private static void reactToIslandChange() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    reactToIslandChange();
                }
            });
            return;
        }
        refoldContainers();
        final List<TrioState> hosts;
        synchronized (HOSTS) {
            hosts = new ArrayList<TrioState>(HOSTS);
        }
        for (int i = 0; i < hosts.size(); i++) {
            final View v = hosts.get(i).host;
            if (v == null) {
                continue;
            }
            v.post(new Runnable() {
                @Override
                public void run() {
                    foldHostContainer(v);
                    v.requestLayout();
                    syncOutSignal(v);
                    syncOutTypeLabel(v);
                    v.invalidate();
                }
            });
        }
    }

    /** Memoised view-id to "is a native network-type view" answers. */
    private static final Map<Integer, Boolean> NATIVE_TYPE_VIEW_CACHE =
            new HashMap<Integer, Boolean>();

    /**
     * Whether {@code view} is one of the native views that draw the network
     * type, matched by the resource name of its id. The two candidates are the
     * classic {@code mobile_type} ImageView and the HyperOS
     * {@code mobile_type_single} TextView; anything else - the signal image,
     * the volte and vowifi views, the dot - is not.
     */
    private static boolean isNativeTypeView(View view) {
        final int id = view.getId();
        if (id <= 0) {
            return false;
        }
        synchronized (NATIVE_TYPE_VIEW_CACHE) {
            final Boolean cached = NATIVE_TYPE_VIEW_CACHE.get(id);
            if (cached != null) {
                return cached.booleanValue();
            }
        }
        String name = null;
        try {
            final android.content.res.Resources res = view.getResources();
            if (res != null) {
                name = res.getResourceEntryName(id);
            }
        } catch (Throwable ignored) {
            // a foreign id is simply not one of ours
        }
        final boolean match = "mobile_type".equals(name)
                || "mobile_type_single".equals(name);
        synchronized (NATIVE_TYPE_VIEW_CACHE) {
            NATIVE_TYPE_VIEW_CACHE.put(Integer.valueOf(id), Boolean.valueOf(match));
        }
        return match;
    }

    /**
     * Recolours the out-of-ring views in the same beat MIUI tints the meter.
     *
     * <p>The tint pipeline reaches this module's two sibling views only
     * indirectly: the tint animation runs once per animation frame through
     * {@code MiuiBatteryMeterView.updateLightDarkTint}, which ends in the
     * battery icon's own {@code invalidate()} - and the icon's next draw is
     * where the draw-pass comparison picks the new ink up. That indirection is
     * the "delayed transition" of issue #2: the native icons ride MIUI's
     * animation, while the label and the reading wait for the glyph's next
     * frame and then flip in one step.
     *
     * <p>Hooking {@code updateLightDarkTint} (the single door both the animated
     * and the instant path walk through - see {@code onLightDarkTintChanged}
     * and the value animator inside the meter) puts the two views on MIUI's own
     * schedule: after the meter has applied this frame's tint, the frame's
     * final tint colour is handed to {@link #applyOutRingInk} directly, no post
     * and no draw needed, so label and reading blend at the same pace as every
     * native icon.
     *
     * <p>The hook fires per animation frame (a transition is a handful of
     * frames), and the body is two field reads and a colour compare per frame
     * - the same weight MIUI itself pays per frame. No book is needed here:
     * {@code applyOutRingInk} compares before writing, and the draw-pass
     * comparison stays as the safety net for tint changes that arrive without
     * this method (a config change, a restored view tree).
     */
    private static int hookMeterTint(XposedModule module, ClassLoader cl) {
        final Class<?> meter = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiBatteryMeterView", cl);
        if (meter == null) {
            log(module, "MiuiBatteryMeterView missing");
            return 0;
        }
        return hook(module, Refl.method(meter, "updateLightDarkTint",
                        ArrayList.class, float.class, int.class, int.class,
                        int.class, boolean.class),
                "hyperduo-meter-tint", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof View && TrioConfig.appearance().typeAnywhere()) {
                            // Signature: (areas, intensity, tintColor, light, dark,
                            // useTint). Resolve the ink exactly the way
                            // TrioState.foreground() resolves it from the very
                            // fields this call installs, so the tint-path colour
                            // and the draw-path colour are one number by
                            // construction.
                            final boolean useTint = Boolean.TRUE.equals(chain.getArg(5));
                            final int intensity =
                                    (chain.getArg(1) instanceof Float)
                                            ? ((Float) chain.getArg(1)).intValue() : 0;
                            final Object tintObj = chain.getArg(2);
                            final Object lightObj = chain.getArg(3);
                            final Object darkObj = chain.getArg(4);
                            final int tint = (tintObj instanceof Integer)
                                    ? (Integer) tintObj : 0;
                            final int light = (lightObj instanceof Integer)
                                    ? (Integer) lightObj : 0;
                            final int dark = (darkObj instanceof Integer)
                                    ? (Integer) darkObj : 0;
                            int ink = useTint ? tint
                                    : (intensity > 0f ? dark : light);
                            if (ink == 0) {
                                // Same fold TrioState.foreground() applies, so a
                                // not-yet-tinted meter paints white here too.
                                ink = 0xFFFFFFFF;
                            }
                            final View v = (View) self;
                            final Object owner = batteryContainerOf(v);
                            if (owner instanceof ViewGroup) {
                                applyOutRingInk((ViewGroup) owner, ink);
                            }
                        }
                        return result;
                    }
                });
    }

    /**
     * The one hooker both expansion entry points share: the panel's injector
     * step and the controller's own setter. Whichever one a given drag path
     * takes, the window hears about it on the same frame.
     */
    private static final XposedInterface.Hooker SHADE_HOOKER = new XposedInterface.Hooker() {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            final Object result = chain.proceed();
            final Object height = chain.getArg(0);
            if (height instanceof Float) {
                noteShadeInjector(chain.getThisObject());
                TrioOverlay.onShadeHeight((Float) height);
            }
            return result;
        }
    };

    /**
     * Learns when the shade is being pulled down, so the glyph window can leave
     * the screen on the first frame of the drag instead of after the animation.
     *
     * <p>Two entry points are hooked because MIUI's drag path does not go
     * through the controller's setter: the touch handler calls the injector's
     * {@code setExpandedHeightInternal} directly, while the animation and the
     * programmatic paths go through {@code setExpandedHeight}. The height is
     * zero while the panel is closed, so a value above zero is the earliest
     * honest signal that the bar is no longer standing still.
     */
    private static int hookShadeExpansion(XposedModule module, ClassLoader cl) {
        int n = 0;
        final Class<?> injector = Refl.cls(
                "com.android.systemui.shade.NotificationPanelViewControllerInjector", cl);
        if (injector != null) {
            n += hook(module, Refl.method(injector, "setExpandedHeightInternal", float.class),
                    "hyperduo-shade-internal", SHADE_HOOKER);
        }
        final Class<?> panel = Refl.cls(
                "com.android.systemui.shade.NotificationPanelViewController", cl);
        if (panel != null) {
            n += hook(module, Refl.method(panel, "setExpandedHeight", float.class),
                    "hyperduo-shade-height", SHADE_HOOKER);
        }
        if (n == 0) {
            log(module, "shade expansion hooks missing");
        }
        return n;
    }

    /**
     * Hears the launcher's overview (recents) gesture. The launcher drives it
     * over ISystemUiProxy and SystemUI's own proxy class answers on the binder
     * thread; the method it ends in is the one signal that exists on this
     * build, so it is used as a pulse: every report pushes the bar's return a
     * little further out, and the last one lets it come back.
     */
    private static int hookOverviewGesture(XposedModule module, ClassLoader cl) {
        final Class<?> proxy = Refl.cls(
                "com.android.systemui.recents.LauncherProxyService", cl);
        if (proxy == null) {
            log(module, "LauncherProxyService missing");
            return 0;
        }
        final Method done = Refl.method(proxy, "notifyAssistantGestureCompletion",
                float.class);
        if (done == null) {
            log(module, "notifyAssistantGestureCompletion missing");
            return 0;
        }
        return hook(module, done, "hyperduo-overview", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final Object result = chain.proceed();
                TrioOverlay.onOverviewPulse();
                return result;
            }
        });
    }

    /**
     * The panel behind the expansion callback, remembered so its height can be
     * read later: a flag set by a callback needs a way to be cleared when the
     * callback that would clear it never comes.
     */
    private static volatile Object sPanel;
    private static Field sPanelHeight;

    static void noteShadeInjector(Object injector) {
        if (injector == null || sPanel != null) {
            return;
        }
        try {
            final Method get = Refl.method(injector.getClass(), "getPanelViewController");
            final Object panel = (get == null) ? null : get.invoke(injector);
            if (panel == null) {
                return;
            }
            sPanelHeight = Refl.field(panel.getClass(), "mExpandedHeight");
            if (sPanelHeight != null) {
                sPanel = panel;
            }
        } catch (Throwable t) {
            log(sModule, "shade panel unavailable: " + t);
        }
    }

    /**
     * Hears the overview gesture where the launcher actually sends it.
     *
     * <p>The gesture arrives as a binder transaction on SystemUI's own overview
     * proxy, and transaction 4 is the progress report the launcher sends while
     * the finger is moving. The methods behind it are not always present on a
     * given build, but the transaction number is the interface itself, so this
     * is the signal that exists everywhere. Only the transaction number is read
     * - the parcel is left untouched for the code that has to parse it.
     */
    private static int hookOverviewProxy(XposedModule module, ClassLoader cl) {
        final Class<?> proxy = Refl.cls("com.android.systemui.recents.MiuiOverviewProxy", cl);
        if (proxy == null) {
            log(module, "MiuiOverviewProxy missing");
            return 0;
        }
        return hook(module, Refl.method(proxy, "onTransact", int.class,
                        android.os.Parcel.class, android.os.Parcel.class, int.class),
                "hyperduo-overview-proxy", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object code = chain.getArg(0);
                        final Object result = chain.proceed();
                        if (code instanceof Integer && (Integer) code == 4) {
                            TrioOverlay.onOverviewPulse();
                        }
                        return result;
                    }
                });
    }

    /**
     * Hears the overview gesture on the proxy the launcher really talks to.
     *
     * <p>The gesture does not go through SystemUI's own overview proxy: the
     * launcher reports its progress over ISystemUiProxy, whose implementation
     * answers on the binder thread and reads the float out of the parcel in
     * transaction 13. Only the transaction number is read here - the parcel is
     * left untouched for the code that has to parse it - which is what makes
     * this hook safe to sit on a call that runs for every frame of a drag.
     */
    private static int hookOverviewProgress(XposedModule module, ClassLoader cl) {
        final Class<?> proxy = Refl.cls(
                "com.android.systemui.recents.LauncherProxyService$1", cl);
        if (proxy == null) {
            log(module, "LauncherProxyService$1 missing");
            return 0;
        }
        return hook(module, Refl.method(proxy, "onTransact", int.class,
                        android.os.Parcel.class, android.os.Parcel.class, int.class),
                "hyperduo-overview-transact", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object code = chain.getArg(0);
                        final Object result = chain.proceed();
                        if (code instanceof Integer && (Integer) code == 13) {
                            TrioOverlay.onOverviewPulse();
                        }
                        return result;
                    }
                });
    }

    /**
     * Hears the moment an app takes the screen.
     *
     * <p>The status bar window controller is told when the bar is running an
     * app's launch animation - the first thing that happens when a full-screen
     * app like the camera comes forward, and well before the bar's own row
     * fades out. Taking the window down on that report is what keeps the glyph
     * from being the last thing on a bar that is on its way out.
     */
    private static int hookLaunchAnimation(XposedModule module, ClassLoader cl) {
        final Class<?> controller = Refl.cls(
                "com.android.systemui.statusbar.window.StatusBarWindowControllerImpl", cl);
        final Class<?> state = Refl.cls(
                "com.android.systemui.statusbar.window.StatusBarWindowControllerImpl$State", cl);
        if (controller == null || state == null) {
            log(module, "status bar window controller missing");
            return 0;
        }
        final Field launching = Refl.field(state, "mIsLaunchAnimationRunning");
        if (launching == null) {
            log(module, "mIsLaunchAnimationRunning missing");
            return 0;
        }
        return hook(module, Refl.method(controller, "apply", state),
                "hyperduo-launch-animation", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object value = launching.get(chain.getArg(0));
                        if (value instanceof Boolean && (Boolean) value) {
                            TrioOverlay.onOverviewPulse();
                        }
                        return result;
                    }
                });
    }

    /**
     * The colour SystemUI last painted the icons of one row in, keyed by that
     * row's battery container.
     *
     * <p>Keyed per row because several rows are on screen at once and they are
     * <em>not</em> tinted alike: the status bar sits on the launcher's light
     * surface while the control centre beside it is dark, and both are tinted in
     * the same frame. One shared slot therefore lets whichever row was tinted
     * last decide for all of them, and every glyph draws in the opposite colour
     * to the icons beside it - the inversion this replaced. The values are bare
     * {@code Integer}s, so - unlike a {@code WeakHashMap<View, TextView>} - they
     * cannot reach back and keep their own keys alive.
     */
    private static final Map<View, Integer> ROW_INK =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());

    /**
     * The colour SystemUI last painted the icons of {@code row}'s own row in, or
     * {@code 0} when that row has not been tinted yet.
     */
    static int rowInk(View row) {
        if (row == null) {
            return 0;
        }
        final Integer ink = ROW_INK.get(row);
        return ink == null ? 0 : ink;
    }

    /** The row (battery container) {@code host} belongs to, or null. */
    static View rowOf(View host) {
        return batteryContainerOf(host);
    }

    /**
     * Hears the colour SystemUI paints its own icons in.
     *
     * <p>The tint fields the colour rule reads are set when the bar is built and
     * lag behind a change of background; the call SystemUI makes to repaint the
     * icons does not - it happens as the change lands. Both spellings are hooked
     * because both are in the tree: {@code onDarkChanged} is the AOSP one, and
     * {@code setIconColor} is the one MIUI added around it. Only the colour
     * argument is read; the rest of the signature is left alone.
     */
    private static int hookIconTint(XposedModule module, ClassLoader cl) {
        final Class<?> icon = Refl.cls("com.android.systemui.statusbar.StatusBarIconView", cl);
        if (icon == null) {
            log(module, "StatusBarIconView missing");
            return 0;
        }
        final XposedInterface.Hooker hooker = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final Object result = chain.proceed();
                final List<Object> args = chain.getArgs();
                if (args == null) {
                    return result;
                }
                int colour = 0;
                for (int i = 0; i < args.size(); i++) {
                    final Object arg = args.get(i);
                    if (arg instanceof Integer) {
                        colour = (Integer) arg;
                        break;
                    }
                }
                if (colour == 0) {
                    return result;
                }
                // Filed against the row this icon belongs to, not one global
                // cell: the status bar and the control centre are tinted in the
                // same frame and to opposite colours, so a shared cell hands
                // whichever was tinted last to both and inverts one of them.
                final Object self = chain.getThisObject();
                if (self instanceof View) {
                    final View row = rowOf((View) self);
                    if (row != null) {
                        ROW_INK.put(row, colour);
                    }
                }
                return result;
            }
        };
        int n = 0;
        n += hook(module, Refl.method(icon, "onDarkChanged",
                        java.util.ArrayList.class, float.class, int.class),
                "hyperduo-tint-dark", hooker);
        n += hook(module, Refl.method(icon, "setIconColor", int.class, boolean.class),
                "hyperduo-tint-set", hooker);
        if (n == 0) {
            log(module, "no icon tint hook");
        }
        return n;
    }

    /**
     * Folds the keyguard row's icons when that row attaches.
     *
     * <p>{@link #refoldContainers()} runs on a config change and on an island
     * change, and the keyguard row's container reaches {@link #settle} down the
     * layout path only while it is still laying out. A row that attaches after
     * that - which is what a SystemUI restart with the lock screen already up
     * produces - keeps its native icons until something else happens to fold it,
     * and nothing else necessarily does. That is the shape of the report where
     * restarting SystemUI a few times makes the lock screen come good.
     *
     * <p>Attaching is the one moment that always happens for the row, so the fold
     * is asked for there. The layout path and the draw pass keep their own
     * attempts; all three end in the same idempotent call.
     */
    private static int hookKeyguardBar(XposedModule module, ClassLoader cl) {
        final Class<?> bar = Refl.cls(
                "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView", cl);
        if (bar == null) {
            log(module, "MiuiKeyguardStatusBarView missing");
            return 0;
        }
        final XposedInterface.Hooker hooker = new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final Object result = chain.proceed();
                final Object self = chain.getThisObject();
                if (!(self instanceof View)) {
                    return result;
                }
                final View row = (View) self;
                // Posted, not run here: the row's icons are inflated as part of
                // attaching, and naming slots before that would name them to a
                // controller that has nothing to refresh yet.
                row.post(new Runnable() {
                    @Override
                    public void run() {
                        // MIUI re-lays the row's icons out after it attaches, and a
                        // GONE plus a zero-size layout does not survive that: the log
                        // shows the mobile views folded (v=8, w=0) and then back
                        // (v=0, w=56) a moment later. The row's own ignore list is
                        // what MIUI consults when it lays them out, so the slots this
                        // module draws itself are named there.
                        blockIcons(row);
                        foldContainers(row);
                    }
                });
                return result;
            }
        };
        // initCallback is the one that matters, and it is later than it looks:
        // onAttachedToWindow only calls super and then hands the real work to
        // initCallback, which is also reached from setDependency. It is the
        // method that finishes configuring the row - setNeedLimitIcon(true),
        // setAnimatorController - and that configuration is what undoes a fold
        // made while attaching, which is how the mobile views came back at
        // v=0 w=56 after being folded to v=8 w=0. Folding after initCallback
        // is what survives.
        //
        // The View entry points are kept as well: on some builds initCallback
        // is absent and attaching is the only moment the row is complete.
        int n = hook(module, Refl.method(bar, "initCallback", String.class),
                "hyperduo-keyguard-init", hooker);
        n += hook(module, Refl.method(bar, "miuiOnAttachedToWindow"),
                "hyperduo-keyguard-bar", hooker);
        n += hook(module, Refl.method(bar, "onAttachedToWindow"),
                "hyperduo-keyguard-attach", hooker);
        if (n == 0) {
            log(module, "no keyguard row attach hook");
        }
        return n;
    }

    /**
     * Names the slots this module draws itself in MIUI's own ignore list for the
     * keyguard row, and asks the row to re-run its layout.
     *
     * <p>This is the part that survives: MIUI lays the row's icons out again after
     * the row attaches, and it skips exactly the slots on this list. Folding them
     * after the fact is what the log shows being undone.
     */
    private static void blockIcons(Object bar) {
        try {
            final Object manager = Refl.get(Refl.field(bar.getClass(), "mTintedIconManager"), bar);
            if (manager == null) {
                log(sModule, "keyguard: no tinted icon manager");
                return;
            }
            final List<String> wanted = new ArrayList<String>(foldedSlots());
            // IconManager.setBlockList(List) clears the list, fills it and calls
            // refreshIconGroup in one go, and refreshIconGroup is what makes MIUI
            // lay the row's icons out again without the named slots. Writing the
            // list by hand and asking for the refresh separately does not work:
            // both the list and the controller that would be asked are on
            // IconManager, one level up from the manager the row holds.
            final Method[] methods = manager.getClass().getMethods();
            for (int i = 0; i < methods.length; i++) {
                final Method m = methods[i];
                if ("setBlockList".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    m.invoke(manager, wanted);
                    log(sModule, "keyguard block list set: " + wanted);
                    return;
                }
            }
            log(sModule, "keyguard: setBlockList missing");
        } catch (Throwable t) {
            log(sModule, "keyguard block list failed: " + t);
        }
    }

    /**
     * Hides a replaced icon the moment MIUI adds it, wherever it is added.
     *
     * <p>{@code IconManager.addHolder} is the one place every status icon is
     * mounted, old pipeline and new: it builds the view and adds it to the group
     * in the same call. That makes it the only point where hiding an icon cannot
     * be undone - MIUI is not restoring anything, it is adding it again, and this
     * runs on every addition.
     *
     * <p>It is also where the module's own block list falls short on this build:
     * the blocked flag {@code addHolder} computes is passed to the old-pipeline
     * view and to the network-speed view, but the modern mobile and wifi views are
     * constructed without it, which is why naming the slots in MIUI's list left
     * the lock screen's signal icons on screen.
     */
    private static int hookIconAdded(XposedModule module, ClassLoader cl) {
        final Class<?> manager = Refl.cls(
                "com.android.systemui.statusbar.phone.ui.IconManager", cl);
        if (manager == null) {
            log(module, "IconManager missing");
            return 0;
        }
        final Method[] methods = manager.getDeclaredMethods();
        int n = 0;
        for (int i = 0; i < methods.length; i++) {
            final Method m = methods[i];
            if (!"addHolder".equals(m.getName()) || m.getParameterTypes().length != 4) {
                continue;
            }
            n += hook(module, m, "hyperduo-icon-added", new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    final Object result = chain.proceed();
                    final Object slot = chain.getArg(1);
                    if (result instanceof View && slot instanceof String
                            && foldedSlots().contains(slot)) {
                        hideIcon((View) result);
                    }
                    return result;
                }
            });
        }
        if (n == 0) {
            log(module, "no addHolder hook");
        }
        return n;
    }

    /**
     * Takes an icon out of the layout and out of drawing.
     *
     * <p>Two ways, because there are two pipelines. The old one has already been
     * laid out by the time this runs, so it needs the zero-size layout and the
     * GONE - the same pair settle() uses. The new one asks
     * {@code ModernStatusBarView.isIconVisible()}, which is driven by the binding
     * and by {@code setVisibleState}, and never by {@code View.getVisibility()}:
     * a GONE there changes nothing, and MIUI restores the state on its own
     * schedule, which is what kept putting the lock screen's signal icons back.
     */
    private static void hideIcon(final View icon) {
        try {
            icon.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (icon.getWidth() != 0 || icon.getHeight() != 0) {
                            icon.layout(0, 0, 0, 0);
                        }
                        icon.setVisibility(View.GONE);
                    } catch (Throwable ignored) {
                        // never let one icon abort the pass
                    }
                    try {
                        final Method state = icon.getClass()
                                .getMethod("setVisibleState", int.class, boolean.class);
                        state.invoke(icon, Integer.valueOf(0), Boolean.TRUE);
                    } catch (Throwable ignored) {
                        // the old pipeline views have no such method
                    }
                }
            });
        } catch (Throwable ignored) {
            // posting is best-effort; the fold path still runs on layout
        }
    }

    /** Folds every icon container inside {@code view}. */
    private static void foldContainers(View view) {
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            if (sIconContainerClass != null && sIconContainerClass.isInstance(group)) {
                foldAndSettle(group);
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                foldContainers(group.getChildAt(i));
            }
        }
    }

    /** True when {@code view} sits inside the keyguard's own status bar row. */
    private static boolean isKeyguardRow(View view) {
        for (ViewParent p = view.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (p.getClass().getSimpleName().contains("Keyguard")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- registrations

    /**
     * Hides the native Wi-Fi / mobile views inside every icon container that
     * renders the trio glyph.
     *
     * <p>{@code ignoredSlots} alone only removes a child from measurement; the
     * layout pass still parks it at the container's left edge, which - in the
     * status bar - is the middle of the screen, so the orphaned "5G" label stays
     * visible next to the clock. Forcing the child {@code GONE} removes it from
     * the pass (and from drawing) entirely. Re-applying on every layout keeps it
     * gone when MIUI re-shows it.
     *
     * <p>Hiding is limited to containers that own a live host: a container whose
     * battery view is hidden (island, minimalism, control-center collapse) never
     * draws the glyph, and stripping its signal icons would leave it empty.
     */
    private static int hookIconContainerLayout(XposedModule module, ClassLoader cl) {
        final Class<?> container = Refl.cls(
                "com.android.systemui.statusbar.views.MiuiStatusIconContainer", cl);
        if (container == null) {
            log(module, "MiuiStatusIconContainer missing");
            return 0;
        }
        sIconContainerClass = container;
        sIgnoredSlotsField = Refl.field(container, "ignoredSlots");
        return hook(module, Refl.method(container, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class),
                "hyperduo-icon-layout", new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        final Object result = chain.proceed();
                        final Object self = chain.getThisObject();
                        if (self instanceof ViewGroup) {
                            settle((ViewGroup) self);
                        }
                        return result;
                    }
                });
    }

    /**
     * Brings one icon container into its steady state: our slots suppressed, the
     * folded native children collapsed out of the layout, and - just as
     * important - the children whose sub-toggle went off handed back to MIUI.
     * Runs inside {@code onLayout}, after the container has finished positioning
     * children.
     */
    /** Last container reported as skipped, so the same one is not logged twice. */
    private static volatile String sLastSkipped;

    /**
     * Records a container whose icons are left alone.
     *
     * <p>Hiding is limited to containers that own a live host, and a container
     * that is skipped keeps drawing its own signal icons. On the lock screen
     * that is what puts the native signal icons next to the glyph, so the chain
     * is written down: it says which container it was and where it sits.
     */
    private static void noteSkipped(View container) {
        final StringBuilder chain = new StringBuilder(container.getClass().getSimpleName());
        for (ViewParent p = container.getParent(); p != null && chain.length() < 200;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            chain.append('<').append(p.getClass().getSimpleName());
        }
        final String note = "skip container: " + chain;
        if (!note.equals(sLastSkipped)) {
            sLastSkipped = note;
            log(sModule, note);
        }
    }

    private static void settle(ViewGroup container) {
        if (!TrioConfig.get().enabled) {
            return;
        }
        ensureFolded(container);
        sampleSignalPresence(container);
        final boolean owned = isOwned(container);
        diagnose(container, owned);
        if (!owned) {
            noteSkipped(container);
            return;
        }
        // Refresh-only: this runs inside onLayout, so it may reposition the
        // label but must never add or remove one.
        // The reading first: the label stands against it when it is mounted, so
        // placing the label before it would place it against last frame's frame.
        refreshOutSignal(container);
        refreshOutTypeLabel(container);
        final List<String> wanted = foldedSlots();
        final int count = container.getChildCount();
        boolean relayout = false;
        for (int i = 0; i < count; i++) {
            final View child;
            try {
                child = container.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null) {
                continue;
            }
            final String slot = slotOf(child);
            if (slot == null || !MANAGED_SLOTS.contains(slot)) {
                continue;
            }
            if (!wanted.contains(slot)) {
                // The sub-toggle for this slot is off, so the native icon belongs
                // to MIUI again. Undo only what we did: a child MIUI itself hid
                // stays hidden, and the zero-size layout below heals on its own,
                // because MIUI lays out every slot that is not ignored.
                if (unmarkCollapsed(child)) {
                    try {
                        if (child.getVisibility() != View.VISIBLE) {
                            child.setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable ignored) {
                        // never let one child abort the pass
                    }
                    // The slot was ignored a moment ago, so the container still
                    // holds the measurement taken with this child collapsed.
                    relayout = true;
                }
                continue;
            }
            // Collapse the child out of the layout. MIUI's own onLayout lays every
            // child out at container-local x=0 in its first pass and its later
            // passes only move the ones it does *not* ignore, so an ignored
            // "mobile" stays stranded at the container's left edge - which for the
            // status bar is the middle of the screen. layout() does not schedule
            // another pass, so this cannot loop.
            try {
                if (child.getWidth() != 0 || child.getHeight() != 0) {
                    child.layout(0, 0, 0, 0);
                }
            } catch (Throwable ignored) {
                // never let one child abort the pass
            }
            // Also take it out of drawing. This has to be re-applied on every
            // pass, not once: gating it on markCollapsed meant the GONE was
            // applied the first time only, while MIUI keeps putting the child
            // back on its own schedule - the log shows a mobile view at v=8
            // and then at v=0 w=56 a few passes later, which is a signal
            // indicator that came back and stayed. The mark is bookkeeping for
            // restoreNative, so it is recorded here rather than used as a
            // condition; setVisibility is already a no-op once the child is
            // GONE, so re-applying it cannot schedule extra layouts.
            if (child.getVisibility() != View.GONE) {
                markCollapsed(child);
                try {
                    child.setVisibility(View.GONE);
                } catch (Throwable ignored) {
                    // never let one child abort the pass
                }
            }
        }
        // The strip this module reserves is what pushes the native icons out of
        // the row, and MIUI's own overflow pass does not act on it - see
        // hideOverflowingIcons. Applied here, after the container has finished
        // positioning its children.
        hideOverflowingIcons(container);
        if (relayout) {
            container.requestLayout();
        }
        suppressNativeTypeViews(container);
    }

    /** Resource names of the native views that draw the network-type text. */
    private static final String[] NATIVE_TYPE_IDS = {"mobile_type_single", "mobile_type"};

    /** Memoised {@code R.id} look-ups; both ids are stable for the process. */
    private static final Map<String, Integer> NATIVE_TYPE_VIEW_IDS =
            new HashMap<String, Integer>();

    /**
     * Hides or restores the native network-type text views inside an owned
     * container, the ones no slot fold reaches.
     *
     * <p>The type text lives <em>inside</em> the mobile slot group: the classic
     * {@code mobile_type} ImageView (a {@code MobileTypeDrawable}) in the signal
     * container, and the HyperOS {@code mobile_type_single} TextView beside it.
     * Folding the "mobile" slot GONEs the whole group, which should be enough -
     * except MIUI's own binder drives those children directly:
     * {@code MiuiMobileIconBinder} collects a {@code mobileTypeSingleVisible}
     * flow and calls {@code MobileSignalAnimatorContainer.setChildVisible},
     * which sets the child VISIBLE and plays an appear animation. Worse, the
     * disappear path clones the child and hangs the copy off the container root
     * as a transient view, drawing it outside the GONE ancestor entirely. Either
     * way the native "4G"/"5G" reappears - and since the first pass of
     * {@code MiuiStatusIconContainer.onLayout} parks every child at the
     * container's left edge, in the status bar that means the middle of the
     * screen.
     *
     * <p>Suppression is wanted exactly while the module draws the type itself,
     * out of ring: in-ring or off, the module draws no type and the native one
     * is the user's requested reading, so it is handed back. The hand-back only
     * touches views this module hid ({@link #unmarkCollapsed}), the same
     * principle {@code restoreNative} applies to the slots; the appear flow
     * re-runs on the next binder emit and re-shows the text on its own.
     *
     * <p>Callable from the layout pass: {@code setVisibility} on a view whose
     * visibility already agrees is a framework no-op, and the once-only
     * {@link #markCollapsed} guard keeps the binder's re-shows from scheduling a
     * layout per pass.
     */
    private static void suppressNativeTypeViews(ViewGroup container) {
        final boolean suppress = TrioConfig.appearance().typeOutOfRing;
        final android.content.res.Resources res = resources();
        if (res == null) {
            return;
        }
        final String pkg = "com.android.systemui";
        for (int i = 0; i < NATIVE_TYPE_IDS.length; i++) {
            final Integer id = nativeTypeId(res, pkg, NATIVE_TYPE_IDS[i]);
            if (id == null) {
                continue;
            }
            View target;
            try {
                target = container.findViewById(id.intValue());
            } catch (Throwable t) {
                continue;
            }
            if (target == null) {
                continue;
            }
            if (suppress) {
                if (target.getVisibility() == View.VISIBLE && markCollapsed(target)) {
                    try {
                        target.setVisibility(View.GONE);
                    } catch (Throwable ignored) {
                        unmarkCollapsed(target);
                    }
                }
            } else if (unmarkCollapsed(target)) {
                try {
                    if (target.getVisibility() != View.VISIBLE) {
                        target.setVisibility(View.VISIBLE);
                    }
                } catch (Throwable ignored) {
                    // never let one child abort the pass
                }
            }
        }
    }

    /** Cached {@code getIdentifier} for one of the native type-view names. */
    private static Integer nativeTypeId(android.content.res.Resources res,
                                        String pkg, String name) {
        synchronized (NATIVE_TYPE_VIEW_IDS) {
            final Integer cached = NATIVE_TYPE_VIEW_IDS.get(name);
            if (cached != null) {
                return cached.intValue() != 0 ? cached : null;
            }
            final int id;
            try {
                id = res.getIdentifier(name, "id", pkg);
            } catch (Throwable t) {
                NATIVE_TYPE_VIEW_IDS.put(name, Integer.valueOf(0));
                return null;
            }
            NATIVE_TYPE_VIEW_IDS.put(name, Integer.valueOf(id));
            return id != 0 ? Integer.valueOf(id) : null;
        }
    }

    /**
     * Records {@code child} as already hidden. Returns false when it was recorded
     * before, so the caller skips a repeat {@code setVisibility} - the redundant
     * call is a no-op for the framework but would re-enter layout if MIUI keeps
     * restoring the child.
     */
    private static boolean markCollapsed(View child) {
        synchronized (COLLAPSED) {
            return COLLAPSED.put(child, Boolean.TRUE) == null;
        }
    }

    /**
     * Forgets that {@code child} was hidden by us, returning true only for the
     * call that did so - the same once-only guard as {@link #markCollapsed}, for
     * the opposite direction. Without it, re-showing a child on every pass would
     * schedule a new layout each time MIUI hid it again.
     */
    private static boolean unmarkCollapsed(View child) {
        synchronized (COLLAPSED) {
            return COLLAPSED.remove(child) != null;
        }
    }

    /**
     * Samples which signal indicators the status bar is currently showing, from
     * the live view tree.
     *
     * <p>This is the only reliable source for the Wi-Fi indicator going away.
     * MIUI stops calling {@code MiuiStatusBarIconViewHelper.transformResId} as
     * soon as an indicator is not visible, so the level learned there goes stale:
     * switching Wi-Fi off left the last level behind and the arcs stayed on
     * screen forever.
     *
     * <p>Note what "present" means here. When Wi-Fi is switched off MIUI does
     * <em>not</em> remove the {@code slot=wifi} child — it keeps the view and
     * merely stops binding it, so the child count stays at one. The live answer
     * is {@code ModernStatusBarView.isIconVisible()}, which reads the binding.
     * Counting children instead would never see the indicator leave.
     *
     * <p>Only the Wi-Fi indicator is sampled. {@code isIconVisible()} stays true
     * on a mobile view that has already been faded out, so it cannot tell mobile
     * off from mobile on; the level dots keep being driven by the last signal
     * resource seen instead.
     *
     * <p>Only the authoritative status-bar container is sampled. The control
     * centre, the QS headers and the keyguard each inflate their own container
     * from the same layout, and those must never drive the shared state.
     */
    private static void sampleSignalPresence(ViewGroup container) {
        if (container == null || !isStatusBarContainer(container)) {
            return;
        }
        boolean wifiVisible = false;
        int count;
        try {
            count = container.getChildCount();
            for (int i = 0; i < count; i++) {
                final View child = container.getChildAt(i);
                if (child == null || !"wifi".equals(slotOf(child))) {
                    continue;
                }
                // Binding visibility is only a fallback when framework
                // connectivity is unavailable. Never treat historical signal
                // levels as proof of a current connection (issues #13/#14).
                final Object visible = Refl.callByName(child, "isIconVisible");
                if (!(visible instanceof Boolean) || ((Boolean) visible).booleanValue()) {
                    wifiVisible = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
            // keep the previous state
        }
        // Live framework connectivity wins over a stale sampled icon level.
        wifiVisible = WifiPresence.resolve(wifiVisible, TrioState.sWifiLevel);
        if (TrioState.setWifiPresent(wifiVisible)) {
            invalidateHosts();
        }
    }

    /**
     * True for the status bar's own icon container, as opposed to the ones the
     * control centre, its fake header and the QS headers inflate from the same
     * {@code system_icons.xml}.
     *
     * <p>Matched either against the container captured from
     * {@code MiuiPhoneStatusBarView.mStatusBarStatusIcons}, or - in case that
     * capture has not run yet - by looking for the {@code MiuiPhoneStatusBarView}
     * ancestor that only the status bar (and the keyguard sharing it) has.
     */
    private static boolean isStatusBarContainer(View container) {
        if (container == sStatusIconContainer) {
            return true;
        }
        for (ViewParent p = container.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (p.getClass().getSimpleName().equals("MiuiPhoneStatusBarView")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes sure the container's ignored slots match the sub-toggles, as seen
     * from the layout pass.
     *
     * <p>Returns without doing anything once they agree, which is what keeps this
     * from looping: {@code addIgnoredSlots} ends in an unconditional
     * {@code requestLayout()}, and re-appending on every pass would schedule a
     * new layout forever. When the slot list cannot be read there is nothing to
     * compare against, and assuming "already in sync" is the only choice that
     * cannot loop. Callers that must apply the state regardless - a fresh
     * container, or a settings change - use {@link #syncSlots}, which appends
     * even when it cannot verify.
     */
    private static void ensureFolded(Object container) {
        final Field f = sIgnoredSlotsField;
        if (f == null || container == null) {
            return;
        }
        final Object value = Refl.get(f, container);
        if (!(value instanceof List)) {
            return;
        }
        final List<?> slots = (List<?>) value;
        final List<String> wanted = foldedSlots();
        for (int i = 0; i < wanted.size(); i++) {
            if (!slots.contains(wanted.get(i))) {
                syncSlots(container);
                return;
            }
        }
        for (int i = 0; i < MANAGED_SLOTS.size(); i++) {
            final String slot = MANAGED_SLOTS.get(i);
            if (!wanted.contains(slot) && slots.contains(slot)) {
                syncSlots(container);
                return;
            }
        }
    }

    /**
     * Reads {@code ModernStatusBarView.getSlot()} (or the displayable one).
     */
    private static String slotOf(View child) {
        final Object slot = Refl.callByName(child, "getSlot");
        return (slot instanceof String) ? (String) slot : null;
    }

    /**
     * True when {@code container} belongs to a {@code MiuiStatusBatteryContainer}
     * that also holds a battery host currently drawing the trio glyph.
     *
     * <p>The icon container is a <em>sibling</em> of the battery view inside
     * {@code MiuiStatusBatteryContainer} (see {@code system_icons.xml}), so
     * walking up from the host never reaches it; the two are bound by the
     * battery container's own {@code mStatusIcon} field plus the registered
     * drawing hosts instead.
     *
     * <p>Every look-up walks the live view tree, so a container that exists
     * before any host has drawn simply stays native until the next layout pass:
     * {@code onDraw} and {@code onLayout} legitimately race on the first frame.
     */
    private static boolean isOwned(Object container) {
        synchronized (OWNED) {
            if (OWNED.containsKey(container)) {
                return true;
            }
        }
        if (!(container instanceof View)) {
            return false;
        }
        final View owner = batteryContainerOf((View) container);
        if (owner == null) {
            return false;
        }
        // Guard against a mis-walk: the container must really be this parent's.
        final Object declared = Refl.get(sStatusIconField, owner);
        if (declared != null && declared != container) {
            return false;
        }
        if (!holdsLiveHost(owner)) {
            // The lock screen's row is a second instance of the same layout -
            // keyguard_status_bar.xml includes system_icons - and it has no live
            // host, because the host match is scoped to the status bar's own row.
            // Its icons still have to be folded: the trio is painted into that
            // meter by the draw path, and without this the native signal icons
            // sit right beside it.
            if (!isKeyguardRow((View) container)) {
                return false;
            }
        }
        own(container);
        return true;
    }

    /** True when some registered glyph host lives under {@code container}. */
    private static boolean holdsLiveHost(View container) {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                if (isDescendant(HOSTS.get(i).host, container)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDescendant(View child, View ancestor) {
        for (ViewParent p = child.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code MiuiStatusBatteryContainer} enclosing {@code v}, or null.
     *
     * <p>Matched by class name rather than by {@code Class.forName} on the host's
     * class loader: the host is inflated through a Compose/Factory wrapper whose
     * loader chain does not expose SystemUI classes, which made every earlier
     * look-up fail ({@code owned=false} for all four containers on device).
     * The concrete class of the parent view is always available directly.
     */
    private static View batteryContainerOf(View v) {
        Class<?> known = sBatteryContainerClass;
        for (ViewParent p = v.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (!(p instanceof View)) {
                return null;
            }
            final View parent = (View) p;
            final Class<?> cls = parent.getClass();
            final boolean match = (known != null)
                    ? known.isInstance(parent)
                    : BATTERY_CONTAINER_CLASS.equals(cls.getName());
            if (match) {
                if (known == null) {
                    sBatteryContainerClass = cls;
                    sStatusIconField = Refl.field(cls, "mStatusIcon");
                }
                return parent;
            }
        }
        return null;
    }

    private static void own(Object container) {
        synchronized (OWNED) {
            OWNED.put(container, Boolean.TRUE);
        }
    }

    /**
     * Logs a container's geometry and children once, so the on-device report
     * pins down which container a stray icon belongs to.
     */
    private static void diagnose(ViewGroup container, boolean owned) {
        if (!debugLog()) {
            return;
        }
        final int[] location = new int[2];
        try {
            container.getLocationOnScreen(location);
        } catch (Throwable ignored) {
            // keep the relative values
        }
        final String children = childrenOf(container);
        final boolean log;
        synchronized (DIAGNOSED) {
            final boolean first = !DIAGNOSED.containsKey(container);
            // Record every container seen, not just the first dozen: the map is
            // weak, so this bounds nothing but the log. Without the record a
            // container past the log cap would look "first" on every pass.
            if (first) {
                DIAGNOSED.put(container, Boolean.TRUE);
            }
            final Boolean previous = DIAG_OWNED.get(container);
            // Log the first sighting and any later flip of `owned`. The first
            // layout normally runs before any glyph host has drawn, so that
            // first header legitimately reads owned=false; a one-shot header
            // would keep reporting that stale value for the whole session.
            final boolean changed = (previous != null) && previous.booleanValue() != owned;
            log = (first || changed) && sDiagHeaders < 48;
            if (log) {
                DIAG_OWNED.put(container, Boolean.valueOf(owned));
                sDiagHeaders++;
            }
        }
        if (log) {
            // The first layout usually runs before MIUI populates the container,
            // so the header alone is not enough: the children are dumped below by
            // signature as soon as they exist.
            final XposedModule module = sModule;
            if (module != null) {
                log(module, "container " + container.getClass().getSimpleName()
                        + " owned=" + owned
                        + " screen=" + location[0] + "," + location[1]
                        + " size=" + container.getWidth() + "x" + container.getHeight()
                        + " measured=" + container.getMeasuredWidth()
                        + " left=" + container.getLeft()
                        + " padStart=" + container.getPaddingStart()
                        + " padEnd=" + container.getPaddingEnd()
                        + " parent=" + (container.getParent() == null ? "null"
                        : container.getParent().getClass().getSimpleName())
                        + " path=" + pathOf(container));
            }
        }
        // Children are dumped until one dump shows the container populated; the
        // signature check keeps a repeated layout from logging the same content.
        if (children.length() == 0 || sDiagDumps >= 24) {
            return;
        }
        synchronized (DIAG_SIG) {
            if (children.equals(DIAG_SIG.get(container))) {
                return;
            }
            DIAG_SIG.put(container, children);
            sDiagDumps++;
        }
        final XposedModule module = sModule;
        if (module != null) {
            log(module, "children of " + container.getClass().getSimpleName()
                    + " at " + location[0] + "," + location[1] + ":" + children);
        }
    }

    /** One compact token per child, used both for logging and for change detection. */
    private static String childrenOf(ViewGroup container) {
        final StringBuilder sb = new StringBuilder();
        final int count;
        try {
            count = container.getChildCount();
        } catch (Throwable t) {
            return sb.toString();
        }
        for (int i = 0; i < count; i++) {
            final View child;
            try {
                child = container.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null) {
                continue;
            }
            sb.append(" | ").append(i)
                    .append(' ').append(child.getClass().getSimpleName())
                    .append(" slot=").append(slotOf(child))
                    .append(" l=").append(child.getLeft())
                    .append(" r=").append(child.getRight())
                    .append(" w=").append(child.getWidth())
                    .append(" mw=").append(child.getMeasuredWidth())
                    .append(" tx=").append(child.getTranslationX())
                    .append(" a=").append(child.getAlpha())
                    .append(" v=").append(child.getVisibility());
        }
        return sb.toString();
    }

    private static String pathOf(View v) {
        final StringBuilder sb = new StringBuilder();
        for (ViewParent p = v.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            sb.append(p.getClass().getSimpleName()).append('<');
        }
        return sb.toString();
    }

    private static TrioState registerHost(View host) {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                if (HOSTS.get(i).host == host) {
                    return HOSTS.get(i);
                }
            }
            final TrioState s = new TrioState(host);
            HOSTS.add(s);
            // Each host hides the native signal icons of the container it lives in
            // (status bar, keyguard, control center, QS headers each have their own).
            // registerHost runs from onDraw, and addIgnoredSlots ends in
            // requestLayout(), so defer it out of the draw pass.
            host.post(new Runnable() {
                @Override
                public void run() {
                    foldHostContainer(host);
                    // The reading is mounted and measured before the label, since
                    // the label stands outside it whenever it is present.
                    syncOutSignal(host);
                    syncOutTypeLabel(host);
                }
            });
            return s;
        }
    }

    private static TrioState stateFor(View host) {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                final TrioState s = HOSTS.get(i);
                if (s.host == host) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * True when {@code host} is the battery view of the status bar's own icon
     * row: the only container a glyph window may be opened for.
     *
     * <p>{@code system_icons.xml} is included by the status bar, the keyguard,
     * the control centre and both QS headers, so several battery views exist at
     * once and every one of them gets an {@code onDraw}. Only the status bar's
     * container is the one this module folds, so only its host may be painted
     * outside the bar - a second window for any of the others would put a glyph
     * on screen that the bar's own layout knows nothing about.
     */
    static boolean isStatusBarHost(View host) {
        if (host == null) {
            return false;
        }
        // The row this module owns is the one inside MiuiPhoneStatusBarView, and it
        // is the only row that may have a window of its own. Everything else that
        // carries a battery view - the keyguard, the control centre, the shade's own
        // copy of the bar at the top of the panel - draws the glyph in its own views
        // instead, which is the path that was always there and needs no window. That
        // is also why the window is only ever open while the bar is standing still:
        // pull the panel down and the bar's row stops being the one on screen, the
        // window goes with it, and the panel's copy takes over by drawing itself.
        //
        // Matched by walking the parents rather than by comparing root views or
        // by asking sStatusIconContainer: mStatusBarStatusIcons can point at a
        // container in another window entirely (it came back with a different root
        // on consecutive boots), which is exactly how the first window attempt
        // ended up refusing every host on the bar it was meant for.
        for (ViewParent p = host.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            final String name = p.getClass().getSimpleName();
            if (name.contains("Keyguard")) {
                return false;
            }
            if (name.contains("MiuiPhoneStatusBarView")) {
                return true;
            }
        }
        return false;
    }

    /** The nearest {@code MiuiStatusIconContainer} ancestor of {@code host}, or null. */
    private static Object iconContainerOf(View host) {
        final Class<?> cls = sIconContainerClass;
        if (cls == null) {
            return null;
        }
        for (ViewParent p = host.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (cls.isInstance(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Asks every known host to draw again.
     *
     * <p>Not the full {@link #invalidateHosts()}: that one also asks the bar to
     * lay out again, which is right after a real change and far too much twice
     * a second. This only repaints the hosts, and it is what turns "the bar is
     * drawing" into a question that can be asked at all.
     */
    static void nudgeHosts() {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                HOSTS.get(i).host.invalidate();
            }
        }
    }

    /** True while the bar's window is known to be covered by an app. */
    private static volatile boolean sBarCovered;

    /**
     * Where the watcher leaves its answer: 1 while the bar is covered, 0 while
     * it is not.
     *
     * <p>The watcher is a root script (service.d) because nothing inside
     * SystemUI can answer the question: a covered window still reports itself
     * visible and opaque, the module is not allowed to read the task list, and
     * the window manager - which does know - will not say without root. The
     * script watches app launches, asks it there, and leaves the answer in a
     * system property, which needs no file permissions to read. The script
     * itself is not shipped with the module and lives in the author's device
     * setup; without it this property simply stays "0" and the insets/visible-
     * rect tests in {@link TrioOverlay} carry the full-screen detection alone.
     */
    private static final String COVER_PROPERTY = "hyperduo.covered";

    /** Reads the watcher's answer; the last known one when there is no watcher. */
    static boolean barCovered() {
        try {
            final Class<?> properties = Class.forName("android.os.SystemProperties");
            final Object value = properties
                    .getMethod("get", String.class, String.class)
                    .invoke(null, COVER_PROPERTY, "0");
            sBarCovered = "1".equals(value);
        } catch (Throwable ignored) {
            // No watcher, or a platform that will not answer: keep the last word.
        }
        return sBarCovered;
    }

    /** The status bar's icon container, or null before the capture hook has run. */
    static Object statusIconContainer() {
        return sStatusIconContainer;
    }

    /** The status bar view itself, or null before the capture hook has run. */
    static View statusBarView() {
        return sStatusBarView;
    }

    private static void unregisterHost(View host) {
        synchronized (HOSTS) {
            for (int i = HOSTS.size() - 1; i >= 0; i--) {
                if (HOSTS.get(i).host == host) {
                    HOSTS.remove(i);
                }
            }
        }
        // The glyph window belongs to the host, not to the view tree: it has to
        // go when the host does, or it would outlive the icon it draws.
        TrioOverlay.release(host);
    }

    /** Repaints every trio host. Signal updates arrive off the UI thread. */
    static void invalidateHosts() {
        // The bar itself is asked to lay out again as well: the host that owns
        // the glyph window can have left the tree since the last pass, and a
        // host that never draws again would never take the window over - the
        // row would stay blank with the window gone.
        final View bar = sStatusBarView;
        if (bar != null) {
            bar.post(new Runnable() {
                @Override
                public void run() {
                    bar.requestLayout();
                    bar.invalidate();
                }
            });
        }
        final List<TrioState> copy;
        synchronized (HOSTS) {
            if (HOSTS.isEmpty()) {
                return;
            }
            copy = new ArrayList<TrioState>(HOSTS);
        }
        for (int i = 0; i < copy.size(); i++) {
            final View v = copy.get(i).host;
            v.post(new Runnable() {
                @Override
                public void run() {
                    // The type string is sampled from a drawable's measure(), so
                    // this is the only path that can carry a changed "5G"/"5GA"
                    // through to a label the glyph canvas does not own. Readings
                    // arrive here too: a SIM returning or the data SIM changing
                    // moves the bars without any draw pass of the glyph.
                    syncOutSignal(v);
                    syncOutTypeLabel(v);
                    v.invalidate();
                }
            });
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Makes {@code container}'s ignored-slot list match the sub-toggles: the
     * slots the glyph draws are added, the ones it no longer draws are removed.
     *
     * <p>Both directions matter. Adding folds a native icon into the glyph;
     * <em>removing</em> is what hands it back, because MIUI lays out - and so
     * positions - every slot that is not ignored. Only slots this class manages
     * are ever removed: {@code ignoredSlots} also carries MIUI's own entries
     * (the control-centre {@code RIGHT_BLOCK_LIST}), and dropping one of those
     * would move icons the user never asked about.
     *
     * <p>The layout request is deliberately conditional. {@code addIgnoredSlots}
     * ends in an unconditional {@code requestLayout()}, so appending on every
     * pass would schedule a new layout forever - which is why this is idempotent
     * while {@code ignoredSlots} is readable, and why it does not add a second
     * request of its own when it appended. When the field cannot be read there is
     * nothing to compare against, so the append happens blind: a MIUI rename must
     * not silently stop the module from folding. Removal is impossible then, and
     * the sub-toggle degrades to the old always-folded behaviour.
     */
    private static void syncSlots(Object container) {
        if (container == null) {
            return;
        }
        final List<String> wanted = foldedSlots();
        final Field f = sIgnoredSlotsField;
        final Object value = (f == null) ? null : Refl.get(f, container);
        if (!(value instanceof List)) {
            if (!wanted.isEmpty()) {
                addSlots(container, wanted);
            }
            return;
        }
        final List<?> slots = (List<?>) value;
        boolean missing = false;
        for (int i = 0; i < wanted.size(); i++) {
            if (!slots.contains(wanted.get(i))) {
                missing = true;
                break;
            }
        }
        boolean removed = false;
        try {
            for (int i = 0; i < MANAGED_SLOTS.size(); i++) {
                final String slot = MANAGED_SLOTS.get(i);
                if (!wanted.contains(slot) && slots.remove(slot)) {
                    removed = true;
                }
            }
        } catch (Throwable ignored) {
            // an immutable list is MIUI's problem, not ours
        }
        if (missing) {
            addSlots(container, wanted);
        } else if (removed) {
            // Nothing else will ask for a pass, and MIUI has to re-measure and
            // re-lay the slot before the handed-back icon reappears in place.
            requestLayout(container);
        }
    }

    /** Appends {@code slots} to the container's ignored list. */
    private static void addSlots(Object container, List<String> slots) {
        Refl.callArgs(container, "addIgnoredSlots",
                new Class<?>[]{List.class},
                new Object[]{new ArrayList<String>(slots)});
    }

    private static void requestLayout(Object container) {
        Refl.callArgs(container, "requestLayout", new Class<?>[0], new Object[0]);
    }

    /**
     * Removes every managed slot again, so MIUI measures and lays them all out.
     * Used when the module is switched off wholesale.
     */
    private static void unfoldSlots(Object container) {
        if (container == null) {
            return;
        }
        final Field f = sIgnoredSlotsField;
        if (f == null) {
            return;
        }
        final Object value = Refl.get(f, container);
        if (!(value instanceof List)) {
            return;
        }
        try {
            ((List<?>) value).removeAll(MANAGED_SLOTS);
        } catch (Throwable ignored) {
            // an immutable list is MIUI's problem, not ours
        }
        requestLayout(container);
    }

    /**
     * Suppresses the native Wi-Fi / mobile icons in the
     * {@code MiuiStatusBatteryContainer} that owns {@code host}.
     *
     * <p>There are seven containers (status bar, keyguard, control center, both
     * QS headers, ...) and each one that renders the trio glyph must hide its own
     * native signal icons. Walking up from the drawing host is the only way to
     * bind the two together without a hard-coded per-host lookup.
     */
    private static void foldHostContainer(View host) {
        final Object container = batteryContainerOf(host);
        if (container == null) {
            return;
        }
        syncSlots(Refl.get(sStatusIconField, container));
    }

    // ------------------------------------------------- out-of-ring type label

    /**
     * The out-of-ring network type label.
     *
     * <p>A {@link TextView} of our own rather than MIUI's {@code mobile_type}
     * view. That one is a child of the mobile slot group, so folding the mobile
     * icon takes it away with it - and the whole point of this mode is to keep
     * showing the type while the glyph carries the signal. Being our own class
     * also gives {@link #findOutTypeLabel} a marker no MIUI view can match by
     * accident, which a tag or a {@code WeakHashMap} keyed on the container
     * would not: {@code mParent} is a strong reference from the label back to
     * the container, so such a map's values would keep their own keys alive.
     */
    private static final class OutTypeLabel extends TextView {
        /** The glyph host supplying the ink scale and the icon colour. */
        View host;
        /**
         * The suffix scale the current text was built with; -1 until first
         * applied.
         *
         * <p>Remembered because a scale change leaves the <em>characters</em>
         * identical - "5GA" is still "5GA" - so the content comparison alone
         * would skip the rebuild and the slider would appear inert.
         */
        int suffixScale = -1;

        OutTypeLabel(Context context) {
            super(context);
        }
    }

    /**
     * Mounts, updates or removes the out-of-ring label for one glyph host.
     *
     * <p>Called only from posted runnables: it may {@code addView}, which must
     * never happen inside a layout pass. The mode check comes first so that the
     * ordinary path - in-ring or off - pays for one child scan and nothing else.
     */
    private static void syncOutTypeLabel(View host) {
        if (host == null) {
            return;
        }
        final Object owner = batteryContainerOf(host);
        if (!(owner instanceof ViewGroup)) {
            return;
        }
        final ViewGroup container = (ViewGroup) owner;
        final TrioAppearance a = TrioConfig.appearance();
        final boolean wanted = a.glyph && a.typeOutOfRing;
        OutTypeLabel label = findOutTypeLabel(container);
        if (!wanted) {
            removeOutTypeLabel(container);
            return;
        }
        final View anchor = meterIn(host, container);
        if (anchor == null) {
            removeOutTypeLabel(container);
            return;
        }
        // Only a guard: inkScale() is 0 exactly when the host has not been
        // measured yet, and an unmeasured meter has no frame to place against.
        // The next posted sync mounts the label.
        if (TrioRenderer.inkScale(host.getWidth(), host.getHeight()) <= 0f) {
            removeOutTypeLabel(container);
            return;
        }
        if (label == null) {
            label = createOutTypeLabel(container);
        }
        label.host = host;
        updateOutTypeLabel(container, label, host, anchor);
    }

    /**
     * Repositions an already-mounted label. Runs inside
     * {@code MiuiStatusIconContainer.onLayout}, so it may relayout the label but
     * must never add or remove a view.
     *
     * <p>The label lives in the {@code MiuiStatusBatteryContainer}, which is the
     * icon container's <em>parent</em>, so this starts by walking up - and the
     * label was placed relative to the battery meter, whose position the same
     * traversal may only just have settled. A wrong frame here is therefore
     * normal on the first pass and heals on the next one.
     */
    private static void refreshOutTypeLabel(ViewGroup iconContainer) {
        final Object owner = batteryContainerOf(iconContainer);
        if (!(owner instanceof ViewGroup)) {
            return;
        }
        final ViewGroup container = (ViewGroup) owner;
        final OutTypeLabel label = findOutTypeLabel(container);
        if (label == null) {
            // Mounting needs a host for the ink scale and must not run in a
            // layout pass, so ask for a posted sync instead - but only once the
            // host can actually be measured, or every pass would queue another.
            requestOutTypeSync(container);
            return;
        }
        final View host = label.host;
        if (host == null || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return;
        }
        final View anchor = meterIn(host, container);
        if (anchor == null) {
            return;
        }
        // Only a guard: inkScale() is 0 exactly when the host has no measured
        // size, and the label cannot be positioned against an unmeasured meter.
        // The text size no longer comes from the ink scale at all - it is the
        // user's own Out-of-ring size, already in screen pixels.
        if (TrioRenderer.inkScale(host.getWidth(), host.getHeight()) <= 0f) {
            return;
        }
        // Text and size both re-measure the view, and a re-measure schedules a
        // layout - which is exactly what must not happen inside onLayout. Hand
        // those over to the posted path and only ever reposition from here.
        final String text = typeText();
        if (text.length() == 0) {
            // The mode change and every type update already arrive through a
            // posted sync, so the label is parked at GONE before this can see an
            // empty type. Nothing to do, and nothing to lay out.
            return;
        }
        if (label.getVisibility() != View.VISIBLE
                || !text.contentEquals(label.getText())
                || label.suffixScale != TrioConfig.appearance().typeSuffixScale
                || label.getTextSize() != outTypeSizePx(container)) {
            requestOutTypeSync(container);
            return;
        }
        // The strip belongs to the icon container and is taken with setPadding,
        // which schedules a layout - illegal from in here. A container rebuilt by
        // MIUI comes back with its own padding and an empty book, so hand that
        // over to the posted path too.
        final View icons = iconContainerIn(container);
        if (icons != null && !OUT_PAD_SAVED.containsKey(icons)) {
            requestOutTypeSync(container);
            return;
        }
        final TrioState state = stateFor(host);
        if (state != null) {
            final int colour = state.foreground();
            if (label.getCurrentTextColor() != colour) {
                label.setTextColor(colour);
            }
        }
        // Against the reading, not against the battery meter. The label and the
        // reading are placed by the same rule - the anchor's left edge minus the
        // gap and the width - so anchoring both at the meter stacks them on top
        // of each other, and the reading is the wider one. The posted path
        // already chains through labelAnchorIn; this pass runs on every layout
        // and would otherwise keep overwriting where that one got it right.
        placeOutTypeLabel(container, label, labelAnchorIn(container, anchor));
    }

    /**
     * Hands a new ink to the out-of-ring views under {@code host}, on the one
     * frame where the tint changed.
     *
     * <p>MIUI's dark-mode pass ends at the battery icon's own
     * {@code invalidate()}: it tints what it owns and nothing else. Both of the
     * views here are siblings of the glyph host - mounted in the battery
     * container, not drawn by it - so that pass never reaches them. The reading
     * is a plain {@code View} whose {@code onDraw} reads the colour live, so it
     * only needs the repaint; the label is a {@code TextView} that nothing
     * re-measures on a tint change, so it needs the colour handed to it
     * outright.
     *
     * <p>The comparison happens in the caller and the book is closed here,
     * before the post: a frame storm must not queue one recolour per frame, and
     * the value written is the one the frame was painted with, so the two never
     * disagree about what is on screen.
     *
     * <p>Everything else is posted. This runs inside the glyph's draw pass, and
     * the lookup walks the parent chain and reads classes - cheap, but the
     * whole point of the book above is that it runs once per tint change rather
     * than once per frame.
     */
    private static void recolourOutRing(TrioState state, int ink) {
        state.outRingInk = ink;
        final View host = state.host;
        host.post(new Runnable() {
            @Override
            public void run() {
                final Object owner = batteryContainerOf(host);
                if (owner instanceof ViewGroup) {
                    applyOutRingInk((ViewGroup) owner, ink);
                }
            }
        });
    }

    /**
     * Hands one ink to the out-of-ring views under {@code container} - the
     * label outright, the reading as a repaint request. The one-line body both
     * tint paths share, so they cannot drift apart.
     *
     * <p>Safe to call directly (no post) from the tint hook, which runs outside
     * any layout or draw pass; the draw-pass path above keeps its post because
     * it runs inside the glyph's onDraw.
     */
    private static void applyOutRingInk(ViewGroup container, int ink) {
        final OutTypeLabel label = findOutTypeLabel(container);
        if (label != null && label.getCurrentTextColor() != ink) {
            label.setTextColor(ink);
        }
        final OutSignalView signal = findOutSignal(container);
        if (signal != null) {
            // It would redraw with the new colour on its own, but only if
            // something asked it to - and nothing did.
            signal.invalidate();
        }
    }

    /**
     * Queues one {@link #syncOutTypeLabel} for the host under {@code container},
     * without ever queuing an empty one: the runnable is a no-op unless the mode
     * is Out of ring, a host is registered and that host has been measured.
     */
    private static void requestOutTypeSync(final ViewGroup container) {
        if (!TrioConfig.appearance().typeOutOfRing) {
            return;
        }
        final View host = hostIn(container);
        if (host == null
                || TrioRenderer.inkScale(host.getWidth(), host.getHeight()) <= 0f) {
            return;
        }
        final View target = host;
        container.post(new Runnable() {
            @Override
            public void run() {
                syncOutTypeLabel(target);
            }
        });
    }

    /** The type string to show, normalised to empty when there is none. */
    private static String typeText() {
        final String text = TrioState.sMobileType;
        return text == null ? "" : text;
    }

    /** The label already mounted on {@code container}, or null. */
    private static OutTypeLabel findOutTypeLabel(ViewGroup container) {
        final int count = container.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child;
            try {
                child = container.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child instanceof OutTypeLabel) {
                return (OutTypeLabel) child;
            }
        }
        return null;
    }

    private static OutTypeLabel createOutTypeLabel(ViewGroup container) {
        final OutTypeLabel label = new OutTypeLabel(container.getContext());
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setLetterSpacing(TrioGeometry.VALUE_LETTER_SPACING);
        label.setVisibility(View.GONE);
        // WRAP_CONTENT on both axes: the size is set explicitly further down by
        // measuring against an unlimited spec. The container never measures this
        // child on its own - MiuiStatusBatteryContainer.onMeasure only touches
        // its three named fields - so whatever we measure stands.
        container.addView(label, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return label;
    }

    private static void removeOutTypeLabel(View container) {
        if (!(container instanceof ViewGroup)) {
            return;
        }
        final ViewGroup group = (ViewGroup) container;
        // Hand the strip back before the early return below: a label that was
        // never mounted can still have left the padding behind if a previous
        // mount was torn down through a different path.
        releaseOutTypeSpace(group);
        final OutTypeLabel label = findOutTypeLabel(group);
        if (label == null) {
            return;
        }
        try {
            group.removeView(label);
        } catch (Throwable ignored) {
            // a container torn down under us is not worth crashing over
        }
        // The reading may still be mounted and still need its strip; the release
        // above cannot tell, so the total is recomputed.
        reserveOutRingStrip(group);
    }

    private static void updateOutTypeLabel(ViewGroup container, OutTypeLabel label,
                                           View host, View anchor) {
        final TrioAppearance a = TrioConfig.appearance();
        final TrioState state = stateFor(host);
        if (state != null) {
            // Pull the status values in before reading them: this runs from a
            // posted runnable, but the state snapshot is only as fresh as the
            // last call to refresh().
            state.refresh();
        }
        final String text = typeText();
        if (text.length() == 0) {
            // Hidden rather than removed: the text comes and goes as the modem
            // reports a type, and add/remove on every layout pass would churn
            // the container for nothing. The reserved strip goes back, though -
            // holding it open with nothing in it would just leave a hole in the
            // status bar.
            releaseOutTypeSpace(container);
            if (label.getTranslationX() != 0f) {
                label.setTranslationX(0f);
            }
            if (label.getVisibility() != View.GONE) {
                label.setVisibility(View.GONE);
            }
            // The reading may still be mounted and still need its strip; the
            // bookkeeping above cannot tell, so it is asked.
            reserveOutRingStrip(container);
            return;
        }
        if (!text.contentEquals(label.getText())
                || label.suffixScale != a.typeSuffixScale) {
            applyOutTypeText(label, text, a.typeSuffixScale);
        }
        // Its own setting, not the in-ring type_size: that one is authored for
        // the ring canvas' 120x120 design space and comes out far too small
        // once the label stands in the status bar's real pixel space.
        final float size = outTypeSizePx(container);
        if (label.getTextSize() != size) {
            // PX, not the SP that the one-argument overload would use: the
            // status bar lays out in raw pixels, so the value is applied as-is
            // rather than scaled by the user's font-size setting.
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
        final Typeface typeface = TrioRenderer.typefaceFor(a.typeWeight);
        if (label.getTypeface() != typeface) {
            label.setTypeface(typeface);
        }
        final int colour = (state != null) ? state.foreground() : 0xFFFFFFFF;
        if (label.getCurrentTextColor() != colour) {
            label.setTextColor(colour);
        }
        if (label.getVisibility() != View.VISIBLE) {
            label.setVisibility(View.VISIBLE);
        }
        label.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // Measured first, because the strip the native row has to give up is the
        // label's own width plus the gap on either side of it. Without it the row
        // lays its icons all the way to the end and paints over the label.
        // reserveOutRingStrip counts the reading too, when one is mounted.
        reserveOutRingStrip(container);
        placeOutTypeLabel(container, label, labelAnchorIn(container, anchor));
        if (debugLog()) {
            log(LOG_INFO, "out type: \"" + text + "\" size=" + size
                    + " label=" + label.getWidth() + "x" + label.getHeight()
                    + " at " + label.getLeft() + "," + label.getTop()
                    + " anchor=" + anchor.getLeft() + ".." + anchor.getRight()
                    + " container=" + container.getWidth() + "x" + container.getHeight());
        }
    }

    /**
     * Sets the label's text, shrinking a trailing "A" when the user asked for
     * it.
     *
     * <p>A {@code SpannableStringBuilder} with a {@link RelativeSizeSpan} rather
     * than two draws: this side is a real {@code TextView}, so the platform lays
     * the runs out and keeps them on one baseline for free. That is exactly what
     * {@code Canvas.drawText} cannot do - it ignores spans - which is why the
     * ring canvas takes the two-run path in {@code TrioRenderer.drawType} and
     * this one takes the span.
     *
     * <p>The span covers only the last character and only when there is a label
     * left after it: a suffix span over a bare "A" would just draw the label
     * smaller, which is not the reference's shape. {@code
     * setLetterSpacing} is left as it is - the tracking applies to the whole
     * label exactly as it did when the type was one size.
     */
    private static void applyOutTypeText(OutTypeLabel label, String text, int scalePercent) {
        if (!TrioGeometry.hasShrunkSuffix(text, scalePercent)) {
            // Plain text, not a spannable: an install that never shrinks the
            // suffix should not pay for span bookkeeping on every type change.
            label.setText(text);
        } else {
            final SpannableStringBuilder span = new SpannableStringBuilder(text);
            final int at = text.length() - 1;
            span.setSpan(new RelativeSizeSpan(scalePercent / 100f), at, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setText(span);
        }
        label.suffixScale = scalePercent;
    }

    /**
     * Puts the label just outside the battery meter, on the side the container
     * reads away from: left of it in LTR, right of it in RTL. This is the gap
     * MIUI's own {@code mobile_type} label used to fill.
     */
    private static void placeOutTypeLabel(ViewGroup container, View view, View anchor) {
        placeOutTypeLabel(container, view, anchor, outLabelAnchorGap(container));
    }

    /**
     * The same placement, with the gap toward the anchor given explicitly.
     *
     * <p>Every out-of-ring view is positioned by a <em>gap</em>, never by a
     * position. That is the whole point: {@link #reserveOutRingStrip} reserves
     * this same number plus the view's width out of the native icon row, so a
     * larger gap moves the view away from its neighbour <em>and</em> pushes the
     * icons over by the same amount. A free position offset - which this
     * replaced - moved the view without reserving anything, so a large enough
     * value painted the reading over the icons it should have displaced.
     *
     * <p>Which physical gap each margin supplies flips with the reading
     * direction, and getting it backwards is invisible in LTR and wrong in RTL:
     *
     * <pre>
     *   LTR:  [label] --label right-- [reading] --signal margin-- [battery]
     *   RTL:  [battery] --signal margin-- [reading] --label left-- [label]
     * </pre>
     *
     * <p>So {@code gap} is always "the space between this view and its anchor",
     * and this method only decides which side that is: the view goes at
     * {@code anchor.getLeft() - gap - width} in LTR and
     * {@code anchor.getRight() + gap} in RTL.
     *
     * <p>The view is centred on its anchor vertically and is never offset on
     * that axis. A status-bar row has no vertical room to reserve, so a vertical
     * nudge could only ever overlap the icons - there is nothing for it to
     * displace.
     */
    private static void placeOutTypeLabel(ViewGroup container, View view, View anchor, int gap) {
        final int width = view.getMeasuredWidth();
        final int height = view.getMeasuredHeight();
        if (width <= 0 || height <= 0 || anchor.getWidth() <= 0) {
            return;
        }
        // getLayoutDirection() rather than isLayoutRtl(): the latter is
        // protected in View, and the result is identical.
        final boolean rtl =
                container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        // Two references, because a view can be standing against either of two
        // neighbours, and the two references are not interchangeable.
        //
        // Against the battery - the reading always, the label when no reading is
        // mounted - the reference is the native icon row's own edge, not the
        // meter's. The two coincide whenever the battery is laid out normally -
        // which is why the meter worked, and why this changes nothing about the
        // ordinary frame - but the charging super island breaks the equivalence:
        // MiuiStatusBatteryContainer stops subtracting the battery's width from
        // the row's bound (so the row slides clear of the island) while still
        // measuring and laying the meter out at its old place and merely marking
        // it INVISIBLE. Anchored on the meter, the reading then landed a whole
        // battery width left of the row's real end, on top of the native icons
        // that had moved there. The row's edge is the one bound that is right in
        // both states - and it is also exactly where reserveOutRingStrip's
        // padding ends, so the views land inside the strip they reserved rather
        // than across the icons.
        //
        // Against the reading - the label when one is mounted - the reference is
        // the reading's own edge. The two stand in that same reserved strip, one
        // against the other, so handing both the row's edge stacks them: the
        // label lands where the reading is and the type is drawn through the
        // bars. reserveOutRingStrip reserves the two side by side, so this is
        // also the only split that lands the pair exactly on the strip.
        final View icons = iconContainerIn(container);
        final boolean useIcons = icons != null && icons.getWidth() > 0;
        // getWidth() and not only the class: a reading that has been measured
        // but not yet laid out still carries an empty frame, and chaining off
        // that would put the label at the container's far left for one pass. It
        // falls back to the row's edge - where it stood before the reading
        // existed - and the next placement chains it.
        final boolean chainReading =
                anchor instanceof OutSignalView && anchor.getWidth() > 0;
        final int edge;
        if (chainReading) {
            edge = rtl ? anchor.getRight() : anchor.getLeft();
        } else if (useIcons) {
            edge = rtl ? icons.getLeft() : icons.getRight();
        } else {
            edge = rtl ? anchor.getRight() : anchor.getLeft();
        }
        int left = rtl ? edge + gap : edge - gap - width;
        // Vertical reference stays with the anchor: its frame is laid out
        // unconditionally, so it is the same row centre island or not.
        int top = anchor.getTop() + (anchor.getHeight() - height) / 2;
        // Stay inside the container even when the meter sits flush against an
        // edge. The container itself carries no padding here - the include in
        // status_bar.xml puts status_bar_padding_start on the parent - so this
        // normally clamps to 0 and only bites in the RTL case, or once the gap
        // has grown past the room the container actually has.
        final int minLeft = container.getPaddingLeft();
        final int maxLeft = container.getWidth() - container.getPaddingRight() - width;
        if (maxLeft >= minLeft) {
            if (left > maxLeft) {
                left = maxLeft;
            }
            if (left < minLeft) {
                left = minLeft;
            }
        }
        final int minTop = container.getPaddingTop();
        final int maxTop = container.getHeight() - container.getPaddingBottom() - height;
        if (maxTop >= minTop) {
            if (top > maxTop) {
                top = maxTop;
            }
            if (top < minTop) {
                top = minTop;
            }
        }
        view.layout(left, top, left + width, top + height);
        // The anchor only moves when the battery itself does, and the battery
        // never moves for the island - MIUI slides the icon row instead. So the
        // frame laid out above is the island-free position, and the island is
        // applied afterwards as a translation of that same frame, exactly the
        // way the container translates its own children. Keeping it out of the
        // layout maths means the two paths never have to agree on a number:
        // whatever shift the row took, the label takes the same one.
        final float shift = islandShiftPx(container);
        if (view.getTranslationX() != -shift) {
            view.setTranslationX(-shift);
        }
    }

    /** The label's margin on the side that faces its anchor, in pixels. */
    private static int outLabelAnchorGap(ViewGroup container) {
        final boolean rtl =
                container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int[] margins = outLabelMargins(container);
        return rtl ? margins[0] : margins[1];
    }

    /**
     * The two gaps the out-of-ring label keeps, in pixels, in the label's own
     * reading order: {@code [0]} is the side the label reads <em>from</em> and
     * {@code [1]} the side it reads <em>to</em>.
     *
     * <p>Not named left/right on purpose. The two settings are physical sides of
     * the label ({@code out_type_margin_left}/{@code _right}), but which physical
     * side a run leads with flips under RTL, so keeping the reading-order pair
     * here and letting each caller pick the physical one is what stops the RTL
     * branch from silently swapping the two.
     *
     * <p>The dp→px conversion follows {@link TrioRenderer#outSignalHeight}: the
     * display's density, never the status bar row's height, which MIUI changes
     * when the control centre is pulled down.
     */
    private static int[] outLabelMargins(View container) {
        final float density = container.getResources().getDisplayMetrics().density;
        final TrioAppearance a = TrioConfig.appearance();
        return new int[] {
                Math.round(a.outTypeMarginLeft * density),
                Math.round(a.outTypeMarginRight * density)};
    }

    /** The gap the out-of-ring reading keeps from the battery, in pixels. */
    private static int outSignalMargin(View container) {
        final float density = container.getResources().getDisplayMetrics().density;
        return Math.round(TrioConfig.appearance().outSignalMargin * density);
    }

    /** The out-of-ring label's font size in pixels, after the dp setting. */
    private static float outTypeSizePx(View container) {
        final float density = container.getResources().getDisplayMetrics().density;
        return TrioConfig.appearance().outTypeSize * density;
    }

    /**
     * The {@code MiuiStatusIconContainer} nested in a battery container, i.e. the
     * view whose end padding decides where the native icon row starts.
     */
    private static View iconContainerIn(View batteryContainer) {
        final Object icons = Refl.get(sStatusIconField, batteryContainer);
        return (icons instanceof View) ? (View) icons : null;
    }

    /**
     * How far MIUI's own icon row has been pushed inward by the expanded super
     * island, in pixels, or 0 when no island is in the way.
     *
     * <p>{@code MiuiStatusIconContainer} keeps an {@code _islandMonitor} whose
     * {@code getIslandWidth()} is that distance: the container's own
     * {@code onLayout} reads it back (through its private
     * {@code getIslandTranslationX()}) and, while it is positive, slides every
     * native icon whose position falls inside it out of the way instead of
     * hiding it under the island. A null monitor and a blocked one both mean
     * "no island", and MIUI signals that with -1 - anything non-positive is
     * therefore treated as zero here.
     *
     * <p>The label is not a {@code StatusIconDisplayable} and never enters the
     * container's {@code layoutStates}, so that pass knows nothing about it and
     * leaves it exactly where it was. Reading the same number lets the label
     * make the same move the row did, which is what keeps it beside the icons
     * rather than under the island.
     *
     * <p>Read-only and callable from anywhere: it only reads a field and invokes
     * a getter, so unlike {@code setPadding} it is safe inside a layout pass.
     */
    private static int islandShiftPx(View iconContainer) {
        if (iconContainer == null) {
            return 0;
        }
        try {
            final Class<?> cls = iconContainer.getClass();
            final Field monitorField = Refl.field(cls, "_islandMonitor");
            if (monitorField == null) {
                return 0;
            }
            final Object monitor = Refl.get(monitorField, iconContainer);
            if (monitor == null) {
                return 0;
            }
            final Method blocked = Refl.method(monitor.getClass(), "getBlocked");
            if (blocked != null && Boolean.TRUE.equals(Refl.invoke(blocked, monitor))) {
                return 0;
            }
            final Method width = Refl.method(monitor.getClass(), "getIslandWidth");
            if (width == null) {
                return 0;
            }
            final Object value = Refl.invoke(width, monitor);
            final int px = (value instanceof Integer) ? (Integer) value : 0;
            return (px > 0) ? px : 0;
        } catch (Throwable ignored) {
            // A missing field or a renamed getter only costs the shift; the
            // label still lands on the anchor, so there is nothing to report.
            return 0;
        }
    }

    /**
     * Makes the native icon row give up {@code reserve} pixels at its end, so the
     * out-of-ring label has room to stand in rather than being painted over.
     *
     * <p>{@code MiuiStatusIconContainer.onLayout} walks its children right to left
     * starting from {@code getWidth() - getPaddingEnd()} and positions each one by
     * {@code translationX}. Growing that end padding is therefore enough to slide
     * the whole row inward - no view has to be added to the container, which is
     * just as well because every one of its children is cast to
     * {@code StatusIconDisplayable} and a foreign view would crash the pass.
     *
     * <p>The original values are remembered against the icon container, so
     * repeated calls cannot accumulate: {@code setPadding} is always called with
     * the original plus the current reserve, never with the current value plus
     * the reserve.
     *
     * <p>Callers must be on a posted path - {@code setPadding} schedules a layout,
     * which is illegal inside one.
     */
    private static void reserveOutTypeSpace(ViewGroup batteryContainer, int reserve) {
        if (reserve <= 0) {
            return;
        }
        final View icons = iconContainerIn(batteryContainer);
        if (icons == null) {
            return;
        }
        int[] saved = OUT_PAD_SAVED.get(icons);
        if (saved == null) {
            saved = new int[] {icons.getPaddingLeft(), icons.getPaddingRight()};
            OUT_PAD_SAVED.put(icons, saved);
        }
        // getPaddingEnd() resolves to the right in LTR and to the left in RTL, and
        // that is the side the icon row is laid out from - so that is the side the
        // room has to come out of.
        final boolean rtl = icons.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int left = saved[0] + (rtl ? reserve : 0);
        final int right = saved[1] + (rtl ? 0 : reserve);
        if (icons.getPaddingLeft() == left && icons.getPaddingRight() == right) {
            return;
        }
        icons.setPadding(left, icons.getPaddingTop(), right, icons.getPaddingBottom());
        if (debugLog()) {
            log(LOG_INFO, "out type: reserved " + reserve + "px, icon padding "
                    + saved[0] + "/" + saved[1] + " -> " + left + "/" + right
                    + ", icons w=" + icons.getWidth());
        }
    }

    /**
     * Hides the native icons the reserved strip has pushed out of the container.
     *
     * <p>MIUI is supposed to do this itself: its own onLayout walks the row and
     * marks whatever no longer fits as hidden. On this build that walk does
     * nothing - the value it accumulates is never stored back, so the test that
     * hides a child never fires - and with the strip reserved the icons simply
     * pile up on each other. That is the overlap, and it happens with or without
     * the island.
     *
     * <p>Only the native icons are touched: the slots this module draws itself
     * are skipped, and the battery is not in this container to begin with.
     */
    private static void hideOverflowingIcons(ViewGroup container) {
        final int[] saved = OUT_PAD_SAVED.get(container);
        if (saved == null) {
            return;
        }
        final int reserve = container.getPaddingRight() - saved[1];
        if (reserve <= 0) {
            return;
        }
        final int limit = container.getWidth() - reserve;
        final int count = container.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child;
            try {
                child = container.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null) {
                continue;
            }
            final String slot = slotOf(child);
            if (slot == null || MANAGED_SLOTS.contains(slot)) {
                continue;
            }
            if (child.getRight() <= limit) {
                if (unmarkCollapsed(child)) {
                    try {
                        if (child.getVisibility() != View.VISIBLE) {
                            child.setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable ignored) {
                        // never let one child abort the pass
                    }
                }
                continue;
            }
            if (child.getVisibility() != View.GONE && markCollapsed(child)) {
                try {
                    child.setVisibility(View.GONE);
                } catch (Throwable ignored) {
                    // never let one child abort the pass
                }
            }
        }
    }

    /** Hands the reserved strip back to the native icon row. Idempotent. */
    private static void releaseOutTypeSpace(View batteryContainer) {
        final View icons = iconContainerIn(batteryContainer);
        if (icons == null) {
            return;
        }
        final int[] saved = OUT_PAD_SAVED.remove(icons);
        if (saved == null) {
            return;
        }
        if (icons.getPaddingLeft() == saved[0] && icons.getPaddingRight() == saved[1]) {
            return;
        }
        icons.setPadding(saved[0], icons.getPaddingTop(), saved[1], icons.getPaddingBottom());
    }

    // ---------------------------------------------------- out-of-ring signal

    /**
     * The out-of-ring SIM reading: four ascending capsule bars, and the dot row
     * beneath them when both SIMs have a reading.
     *
     * <p>A {@link View} of our own rather than MIUI's signal icon. That icon is
     * one icon for one reading, while this mode draws a reading per SIM and has
     * to place them itself; and being our own class gives {@link #findOutSignal}
     * a marker no MIUI view can match by accident.
     *
     * <p>It is mounted in the battery container, never in the icon container:
     * {@code MiuiStatusIconContainer.onLayout} casts each of its children to
     * {@code StatusIconDisplayable}, and a foreign child would crash the pass.
     */
    private static final class OutSignalView extends View {
        /** The glyph host the reading and the icon colour come from. */
        View host;
        /** Bars to light, {@code 0..}{@link TrioGeometry#STACK_COLUMNS}. */
        int bars;
        /** Dots to light, or {@code -1} when there is no second reading. */
        int dots = -1;

        OutSignalView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final View source = host;
            if (source == null) {
                return;
            }
            // Read on every frame rather than cached. Nothing invalidates this
            // view when the tint changes - it is a sibling of the glyph host,
            // outside the chain MIUI's dark-mode pass walks - so the frame has
            // to be asked for, and it is asked for by recolourOutRing().
            final TrioState state = stateFor(source);
            final int fg = (state != null) ? state.foreground() : 0xFFFFFFFF;
            TrioRenderer.drawOutSignal(canvas, getWidth(), getHeight(), bars, dots, fg,
                    TrioConfig.appearance());
        }
    }

    /**
     * Mounts, updates or removes the out-of-ring reading for one glyph host.
     *
     * <p>Posted only, for the same reason as {@link #syncOutTypeLabel}: it may
     * add a view, and this runnable runs from a layout pass otherwise.
     */
    private static void syncOutSignal(View host) {
        if (host == null) {
            return;
        }
        final Object owner = batteryContainerOf(host);
        if (!(owner instanceof ViewGroup)) {
            return;
        }
        final ViewGroup container = (ViewGroup) owner;
        // Two ways in: the user asked for the reading, or the island took the
        // glyph away while the user still wants the signal drawn in-ring - the
        // reading is then the only place left for it.
        final boolean wanted = outSignalWanted();
        final OutSignalView view = findOutSignal(container);
        if (!wanted) {
            removeOutSignal(container);
            return;
        }
        final View anchor = meterIn(host, container);
        if (anchor == null) {
            removeOutSignal(container);
            return;
        }
        // Only a guard: inkScale() is 0 exactly when the host has not been
        // measured yet, and an unmeasured meter has no frame to place against.
        if (TrioRenderer.inkScale(host.getWidth(), host.getHeight()) <= 0f) {
            removeOutSignal(container);
            return;
        }
        final OutSignalView target = (view != null) ? view : createOutSignal(container);
        target.host = host;
        updateOutSignal(container, target, host, anchor);
    }

    /**
     * Repositions an already-mounted reading. Runs inside the icon container's
     * layout pass, so it may relayout the view but must never measure it: a
     * re-measure schedules a layout, which is illegal from in there. Anything
     * that would change its size is handed to the posted path instead.
     */
    private static void refreshOutSignal(ViewGroup iconContainer) {
        final Object owner = batteryContainerOf(iconContainer);
        if (!(owner instanceof ViewGroup)) {
            return;
        }
        final ViewGroup container = (ViewGroup) owner;
        final OutSignalView view = findOutSignal(container);
        if (view == null) {
            requestOutSignalSync(container);
            return;
        }
        final View host = view.host;
        if (host == null || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return;
        }
        final View anchor = meterIn(host, container);
        if (anchor == null) {
            return;
        }
        if (TrioRenderer.inkScale(host.getWidth(), host.getHeight()) <= 0f) {
            return;
        }
        if (anchor.getHeight() <= 0) {
            return;
        }
        final int[] reading = new int[2];
        outSignalReading(stateFor(host), reading);
        if (reading[0] < 0) {
            reading[0] = 0;
        }
        // Size changed - a SIM appearing or disappearing, the row resizing, or
        // the size slider moving - is a re-measure, so it goes back through the
        // posted path.
        if (view.bars != reading[0] || view.dots != reading[1]
                || view.getMeasuredHeight() != outSignalHeight(host)) {
            requestOutSignalSync(container);
            return;
        }
        // Shares the label's slot geometry: both stand outside the battery
        // meter, one against the other, so they are placed by one routine. Each
        // is placed by its own reserved margin - the reading by the signal
        // margin, the label by its two - which is why neither can be nudged onto
        // a neighbour.
        placeOutTypeLabel(container, view, anchor, outSignalMargin(container));
    }

    /**
     * Queues one {@link #syncOutSignal} for the host under {@code container},
     * without ever queuing an empty one: the runnable is a no-op unless the
     * module is drawing the reading itself and that host has been measured.
     */
    private static void requestOutSignalSync(final ViewGroup container) {
        // Same condition syncOutSignal mounts the reading under, so a layout
        // pass can never queue a sync the posted path would immediately undo -
        // which matters under the island, where the reading is wanted with the
        // stacked switch off as long as the user keeps the signal on.
        if (!outSignalWanted()) {
            return;
        }
        final View host = hostIn(container);
        if (host == null
                || TrioRenderer.inkScale(host.getWidth(), host.getHeight()) <= 0f) {
            return;
        }
        final View target = host;
        container.post(new Runnable() {
            @Override
            public void run() {
                syncOutSignal(target);
            }
        });
    }

    /**
     * The bars and dots a host's current readings resolve to.
     *
     * <p>One place asks the renderer, so the posted pass that measures the view
     * and the layout pass that compares against it can never disagree about
     * what the reading is.
     */
    private static void outSignalReading(TrioState state, int[] out) {
        final int[] slots = (state != null) ? state.slotLevels : null;
        final int mobile = (state != null) ? state.mobileLevel : -1;
        TrioRenderer.outSignalLevels(TrioConfig.appearance().dataSimOnly, mobile, slots,
                TrioState.sDataSlot, out);
    }

    private static OutSignalView findOutSignal(ViewGroup container) {
        final int count = container.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child;
            try {
                child = container.getChildAt(i);
            } catch (Throwable ignored) {
                continue;
            }
            if (child instanceof OutSignalView) {
                return (OutSignalView) child;
            }
        }
        return null;
    }

    private static OutSignalView createOutSignal(ViewGroup container) {
        final OutSignalView view = new OutSignalView(container.getContext());
        view.setVisibility(View.GONE);
        // WRAP_CONTENT on both axes: the size is set explicitly by measuring
        // against exact specs further down. MiuiStatusBatteryContainer.onMeasure
        // only touches its three named fields, so whatever we measure stands.
        container.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private static void removeOutSignal(View container) {
        if (!(container instanceof ViewGroup)) {
            return;
        }
        final ViewGroup group = (ViewGroup) container;
        final OutSignalView view = findOutSignal(group);
        if (view == null) {
            return;
        }
        try {
            group.removeView(view);
        } catch (Throwable ignored) {
            // a container torn down under us is not worth crashing over
        }
        // The strip the reading held has to go back the moment it does, or the
        // native icon row would keep laying out short of a view that is gone.
        reserveOutRingStrip(group);
    }

    private static void updateOutSignal(ViewGroup container, OutSignalView view,
                                        View host, View anchor) {
        final TrioState state = stateFor(host);
        if (state != null) {
            // The snapshot is only as fresh as the last refresh(), and this runs
            // from a posted runnable rather than from the draw path.
            state.refresh();
        }
        final int[] reading = new int[2];
        outSignalReading(state, reading);
        // A reading telephony has not answered for yet is drawn unlit rather
        // than absent. Folding the mobile slot has already happened by the time
        // this runs, so hiding the view would leave a hole in the status bar
        // until the modem answers - and four dark bars is also what MIUI itself
        // draws for "no service".
        if (reading[0] < 0) {
            reading[0] = 0;
        }
        final int height = outSignalHeight(host);
        if (height <= 0) {
            return;
        }
        final int width = TrioRenderer.outSignalWidth(height);
        if (width <= 0) {
            return;
        }
        view.bars = reading[0];
        view.dots = reading[1];
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        // Measured first, then reserved: the strip the native row gives up is
        // this view's width plus the label's, and the gap around both.
        reserveOutRingStrip(container);
        placeOutTypeLabel(container, view, anchor, outSignalMargin(container));
        // The reading is not part of this view's geometry: the height comes from
        // the battery meter and the width from that height alone, so switching
        // between the one-row and two-row reading - or any SIM falling off the
        // network - repaints nothing on its own. setVisibility() is skipped
        // because the view is already VISIBLE, and the invalidate() the rest of
        // the module does lands on the glyph host, which is a sibling: it never
        // redraws this view. Without this line the new reading only appears when
        // something unrelated re-measures this view, which in practice means the
        // out-of-ring size slider.
        view.invalidate();
        if (debugLog()) {
            log(LOG_INFO, "out signal: bars=" + reading[0] + " dots=" + reading[1]
                    + " view=" + view.getWidth() + "x" + view.getHeight()
                    + " at " + view.getLeft() + "," + view.getTop()
                    + " anchor=" + anchor.getLeft() + ".." + anchor.getRight()
                    + " container=" + container.getWidth() + "x" + container.getHeight());
        }
    }

    /**
     * The height the out-of-ring reading wants, after the size setting. Both the
     * measure and the change check above go through here so a slider move and a
     * re-measure can never disagree about the target.
     *
     * <p>Only the display's density is taken from {@code host}; its measured
     * height deliberately is not. MIUI lays the battery container out at one
     * height at rest and at the full {@code statusBars} inset once the control
     * centre is pulled down, so a size derived from the row grew with the shade.
     */
    private static int outSignalHeight(View host) {
        final float density = host.getResources().getDisplayMetrics().density;
        return TrioRenderer.outSignalHeight(TrioConfig.appearance().outSignalSize, density);
    }

    /**
     * The view the out-of-ring label stands next to: the SIM reading when it is
     * mounted, the battery meter otherwise.
     *
     * <p>That is MIUI's own reading order - network type, then signal, then
     * battery - so the label is always the outermost of the two and the two
     * never claim the same strip.
     */
    private static View labelAnchorIn(ViewGroup container, View meter) {
        final OutSignalView signal = findOutSignal(container);
        if (signal != null && signal.getVisibility() == View.VISIBLE) {
            return signal;
        }
        return meter;
    }

    /**
     * Gives the native icon row exactly the room the out-of-ring views need: the
     * reading and the label, plus the gap that separates each from the next
     * thing. With neither mounted the strip is handed back entirely.
     *
     * <p>Both callers use this rather than reserving for themselves, because the
     * strip is one number: whichever view is updated last would otherwise decide
     * the padding for both of them.
     */
    private static void reserveOutRingStrip(ViewGroup container) {
        final OutSignalView signal = findOutSignal(container);
        final boolean signalOn = signal != null && signal.getVisibility() == View.VISIBLE;
        final OutTypeLabel label = findOutTypeLabel(container);
        final boolean labelOn = label != null && label.getVisibility() == View.VISIBLE;
        // The strip is a single scalar, so each view's facing margin has to be
        // resolved to a physical side here. In LTR a view stands left of its
        // anchor, so the side facing the anchor is its right margin; RTL mirrors,
        // exactly as placeOutTypeLabel does for the placement itself.
        final boolean rtl =
                container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int[] margins = outLabelMargins(container);
        final int labelInward = rtl ? margins[0] : margins[1];
        final int labelOutward = rtl ? margins[1] : margins[0];
        // The reading's own margin, not a label margin: the two are separate
        // settings, and the reading's is the one that decides how far it sits
        // from the battery. Reserving it here is what makes the setting move the
        // icons rather than slide the reading over them.
        final int signalGap = outSignalMargin(container);
        int total = 0;
        if (signalOn) {
            total += signal.getMeasuredWidth() + signalGap;
        }
        if (labelOn) {
            // Both of the label's gaps are always drawn, so both are always
            // reserved: the inward one separates it from the reading when one is
            // mounted and from the battery when it stands alone, and the outward
            // one separates it from the native icons on the other side in either
            // case. Leaving the outward gap out whenever the reading was present
            // - which is what this did - made the reserved total a little
            // narrower than the span actually drawn, so the icons stopped flush
            // against the label and the outward margin did nothing at all while
            // the reading was on screen.
            total += label.getMeasuredWidth() + labelInward + labelOutward;
        }
        if (total <= 0) {
            releaseOutTypeSpace(container);
            return;
        }
        reserveOutTypeSpace(container, total);
    }

    /**
     * The direct child of {@code container} that holds {@code host}, i.e. the
     * {@code MiuiBatteryMeterView} the glyph is drawn in.
     *
     * <p>Found by walking the live parent chain rather than by class name, for
     * the same reason {@link #batteryContainerOf} matches by name: the host is
     * inflated through a wrapper whose loader chain does not expose SystemUI
     * classes.
     */
    private static View meterIn(View host, View container) {
        View child = host;
        for (ViewParent p = host.getParent(); p != null;
             p = (p instanceof View) ? ((View) p).getParent() : null) {
            if (p == container) {
                return child;
            }
            if (!(p instanceof View)) {
                return null;
            }
            child = (View) p;
        }
        return null;
    }

    /** A registered glyph host living under {@code container}, or null. */
    private static View hostIn(View container) {
        synchronized (HOSTS) {
            for (int i = 0; i < HOSTS.size(); i++) {
                if (isDescendant(HOSTS.get(i).host, container)) {
                    return HOSTS.get(i).host;
                }
            }
        }
        return null;
    }

    private static void hide(Object view) {
        if (view instanceof View) {
            ((View) view).setVisibility(View.GONE);
        }
    }

    /** Resources handle for resolving signal icon entry names. */
    private static android.content.res.Resources resources() {
        final Object container = sStatusIconContainer;
        if (container instanceof View) {
            try {
                return ((View) container).getResources();
            } catch (Throwable ignored) {
                // fall through
            }
        }
        synchronized (HOSTS) {
            if (!HOSTS.isEmpty()) {
                try {
                    return HOSTS.get(0).host.getResources();
                } catch (Throwable ignored) {
                    // fall through
                }
            }
        }
        return null;
    }

    private static void log(XposedModule module, String msg) {
        try {
            module.log(LOG_INFO, TAG, msg);
        } catch (Throwable ignored) {
            // logging must never break hook installation
        }
    }

    /**
     * Logs through the handle captured at install time. Used by callers that run
     * outside the hook callbacks (the settings channel) and so cannot pass the
     * module instance along.
     */
    static void log(int priority, String msg) {
        final XposedModule module = sModule;
        if (module == null) {
            return;
        }
        try {
            module.log(priority, TAG, msg);
        } catch (Throwable ignored) {
            // logging must never break a config change
        }
    }
}
