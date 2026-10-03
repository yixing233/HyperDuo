package io.github.yixing233.hyperduo;

/**
 * The single source of truth for every setting shared between the settings app
 * and the hooked SystemUI process.
 *
 * <p>Both sides talk over the Xposed framework's remote preferences: the
 * settings app writes, the hooked process only reads. Because the framework
 * casts stored values to the requested type without conversion, every key is
 * read and written with exactly one type for the whole lifetime of the module.
 * Keep the {@code DEF_*} defaults here as well so both sides agree when the
 * framework cannot provide remote preferences (embedded mode, or a first run
 * before the user ever opened the settings screen).
 */
public final class Prefs {

    private Prefs() {
    }

    /** Remote-preference group name. Also the settings app's file name. */
    public static final String NAME = "hyperduo_settings";

    /**
     * Explicit broadcast the settings app sends to SystemUI after every write,
     * carrying the whole snapshot as extras.
     *
     * <p>Remote preferences remain the store of record, but the framework's
     * "a value changed" callback has been observed to never reach the hooked
     * process on this device: the daemon database held the new {@code enabled}
     * value while SystemUI kept drawing from the old one. The broadcast is a
     * second, callback-independent path for exactly that case.
     *
     * <p>Lives here rather than in {@code TrioConfig} because the settings app
     * must be able to name it: {@code TrioConfig} references
     * {@code io.github.libxposed.api}, which is {@code compileOnly} and so is
     * not on the app's runtime class path.
     */
    public static final String ACTION_RELOAD = "io.github.yixing233.hyperduo.action.RELOAD";

