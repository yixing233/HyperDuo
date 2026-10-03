package io.github.yixing233.hyperduo.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.PackageInfoCompat
import io.github.yixing233.hyperduo.HyperDuoApp
import io.github.yixing233.hyperduo.Prefs
import io.github.yixing233.hyperduo.R
import io.github.yixing233.hyperduo.TrioAppearance
import io.github.yixing233.hyperduo.TrioPreviewView
import io.github.yixing233.hyperduo.TrioSettings
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Inner padding of a preference row inside its Card. Miuix's Card carries no
 * outer margin and its preferences default to a uniform 16.dp box; the MIUI look
 * wants 18.dp against the card edge and a slightly tighter 14.dp vertical rhythm.
 */
private val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

/**
 * Blur radius of the top bar, in dp.
 *
 * Well above the library's 20.dp default because the bar is as tall as the
 * status bar plus a large title: at a small radius the rows underneath stay
 * legible and the bar reads as a translucent scrim rather than frosted glass.
 */
private val BarBlurRadius = 40f

/**
 * Group heading. Miuix's [SmallTitle] defaults to a 28.dp vertical margin, which
 * reads as a stray indent once the page itself only insets 12.dp; the reference
 * page narrows it to 18.dp so the heading lines up with the card padding instead
 * of the card edge.
 */
@Composable
private fun SectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** Which role colour the picker dialog is editing. */
private enum class RoleColor(val labelRes: Int) {
    Low(R.string.color_low_title),
    Charging(R.string.color_charging_title),
    Critical(R.string.color_critical_title),
}

/**
 * The page's top-level sections, in strip order. The label reuses the section
 * heading the group already had, so the strip reads as a table of contents for
 * the page rather than a second vocabulary for the same four things.
 *
 * Each section is also a destination on the nav back stack ([route]), which is
 * what gives a section switch its own animated page change and gives the system
 * back gesture something to pop. The routes' names and the strip's names are the
 * same four words, but they are separate types on purpose: the label is a
 * resource id, which has no business living on a value that gets serialized into
 * the saved-state bundle.
 */
private enum class SettingsTab(val labelRes: Int, val route: Route) {
    General(R.string.tab_general, Route.General),
    Geometry(R.string.group_geometry, Route.Geometry),
    Colors(R.string.group_colors, Route.Colors),
    About(R.string.about_title, Route.About),
}

/**
 * Destinations of the settings back stack, one per section.
 *
 * Serialized data objects rather than a plain enum or class: miuix-nav keeps its
 * stack in rememberSaveable and rebuilds it through a serializer, so every route
 * has to round-trip. It also namespaces each route's saved state by the route's
 * own toString(), and an object's toString() is its class name — stable across a
 * process death, unlike the identity hash a plain class would print.
 *
 * The on-screen state that is not a place — the colour picker, the restart sheet,
 * the reset prompt — deliberately stays a dialog. Those are prompts dismissed
 * over the page, and pushing them would turn the back gesture into "cancel"
 * instead of "go back a section".
 */
@Serializable
private sealed interface Route : NavKey {
    @Serializable
    data object General : Route

    @Serializable
    data object Geometry : Route

    @Serializable
    data object Colors : Route

    @Serializable
    data object About : Route
}

/**
 * The section change: the two sections cross-fade where they stand.
 *
 * The library's default preset moves a page — the entering section slides in
 * full-width from the trailing edge, the outgoing one parallaxes a quarter width
 * the other way, and the effects layer lays a 50% scrim over the lot. That is the
 * right motion for arriving at a new screen and the wrong one here: the four tabs
 * are four views of one page, and the strip above them never moves. Sliding them
 * sideways reads as the page tearing in half.
 *
 * So this transition moves nothing. It only ramps the incoming layer's alpha up
 * from 0 over the outgoing one, which stays fully opaque until it is covered. The
 * two are the same size and sit in the same place, so the alpha alone reads as one
 * section turning into the next.
 *
 * `relativeDepth` is the single driver: 0 at rest on top, -1 fully out above it,
 * +1 fully covered by the layer above. Both directions therefore come out
 * symmetric — a push fades the new section in over the old, a pop fades the
 * revealed section back in the same way — with no per-direction branch.
 *
 * The covered branch still assigns alpha explicitly rather than leaving it alone:
 * the block runs inside a retained `graphicsLayer`, so a property it stops setting
 * keeps the value from the frame before — which would freeze the layer at the alpha
 * of its last entering frame instead of letting it read as opaque.
 */
private val SectionTransition: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    // 220ms rather than the preset's 500ms: a section swap is a glance sideways,
    // not a journey, and the default was tuned for a full page push.
    motion = NavMotion(programmatic = NavSettleSpec.Tween(220, FastOutSlowInEasing)),
) { scope ->
    val d = scope.relativeDepth
    alpha = if (d <= 0f) (1f + d).coerceIn(0f, 1f) else 1f
}

/**
 * The transition above draws no scrim and rounds no corners, because there is
 * nothing to separate: no layer ever sits on top of another here, and the corner
 * clip exists to round the edge of a page sliding across the screen.
 */
private val SectionEffects = NavDisplayEffects(
    enableCornerClip = false,
    dimAmount = 0f,
)

