package io.github.yixing233.hyperduo;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.SystemClock;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.View;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of everything the trio glyph needs: battery level / charge state, the
 * Wi-Fi and mobile signal levels, and the tint colours MIUI is currently using
 * for this status bar.
 *
 * <p>All battery/tint values live on {@code MiuiBatteryMeterIconView} as public
 * fields, so they are read reflectively; every read degrades to a sane default.
 *
 * <p>Signal levels are learned from the signal icons the Wi-Fi and mobile binders
 * apply (see {@link #noteSignalIcon}). Those arrive as raw resource ids, which are
 * resolved to entry names once a host view — and therefore a {@code Resources} —
 * is available.
 *
 * <p>That path carries exactly one mobile level: {@code transformResId} is static
 * and its arguments never name a SIM, so it can only ever describe whichever SIM
 * MIUI is drawing. Reading one level <em>per</em> SIM therefore has to come from
 * telephony directly — see {@link #sampleSimsIfDue()}.
 */
final class TrioState {

    /** Latest resolved signal levels; shared by every trio host. */
    static volatile int sWifiLevel = -1;
    static volatile int sMobileLevel = -1;

    /**
     * Whether the status bar is currently showing a Wi-Fi indicator at all.
     *
     * <p>The level alone is not enough: MIUI only calls
     * {@code MiuiStatusBarIconViewHelper.transformResId} while it is binding a
     * signal icon, so when Wi-Fi is switched off the last level simply stays
     * behind and the arcs would never disappear. Presence is sampled from the
     * live view tree instead (see {@code TrioHooks.settle}), which is the only
     * source that tracks the indicator going away.
     */
    static volatile boolean sWifiPresent = true;

    /**
     * Mobile network type label as MIUI itself renders it: "5G", "4G", "5GA",
     * "3G"... Empty when there is nothing to show.
     *
     * <p>Sampled from {@code MobileTypeDrawable.mMobileType} after MIUI's own
     * normalisation has run, so this is exactly the string the stock status bar
     * would have painted. {@code "5G++"} has already been rewritten to "5G" with
     * a separate double-plus flag by the time the hook reads it, which is why the
     * module never has to know about that special case.
     */
    static volatile String sMobileType = "";

    /** How many SIM slots the reading covers; SIM 1 and SIM 2. */
    static final int SIM_SLOTS = 2;

    /**
     * Signal level per SIM <em>slot</em> — index 0 is SIM 1, index 1 is SIM 2 —
     * or {@code -1} where that slot is empty or its level is unknown.
     *
     * <p>Only used by the dual-SIM reading; the single row keeps using the
     * {@code transformResId} level above, which tracks the current data SIM.
     *
     * <p>Length is always {@link #SIM_SLOTS}, so the renderer can read it without
     * a length check.
     *
     * <p>Starts at {@code -1} rather than the zero-filled default: {@code 0} is a
     * real reading (a SIM with no service), and a fresh array must not look like
     * "two SIMs, both readable" before telephony has answered once. Getting that
     * wrong draws two grey rows instead of falling back to the single row.
     */
    static volatile int[] sSlotLevels = { -1, -1 };

    /** The current default-data SIM's slot, or {@code -1} when it is unknown. */
    static volatile int sDataSlot = -1;

    /** Telephony app context, captured from the first hooked view. */
    private static volatile Context sContext;
    /** Last tint line logged, so a per-frame call site logs only when it changes. */
    private static volatile String sLastTint;
    /** Next {@link SystemClock#elapsedRealtime} at which telephony may be polled. */
    private static volatile long sSimsDueAt;
    /** How often telephony is polled. Signal strength is not a per-frame value. */
    private static final long SIMS_INTERVAL_MS = 2000L;

    /** Raw signal icon ids seen before a host existed, newest last. */
    private static final List<Integer> PENDING = new ArrayList<>();
    private static final int PENDING_MAX = 8;

    /** Signal icon id -> classification code. Ids are process-stable. */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Integer> CLASSIFIED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Not a signal-level icon: leave the last level alone. */
    private static final int C_IGNORE = -1;
    /** Genuine "no signal" marker: clear the dots. */
    private static final int C_CLEAR = -2;
    /** Wi-Fi levels are stored as this base plus 0..3, to share one int channel. */
    private static final int C_WIFI_BASE = 100;

    private static final int DEFAULT_FOREGROUND = 0xFFFFFFFF;

    private static Field fLevel;
    private static Field fCharging;
    private static Field fQuick;
    private static Field fLow;
    private static Field fPowerSave;
    private static Field fPerformance;
    private static Field fUseTint;
    private static Field fTint;
    private static Field fLight;
    private static Field fDark;
    private static Field fIntensity;
    private static boolean fieldsReady;

    final View host;

    // Battery state of this host.
    int level = -1;
    boolean charging;
    boolean quickCharging;
    boolean low;
    boolean powerSave;
    boolean performanceMode;

    // Tint state of this host.
    boolean useTint;
    int tintColor;
    int lightColor;
    int darkColor;
    float darkIntensity;
    /**
     * Ink last handed to the out-of-ring views under this host, or {@code 0}
     * before the first hand-over.
     *
     * <p>Kept per host rather than in one static: the status bar and the
     * control centre each own a battery container, and the ink is the same for
     * both, so a shared cell would let whichever drew first swallow the change
     * for the other. {@link #foreground()} never returns {@code 0} - it
     * substitutes a default - so the first comparison always fires.
     */
    int outRingInk;

    // Signal levels (shared).
    int wifiLevel = -1;
    int mobileLevel = -1;
    boolean wifiPresent = true;
    String mobileType = "";
    /** Per-SIM levels by slot; a fresh array each refresh, never mutated in place. */
    int[] slotLevels = { -1, -1 };
    /** How many of {@link #slotLevels} carry a reading. */
    int sims;

    TrioState(View host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ fields

    private static synchronized void ensureFields(Class<?> c) {
        if (fieldsReady) {
            return;
        }
        fLevel = Refl.field(c, "mLevel");
        fCharging = Refl.field(c, "mCharging");
        fQuick = Refl.field(c, "mQuickCharging");
        fLow = Refl.field(c, "mLow");
        fPowerSave = Refl.field(c, "mPowerSave");
        fPerformance = Refl.field(c, "mPerformanceMode");
        fUseTint = Refl.field(c, "mUseTint");
        fTint = Refl.field(c, "mTintColor");
        fLight = Refl.field(c, "mLightColor");
        fDark = Refl.field(c, "mDarkColor");
        fIntensity = Refl.field(c, "mDarkIntensity");
        fieldsReady = fLevel != null;
    }

    /** Re-reads every status value from the host. Cheap enough to call in onDraw. */
    void refresh() {
        ensureFields(host.getClass());
        level = Refl.getInt(fLevel, host, level);
        charging = Refl.getBool(fCharging, host, charging);
        quickCharging = Refl.getBool(fQuick, host, quickCharging);
        low = Refl.getBool(fLow, host, low);
        powerSave = Refl.getBool(fPowerSave, host, powerSave);
        performanceMode = Refl.getBool(fPerformance, host, performanceMode);
        useTint = Refl.getBool(fUseTint, host, useTint);
        tintColor = Refl.getInt(fTint, host, tintColor);
        lightColor = Refl.getInt(fLight, host, lightColor);
        darkColor = Refl.getInt(fDark, host, darkColor);
        darkIntensity = Refl.getFloat(fIntensity, host, darkIntensity);

        resolvePending(resourcesOf(host));

        // One telephony poll is enough for every host, so it is rate-limited here
        // rather than expressed as a background subscription no host owns. It runs
        // before the levels are read so the frame that discovers a change is also
        // the frame that draws it - no extra invalidation round is needed. Polled
        // when the second row is wanted, when the out-of-ring reading needs a
        // level per SIM, or when the icon chain has never answered and the data
        // SIM's own reading is the only thing left to fall back on.
        if (TrioConfig.get().dualSim || TrioConfig.appearance().stackedOut()
                || mobileLevel < 0) {
            sampleSimsIfDue();
        }

        wifiLevel = sWifiLevel;
        mobileLevel = sMobileLevel;
        wifiPresent = sWifiPresent;
        mobileType = sMobileType;

        final int[] slots = sSlotLevels;
        slotLevels = new int[] { slots[0], slots[1] };
        sims = (slots[0] >= 0 ? 1 : 0) + (slots[1] >= 0 ? 1 : 0);

        // Single row, no answer from the icon chain: the row still means "the SIM
        // that carries data", and the sampler resolved exactly which one that is.
        if (mobileLevel < 0 && sDataSlot >= 0 && sDataSlot < SIM_SLOTS
                && slots[sDataSlot] >= 0) {
            mobileLevel = slots[sDataSlot];
        }
    }

    // ------------------------------------------------------------- per-SIM level

    /**
     * Captures the context telephony needs, from a view the module already has.
     *
     * <p>SystemUI is {@code android.uid.systemui} with
     * {@code READ_PRIVILEGED_PHONE_STATE}, and this module runs inside it, so the
     * framework answers these calls without the module's own manifest asking for
     * anything.
     */
    static void attachContext(Context context) {
        if (context == null || sContext != null) {
            return;
        }
        try {
            final Context app = context.getApplicationContext();
            sContext = (app != null) ? app : context;
        } catch (Throwable ignored) {
            // leave it null; the reading stays on the single-row fallback
        }
    }

    /**
     * Re-reads the per-SIM levels at most once every {@link #SIMS_INTERVAL_MS}.
     *
     * <p>Called from {@code refresh()}, which runs in {@code onDraw}: the guard
     * is what keeps a per-frame call from becoming a per-frame IPC.
     */
    private static void sampleSimsIfDue() {
        final long now = SystemClock.elapsedRealtime();
        if (now < sSimsDueAt) {
            return;
        }
        sSimsDueAt = now + SIMS_INTERVAL_MS;
        sampleSims(sContext);
    }

    /**
     * Re-reads the per-SIM levels right away, bypassing the frame-rate guard, and
     * reports whether anything changed.
     *
     * <p>Called from the signal-icon hook, which is an event-driven refresh rather
     * than a per-frame one, so the guard is not what protects that path. Repainting
     * only on a real change is what keeps MIUI's own icon churn from becoming a
     * repaint loop.
     */
    static boolean pollSimsNow() {
        if (sContext == null) {
            return false;
        }
        final int[] before = sSlotLevels;
        final int beforeData = sDataSlot;
        sampleSims(sContext);
        final int[] after = sSlotLevels;
        return beforeData != sDataSlot || before[0] != after[0] || before[1] != after[1];
    }

    /**
     * Reads one signal level per SIM slot straight from telephony.
     *
     * <p>Why not the signal icons: {@code transformResId} is static and its
     * arguments name neither a SIM nor a slot, so the icons cannot say which SIM
     * a level belongs to. The framework can, and {@link SubscriptionInfo} is the
     * one place that maps a subscription to its slot — resolved at run time
     * rather than assumed, because the ids differ per device.
     *
     * <p>Every step is defensive: any missing permission, dead radio or absent
     * subscription leaves the corresponding slot at {@code -1}, and the caller
     * then falls back to a single row rather than drawing empty dots.
     */
    private static void sampleSims(Context context) {
        if (context == null) {
            return;
        }
        final int[] levels = { -1, -1 };
        int dataSlot = -1;
        try {
            final SubscriptionManager subs =
                    (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            final TelephonyManager tel =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (subs == null || tel == null) {
                return;
            }
            final int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
            final List<SubscriptionInfo> infos = subs.getActiveSubscriptionInfoList();
            if (infos != null) {
                for (int i = 0; i < infos.size(); i++) {
                    final SubscriptionInfo info = infos.get(i);
                    if (info == null) {
                        continue;
                    }
                    final int subId = info.getSubscriptionId();
                    final int slot = info.getSimSlotIndex();
                    if (subId == dataSubId) {
                        dataSlot = slot;
                    }
                    if (slot < 0 || slot >= SIM_SLOTS) {
                        continue;
                    }
                    levels[slot] = levelOf(tel, subId);
                }
            }
        } catch (Throwable ignored) {
            // keep whatever the previous poll found
        }
        // Publish only once the whole reading is consistent, so a frame never
        // pairs SIM 1's level with SIM 2's from a different poll.
        sSlotLevels = levels;
        sDataSlot = dataSlot;
    }

    /**
     * The 0..4 level MIUI draws for one subscription, or {@code -1}.
     *
     * <p>Deliberately <em>not</em> AOSP's {@code SignalStrength.getLevel()}: MIUI
     * labels its bars from its own level and the two disagree. A live Xiaomi 14 on
     * 5G NR reported {@code level = 3} with {@code miuiLevel = 4} on both SIMs
     * while the stock bar drew a full four bars, so a dual row fed by
     * {@code getLevel()} sat one notch below the icon the single row matches.
     */
    private static int levelOf(TelephonyManager tel, int subId) {
        try {
            final TelephonyManager perSim = tel.createForSubscriptionId(subId);
            if (perSim == null) {
                return -1;
            }
            final SignalStrength strength = perSim.getSignalStrength();
            if (strength == null) {
                return -1;
            }
            final int level = miuiLevel(strength);
            return (level < 0 || level > 4) ? -1 : level;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * MIUI's own signal level, the one its status bar icon is chosen from, or
     * AOSP's where MIUI does not provide it.
     *
     * <p>{@code SignalStrength.getMiuiLevel()} is a MIUI addition absent from the
     * public SDK - even the SDK 37 {@code android.jar} has only
     * {@code getLevel()} - so it is reached reflectively. MIUI's own
     * {@code MobileSignalController.updateTelephony()} makes exactly this call to
     * pick {@code stat_sys_signal_N}, which is why bare {@code getLevel()} can sit
     * a notch below the icon on screen.
     */
    private static int miuiLevel(SignalStrength strength) {
        final Object v = Refl.callByName(strength, "getMiuiLevel");
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return strength.getLevel();
    }

    private static Resources resourcesOf(View v) {
        try {
            return v.getResources();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ colours

    /** The plain icon colour: what MIUI would paint the battery icon in. */
    int foreground() {
        // The style follows the bar, not the system theme: MIUI's own fields say
        // what this bar is drawing on, and they are the same pair its icons use.
        // They are the first and only real answer here; the system's night mode is
        // kept as the last resort for a host whose fields never arrived (a host
        // inflated a moment ago, a bar whose tint never came through).
        int c = useTint ? tintColor : (darkIntensity > 0f ? darkColor : lightColor);
        if (c == 0) {
            c = nightMode() ? 0xFFFFFFFF : DEFAULT_FOREGROUND;
        }
        noteTint(c);
        return c;
    }

    /**
     * Records the tint inputs once per change, so the values MIUI is handing over
     * can be read off a device instead of guessed at.
     */
    private void noteTint(int resolved) {
        final String note = "tint: useTint=" + useTint
                + " tint=" + Integer.toHexString(tintColor)
                + " light=" + Integer.toHexString(lightColor)
                + " dark=" + Integer.toHexString(darkColor)
                + " intensity=" + darkIntensity
                + " -> " + Integer.toHexString(resolved);
        if (!note.equals(sLastTint)) {
            sLastTint = note;
            TrioHooks.log(TrioHooks.LOG_INFO, note);
        }
    }

    /** The system's night mode, as the configuration currently reports it. */
    private boolean nightMode() {
        final Context context = sContext;
        if (context == null) {
            return false;
        }
        try {
            return (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ signal levels

    /**
     * Records a signal icon resource id — the {@code rawResId} the Wi-Fi and
     * mobile binders pass to
     * {@code MiuiStatusBarIconViewHelper.transformResId(int,boolean,boolean)}.
     *
     * <p>Names look like {@code stat_sys_wifi_signal_2} or
     * {@code stat_sys_signal_3}; decorated variants
     * ({@code stat_sys_signal_0_no_voice_darkmode}) still carry the level as the
     * token right after {@code signal}. Ids seen before a {@code Resources} handle
     * exists are buffered and resolved on the next draw.
     */
    static void noteSignalIcon(Resources res, int resId) {
        if (resId == 0) {
            return;
        }
        if (res == null) {
            synchronized (PENDING) {
                if (PENDING.size() >= PENDING_MAX) {
                    PENDING.remove(0);
                }
                PENDING.add(Integer.valueOf(resId));
            }
            return;
        }
        apply(res, resId);
    }

    /**
     * Records Wi-Fi indicator presence and reports whether it changed.
     *
     * <p>Called from the icon container's layout, from the live children: a
     * {@code slot=wifi} child means the indicator is on screen. Reporting the
     * change lets the caller redraw only on the edge, not on every layout pass.
     */
    static boolean setWifiPresent(boolean present) {
        if (sWifiPresent == present) {
            return false;
        }
        sWifiPresent = present;
        return true;
    }

    /**
     * Records the mobile network type label and reports whether it changed.
     *
     * <p>Fed from {@code MobileTypeDrawable.measure()} after MIUI has already
     * normalised the string, so whatever the stock status bar would show ("5G",
     * "5GA", "4G") is what lands here. A {@code null} is treated as "no type",
     * which is what the view model holds while the radio is still settling.
     */
    static boolean setMobileType(String type) {
        final String next = type == null ? "" : type;
        if (next.equals(sMobileType)) {
            return false;
        }
        sMobileType = next;
        return true;
    }

    private static void resolvePending(Resources res) {
        if (res == null) {
            return;
        }
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            for (int i = 0; i < PENDING.size(); i++) {
                apply(res, PENDING.get(i).intValue());
            }
            PENDING.clear();
        }
    }

    private static void apply(Resources res, int resId) {
        // Classification is a pure function of the resource id, and this runs on
        // the hot transformResId path (20+ call sites, every signal update), so
        // cache it: one getResourceEntryName per distinct id per process.
        final Integer hit = CLASSIFIED.get(Integer.valueOf(resId));
        final int code = hit != null ? hit.intValue() : classify(res, resId);
        if (code == C_IGNORE) {
            return;
        }
        if (code == C_CLEAR) {
            // Not a level icon but a genuine "no signal" marker: clear the dots
            // rather than leaving the last level lit forever.
            sMobileLevel = 0;
            return;
        }
        if (code >= C_WIFI_BASE) {
            sWifiLevel = code - C_WIFI_BASE;
        } else {
            sMobileLevel = code;
        }
    }

    /**
     * Resolves a signal icon id to a classification code, caching the result.
     * Never returns null.
     */
    private static int classify(Resources res, int resId) {
        final int code = computeCode(res, resId);
        CLASSIFIED.put(Integer.valueOf(resId), Integer.valueOf(code));
        return code;
    }

    private static int computeCode(Resources res, int resId) {
        final String name;
        try {
            name = res.getResourceEntryName(resId);
        } catch (Throwable t) {
            return C_IGNORE;
        }
        if (name == null) {
            return C_IGNORE;
        }
        // Match the primary Wi-Fi entry only. A loose "wifi_signal" substring
        // would also swallow stat_sys_slave_wifi_signal_* and
        // ic_no_internet_wifi_signal_*, which are different indicators.
        final boolean wifi = name.startsWith("stat_sys_wifi_signal");
        if (!wifi && !name.startsWith("stat_sys_signal")) {
            return C_IGNORE;
        }
        final int value = parseLevel(name);
        if (value < 0) {
            // Not a level icon (volte, vonr, data_left, satellite_null, ...):
            // decoration rather than a level change, so keep the last level.
            // The two genuine "no signal" markers are matched exactly, because
            // stat_sys_signal_satellite_null also ends in _null and belongs to
            // the separate satellite indicator.
            if ("stat_sys_signal_null".equals(name)
                    || "stat_sys_signal_flightmode".equals(name)) {
                return C_CLEAR;
            }
            return C_IGNORE;
        }
        return wifi ? C_WIFI_BASE + value : value;
    }

    /** {@code stat_sys_wifi_signal_2} → 2, {@code stat_sys_signal_0_no_voice} → 0. */
    private static int parseLevel(String name) {
        final String[] parts = name.split("_");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("signal".equals(parts[i])) {
                final String n = parts[i + 1];
                if (n.length() == 1 && n.charAt(0) >= '0' && n.charAt(0) <= '9') {
                    return n.charAt(0) - '0';
                }
                return -1;
            }
        }
        return -1;
    }
}