    /**
     * Package the reload broadcast is addressed to. SystemUI registers the
     * receiver, so an explicit package keeps the intent away from every other
     * process on the device.
     */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    // ------------------------------------------------------------------ keys
    public static final String KEY_ENABLED = "enabled";
    /**
     * Draws the glyph in a window of its own instead of inside the status bar
     * view - see {@code TrioOverlay}.
     *
     * <p>The status bar window is only {@code status_bar_height} tall (121px,
     * about 43dp, on this device) and clips everything past that rectangle, so a
     * glyph that wants more height than the bar has can never be drawn whole in
     * the bar, whatever the view tree does.
     *
     * <p>On by default, and the only route that draws the glyph whole: growing
     * the status bar's own row to make room was tried and abandoned - MIUI's
     * measure chain re-derives the size of every box on the way up, so each level
     * fixed exposed the next one, and the window's own height was taken back by
     * MIUI's controller on its own schedule. The cost of this route is placement:
     * the screen's top edge is hard, so a glyph taller than the bar sits a few dp
     * below the icons beside it instead of on their centre line.
     */
    /**
     * Stored under a name that did not exist while this route was the fallback.
     *
     * <p>The earlier default (off) was written into the framework's remote
     * preferences by the settings app on first open, and a stored value wins over
     * any new default - an install that has it would keep the window route off no
     * matter what {@link #DEF_OVERLAY} says. The framework does no type conversion
     * and never forgets a key, so the new route gets a key with no history.
     */
    public static final String KEY_OVERLAY = "glyph_window";
    public static final String KEY_SHOW_WIFI = "show_wifi";
    public static final String KEY_SHOW_MOBILE = "show_mobile";
    public static final String KEY_SHOW_VALUE = "show_value";
    public static final String KEY_SHOW_BOLT = "show_bolt";
    /**
     * Where the mobile network type ("5G", "4G"...) is drawn: hidden, inside the
     * ring, or outside it.
     *
     * <p>An {@code int} rather than the boolean it replaced. The framework does
     * no type conversion, so {@link #KEY_SHOW_MOBILE_TYPE} could not simply be
     * re-read as an int: on an existing install the stored boolean would make
     * {@code getInt} throw {@code ClassCastException}. A new key keeps every
     * installed value readable, and the old one is consulted once during
     * migration - see {@code TrioSettings.readMobileTypeMode}.
     */
    public static final String KEY_MOBILE_TYPE_MODE = "mobile_type_mode";
    /**
     * Legacy, read-only for migration.
     *
     * <p>Superseded by {@link #KEY_MOBILE_TYPE_MODE}. Never write it again; the
     * only remaining reader is the one-time migration in
     * {@code TrioSettings.from(SharedPreferences)}, which maps an existing
     * "on" to {@link #MOBILE_TYPE_IN_RING} while the new key is absent.
     */
    public static final String KEY_SHOW_MOBILE_TYPE = "show_mobile_type";
    /**
     * Draws the battery percentage enlarged in the middle of the ring, with the
     * Wi-Fi arcs shrunk into the 12 o'clock notch.
     *
     * <p>The stored name is frozen at {@code "swap_wifi_value"} and must never
     * change: it is a boolean the user may already have set, and rewriting the
     * key would silently reset that choice on every existing install. Only the
     * <em>label</em> was renamed - the setting used to be presented as a swap
     * between the two slots, which described the mechanism rather than what the
     * user gets.
     */
    public static final String KEY_VALUE_CENTRED = "swap_wifi_value";
    /**
     * Which of the three-in-one layouts is drawn: the ring arrangement or the
     * rectangular one.
     *
     * <p>An {@code int} holding one of the {@code STYLE_*} constants below. Like
     * every other key it keeps exactly one type for the whole lifetime of the
     * module, so the value is only ever read back with {@code getInt}.
     */
    public static final String KEY_TRIO_STYLE = "trio_style";
    /**
     * Draws the mobile level as two rows of four dots - one SIM each - instead of
     * the single row of the current data SIM.
     *
     * <p>Only honoured while there is no Wi-Fi ink and no charging bolt: with the
     * arcs or the bolt on screen the glyph's top and bottom slots are taken, and
     * the user asked for the two-SIM reading precisely as the alternative to
     * those. The top row is SIM 1 (slot 0) and the bottom row SIM 2 (slot 1).
     * With a single SIM, or while the per-SIM levels cannot be read, the single
     * row stays and keeps showing the current data SIM.
     *
     * <p>A brand-new key with a brand-new name. The framework does no type
     * conversion, so a key may only ever be read back with the type it was
     * written with - see the note on {@link #KEY_MOBILE_TYPE_MODE}.
     */
    public static final String KEY_DUAL_SIM = "dual_sim_signal";
    /**
     * Where the mobile signal is drawn: inside the glyph, or as a status-bar view
     * outside it.
     *
     * <p>An {@code int} holding one of the {@code SIGNAL_*} constants below.
     * Outside the glyph the module stops drawing the level dots entirely and -
     * depending on {@link #KEY_STACKED_SIGNAL} - either hands the native signal
     * icon back or draws one of its own next to the battery.
     *
     * <p>A brand-new key with a brand-new name: the framework does no type
     * conversion, so a key may only ever be read back with the type it was
     * written with - see the note on {@link #KEY_MOBILE_TYPE_MODE}.
     */
    public static final String KEY_SIGNAL_MODE = "signal_mode";
    /**
     * Draws the out-of-ring signal as four ascending capsule bars for one SIM
     * over four dots for the other, instead of leaving the native icon in place.
     *
     * <p>Only ever honoured while {@link #KEY_SIGNAL_MODE} is
     * {@link #SIGNAL_OUT_RING}: inside the glyph the rings' own level dots are the
     * signal, and there is nothing here for this switch to change. Turning it off
     * outside the glyph is not "draw the default dots" but "let MIUI draw its own
     * signal icon", which is what makes it a meaningful choice rather than a
     * second way of saying the same thing.
     *
     * <p>A brand-new key with a brand-new name.
     */
    public static final String KEY_STACKED_SIGNAL = "stacked_signal";
    /**
     * Draws only the current data SIM's out-of-ring signal row, leaving the other
     * SIM out entirely.
     *
     * <p>Gated on {@link #KEY_STACKED_SIGNAL} rather than on anything of its own:
     * while the native icon is in place the module draws no signal at all, so
     * there would be nothing for this to narrow. Being switched off by
     * <em>another</em> row is fine; a row that switches itself off is not.
     *
     * <p>A brand-new key with a brand-new name.
     */
    public static final String KEY_DATA_SIM_ONLY = "data_sim_only";