@Composable
fun SettingsScreen(repository: SettingsRepository) {
    // Kept as explicit state objects, not just `by` delegates, because the nav
    // entries below are built once and remembered: an entry that closed over the
    // *value* of settings would keep rendering the snapshot it was built with.
    // Handing the entries these objects makes every read a real snapshot read,
    // taken while the destination composes.
    val settingsState = remember { mutableStateOf(repository.read()) }
    var settings by settingsState
    val serviceState = remember { mutableStateOf(HyperDuoApp.xposedService) }
    var service by serviceState
    var editing by remember { mutableStateOf<RoleColor?>(null) }
    var resettingColors by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }

    // The section the page is on *is* the top of the back stack, rather than a
    // second piece of state kept in step with it: the strip and the system back
    // gesture then read and write the same list, so they cannot disagree about
    // where the user is.
    //
    // The supertype is spelled out because the reified parameter is otherwise
    // inferred from the argument, and a stack declared as the first concrete
    // route cannot hold the other three once the process is recreated.
    val nav = rememberNavController<Route>(Route.General)

    // The framework service can bind or die at any moment; both the status row
    // and every remote write have to follow it.
    DisposableEffect(repository) {
        val listener: (XposedService?) -> Unit = { service = it }
        HyperDuoApp.addServiceListener(listener)
        onDispose { HyperDuoApp.removeServiceListener(listener) }
    }

    // Held here rather than inside the About tab: a LazyColumn disposes items
    // that scroll out of view, and a download in flight must survive the user
    // flicking over to another tab and back.
    val context = LocalContext.current
    val updater = remember(context) { UpdateController(context.applicationContext) }
    DisposableEffect(updater) { onDispose { updater.dispose() } }

    // Same reasoning as the updater: the dialog can be dismissed mid-kill, and a
    // root prompt that outlives the dialog still has to land somewhere.
    val restarter = remember { RestartController() }
    DisposableEffect(restarter) { onDispose { restarter.dispose() } }

    // Single funnel: apply the write, then re-read so the preview never shows a
    // value the repository did not accept (clamping happens on read).
    fun update(block: (SettingsRepository) -> Unit) {
        block(repository)
        settings = repository.read()
    }

    // Switching section: tapping the one already open is a no-op rather than a
    // second copy of it on the stack — the library rejects a repeated route, and
    // the strip accepts taps faster than a transition runs. Going back to a
    // section further down walks back to it instead of pushing it again, so the
    // stack stays a path through the page rather than a log of every tap.
    fun selectTab(next: SettingsTab) {
        val backStack = nav.backStack
        if (backStack.lastOrNull() == next.route) return
        if (backStack.contains(next.route)) {
            nav.popUntil { it == next.route }
        } else {
            nav.push(next.route)
        }
    }

    // The preview and the strip are the head of every section rather than a band
    // pinned above the display. They then scroll away with the rows they belong
    // to, which is how this page behaved before it had a back stack, and the
    // display has no reason to reshape the page to run a transition over it.
    //
    // Being the same two rows in all four sections, they also cost the transition
    // nothing: two copies fading through one another land on identical pixels.
    fun LazyListScope.sectionHeader() {
        item { PreviewCard(settingsState.value) }
        item {
            // Read here rather than taken from the enclosing scope: the entries
            // are built once and remembered, so a value captured above would keep
            // highlighting whichever section was open when the screen appeared.
            val current = SettingsTab.entries.firstOrNull { it.route == nav.backStack.lastOrNull() }
                ?: SettingsTab.General
            val labels = SettingsTab.entries.map { stringResource(it.labelRes) }
            TabRow(
                tabs = labels,
                selectedTabIndex = current.ordinal,
                onTabSelected = { index -> selectTab(SettingsTab.entries[index]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    // Resolved here rather than inside the click lambda: a string resource needs
    // a composable context, and the dialog is opened from a lambda.
    val restartTitle = stringResource(R.string.restart_title)
    val restartDone = stringResource(R.string.restart_done)

    // Miuix's own host rather than a system Toast: the snackbar is drawn on the
    // same surface as the page that raised it, so it follows the theme and dies
    // with the screen instead of hovering over whatever comes next.
    //
    // Short (4s) rather than Long (10s): these messages are one short
    // sentence, and ten seconds is a long time to look at one. The user can
    // swipe the bar away if even four is too long.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Remembered so the row callbacks below capture one stable value: a lambda
    // rebuilt every recomposition would make every row that takes it unstable.
    val showMessage: (String) -> Unit = remember(scope, snackbarHostState) {
        { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
            Unit
        }
    }

    // The kill lands on System UI, not on this process, so the confirmation
    // outlives the dialog and has to be shown from here.
    LaunchedEffect(restarter.state) {
        if (restarter.state is RestartState.Done) {
            showMessage(restartDone)
            restarting = false
        }
    }

    // Hoisted out of the draw lambda below, which is not a composable scope and
    // so cannot read the theme itself.
    val surfaceColor = MiuixTheme.colorScheme.surface

    // Blur needs a runtime shader (API 33). The library skips the effect itself
    // on older devices, but that is not enough here: the blur is what replaces
    // the bar's background, and the bar applies the modifier before that
    // background, so an un-gated bar would end up with no background at all and
    // the list would scroll straight through the title.
    val barBlurSupported = isRuntimeShaderSupported()

    // Remembered unconditionally: a conditional remember would break the slot
    // table, and the layer costs nothing while nothing samples it.
    //
    // Keyed on the colour alone; rememberLayerBackdrop reads the draw lambda
    // through rememberUpdatedState, so a fresh lambda per frame does not rebuild
    // the backdrop and reset its coordinates.
    val barBackdrop = rememberLayerBackdrop {
        // The captured layer has to be opaque first. The page leaves its margins
        // and the gaps between cards transparent, and blurring those pixels
        // smears their neighbours' colour across the whole bar.
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                // The blur is drawn by the modifier, which the bar applies before
                // its own background, so the background has to get out of the
                // way for the effect to be visible at all.
                modifier = if (barBlurSupported) {
                    Modifier.textureBlur(
                        backdrop = barBackdrop,
                        // The bar is flush with the screen edges, so there is no
                        // corner for a rounded shape to round off.
                        shape = RectangleShape,
                        blurRadius = BarBlurRadius,
                    )
                } else {
                    Modifier
                },
                color = if (barBlurSupported) Color.Transparent else surfaceColor,
                largeTitle = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        // The dialog is the only place a kill can be started, so
                        // a stale failure from last time must not greet the user.
                        onClick = {
                            restarter.reset()
                            restarting = true
                        },
                    ) {
                        Icon(imageVector = MiuixIcons.Refresh, contentDescription = restartTitle)
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val sectionPadding = PaddingValues(
            start = 12.dp,
            // The bar's inset belongs to the list now that the header is its first
            // row: the preview used to be laid out outside the display, where the
            // Scaffold's padding reached it.
            top = padding.calculateTopPadding() + 12.dp,
            end = 12.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        )

        // The body of the page: one destination per section, each with its own
        // list. Nothing is pinned above it, so the page keeps the shape it had
        // before there was any navigation at all — the preview and the strip are
        // simply the first two rows of whichever section is open.
        //
        // The default transition and its effects are replaced wholesale: they
        // are built for arriving at a page, and these four entries are four
        // views of one page. See [SectionTransition].
        NavDisplay(
            backStack = nav.backStack,
            modifier = Modifier
                .fillMaxSize()
                // Both modifiers reach down into the display rather than
                // sitting on a wrapper: the blur samples all of it, and the
                // scroll listener has to be above the list — which lives one
                // level down inside a destination — to hear the scroll that
                // collapses the bar.
                .layerBackdrop(barBackdrop)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            onBack = { nav.pop() },
            transition = SectionTransition,
            effects = SectionEffects,
        ) {
            // Every entry reads settingsState/serviceState rather than a value
            // captured here: the provider is remembered, so an entry built with a
            // snapshot of the settings would go on showing that snapshot.
            entry<Route.General> {
                SectionList(sectionPadding, isTop = nav.backStack.lastOrNull() == Route.General) {
                    sectionHeader()
                    val s = settingsState.value
                    generalTab(s) { block -> update(block) }
                }
            }

            entry<Route.Geometry> {
                SectionList(sectionPadding, isTop = nav.backStack.lastOrNull() == Route.Geometry) {
                    sectionHeader()
                    val s = settingsState.value
                    geometryTab(s) { block -> update(block) }
                }
            }

            entry<Route.Colors> {
                SectionList(sectionPadding, isTop = nav.backStack.lastOrNull() == Route.Colors) {
                    sectionHeader()
                    val s = settingsState.value
                    colorsTab(
                        settings = s,
                        update = { block -> update(block) },
                        onEdit = { role -> editing = role },
                        onReset = { resettingColors = true },
                    )
                }
            }

            entry<Route.About> {
                SectionList(sectionPadding, isTop = nav.backStack.lastOrNull() == Route.About) {
                    sectionHeader()
                    val s = settingsState.value
                    aboutTab(s, serviceState.value, updater, showMessage) { block ->
                        update(block)
                    }
                }
            }
        }

        // Must stay inside the Scaffold: MiuixPopupHost reads LocalRootDialogStates,
        // which Scaffold provides. Composed outside this lambda the dialog would
        // register itself on a list nothing ever renders, and silently never appear.
        val current = editing
        if (current != null) {
            ColorDialog(
                role = current,
                settings = settings,
                onPick = { onDark, argb ->
                    update { repo ->
                        when (current) {
                            RoleColor.Critical ->
                                if (onDark) repo.setColorCriticalOnDark(argb)
                                else repo.setColorCriticalOnLight(argb)

                            RoleColor.Low ->
                                if (onDark) repo.setColorLowOnDark(argb)
                                else repo.setColorLowOnLight(argb)

                            RoleColor.Charging ->
                                if (onDark) repo.setColorChargingOnDark(argb)
                                else repo.setColorChargingOnLight(argb)
                        }
                    }
                },
                onDismiss = { editing = null },
            )
        }

        RestartDialog(
            controller = restarter,
            show = restarting,
            onDismiss = { restarting = false },
        )

        ResetColorsDialog(
            show = resettingColors,
            onDismiss = { resettingColors = false },
            onConfirm = {
                update { it.resetRoleColors() }
                resettingColors = false
            },
        )
    }
}

/**
 * The scrolling body of one destination: the shared header and then the rows of
 * whichever section this is.
 *
 * Each destination carries its own copy of the header — the preview and the strip
 * are the same two rows everywhere — because they have to scroll with the list
 * they belong to. A band pinned above the display would not: it would have to sit
 * outside every destination, which is a different page shape from the one the rows
 * were written for.
 *
 * The rows arrive as a [LazyListScope] builder rather than a composable so each
 * section keeps contributing plain `item {}`s: the list owns their identity and
 * therefore their state, and the shape of the four `*Tab` functions is unchanged.
 *
 * [isTop] is whether this destination is the section currently on screen — the top
 * of the back stack. It gates the reset to the first row, so a section opens at the
 * top every time the strip brings it forward rather than resuming where it was left.
 */
@Composable
private fun SectionList(
    contentPadding: PaddingValues,
    isTop: Boolean,
    content: LazyListScope.() -> Unit,
) {
    // A section is a place the reader arrives at, not a document they come back to:
    // opening one starts at its first row. The offset therefore belongs to the visit
    // and is not carried across — the entry stays composed while it is covered, so a
    // list that kept its position would drop the reader into the middle of a section
    // they had just chosen from the strip.
    //
    // Keyed on [isTop] rather than run once, because the same composed list is
    // covered and uncovered as sections change; the reset has to happen on each
    // arrival, not on the composition that happened to create it.
    val listState = rememberLazyListState()
    LaunchedEffect(isTop) {
        if (isTop) listState.scrollToItem(0)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // Opaque, because a destination here is a whole page. The section being
            // left stays composed and keeps being drawn underneath for the length of
            // the transition — the visible window is `-1 < d <= 1`, deliberately wide
            // enough to cross-fade from. Nothing else hides it: both lists are the same
            // size and sit in the same place, so the covered one is covered only by the
            // pixels the top one actually paints. A transparent list therefore lets the
            // previous section show through the gaps between cards and below the end of
            // a shorter section, which reads as the old tab lying under the new one.
            //
            // The same colour the Scaffold paints, which was doing this job alone while
            // there was only ever one list to paint.
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/**
 * General: the master and appearance switches, then the advanced rows.
 * Advanced has no tab of its own, so it folds in here.
 */
private fun LazyListScope.generalTab(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    item { SectionTitle(stringResource(R.string.group_appearance)) }
    item {
        Card {
            // The same appearance the renderer builds from these settings, so a
            // row here and the glyph on screen can never disagree about what is
            // switched on - which is what used to let the two drift apart.
            val a = TrioAppearance.of(settings)
            SwitchPreference(
                checked = settings.enabled,
                onCheckedChange = { value -> update { it.setEnabled(value) } },
                title = stringResource(R.string.master_title),
                summary = stringResource(R.string.master_summary),
                insideMargin = SettingsItemMargin,
            )
            // 0 = the ring arrangement, 1 = the rectangular one; the option list
            // is ordered so its index is exactly the stored value.
            //
            // Sits directly under the master switch rather than with the sliders:
            // it decides which arrangement every other row below applies to, and
            // it is the one setting here that is a choice of layout rather than
            // of content. Gated on the master switch only - the arrangement is
            // still worth picking while the icon itself is off, and nothing here
            // depends on Wi-Fi or the percentage being shown.
            val styles = listOf(
                stringResource(R.string.trio_style_ring),
                stringResource(R.string.trio_style_rect),
            )
            OverlayDropdownPreference(
                items = styles,
                selectedIndex = a.style,
                title = stringResource(R.string.trio_style_title),
                summary = stringResource(R.string.trio_style_summary),
                insideMargin = SettingsItemMargin,
                enabled = a.glyph,
                onSelectedIndexChange = { index -> update { repo -> repo.setTrioStyle(index) } },
            )
            // Where the glyph is drawn, not what it draws. The status bar window
            // is only about 43dp tall and clips the rest, so this is the switch
            // between "whole glyph, in a window of its own" and "inside the bar,
            // cut down to the bar's height".
            SwitchPreference(
                checked = settings.overlayGlyph,
                onCheckedChange = { value -> update { it.setOverlay(value) } },
                title = stringResource(R.string.overlay_title),
                summary = stringResource(R.string.overlay_summary),
                insideMargin = SettingsItemMargin,
                enabled = a.glyph,
            )
            SwitchPreference(
                checked = a.wifi,
                onCheckedChange = { value -> update { it.setShowWifi(value) } },
                title = stringResource(R.string.show_wifi_title),
                summary = stringResource(R.string.show_wifi_summary),
                insideMargin = SettingsItemMargin,
                enabled = a.glyph,
            )
            SwitchPreference(
                checked = a.mobile,
                onCheckedChange = { value -> update { it.setShowMobile(value) } },
                title = stringResource(R.string.show_mobile_title),
                summary = stringResource(R.string.show_mobile_summary),
                insideMargin = SettingsItemMargin,
                enabled = a.glyph,
            )
            // Sits directly under the mobile meter because it only ever reshapes
            // that meter: one row per SIM instead of one row for the data SIM.
            // Gated on showMobile for the same reason the bolt row is gated on
            // showValue - with no meter there is nothing for this to reshape.
            val mobileGate = gateHint(
                a.glyph to R.string.master_title,
                a.mobile to R.string.show_mobile_title,
            )
            TooltipBox(text = mobileGate.orEmpty(), enabled = mobileGate != null) {
                SwitchPreference(
                    checked = a.dualSim,
                    onCheckedChange = { value -> update { it.setDualSim(value) } },
                    title = stringResource(R.string.dual_sim_title),
                    summary = stringResource(R.string.dual_sim_summary),
                    insideMargin = SettingsItemMargin,
                    enabled = a.glyph && a.mobile,
                )
            }
            SwitchPreference(
                checked = a.value,
                onCheckedChange = { value -> update { it.setShowValue(value) } },
                title = stringResource(R.string.show_value_title),
                summary = stringResource(R.string.show_value_summary),
                insideMargin = SettingsItemMargin,
                enabled = a.glyph,
            )
            // The three rows below are the out-of-ring reading. The first is a
            // position rather than a switch, and the other two are switches that
            // only mean anything once the reading has actually moved out of the
            // ring - which is why both of them name that position as their gate.
            //
            // Stored values are 0 = inside the ring, 1 = outside it, so the
            // option list is ordered so its index is exactly the stored value.
            val signalModes = listOf(
                stringResource(R.string.signal_mode_inside),
                stringResource(R.string.signal_mode_outside),
            )
            OverlayDropdownPreference(
                items = signalModes,
                selectedIndex = a.signalMode,
                title = stringResource(R.string.signal_mode_title),
                summary = stringResource(R.string.signal_mode_summary),
                insideMargin = SettingsItemMargin,
                // Gated on the master switch only: the position is still worth
                // picking while the icon is off, and it is the reading's own
                // switch, not the dots', so the mobile row above must not hold
                // it shut.
                enabled = a.glyph,
                onSelectedIndexChange = { index -> update { repo -> repo.setSignalMode(index) } },
            )
            // Every gate here is another row, so the hint can always name the
            // switch that is holding it shut. Self-gating would be unrecoverable:
            // the rule would go false when the switch is turned off, and the row
            // would disable before it could be turned back on.
            val stackedGate = gateHint(
                a.glyph to R.string.master_title,
                a.signalOutOfRing to R.string.signal_mode_title,
            )
            TooltipBox(text = stackedGate.orEmpty(), enabled = stackedGate != null) {
                SwitchPreference(
                    checked = a.stackedSignal,
                    onCheckedChange = { value -> update { it.setStackedSignal(value) } },
                    title = stringResource(R.string.stacked_signal_title),
                    summary = stringResource(R.string.stacked_signal_summary),
                    insideMargin = SettingsItemMargin,
                    enabled = a.glyph && a.signalOutOfRing,
                )
            }
            // Needs the reading to be in the out-of-ring form before there is a
            // second reading to hide: turned off, the native icon shows both SIMs
            // on its own and this row could not deliver what it promises. The
            // stacked switch is named rather than the rule's own outcome
            // (stackedOut), so the hint points at the row the user can act on.
            val dataSimGate = gateHint(
                a.glyph to R.string.master_title,
                a.signalOutOfRing to R.string.signal_mode_title,
                a.stackedSignal to R.string.stacked_signal_title,
            )
            TooltipBox(text = dataSimGate.orEmpty(), enabled = dataSimGate != null) {
                SwitchPreference(
                    checked = a.dataSimOnly,
                    onCheckedChange = { value -> update { it.setDataSimOnly(value) } },
                    title = stringResource(R.string.data_sim_only_title),
                    summary = stringResource(R.string.data_sim_only_summary),
                    insideMargin = SettingsItemMargin,
                    enabled = a.glyph && a.signalOutOfRing && a.stackedSignal,
                )
            }
            // The rows below inherit the master gate, but the ones with a second
            // dependency explain the specific switch that is holding them shut:
            // "off because the master is off" is already visible on screen.
            //
            // The gate is the percentage switch, and it has to be that switch
            // rather than {@link TrioAppearance#drawsBolt}. drawsBolt() folds in
            // this very row's own value, so gating on it makes the switch lock
            // itself: turn the bolt off and "the rule would draw a bolt" goes
            // false, the row disables, and it can never be turned back on.
            // A row may be gated by other switches but never by its own.
            val valueGate = gateHint(
                a.glyph to R.string.master_title,
                a.value to R.string.show_value_title,
            )
            TooltipBox(text = valueGate.orEmpty(), enabled = valueGate != null) {
                SwitchPreference(
                    checked = a.boltWanted,
                    onCheckedChange = { value -> update { it.setShowBolt(value) } },
                    title = stringResource(R.string.show_bolt_title),
                    summary = stringResource(R.string.show_bolt_summary),
                    insideMargin = SettingsItemMargin,
                    enabled = a.glyph && a.value,
                )
            }
            // 0 = hidden, 1 = inside the ring, 2 = drawn outside the ring; the
            // option list is ordered so its index is exactly the stored value.
            //
            // Gated on the master switch only, not on showValue the way the bolt
            // row above is: the renderer picks the in-ring type centre slot
            // without consulting showValue (TrioRenderer typeInCentre), and the
            // out-of-ring label is a separate view entirely. Requiring the
            // percentage would leave this row unreachable for no reason.
            val typeModes = listOf(
                stringResource(R.string.mobile_type_off),
                stringResource(R.string.mobile_type_inside),
                stringResource(R.string.mobile_type_outside),
            )
            OverlayDropdownPreference(
                items = typeModes,
                selectedIndex = a.typeMode,
                title = stringResource(R.string.show_mobile_type_title),
                summary = stringResource(R.string.show_mobile_type_summary),
                insideMargin = SettingsItemMargin,
                enabled = a.glyph,
                onSelectedIndexChange = { index -> update { repo -> repo.setMobileTypeMode(index) } },
            )
            // The centred-percentage row has three dependencies, and the style is
            // one of them: the rectangle has no notch, so
            // {@code TrioAppearance.centreValue} is false there whatever the
            // stored switch says. Gating on that rule - rather than on
            // {@code settings.valueCentred && !a.rect} - keeps the reason in one
            // place. It is also the one intended behaviour change of this
            // refactor: under the rectangle the row is now plainly unavailable
            // instead of silently doing nothing.
            val centreHint = gateHint(
                a.glyph to R.string.master_title,
                !a.rect to R.string.trio_style_ring,
                a.wifi to R.string.show_wifi_title,
                a.value to R.string.show_value_title,
            )
            TooltipBox(text = centreHint.orEmpty(), enabled = centreHint != null) {
                SwitchPreference(
                    checked = settings.valueCentred,
                    onCheckedChange = { value -> update { it.setValueCentred(value) } },
                    title = stringResource(R.string.value_centre_title),
                    summary = stringResource(R.string.value_centre_summary),
                    insideMargin = SettingsItemMargin,
                    // The arcs have to be drawn for "move them into the notch" to
                    // mean anything, and the percentage has to be drawn for it to
                    // be centred at all.
                    enabled = a.glyph && !a.rect && a.wifi && a.value,
                )
            }
        }
    }
}

/** Geometry: the stroke, text, network-type and track sliders. */
private fun LazyListScope.geometryTab(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    // Same rule the renderer builds, so the two style-dependent labels below -
    // the bar thickness, and the arc's vertical placement in the top slot -
    // follow the arrangement actually on screen instead of a second copy of it.
    val a = TrioAppearance.of(settings)

    item { SectionTitle(stringResource(R.string.group_stroke)) }
    item {
        Card {
            IntSlider(
                value = settings.ringStroke,
                min = Prefs.MIN_RING_STROKE,
                max = Prefs.MAX_RING_STROKE,
                // One stroke setting, two names: the rectangle spends it on the
                // bar's thickness, the ring on the outline's width.
                title = stringResource(
                    if (a.rect) R.string.stroke_title_rect else R.string.stroke_title,
                ),
                summary = stringResource(
                    if (a.rect) R.string.stroke_summary_rect else R.string.stroke_summary,
                ),
                enabled = a.glyph,
                onValueChange = { v -> update { it.setRingStroke(v) } },
            )
            IntSlider(
                value = settings.arcStroke,
                min = Prefs.MIN_ARC_STROKE,
                max = Prefs.MAX_ARC_STROKE,
                title = stringResource(R.string.arc_stroke_title),
                // In the rectangle the arc group hangs off the top slot by its
                // own stroke, so this slider moves the arcs as well as thickening
                // them; the ring's summary has nothing to add there.
                summary = stringResource(
                    if (a.rect) {
                        R.string.arc_stroke_summary_rect
                    } else {
                        R.string.arc_stroke_summary
                    },
                ),
                enabled = a.glyph,
                onValueChange = { v -> update { it.setArcStroke(v) } },
            )
        }
    }
    item { SectionTitle(stringResource(R.string.group_text)) }
    item {
        Card {
            IntSlider(
                value = settings.valueSize,
                min = Prefs.MIN_VALUE_SIZE,
                max = Prefs.MAX_VALUE_SIZE,
                title = stringResource(R.string.value_size_title),
                summary = stringResource(R.string.value_size_summary),
                enabled = a.glyph,
                onValueChange = { v -> update { it.setValueSize(v) } },
            )
            val weightHint = gateHint(
                a.glyph to R.string.master_title,
                a.value to R.string.show_value_title,
            )
            TooltipBox(text = weightHint.orEmpty(), enabled = weightHint != null) {
                IntSlider(
                    value = settings.valueWeight,
                    min = Prefs.MIN_VALUE_WEIGHT,
                    max = Prefs.MAX_VALUE_WEIGHT,
                    title = stringResource(R.string.value_weight_title),
                    summary = stringResource(R.string.value_weight_summary),
                    // A 100..900 range would give the slider 799 steps, so
                    // it snaps in hundreds: the nine weights the platform
                    // actually ships distinct faces for.
                    step = WEIGHT_STEP,
                    enabled = a.glyph && a.value,
                    onValueChange = { v -> update { it.setValueWeight(v) } },
                )
            }
        }
    }
    item { SectionTitle(stringResource(R.string.group_type)) }
    item {
        Card {
            // These two sizes drive the same glyph in two different spaces.
            // The in-ring size is authored against the ring's 120x120 design
            // space, so it tops out at 44; the out-of-ring label is laid out
            // in the status bar's real pixel space and needs the wider 16..64
            // range it carries on its own slider below. They are deliberately
            // separate settings: one number cannot serve both spaces.
            val typeSizeHint = gateHint(
                a.glyph to R.string.master_title,
                a.typeInRing to R.string.show_mobile_type_title,
            )
            TooltipBox(text = typeSizeHint.orEmpty(), enabled = typeSizeHint != null) {
                IntSlider(
                    value = settings.typeSize,
                    min = Prefs.MIN_TYPE_SIZE,
                    max = Prefs.MAX_TYPE_SIZE,
                    title = stringResource(R.string.type_size_title),
                    summary = stringResource(R.string.type_size_summary),
                    enabled = a.glyph && a.typeInRing,
                    onValueChange = { v -> update { it.setTypeSize(v) } },
                )
            }
            // The out-of-ring label has its own size, live only when the label
            // actually sits out of the ring.
            val outTypeSizeHint = gateHint(
                a.glyph to R.string.master_title,
                a.typeOutOfRing to R.string.show_mobile_type_title,
            )
            TooltipBox(
                text = outTypeSizeHint.orEmpty(),
                enabled = outTypeSizeHint != null,
            ) {
                IntSlider(
                    value = settings.outTypeSize,
                    min = Prefs.MIN_OUT_TYPE_SIZE,
                    max = Prefs.MAX_OUT_TYPE_SIZE,
                    title = stringResource(R.string.out_type_size_title),
                    summary = stringResource(R.string.out_type_size_summary),
                    enabled = a.glyph && a.typeOutOfRing,
                    onValueChange = { v -> update { it.setOutTypeSize(v) } },
                )
            }
            // The trailing "A" of "5GA", shrunk against the main label. It is a
            // property of the label itself rather than of either position, so
            // its gate is "a network type is drawn somewhere" - and it stays
            // live while the type is out of ring, where the label is a real
            // TextView and both draws have to agree.
            val suffixScaleHint = gateHint(
                a.glyph to R.string.master_title,
                a.typeAnywhere() to R.string.show_mobile_type_title,
            )
            TooltipBox(text = suffixScaleHint.orEmpty(), enabled = suffixScaleHint != null) {
                IntSlider(
                    value = settings.typeSuffixScale,
                    min = Prefs.MIN_TYPE_SUFFIX_SCALE,
                    max = Prefs.MAX_TYPE_SUFFIX_SCALE,
                    title = stringResource(R.string.type_suffix_scale_title),
                    summary = stringResource(R.string.type_suffix_scale_summary),
                    enabled = a.glyph && a.typeAnywhere(),
                    onValueChange = { v -> update { it.setTypeSuffixScale(v) } },
                )
            }
            // The label's two margins. Same gate as its size: they only mean
            // anything while the label is the status bar's own child, and the
            // row names each margin by its physical side. Under RTL the pair
            // swaps those sides, which the summary says rather than the row
            // pretending the labels are reading-order starts.
            val marginHint = gateHint(
                a.glyph to R.string.master_title,
                a.typeOutOfRing to R.string.show_mobile_type_title,
            )
            TooltipBox(text = marginHint.orEmpty(), enabled = marginHint != null) {
                IntSlider(
                    value = settings.outTypeMarginLeft,
                    min = Prefs.MIN_OUT_TYPE_MARGIN,
                    max = Prefs.MAX_OUT_TYPE_MARGIN,
                    title = stringResource(R.string.out_type_margin_left_title),
                    summary = stringResource(R.string.out_type_margin_left_summary),
                    enabled = a.glyph && a.typeOutOfRing,
                    onValueChange = { v -> update { it.setOutTypeMarginLeft(v) } },
                )
            }
            TooltipBox(text = marginHint.orEmpty(), enabled = marginHint != null) {
                IntSlider(
                    value = settings.outTypeMarginRight,
                    min = Prefs.MIN_OUT_TYPE_MARGIN,
                    max = Prefs.MAX_OUT_TYPE_MARGIN,
                    title = stringResource(R.string.out_type_margin_right_title),
                    summary = stringResource(R.string.out_type_margin_right_summary),
                    enabled = a.glyph && a.typeOutOfRing,
                    onValueChange = { v -> update { it.setOutTypeMarginRight(v) } },
                )
            }
            // The size of the out-of-ring reading itself. It is authored against
            // neither design space and against no view either: the setting is a
            // plain dp, resolved against the display's density, so it is the same
            // reading whatever the status bar row happens to be doing - MIUI
            // lays that row out 88px tall at rest and 134px with the control
            // centre pulled down, and a share of either would drift with it.
            //
            // Gated on the switches the reading needs, named individually so the
            // hint points at a row the user can act on. The knob itself is
            // {@code a.stackedOut()} - the rule that decides there is a reading to
            // size - rather than a second copy of the same three conditions.
            val outSignalSizeHint = gateHint(
                a.glyph to R.string.master_title,
                a.mobile to R.string.show_mobile_title,
                a.signalOutOfRing to R.string.signal_mode_title,
                a.stackedSignal to R.string.stacked_signal_title,
            )
            TooltipBox(
                text = outSignalSizeHint.orEmpty(),
                enabled = outSignalSizeHint != null,
            ) {
                IntSlider(
                    value = settings.outSignalSize,
                    min = Prefs.MIN_OUT_SIGNAL_SIZE,
                    max = Prefs.MAX_OUT_SIGNAL_SIZE,
                    title = stringResource(R.string.out_signal_size_title),
                    summary = stringResource(R.string.out_signal_size_summary),
                    enabled = a.stackedOut(),
                    onValueChange = { v -> update { it.setOutSignalSize(v) } },
                )
            }
            // Where the reading sits inside the row it was given. Same gate as
            // its size - the nudge only has a reading to move while one is
            // drawn - and the same four conditions, named for the hint.
            TooltipBox(
                text = outSignalSizeHint.orEmpty(),
                enabled = outSignalSizeHint != null,
            ) {
                IntSlider(
                    value = settings.outSignalOffsetX,
                    min = Prefs.MIN_OUT_SIGNAL_OFFSET,
                    max = Prefs.MAX_OUT_SIGNAL_OFFSET,
                    title = stringResource(R.string.out_signal_offset_x_title),
                    summary = stringResource(R.string.out_signal_offset_x_summary),
                    enabled = a.stackedOut(),
                    onValueChange = { v -> update { it.setOutSignalOffsetX(v) } },
                )
            }
            TooltipBox(
                text = outSignalSizeHint.orEmpty(),
                enabled = outSignalSizeHint != null,
            ) {
                IntSlider(
                    value = settings.outSignalOffsetY,
                    min = Prefs.MIN_OUT_SIGNAL_OFFSET,
                    max = Prefs.MAX_OUT_SIGNAL_OFFSET,
                    title = stringResource(R.string.out_signal_offset_y_title),
                    summary = stringResource(R.string.out_signal_offset_y_summary),
                    enabled = a.stackedOut(),
                    onValueChange = { v -> update { it.setOutSignalOffsetY(v) } },
                )
            }
            // The weight still applies in both positions.
            val typeWeightHint = gateHint(
                a.glyph to R.string.master_title,
                a.typeAnywhere() to R.string.show_mobile_type_title,
            )
            TooltipBox(text = typeWeightHint.orEmpty(), enabled = typeWeightHint != null) {
                IntSlider(
                    value = settings.typeWeight,
                    min = Prefs.MIN_TYPE_WEIGHT,
                    max = Prefs.MAX_TYPE_WEIGHT,
                    title = stringResource(R.string.type_weight_title),
                    summary = stringResource(R.string.type_weight_summary),
                    step = WEIGHT_STEP,
                    enabled = a.glyph && a.typeAnywhere(),
                    onValueChange = { v -> update { it.setTypeWeight(v) } },
                )
            }
        }
    }
    item { SectionTitle(stringResource(R.string.group_track)) }
    item {
        Card {
            IntSlider(
                value = settings.trackAlpha,
                min = Prefs.MIN_TRACK_ALPHA,
                max = Prefs.MAX_TRACK_ALPHA,
                title = stringResource(R.string.track_alpha_title),
                summary = stringResource(R.string.track_alpha_summary),
                enabled = a.glyph,
                onValueChange = { v -> update { it.setTrackAlpha(v) } },
            )
        }
    }
}

/**
 * Colors: the role-colour switch, the low-battery threshold and the three
 * per-role colour rows. The threshold stays here rather than under Geometry
 * because it only ever chooses between two colours.
 */
private fun LazyListScope.colorsTab(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
    onEdit: (RoleColor) -> Unit,
    onReset: () -> Unit,
) {
    item {
        Card {
            // Built here for the same reason as the other two tabs: the rows
            // follow the rule rather than restating the settings.
            val a = TrioAppearance.of(settings)
            SwitchPreference(
                checked = a.roleColors,
                onCheckedChange = { value -> update { it.setRoleColors(value) } },
                title = stringResource(R.string.color_role_title),
                summary = stringResource(R.string.color_role_summary),
                insideMargin = SettingsItemMargin,
                enabled = a.glyph,
            )
            // One gate for the whole card: everything under the switch is
            // meaningless while per-role colours are off, so they share the hint.
            val roleGate = gateHint(
                a.glyph to R.string.master_title,
                a.roleColors to R.string.color_role_title,
            )
            TooltipBox(text = roleGate.orEmpty(), enabled = roleGate != null) {
                IntSlider(
                    value = settings.lowThreshold,
                    min = Prefs.MIN_LOW_THRESHOLD,
                    max = Prefs.MAX_LOW_THRESHOLD,
                    title = stringResource(R.string.low_threshold_title),
                    summary = stringResource(R.string.low_threshold_summary),
                    enabled = a.glyph && a.roleColors,
                    onValueChange = { v -> update { it.setLowThreshold(v) } },
                )
            }
            RoleColor.entries.forEach { role ->
                TooltipBox(text = roleGate.orEmpty(), enabled = roleGate != null) {
                    ArrowPreference(
                        title = stringResource(role.labelRes),
                        summary = stringResource(role.colorFieldLabelRes),
                        insideMargin = SettingsItemMargin,
                        startAction = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Swatch(colorFor(settings, role, onDark = true))
                                Swatch(colorFor(settings, role, onDark = false))
                            }
                        },
                        enabled = a.glyph && a.roleColors,
                        onClick = { onEdit(role) },
                    )
                }
            }
            TooltipBox(text = roleGate.orEmpty(), enabled = roleGate != null) {
                ArrowPreference(
                    title = stringResource(R.string.color_reset),
                    summary = stringResource(R.string.color_reset_summary),
                    insideMargin = SettingsItemMargin,
                    enabled = a.glyph && a.roleColors,
                    // Asks first: the reset throws away every custom colour at
                    // once and there is no undo behind it.
                    onClick = onReset,
                )
            }
        }
    }
}

/** About: activation state, the counters, module info, updates and the log switch. */
private fun LazyListScope.aboutTab(
    settings: TrioSettings,
    service: XposedService?,
    updater: UpdateController,
    showMessage: (String) -> Unit,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    item { ActivatedRow(settings = settings, service = service) }
    item { InfoCard(service = service) }
    item { UpdateCard(updater, showMessage) }
    item {
        Card {
            SwitchPreference(
                checked = settings.debugLog,
                onCheckedChange = { value -> update { it.setDebugLog(value) } },
                title = stringResource(R.string.debug_title),
                summary = stringResource(R.string.debug_summary),
                insideMargin = SettingsItemMargin,
            )
        }
    }
}

/**
 * The in-app updater.
 *
 * <p>The card is a short stack of Miuix preference rows, one per action the
 * current state actually offers — check, download, install, open the release
 * page — with the state itself spelled out as the summary of the first row.
 * A row only exists while its verb is possible, so the card never shows an
 * action that would do nothing, and it never says the same words twice the way
 * a heading plus a button of the same name did.
 *
 * <p>The repository row is the one deliberate exception to that rule, and it
 * sits last so it stays out of the way: reading the source is possible in every
 * state, including the ones the card handles worst — no release published yet, or
 * a check that just failed.
 *
 * <p>The state lives in [updater], which the screen remembers, so scrolling this
 * card out of view and back does not restart a download in flight.
 */
@Composable
private fun UpdateCard(updater: UpdateController, showMessage: (String) -> Unit) {
    val state = updater.state

    // Null only for Ready, where the row's own title is already the whole status
    // and a summary would print the same sentence a second time.
    val status: String? = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_summary_idle)
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.UpToDate -> stringResource(R.string.update_uptodate, state.current)
        is UpdateState.NoRelease -> stringResource(R.string.update_no_release)
        is UpdateState.Available -> stringResource(R.string.update_available, state.info.version)
        is UpdateState.Downloading -> stringResource(
            R.string.update_downloading,
            (state.progress * 100f).toInt(),
        )

        is UpdateState.Ready -> null
        // The raw reason is diagnostic text — an HTTP code, a socket error — and
        // would lose its meaning if it were run through a string resource.
        is UpdateState.Failed -> listOfNotNull(
            stringResource(R.string.update_failed),
            state.detail?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    // A download reports itself under the row it belongs to, where the bar spans
    // the same width as the text instead of sitting in a slot of its own.
    val progressBar: (@Composable () -> Unit)? = if (state is UpdateState.Downloading) {
        {
            LinearProgressIndicator(
                // Null means the server never declared a length: the bar then
                // animates instead of claiming a progress it does not have.
                progress = state.progress.takeIf { it > 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        null
    }

    // Both states that are waiting on the network replace the row's arrow with a
    // spinner, so the row still says "working" once the summary word has stopped
    // being the thing the eye lands on. The branches are written out one by one
    // because the arrow is the default and cannot be restored by passing null.
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading

    // Each row is titled with the verb it performs, so a failure offers "retry"
    // and a download in flight says "download", instead of both calling
    // themselves "check for updates" while doing something else.
    val rowTitle = when (state) {
        is UpdateState.Downloading -> stringResource(R.string.update_download)
        is UpdateState.Failed -> stringResource(R.string.update_retry)
        // Not "check for updates": this row no longer offers a check, because a
        // fresh check would drop the download the next row is about to install.
        is UpdateState.Ready -> stringResource(R.string.update_ready)
        else -> stringResource(R.string.update_title)
    }

    Card {
        // The one row that is always there: it reports the state and, whenever
        // nothing is in flight and nothing is waiting to be installed, offers
        // another look at the release feed. It is also the retry action after a
        // failure, because its summary is the failure — asking the user to press a
        // second row called "retry" to find that out would be one row too many.
        if (busy) {
            ArrowPreference(
                title = rowTitle,
                summary = status,
                insideMargin = SettingsItemMargin,
                enabled = false,
                endActions = {
                    Box(
                        modifier = Modifier.padding(end = 8.dp).size(26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(size = 20.dp)
                    }
                },
                bottomAction = progressBar,
            )
        } else if (state is UpdateState.Ready) {
            // Reporting only, and deliberately not tappable: checking again would
            // replace "downloaded" with "up to date" and take the install row down
            // with it, costing the user a second download of a file already on
            // disk. The empty endActions removes the arrow, which would otherwise
            // promise an action this row does not have.
            ArrowPreference(
                title = rowTitle,
                summary = status,
                insideMargin = SettingsItemMargin,
                endActions = {},
            )
        } else {
            ArrowPreference(
                title = rowTitle,
                summary = status,
                insideMargin = SettingsItemMargin,
                onClick = { updater.check() },
            )
        }

        if (state is UpdateState.Available) {
            // What the install would cost: the version being replaced and the
            // download size, so the choice can be made before tapping.
            val notes = state.info.notes.lines().firstOrNull { it.isNotBlank() }
            // The release notes are the only thing the user can weigh before
            // committing to an install, so a short excerpt earns its place.
            val noteExcerpt: (@Composable () -> Unit)? = if (notes == null) {
                null
            } else {
                {
                    Text(
                        text = notes,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            ArrowPreference(
                title = stringResource(R.string.update_download),
                summary = stringResource(
                    R.string.update_available_summary,
                    state.current,
                    formatSize(state.info.apkSize),
                ),
                insideMargin = SettingsItemMargin,
                bottomAction = noteExcerpt,
                onClick = { updater.download(state.info) },
            )
        }

        if (state is UpdateState.Ready) {
            // Resolved before the click, not inside it: a string resource needs a
            // composable context, and the snackbar is raised from a lambda.
            val refusedHint = stringResource(R.string.update_permission_needed)
            val failedHint = stringResource(R.string.update_install_failed)
            ArrowPreference(
                title = stringResource(R.string.update_install),
                summary = stringResource(R.string.update_ready_summary),
                insideMargin = SettingsItemMargin,
                onClick = {
                    when (updater.install(state.file)) {
                        InstallResult.Started -> Unit

                        // Only this case is the user's to fix, and only the system
                        // settings page can hand over the grant. The message says why
                        // the screen changed under the finger.
                        InstallResult.NeedsPermission -> {
                            showMessage(refusedHint)
                            updater.openInstallPermissionScreen()
                        }

                        // Granting a permission the app already holds would lead to
                        // a settings page with nothing to change.
                        InstallResult.NoInstaller -> showMessage(failedHint)
                    }
                },
            )
        }

        // Both states where the user may want to read more than the card can
        // hold get a way out, and only those states need one.
        if (state is UpdateState.Available || state is UpdateState.Failed) {
            ArrowPreference(
                title = stringResource(R.string.update_release_page),
                insideMargin = SettingsItemMargin,
                onClick = {
                    updater.openInBrowser(
                        (state as? UpdateState.Available)?.info?.pageUrl ?: RELEASES_PAGE,
                    )
                },
            )
        }

        // Last, and unlike every row above it, always present: the repository is
        // what the user reads when the card cannot answer — no release published,
        // a check that failed, or a changelog that explains too little — so taking
        // it away exactly when the card is least useful would be backwards.
        ArrowPreference(
            title = stringResource(R.string.update_repo),
            summary = stringResource(R.string.update_repo_summary),
            insideMargin = SettingsItemMargin,
            onClick = { updater.openInBrowser(REPO_HOME) },
        )
    }
}

/**
 * Previews the glyph in seven states, drawn by the very renderer the status bar
 * uses, so the two can never disagree about the geometry.
 *
 * <p>The no-Wi-Fi state is the one worth watching: with no Wi-Fi ink the value
 * moves down into the ring centre, which is where the percentage lives whenever
 * Wi-Fi is off.
 *
 * <p>The last two cells are the odd ones out: neither is the glyph but one of
 * the two things the status bar lays out beside the icon when the reading is
 * moved out of the ring. They get their own cells because neither has a size or
 * a shape that shows up anywhere else on this screen.
 */
@Composable
private fun PreviewCard(settings: TrioSettings) {
    Card {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.preview_title),
                style = MiuixTheme.textStyles.subtitle,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PreviewCell(settings, 88, charging = true, caption = stringResource(R.string.preview_charging))
                PreviewCell(
                    settings, 88,
                    charging = true,
                    quickCharging = true,
                    caption = stringResource(R.string.preview_quick_charge),
                )
                PreviewCell(settings, 64, caption = stringResource(R.string.preview_normal))
                PreviewCell(
                    settings, 79,
                    wifiLevel = NO_WIFI_LEVEL,
                    slotLevels = DUAL_SIM_PREVIEW,
                    mobileType = MOBILE_TYPE_PREVIEW,
                    caption = stringResource(R.string.preview_no_wifi),
                )
                PreviewCell(settings, 24, powerSave = true, low = true, caption = stringResource(R.string.preview_low))
                PreviewCell(settings, 12, low = true, caption = stringResource(R.string.preview_critical))
                PreviewCell(
                    settings, 79,
                    mobileType = MOBILE_TYPE_PREVIEW,
                    outTypeOnly = true,
                    caption = stringResource(R.string.preview_out_type),
                )
                PreviewCell(
                    settings, 79,
                    slotLevels = DUAL_SIM_PREVIEW,
                    outSignalOnly = true,
                    caption = stringResource(R.string.preview_out_signal),
                )
            }
        }
    }
}

@Composable
private fun RowScope.PreviewCell(
    settings: TrioSettings,
    level: Int,
    charging: Boolean = false,
    quickCharging: Boolean = false,
    powerSave: Boolean = false,
    low: Boolean = false,
    wifiLevel: Int = WIFI_LEVEL,
    slotLevels: IntArray? = null,
    mobileType: String = "",
    /** Draws the out-of-ring type label instead of the glyph in this cell. */
    outTypeOnly: Boolean = false,
    /** Draws the out-of-ring signal reading instead of the glyph in this cell. */
    outSignalOnly: Boolean = false,
    caption: String,
) {
    // The status bar sits on the wallpaper, so the preview follows the app's own
    // light/dark mode rather than trying to guess the wallpaper.
    val dark = isDarkTheme()

    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AndroidView(
            modifier = Modifier.size(PREVIEW_SIZE),
            factory = { context -> TrioPreviewView(context) },
            update = { view ->
                view.setSettings(settings)
                view.setOutTypeOnly(outTypeOnly)
                view.setOutSignalOnly(outSignalOnly)
                view.setPreviewBackground(if (dark) 0xFF1C1B1F.toInt() else 0xFFF2F2F7.toInt())
                view.setForeground(if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
                view.setState(level, charging, quickCharging, powerSave, low, wifiLevel,
                    MOBILE_LEVEL, slotLevels, mobileType)
            },
        )
        Text(
            text = caption,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * One label/value pair of the about card: a heavy dark label over a lighter grey
 * value, stacked so long values (the framework string, the scope list) stay free
 * to wrap without a second column squeezing them.
 */
@Composable
private fun InfoRow(label: String, value: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.headline1,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = value,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}

/**
 * The top block of the about page: a tall "activated" card on the left and two
 * short statistic cards stacked on the right.
 *
 * <p>The size is driven by aspectRatio(1f) rather than a fixed height, matching
 * the reference: the side length follows the available width on any device, so
 * the block keeps its proportions instead of looking squat on a tablet.
 */
@Composable
private fun ActivatedRow(settings: TrioSettings, service: XposedService?) {
    val context = LocalContext.current
    val module = remember(context) { readModuleInfo(context) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivatedCard(
            active = service != null,
            version = module.moduleVersion,
            modifier = Modifier.weight(1f).aspectRatio(1f),
        )
        Column(
            modifier = Modifier.weight(1f).aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                label = stringResource(R.string.about_scope_count),
                value = scopeCount(service).toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.about_enabled_count),
                value = enabledCount(settings).toString(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The green activation card: state as a heavy title, module version underneath,
 * and the ring-and-check badge bleeding off the bottom-right corner.
 */
@Composable
private fun ActivatedCard(active: Boolean, version: String, modifier: Modifier = Modifier) {
    val dark = isDarkTheme()
    val content = if (dark) MiuixTheme.colorScheme.onSurface else ActivatedContent

    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.defaultColors(
            color = if (dark) ActivatedContainerDark else ActivatedContainer,
            contentColor = content,
        ),
        // These cards are read-outs, not buttons: they have nowhere to navigate
        // to, so they stay non-clickable. Miuix wires the press feedback on
        // independently of onClick, which gives the MIUI "give" under the finger
        // without pretending there is an action behind it. Tilt is the variant
        // the reference page uses on its equivalent cards.
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        // clipToBounds rather than clip(RoundedCornerShape): the card has already
        // drawn its squircle outline, and clipping to a plain rounded rectangle
        // here would square off the corners the user is looking at.
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text(
                    text = stringResource(
                        if (active) R.string.about_activated else R.string.about_inactive,
                    ),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (active) {
                        stringResource(R.string.about_module_version, version)
                    } else {
                        stringResource(R.string.about_inactive_hint)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // The badge is not aligned to the corner: it is nudged 27x31 further
            // out so a different slice of the ring survives the clip. The vector
            // is the reference's own asset rather than a redrawing of it — the
            // ring-to-viewport ratio is what the eye compares, and it is easy to
            // get wrong by hand.
            Box(
                modifier = Modifier.fillMaxSize().offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_check_circle_outline),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (dark) ActivatedBadgeDark else ActivatedBadge),
                    modifier = Modifier.size(110.dp),
                )
            }
        }
    }
}

/** One of the two short right-hand cards: a quiet label over a large figure. */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxHeight(),
        // Same read-out-not-a-button treatment as [ActivatedCard]; the two cards
        // sit side by side, so they must not disagree about how they respond.
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The reference's four identity rows first, then the two the reference has no
 * equivalent for — the scope list and the Android level are what a user actually
 * needs to report a bug, so they stay on rather than being dropped for symmetry.
 */
@Composable
private fun InfoCard(service: XposedService?) {
    val context = LocalContext.current

    // Every framework getter is a binder call into the daemon: bind them to the
    // service identity so they run when it changes rather than on every
    // recomposition.
    val unknown = stringResource(R.string.info_unknown)
    val module = remember(context) { readModuleInfo(context) }
    val framework = remember(service, unknown) { readFrameworkInfo(service, unknown) }
    val scope = remember(service, unknown) { readScope(service, unknown) }

    // fillMaxWidth is required on both the card and its content column: Miuix's Card
    // wraps to its content, and this card's text is narrower than the sibling cards,
    // so without it the About card renders visibly inset on the right.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            InfoRow(stringResource(R.string.info_system_version), module.systemVersion)
            InfoRow(stringResource(R.string.info_module_version), module.moduleVersion)
            InfoRow(stringResource(R.string.info_framework), framework)
            InfoRow(stringResource(R.string.info_device_model), module.deviceModel)
            InfoRow(stringResource(R.string.info_scope), scope)
            InfoRow(
                label = stringResource(R.string.info_android_version),
                value = module.androidVersion,
                bottomPadding = 12.dp,
            )

            Text(
                text = stringResource(R.string.about_summary),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The confirmation for restarting System UI.
 *
 * <p>[OverlayDialog] rather than a bottom sheet: this is the dialog the colour
 * picker already uses, so the confirmation reads as part of the module instead
 * of borrowing a second popup style. Rendering through the Scaffold's popup host
 * is also why this has to stay inside the Scaffold's content lambda.
 *
 * <p>Dismissal is refused while the kill is in flight — the user may already be
 * looking at a root prompt, and closing the dialog there would leave the outcome
 * with nowhere to be reported.
 */
@Composable
private fun RestartDialog(
    controller: RestartController,
    show: Boolean,
    onDismiss: () -> Unit,
) {
    val state = controller.state
    val running = state is RestartState.Running

    OverlayDialog(
        show = show,
        title = stringResource(R.string.restart_title),
        summary = stringResource(R.string.restart_summary),
        onDismissRequest = { if (!running) onDismiss() },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The raw shell text rides along with the localised headline, the
            // same way a failed update pairs its diagnostic detail.
            if (state is RestartState.Failed) {
                Text(
                    text = listOfNotNull(
                        stringResource(R.string.restart_failed),
                        state.detail?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { controller.restart() },
                    enabled = !running,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(
                            if (running) R.string.restart_running else R.string.restart_confirm,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The confirmation for resetting the three role colours.
 *
 * <p>Same [OverlayDialog] shell as the restart confirmation, so it stays inside
 * the Scaffold's content lambda for the same reason.
 */
@Composable
private fun ResetColorsDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.color_reset),
        summary = stringResource(R.string.color_reset_dialog_summary),
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.color_reset_confirm))
            }
        }
    }
}

@Composable
private fun ColorDialog(
    role: RoleColor,
    settings: TrioSettings,
    onPick: (onDark: Boolean, argb: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var onDark by remember { mutableStateOf(true) }

    OverlayDialog(
        show = true,
        title = stringResource(role.labelRes),
        summary = stringResource(R.string.color_dialog_summary),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackgroundChip(
                    label = stringResource(R.string.color_on_dark),
                    selected = onDark,
                    modifier = Modifier.weight(1f),
                ) { onDark = true }
                BackgroundChip(
                    label = stringResource(R.string.color_on_light),
                    selected = !onDark,
                    modifier = Modifier.weight(1f),
                ) { onDark = false }
            }
            ColorPalette(
                color = Color(colorFor(settings, role, onDark)),
                onColorChanged = { picked -> onPick(onDark, picked.toArgb()) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BackgroundChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
        )
    }
    Button(onClick = onClick, modifier = modifier, colors = colors) {
        Text(text = label, style = MiuixTheme.textStyles.button)
    }
}

@Composable
private fun Swatch(color: Int) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(color)),
    )
}

/**
 * Integer slider over a closed range, showing the current value at the end.
 *
 * @param step increment between selectable values; the range must divide by it.
 */
@Composable
private fun IntSlider(
    value: Int,
    min: Int,
    max: Int,
    title: String,
    summary: String,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    step: Int = 1,
) {
    SliderPreference(
        value = value.toFloat(),
        // Rounded here rather than stored rounded: the slider reports floats,
        // and every preset in this screen is a whole number. Snapping through
        // [step] keeps a wide range (100..900) from producing values no font
        // actually ships.
        onValueChange = { raw ->
            val snapped = Math.round(raw / step) * step
            onValueChange(snapped.coerceIn(min, max))
        },
        title = title,
        summary = summary,
        valueText = value.toString(),
        enabled = enabled,
        valueRange = min.toFloat()..max.toFloat(),
        steps = ((max - min) / step - 1).coerceAtLeast(0),
        insideMargin = SettingsItemMargin,
    )
}

/**
 * The reason a gated row is greyed out, or null when it is not gated at all.
 *
 * <p>Each gate is a dependency paired with the title of the row that owns it.
 * The first dependency that is switched off wins, so a row that needs two of
 * them names the one the user is likeliest to fix first — reading order, top
 * to bottom. Returning null rather than a generic sentence is what lets the
 * caller pass the result straight to `TooltipBox(enabled = ...)`: a row that is
 * live has nothing to explain, and a tooltip that says nothing would still eat
 * the long-press.
 */
@Composable
private fun gateHint(vararg gates: Pair<Boolean, Int>): String? {
    val missing = gates.firstOrNull { !it.first } ?: return null
    return stringResource(R.string.gate_hint, stringResource(missing.second))
}

private val RoleColor.colorFieldLabelRes: Int
    get() = R.string.color_field_summary

private fun colorFor(settings: TrioSettings, role: RoleColor, onDark: Boolean): Int =
    when (role) {
        RoleColor.Critical -> if (onDark) settings.criticalOnDark else settings.criticalOnLight
        RoleColor.Low -> if (onDark) settings.lowOnDark else settings.lowOnLight
        RoleColor.Charging -> if (onDark) settings.chargingOnDark else settings.chargingOnLight
    }

/** Signal levels the preview shows; the middle of each range. */
private const val WIFI_LEVEL = 3
private const val MOBILE_LEVEL = 4

/** Font weights snap in hundreds: 100, 200, ... 900. */
private const val WEIGHT_STEP = 100

/** What the renderer treats as "no Wi-Fi ink": the value drops to the centre. */
private const val NO_WIFI_LEVEL = -1

/**
 * Preview glyph edge. Sized so the eight cells still fit one row inside a 368.dp
 * card: eight of these plus seven 4.dp gutters come to 340.dp, clear of the
 * 344.dp content width, and the cell that is not a glyph has no fixed aspect of
 * its own to preserve.
 */
private val PREVIEW_SIZE = 39.dp

/**
 * Stand-in label for the type preview cells; the real one comes from SystemUI.
 *
 * <p>Deliberately the "5GA" form rather than "5G": the trailing A is what the
 * suffix-scale row changes, so this is the only cell where that setting can be
 * seen at all.
 */
private const val MOBILE_TYPE_PREVIEW = "5GA"

/**
 * Stand-in per-SIM levels for the preview: SIM 1 one step down from SIM 2, so a
 * preview that has quietly collapsed to a single row still looks wrong rather
 * than looking like a plausible single-SIM reading.
 */
private val DUAL_SIM_PREVIEW = intArrayOf(2, 4)

/** Palette for the activation card, sampled off the reference screenshot. */
private val ActivatedContainer = Color(0xFFE4F9E5)
private val ActivatedContent = Color(0xFF101010)
private val ActivatedBadge = Color(0xFF7FD78A)

/** Dark-mode counterparts; the reference only shows the light theme. */
private val ActivatedContainerDark = Color(0xFF1D2A1F)
private val ActivatedBadgeDark = Color(0xFF6FC77C)

/**
 * True when the app is rendering its dark palette, following the system setting
 * too. Miuix exposes the mode rather than a resolved boolean, so the system case
 * has to be resolved here.
 */
@Composable
private fun isDarkTheme(): Boolean = MiuixTheme.colorSchemeMode == ColorSchemeMode.Dark ||
    (MiuixTheme.colorSchemeMode == ColorSchemeMode.System && isSystemInDarkTheme())

/** Everything the about card renders about this installation. */
private data class ModuleInfo(
    val moduleVersion: String,
    val systemVersion: String,
    val androidVersion: String,
    val deviceModel: String,
)

/**
 * Reads the values that never change while the process lives.
 *
 * [Build.VERSION.INCREMENTAL] and [Build.DISPLAY] are both shown when they differ:
 * on MIUI the former carries the ROM build (OS4.0.0.27.XNCCNXM) and the latter the
 * underlying AOSP build id, and either one alone leaves the user guessing.
 */
private fun readModuleInfo(context: Context): ModuleInfo {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = packageInfo?.versionName ?: "?"
    val versionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    val systemVersion = listOf(Build.VERSION.INCREMENTAL, Build.DISPLAY)
        .filter { !it.isNullOrBlank() }
        .distinct()
        .joinToString(" · ")
    return ModuleInfo(
        moduleVersion = "$versionName ($versionCode)",
        systemVersion = systemVersion.ifBlank { "?" },
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = readDeviceName(context),
    )
}

/**
 * The name the user sees in Settings, not the one printed on the box.
 *
 * <p>[Build.MODEL] is the SKU (`23127PN0CC`); the marketing name (`Xiaomi 14`)
 * only exists as [Settings.Global.DEVICE_NAME], and `Build.MARKET_NAME` is not
 * present in this SDK. A device that has never had the setting written falls
 * back to the model rather than showing an empty row.
 */
private fun readDeviceName(context: Context): String {
    val name = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()
    return name?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty()
}

/**
 * How many packages the framework will load this module into; the reference page
 * shows an equivalent count, and it is the fastest way to spot a mis-scoped
 * install.
 */
private fun scopeCount(service: XposedService?): Int {
    if (service == null) return 0
    return runCatching { service.scope.size }.getOrDefault(0)
}

/**
 * How many of the appearance switches are on, as a single at-a-glance figure.
 *
 * <p>Derived from [TrioAppearance] rather than read off [TrioSettings] directly.
 * The switches gate one another, and the count used to ignore both the master
 * switch and those gates, so it could report a healthy total while the glyph
 * drew nothing at all.
 */
private fun enabledCount(settings: TrioSettings): Int {
    val a = TrioAppearance.of(settings)
    if (!a.glyph) return 0
    return listOf(
        a.wifi,
        // The reading counts in either position: in the ring it is the dot row,
        // outside it is the bars the out-of-ring view draws.
        a.mobile,
        // The second row only exists when the mobile level is drawn as dots, so
        // out of ring - where nothing is drawn along the bottom at all - it is
        // not a second thing on screen however the switch is set.
        a.dualSim && a.signalDots(),
        a.value,
        a.drawsBolt(),
        // The network type is a three-way mode now; anything but "off" counts as on.
        a.typeAnywhere(),
    ).count { it }
}

/**
 * Every getter here is a binder call into the framework daemon, so all of them are
 * wrapped: a service that died between binding and this call must degrade to
 * [unknown] rather than take the settings screen down with it.
 */
private fun readFrameworkInfo(service: XposedService?, unknown: String): String {
    if (service == null) return unknown
    return runCatching {
        val name = service.frameworkName
        val version = service.frameworkVersion
        val code = service.frameworkVersionCode
        "$name $version ($code) API ${service.apiVersion}"
    }.getOrDefault(unknown)
}

private fun readScope(service: XposedService?, unknown: String): String {
    if (service == null) return unknown
    val packages = runCatching { service.scope }.getOrNull()
    if (packages.isNullOrEmpty()) return unknown
    return packages.joinToString("\n")
}
