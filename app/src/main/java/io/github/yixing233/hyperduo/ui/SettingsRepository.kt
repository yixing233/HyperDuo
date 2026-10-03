package io.github.yixing233.hyperduo.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.github.yixing233.hyperduo.HyperDuoApp
import io.github.yixing233.hyperduo.Prefs
import io.github.yixing233.hyperduo.TrioSettings
import io.github.libxposed.service.XposedService

/**
 * Reads and writes every setting, and mirrors each write to the Xposed
 * framework so the hooked SystemUI process sees it immediately.
 *
 * <p>Two stores are involved and only one of them is authoritative for the
 * hooked process:
 *
 * <ul>
 *   <li>the app's own {@link SharedPreferences} file, which drives this UI and
 *       survives while the framework is absent;
 *   <li>the framework's remote-preference group of the same name, which is what
 *       the hooked process actually reads.
 * </ul>
 *
 * <p>The mirror is therefore not "sync on connect and forget": every single
 * write is pushed, otherwise a value the user changes while the service is
 * bound would never reach SystemUI.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val service: XposedService? get() = HyperDuoApp.xposedService

    private fun preferences() =
        appContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    /** The current values, as the preview should render them. */
    fun read(): TrioSettings = TrioSettings.from(preferences())

    // ------------------------------------------------------------- appearance

    fun setEnabled(value: Boolean) = writeBoolean(Prefs.KEY_ENABLED, value)
    fun setShowWifi(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_WIFI, value)
    fun setShowMobile(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_MOBILE, value)
    fun setShowValue(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_VALUE, value)
    fun setShowBolt(value: Boolean) = writeBoolean(Prefs.KEY_SHOW_BOLT, value)
    fun setDualSim(value: Boolean) = writeBoolean(Prefs.KEY_DUAL_SIM, value)
    fun setValueCentred(value: Boolean) = writeBoolean(Prefs.KEY_VALUE_CENTRED, value)
    /** 0 = hidden, 1 = inside the ring, 2 = outside it. */
    fun setMobileTypeMode(value: Int) = writeInt(Prefs.KEY_MOBILE_TYPE_MODE, value)
    /** 0 = the ring arrangement, 1 = the rectangular one. */
    fun setTrioStyle(value: Int) = writeInt(Prefs.KEY_TRIO_STYLE, value)
    /** 0 = the signal readings stay inside the ring, 1 = they move outside it. */
    fun setSignalMode(value: Int) = writeInt(Prefs.KEY_SIGNAL_MODE, value)
    fun setStackedSignal(value: Boolean) = writeBoolean(Prefs.KEY_STACKED_SIGNAL, value)
    fun setDataSimOnly(value: Boolean) = writeBoolean(Prefs.KEY_DATA_SIM_ONLY, value)

    /** Draws the glyph in a window of its own - see [Prefs.KEY_OVERLAY]. */
    fun setOverlay(value: Boolean) = writeBoolean(Prefs.KEY_OVERLAY, value)

    // ----------------------------------------------------------------- colours

    fun setRoleColors(value: Boolean) = writeBoolean(Prefs.KEY_ROLE_COLORS, value)
    fun setColorCriticalOnDark(value: Int) = writeInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, value)
    fun setColorCriticalOnLight(value: Int) = writeInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, value)
    fun setColorChargingOnDark(value: Int) = writeInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, value)
    fun setColorChargingOnLight(value: Int) = writeInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, value)
    fun setColorLowOnDark(value: Int) = writeInt(Prefs.KEY_COLOR_LOW_ON_DARK, value)
    fun setColorLowOnLight(value: Int) = writeInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, value)

    /** Restores all six role colours to their defaults in one write. */
    fun resetRoleColors() {
        preferences().edit(commit = true) {
            putInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, Prefs.DEF_COLOR_CRITICAL_ON_DARK)
            putInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, Prefs.DEF_COLOR_CRITICAL_ON_LIGHT)
            putInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, Prefs.DEF_COLOR_CHARGING_ON_DARK)
            putInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, Prefs.DEF_COLOR_CHARGING_ON_LIGHT)
            putInt(Prefs.KEY_COLOR_LOW_ON_DARK, Prefs.DEF_COLOR_LOW_ON_DARK)
            putInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, Prefs.DEF_COLOR_LOW_ON_LIGHT)
        }
        push { prefs ->
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, Prefs.DEF_COLOR_CRITICAL_ON_DARK)
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, Prefs.DEF_COLOR_CRITICAL_ON_LIGHT)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, Prefs.DEF_COLOR_CHARGING_ON_DARK)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, Prefs.DEF_COLOR_CHARGING_ON_LIGHT)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_DARK, Prefs.DEF_COLOR_LOW_ON_DARK)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, Prefs.DEF_COLOR_LOW_ON_LIGHT)
        }
        notifyModule()
    }

    // ------------------------------------------------------------------ sizing

    fun setLowThreshold(value: Int) = writeInt(Prefs.KEY_LOW_THRESHOLD, value)
    fun setRingStroke(value: Int) = writeInt(Prefs.KEY_RING_STROKE, value)
    fun setArcStroke(value: Int) = writeInt(Prefs.KEY_ARC_STROKE, value)
    fun setValueSize(value: Int) = writeInt(Prefs.KEY_VALUE_SIZE, value)
    fun setValueWeight(value: Int) = writeInt(Prefs.KEY_VALUE_WEIGHT, value)
    fun setTypeSize(value: Int) = writeInt(Prefs.KEY_TYPE_SIZE, value)
    fun setOutTypeSize(value: Int) = writeInt(Prefs.KEY_OUT_TYPE_SIZE, value)
    fun setOutSignalSize(value: Int) = writeInt(Prefs.KEY_OUT_SIGNAL_SIZE, value)
    fun setOutTypeMarginLeft(value: Int) = writeInt(Prefs.KEY_OUT_TYPE_MARGIN_LEFT, value)
    fun setOutTypeMarginRight(value: Int) = writeInt(Prefs.KEY_OUT_TYPE_MARGIN_RIGHT, value)
    fun setOutSignalOffsetX(value: Int) = writeInt(Prefs.KEY_OUT_SIGNAL_OFFSET_X, value)
    fun setOutSignalOffsetY(value: Int) = writeInt(Prefs.KEY_OUT_SIGNAL_OFFSET_Y, value)
    fun setTypeSuffixScale(value: Int) = writeInt(Prefs.KEY_TYPE_SUFFIX_SCALE, value)
    fun setTypeWeight(value: Int) = writeInt(Prefs.KEY_TYPE_WEIGHT, value)
    fun setTrackAlpha(value: Int) = writeInt(Prefs.KEY_TRACK_ALPHA, value)

    // ---------------------------------------------------------------- advanced

    fun setDebugLog(value: Boolean) = writeBoolean(Prefs.KEY_DEBUG_LOG, value)

    // ----------------------------------------------------------- remote mirror

    /**
     * Pushes every value currently in the app's own file to the framework.
     *
     * <p>Called once when the service binds: while the framework is not
     * connected writes only land in the app's file, so a plain "push on write"
     * would leave SystemUI running with stale values from a previous session.
     */
    fun syncAllToFramework() {
        val current = preferences()
        val snapshot = TrioSettings.from(current)
        push { prefs ->
            prefs.putBoolean(Prefs.KEY_ENABLED, snapshot.enabled)
            prefs.putBoolean(Prefs.KEY_SHOW_WIFI, snapshot.showWifi)
            prefs.putBoolean(Prefs.KEY_SHOW_MOBILE, snapshot.showMobile)
            prefs.putBoolean(Prefs.KEY_SHOW_VALUE, snapshot.showValue)
            prefs.putBoolean(Prefs.KEY_SHOW_BOLT, snapshot.showBolt)
            prefs.putBoolean(Prefs.KEY_DUAL_SIM, snapshot.dualSim)
            prefs.putInt(Prefs.KEY_MOBILE_TYPE_MODE, snapshot.mobileTypeMode)
            prefs.putBoolean(Prefs.KEY_VALUE_CENTRED, snapshot.valueCentred)
            prefs.putInt(Prefs.KEY_TRIO_STYLE, snapshot.trioStyle)
            prefs.putInt(Prefs.KEY_SIGNAL_MODE, snapshot.signalMode)
            prefs.putBoolean(Prefs.KEY_STACKED_SIGNAL, snapshot.stackedSignal)
            prefs.putBoolean(Prefs.KEY_DATA_SIM_ONLY, snapshot.dataSimOnly)
            prefs.putBoolean(Prefs.KEY_OVERLAY, snapshot.overlayGlyph)

            prefs.putBoolean(Prefs.KEY_ROLE_COLORS, snapshot.roleColors)
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_DARK, snapshot.criticalOnDark)
            prefs.putInt(Prefs.KEY_COLOR_CRITICAL_ON_LIGHT, snapshot.criticalOnLight)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_DARK, snapshot.chargingOnDark)
            prefs.putInt(Prefs.KEY_COLOR_CHARGING_ON_LIGHT, snapshot.chargingOnLight)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_DARK, snapshot.lowOnDark)
            prefs.putInt(Prefs.KEY_COLOR_LOW_ON_LIGHT, snapshot.lowOnLight)
            prefs.putInt(Prefs.KEY_LOW_THRESHOLD, snapshot.lowThreshold)

            prefs.putInt(Prefs.KEY_RING_STROKE, snapshot.ringStroke)
            prefs.putInt(Prefs.KEY_ARC_STROKE, snapshot.arcStroke)
            prefs.putInt(Prefs.KEY_VALUE_SIZE, snapshot.valueSize)
            prefs.putInt(Prefs.KEY_VALUE_WEIGHT, snapshot.valueWeight)
            prefs.putInt(Prefs.KEY_TYPE_SIZE, snapshot.typeSize)
            prefs.putInt(Prefs.KEY_OUT_TYPE_SIZE, snapshot.outTypeSize)
            prefs.putInt(Prefs.KEY_OUT_SIGNAL_SIZE, snapshot.outSignalSize)
            prefs.putInt(Prefs.KEY_OUT_TYPE_MARGIN_LEFT, snapshot.outTypeMarginLeft)
            prefs.putInt(Prefs.KEY_OUT_TYPE_MARGIN_RIGHT, snapshot.outTypeMarginRight)
            prefs.putInt(Prefs.KEY_OUT_SIGNAL_OFFSET_X, snapshot.outSignalOffsetX)
            prefs.putInt(Prefs.KEY_OUT_SIGNAL_OFFSET_Y, snapshot.outSignalOffsetY)
            prefs.putInt(Prefs.KEY_TYPE_SUFFIX_SCALE, snapshot.typeSuffixScale)
            prefs.putInt(Prefs.KEY_TYPE_WEIGHT, snapshot.typeWeight)
            prefs.putInt(Prefs.KEY_TRACK_ALPHA, snapshot.trackAlpha)

            prefs.putBoolean(Prefs.KEY_DEBUG_LOG, current.getBoolean(Prefs.KEY_DEBUG_LOG, Prefs.DEF_DEBUG_LOG))
        }
        notifyModule()
    }

    private fun writeBoolean(key: String, value: Boolean) {
        preferences().edit(commit = true) { putBoolean(key, value) }
        push { it.putBoolean(key, value) }
        notifyModule()
    }

    private fun writeInt(key: String, value: Int) {
        preferences().edit(commit = true) { putInt(key, value) }
        push { it.putInt(key, value) }
        notifyModule()
    }

    /**
     * Tells the hooked process to re-read the settings, carrying the whole
     * snapshot in the intent.
     *
     * <p>This is a second, callback-independent path: the framework's
     * remote-preference change callback is the intended mechanism, but it has
     * been observed to stop reaching SystemUI on device - the daemon database
     * held the new value while the status bar kept drawing the old one. The
     * broadcast is sent even when the service is not bound, because the hooked
     * process is up regardless and only needs the extras.
     *
     * <p>Delivery is asynchronous, so no ordering against [push] is needed; both
     * carry the same values.
     */
    private fun notifyModule() {
        runCatching {
            val intent = Intent(Prefs.ACTION_RELOAD)
                .setPackage(Prefs.SYSTEMUI_PACKAGE)
                .putExtras(TrioSettings.from(preferences()).toBundle())
            appContext.sendBroadcast(intent)
        }.onFailure { Log.w(TAG, "cannot notify the hooked process", it) }
    }

    /** Applies [block] to the framework's remote preferences, if connected. */
    private fun push(block: (SharedPreferences.Editor) -> Unit) {
        val bound = service
        if (bound == null) {
            // Not fatal: onServiceBind replays every value through
            // syncAllToFramework(), so the write is not lost. Logged because a
            // silent return here looks exactly like "live updates are broken".
            Log.w(TAG, "framework not bound, settings will sync on connect")
            return
        }
        runCatching {
            val editor = bound.getRemotePreferences(Prefs.NAME).edit()
            block(editor)
            // commit() rather than apply(): the value has to be visible in the
            // hooked process before the next status-bar frame is drawn.
            editor.commit()
        }.onFailure { Log.e(TAG, "cannot push settings to framework", it) }
    }

    private companion object {
        const val TAG = "HyperDuo"
    }
}