    public static final String KEY_ROLE_COLORS = "role_colors";
    public static final String KEY_COLOR_CRITICAL_ON_DARK = "color_critical_on_dark";
    public static final String KEY_COLOR_CRITICAL_ON_LIGHT = "color_critical_on_light";
    public static final String KEY_COLOR_CHARGING_ON_DARK = "color_charging_on_dark";
    public static final String KEY_COLOR_CHARGING_ON_LIGHT = "color_charging_on_light";
    public static final String KEY_COLOR_LOW_ON_DARK = "color_low_on_dark";
    public static final String KEY_COLOR_LOW_ON_LIGHT = "color_low_on_light";
    public static final String KEY_LOW_THRESHOLD = "low_threshold";

    public static final String KEY_RING_STROKE = "ring_stroke";
    public static final String KEY_ARC_STROKE = "arc_stroke";
    public static final String KEY_VALUE_SIZE = "value_size";
    public static final String KEY_VALUE_WEIGHT = "value_weight";
    /** Font size of the network type drawn inside the ring. */
    public static final String KEY_TYPE_SIZE = "type_size";
    /**
     * Font size of the network type when it is drawn <em>outside</em> the ring.
     *
     * <p>Deliberately a separate key from {@link #KEY_TYPE_SIZE}: the in-ring
     * label lives in the ring's 120x120 design space and its size is tuned
     * against the ring geometry, whereas the out-of-ring label is a plain
     * status-bar TextView laid out in real pixels next to MIUI's own icons, so
     * it is tuned on its own scale - and its range reaches higher accordingly.
     * Sharing one key between the two spaces is what this key fixes.
     *
     * <p>An {@code int}, like {@link #KEY_TYPE_SIZE}. A brand-new key name and
     * never a reuse of the old one: the framework does no type conversion, so
     * every key has exactly one type for the whole lifetime of the module.
     */
    public static final String KEY_OUT_TYPE_SIZE = "out_type_size";
    /**
     * The stacked out-of-ring signal's height, in density pixels.
     *
     * <p>A dp rather than a percentage of anything, because the row this reading
     * stands in does not keep one height. At rest the battery container measures
     * one way, and the moment the control centre is pulled down MIUI lays the
     * same container out through the whole {@code statusBars} inset instead - on
     * the test device 88px becomes 134px, so a reading sized as a share of its
     * row grew by half just because a shade was opened. A dp is resolved against
     * the display's density and never against whoever is laying the row out.
     *
     * <p>Named {@code _dp} rather than reusing the shorter key a percentage once
     * used: the unit changed, and an install that still holds a percentage in the
     * old key would have it read as a dp. Nothing shipped with that key, so no
     * migration is owed and the old name is simply retired.
     *
     * <p>An {@code int}, like {@link #KEY_OUT_TYPE_SIZE}: the framework does no
     * type conversion, so every key has exactly one type for the whole lifetime
     * of the module.
     */
    public static final String KEY_OUT_SIGNAL_SIZE = "out_signal_size_dp";
    /**
     * The gap the out-of-ring network type keeps on its two sides, in density
     * pixels.
     *
     * <p>Two keys rather than one: in the status bar the label is flanked by
     * different things on each side - the native icon row on the outside, the
     * out-of-ring signal reading (or the battery) on the inside - and the two
     * gaps are tuned against those different neighbours.
     *
     * <p>Both are dplike rather than pixels for the same reason as
     * {@link #KEY_OUT_SIGNAL_SIZE}: the label lives in the status bar's real
     * pixel space, whose row height MIUI changes under the control centre, so a
     * length measured in pixels would drift with the shade.
     *
     * <p>Each defaults to the single gap the label used to have hard-coded, so
     * an install that never touches these keeps exactly the spacing it had.
     *
     * <p>New names, never a reuse: the framework does no type conversion, so
     * every key has exactly one type for the whole lifetime of the module - see
     * the note on {@link #KEY_MOBILE_TYPE_MODE}.
     */
    public static final String KEY_OUT_TYPE_MARGIN_LEFT = "out_type_margin_left_dp";
    public static final String KEY_OUT_TYPE_MARGIN_RIGHT = "out_type_margin_right_dp";
    /**
     * How far the out-of-ring signal reading is nudged from the place the status
     * bar computed for it, in density pixels; positive is right and down.
     *
     * <p>Applied to the reading's laid-out frame rather than to its drawing, so
     * the label anchored on the reading follows it instead of being left behind.
     * Kept as dp for the same reason as the margins above.
     *
     * <p>New names, never a reuse - see {@link #KEY_MOBILE_TYPE_MODE}.
     */
    public static final String KEY_OUT_SIGNAL_OFFSET_X = "out_signal_offset_x_dp";
    public static final String KEY_OUT_SIGNAL_OFFSET_Y = "out_signal_offset_y_dp";
    /**
     * How large a trailing "A" is drawn against the rest of the network type, as
     * a percentage.
     *
     * <p>The reference draws "5GA" with the "A" noticeably smaller than the
     * "5G", sitting on the same baseline; 65 reproduces the reference's
     * {@code A height / main height} of about 56/86. 100 turns the shrink off
     * and restores the single-size label.
     *
     * <p>One key for both places the type is drawn - the ring canvas and the
     * out-of-ring status-bar label - because the reference's proportion is a
     * property of the label, not of where it happens to sit.
     *
     * <p>A new name, never a reuse - see {@link #KEY_MOBILE_TYPE_MODE}.
     */
    public static final String KEY_TYPE_SUFFIX_SCALE = "type_suffix_scale";
    public static final String KEY_TYPE_WEIGHT = "type_weight";
    public static final String KEY_TRACK_ALPHA = "track_alpha";

    public static final String KEY_DEBUG_LOG = "debug_log";

    // -------------------------------------------------------------- defaults
    public static final boolean DEF_ENABLED = true;
    public static final boolean DEF_OVERLAY = true;
    public static final boolean DEF_SHOW_WIFI = true;
    public static final boolean DEF_SHOW_MOBILE = true;
    public static final boolean DEF_SHOW_VALUE = true;
    public static final boolean DEF_SHOW_BOLT = true;
    /**
     * Legacy, read-only for migration.
     *
     * <p>Superseded by {@link #DEF_MOBILE_TYPE_MODE}; kept only so the migration
     * can tell what an installed user had before the int key existed.
     */
    public static final boolean DEF_SHOW_MOBILE_TYPE = false;

    /** Off: the network type is not drawn at all. */
    public static final int MOBILE_TYPE_OFF = 0;
    /** Draw the network type inside the ring, in the middle when Wi-Fi is absent. */
    public static final int MOBILE_TYPE_IN_RING = 1;
    /** Draw the network type outside the ring. */
    public static final int MOBILE_TYPE_OUT_RING = 2;
    /**
     * Off by default: the shipment look keeps the battery number in the centre
     * when there is no Wi-Fi, and draws no network type at all.
     */
    public static final int DEF_MOBILE_TYPE_MODE = MOBILE_TYPE_OFF;
    /**
     * Off by default: the shipped look keeps the Wi-Fi arcs in the middle of the
     * ring and the battery reading small in the 12 o'clock notch.
     *
     * <p>Turning this on moves the percentage into the middle, drawn at
     * {@link TrioGeometry#CENTRE_SIZE_RATIO} scale, and shrinks the arcs into the
     * notch.
     */
    public static final boolean DEF_VALUE_CENTRED = false;

    /** The original arrangement: a battery ring with the content inside it. */
    public static final int STYLE_RING = 0;
    /**
     * The rectangular arrangement: a column of four level dots down each side,
     * the network state in the top slot between them, an enlarged percentage
     * below, and a full-width battery bar along the bottom. There is no ring.
     */
    public static final int STYLE_RECT = 1;
    /**
     * The ring, for every install that has never chosen.
     *
     * <p>Only the styles themselves are honoured: the settings screen offers no
     * third entry, so an unrecognised stored value degrades to the ring rather
     * than to a blank glyph.
     */
    public static final int DEF_TRIO_STYLE = STYLE_RING;

    /** The signal is the glyph's own level dots, exactly as shipped. */
    public static final int SIGNAL_IN_RING = 0;
    /**
     * The signal leaves the glyph and is drawn beside the battery instead.
     *
     * <p>What stands there depends on {@link #KEY_STACKED_SIGNAL}: off means
     * MIUI's own signal icon keeps its place, on means the module draws the
     * stacked bars-and-dots reading of its own.
     */
    public static final int SIGNAL_OUT_RING = 1;
    /**
     * Inside the glyph, for every install that has never chosen: the shipped look
     * keeps the level dots in the ring's lower opening.
     */
    public static final int DEF_SIGNAL_MODE = SIGNAL_IN_RING;

    /**
     * Off by default: outside the glyph the shipped behaviour is to hand the
     * signal back to MIUI rather than draw a second version of it.
     */
    public static final boolean DEF_STACKED_SIGNAL = false;
    /** Off by default: the stacked reading shows both SIMs unless told otherwise. */
    public static final boolean DEF_DATA_SIM_ONLY = false;

    /**
     * Off by default: the shipped look draws one row of dots for the current data
     * SIM, and the second row is an opt-in reading for a dual-SIM device.
     */
    public static final boolean DEF_DUAL_SIM = false;

    public static final boolean DEF_ROLE_COLORS = true;
    public static final int DEF_COLOR_CRITICAL_ON_DARK = 0xFFFF3B30;
    public static final int DEF_COLOR_CRITICAL_ON_LIGHT = 0xFFFF3B30;
    public static final int DEF_COLOR_CHARGING_ON_DARK = 0xFF34C759;
    public static final int DEF_COLOR_CHARGING_ON_LIGHT = 0xFF1F8F3D;
    public static final int DEF_COLOR_LOW_ON_DARK = 0xFFF2B900;
    public static final int DEF_COLOR_LOW_ON_LIGHT = 0xFFC99700;
    public static final int DEF_LOW_THRESHOLD = 20;

    public static final int DEF_RING_STROKE = 14;
    public static final int DEF_ARC_STROKE = 12;
    public static final int DEF_VALUE_SIZE = 36;
    /**
     * 700 is {@code Typeface.BOLD}, the weight the reference glyph uses. The
     * whole 100..900 range is exposed so thin skinning and heavy digits are both
     * reachable.
     */
    public static final int DEF_VALUE_WEIGHT = 700;
    /**
     * Font size of the centred network type. Deliberately an absolute size rather
     * than a ratio of {@link #DEF_VALUE_SIZE}: the type is a standalone label the
     * user tunes on its own, and 32 is what the old 0.9 x 36 ratio produced, so
     * the default look is unchanged.
     */
    public static final int DEF_TYPE_SIZE = 32;
    /**
     * Font size of the network type when it is drawn outside the ring, at the
     * status bar's own scale. 32 matches {@link #DEF_TYPE_SIZE}, so a user who
     * never touches the new slider keeps exactly the look they had.
     */
    public static final int DEF_OUT_TYPE_SIZE = 32;
    /**
     * The stacked out-of-ring signal's height, in density pixels.
     *
     * <p>15dp: MIUI's own four signal bars measure 44px on the test device at
     * density 3, and 15dp is 45px there - the reading ships at the size of the
     * native icon it replaces. Being a dp, it lands on the same physical size on
     * any density instead of only matching on this one.
     */
    public static final int DEF_OUT_SIGNAL_SIZE = 15;
    /**
     * The gap the out-of-ring label keeps on each side, in dp.
     *
     * <p>2dp is the value the single hard-coded {@code OUT_LABEL_GAP_DP} used to
     * hold, so the shipped spacing is unchanged and only a user who moves the
     * sliders sees anything different.
     */
    public static final int DEF_OUT_TYPE_MARGIN_LEFT = 2;
    public static final int DEF_OUT_TYPE_MARGIN_RIGHT = 2;
    /**
     * No nudge by default: the reading sits exactly where the status bar laid it
     * out, which is the look every install already has.
     */
    public static final int DEF_OUT_SIGNAL_OFFSET_X = 0;
    public static final int DEF_OUT_SIGNAL_OFFSET_Y = 0;
    /**
     * 65% for the trailing "A": the reference's {@code 56/86} ratio, measured
     * from {@code docs/ref-5ga.png}. 100 turns the shrink off.
     */
    public static final int DEF_TYPE_SUFFIX_SCALE = 65;
    public static final int DEF_TYPE_WEIGHT = 700;
    public static final int DEF_TRACK_ALPHA = 56;

    public static final boolean DEF_DEBUG_LOG = false;

    // --------------------------------------------------------------- bounds
    public static final int MIN_RING_STROKE = 4;
    public static final int MAX_RING_STROKE = 16;
    public static final int MIN_ARC_STROKE = 3;
    public static final int MAX_ARC_STROKE = 16;
    public static final int MIN_VALUE_SIZE = 16;
    public static final int MAX_VALUE_SIZE = 44;
    public static final int MIN_VALUE_WEIGHT = 100;
    public static final int MAX_VALUE_WEIGHT = 900;
    public static final int MIN_TYPE_SIZE = 16;
    public static final int MAX_TYPE_SIZE = 44;
    public static final int MIN_OUT_TYPE_SIZE = 16;
    /**
     * Higher than {@link #MAX_TYPE_SIZE} on purpose: the out-of-ring label sits
     * in the status bar's real pixel space rather than the ring's design space,
     * so it needs headroom the in-ring label does not.
     */
    public static final int MAX_OUT_TYPE_SIZE = 64;
    /**
     * The physical span the slider offers, in density pixels: 6dp (18px at
     * density 3) is where the four bars stop being distinguishable, and 20dp
     * (60px) is the tallest the status bar row shows before the reading starts
     * pushing the icons around it.
     *
     * <p>Narrow on purpose. The point of the setting is to match or gently
     * nudge MIUI's own signal bars, which measure 44px - about 14.7dp - so the
     * shipped 15dp sits in the middle of the range and a quarter of the slider
     * either way is already a visible change.
     */
    public static final int MIN_OUT_SIGNAL_SIZE = 6;
    public static final int MAX_OUT_SIGNAL_SIZE = 20;
    /**
     * The span the two label margins offer, in dp.
     *
     * <p>0 lets the label butt right up against its neighbour and 16 is already
     * wider than the gap between the native icons, so the whole useful range is
     * inside it. The default 2 sits near the low end, where the shipped look is.
     */
    public static final int MIN_OUT_TYPE_MARGIN = 0;
    public static final int MAX_OUT_TYPE_MARGIN = 16;
    /**
     * The nudge the out-of-ring reading accepts, in dp, in either direction.
     *
     * <p>Bounded at 12 so the reading cannot be walked far enough to collide
     * with the battery on one side or leave the status bar's touchable strip on
     * the other; the same span is offered on both axes.
     */
    public static final int MIN_OUT_SIGNAL_OFFSET = -12;
    public static final int MAX_OUT_SIGNAL_OFFSET = 12;
    /**
     * The trailing "A" may be the same size as the rest (100) or a little over
     * half of it (50). Below 50 the suffix stops reading as a letter at status
     * bar sizes, and above 100 it would be the main glyph's larger twin.
     */
    public static final int MIN_TYPE_SUFFIX_SCALE = 50;
    public static final int MAX_TYPE_SUFFIX_SCALE = 100;
    public static final int MIN_TYPE_WEIGHT = 100;
    public static final int MAX_TYPE_WEIGHT = 900;
    public static final int MIN_TRACK_ALPHA = 0;
    public static final int MAX_TRACK_ALPHA = 255;
    public static final int MIN_LOW_THRESHOLD = 5;
    public static final int MAX_LOW_THRESHOLD = 50;

    public static final int MIN_MOBILE_TYPE_MODE = MOBILE_TYPE_OFF;
    public static final int MAX_MOBILE_TYPE_MODE = MOBILE_TYPE_OUT_RING;

    public static final int MIN_TRIO_STYLE = STYLE_RING;
    public static final int MAX_TRIO_STYLE = STYLE_RECT;

    public static final int MIN_SIGNAL_MODE = SIGNAL_IN_RING;
    public static final int MAX_SIGNAL_MODE = SIGNAL_OUT_RING;

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
