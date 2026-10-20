// Lucian UX/UI Prompt #064 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#064
package vn.anta.booking.ui.auth

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the envelope is: open on the address step, sealed while the code is asked for, flying off once it is sent,
 * back and stamped on the code step.
 */
internal enum class Seal { OPEN, SEALED, FLYING, SENT }

/** Moves the envelope through [Seal] from what the code flow reports: a send started, a code went out, a send failed. */
@Stable
internal class SealState(sent: Boolean) {
    var phase by mutableStateOf(if (sent) Seal.SENT else Seal.OPEN)
        private set
    private var sealedAt = 0L
    private var deliveriesWhenSealed = -1

    /** Counts seals, so a flight planned for an earlier one never lands on a later one. */
    private var seals = 0

    /** The user asked for a code: the letter goes in and the flap closes while the request runs. Call it before asking. */
    fun seal(deliveries: Int) {
        phase = Seal.SEALED
        sealedAt = SystemClock.uptimeMillis()
        deliveriesWhenSealed = deliveries
        seals++
    }

    /** Nothing was sent after all: back to the step the user is on. */
    fun unseal(onCodeStep: Boolean) {
        phase = if (onCodeStep) Seal.SENT else Seal.OPEN
    }

    /**
     * The flow moved between its steps. Back to the address opens the envelope; reaching the code step without having
     * seen the send, as after the screen was recreated mid-request, shows the stamped envelope at once.
     */
    fun stepChanged(onCodeStep: Boolean) {
        if (!onCodeStep) phase = Seal.OPEN else if (phase == Seal.OPEN) phase = Seal.SENT
    }

    /** A code went out: the flap finishes closing, the envelope flies off and comes back stamped. */
    suspend fun deliver(deliveries: Int, reducedMotion: Boolean) {
        if (phase != Seal.SEALED || deliveries == deliveriesWhenSealed) return
        val seal = seals
        if (!reducedMotion) delay((SEAL_MS - (SystemClock.uptimeMillis() - sealedAt)).coerceAtLeast(0))
        if (phase != Seal.SEALED || seals != seal) return
        phase = Seal.FLYING
        if (!reducedMotion) delay(FLY_MS)
        if (phase == Seal.FLYING && seals == seal) phase = Seal.SENT
    }

    /** A request ended. Without a new code it failed: back to the step the user was on, which shows the error. */
    fun settled(deliveries: Int, onCodeStep: Boolean) {
        if (phase == Seal.SEALED && deliveries == deliveriesWhenSealed) unseal(onCodeStep)
    }
}

/**
 * [deliveries] counts codes sent and [requestsFinished] counts requests that ended, whatever their outcome: counters
 * rather than a busy flag, so a request that fails between two frames is still seen.
 */
@Composable
internal fun rememberSealState(onCodeStep: Boolean, deliveries: Int, requestsFinished: Int, reducedMotion: Boolean): SealState {
    val state = remember { SealState(onCodeStep) }
    LaunchedEffect(onCodeStep) { state.stepChanged(onCodeStep) }
    LaunchedEffect(deliveries) { state.deliver(deliveries, reducedMotion) }
    LaunchedEffect(requestsFinished) { state.settled(deliveries, onCodeStep) }
    return state
}

/** The system's "remove animations" setting, Android's counterpart of prefers-reduced-motion. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The card both steps share: the envelope on top, then the address form ([ask]) or, once a code is out, the code
 * form ([done]). The forms trade places with a short slide while the envelope flies.
 */
@Composable
internal fun EnvelopeCard(phase: Seal, reducedMotion: Boolean, modifier: Modifier = Modifier, ask: @Composable ColumnScope.() -> Unit, done: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(30.dp)
    val density = LocalDensity.current
    val rise = with(density) { 20.dp.roundToPx() }
    val drop = with(density) { 30.dp.roundToPx() }
    Column(modifier.shadow(18.dp, shape, clip = false).clip(shape).background(colors.surface).border(1.dp, colors.outlineVariant, shape)) {
        Box(Modifier.fillMaxWidth().height(ART_AREA), contentAlignment = Alignment.TopCenter) {
            EnvelopeArt(phase, reducedMotion, Modifier.padding(top = 40.dp))
        }
        AnimatedContent(targetState = phase == Seal.SENT, transitionSpec = { panes(targetState, reducedMotion, rise, drop) }, label = "pane") { sent ->
            Column(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (sent) done() else ask()
            }
        }
    }
}

private fun AnimatedContentTransitionScope<Boolean>.panes(toCode: Boolean, reducedMotion: Boolean, rise: Int, drop: Int): ContentTransform {
    if (reducedMotion) return (EnterTransition.None togetherWith ExitTransition.None) using SizeTransform(clip = false) { _, _ -> snap() }
    val wait = if (toCode) PANE_DELAY_MS else 0
    val enter = fadeIn(tween(350, delayMillis = wait)) + slideInVertically(tween(550, delayMillis = wait, easing = Ease)) { if (toCode) drop else -rise }
    val exit = fadeOut(tween(350)) + slideOutVertically(tween(550, easing = Ease)) { if (toCode) -rise else drop }
    return (enter togetherWith exit) using SizeTransform(clip = false) { _, _ -> tween(550, easing = Ease) }
}

/** Title and explanation of a step, with the step counter above them. An [announce]d title is read out when it appears. */
@Composable
internal fun EnvelopeHeading(step: String, title: String, body: AnnotatedString, announce: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(step, style = MaterialTheme.typography.labelLarge, color = colors.primary)
    Spacer(Modifier.height(6.dp))
    Text(
        title, Modifier.semantics { if (announce) liveRegion = LiveRegionMode.Polite },
        style = HeadingStyle, color = colors.onSurface, textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(body, style = BodyStyle, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
}

/**
 * A one-line field in a filled box: ringed and haloed in the accent while typing, ringed in the error colour and
 * shaken once when what was typed is refused. [shake] and [selectAll] are one-off requests: the field acts on them,
 * then reports through [onShaken] and [onSelected] so the screen can clear them and they do not repeat.
 */
@Composable
internal fun EnvelopeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions,
    onImeAction: () -> Unit,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = FieldStyle,
    bad: Boolean = false,
    shake: Boolean = false,
    onShaken: () -> Unit = {},
    selectAll: Boolean = false,
    onSelected: () -> Unit = {},
    autoFocus: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // What the screen holds wins: it trims, filters and fills in text (a fetched code) on its own.
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    val nudge = remember { Animatable(0f) }
    val box by animateColorAsState(if (focused) colors.surface else colors.surfaceContainer, motion(reducedMotion) { tween(250) }, label = "box")
    val ring by animateColorAsState(if (bad) colors.error else if (focused) colors.primary else Color.Transparent, motion(reducedMotion) { tween(250) }, label = "ring")
    val halo by animateColorAsState(if (focused && !bad) colors.primary.copy(alpha = HALO_ALPHA) else Color.Transparent, motion(reducedMotion) { tween(250) }, label = "halo")

    LaunchedEffect(shake) {
        if (!shake) return@LaunchedEffect
        onShaken()
        if (reducedMotion) return@LaunchedEffect
        nudge.snapTo(-6f)
        nudge.animateTo(0f, keyframes { durationMillis = 300; 5f at 150 })
    }
    LaunchedEffect(selectAll) {
        if (!selectAll) return@LaunchedEffect
        onSelected()
        focus.requestFocus()
        field = TextFieldValue(value, TextRange(0, value.length))
    }
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }

    val shape = RoundedCornerShape(15.dp)
    BasicTextField(
        value = shown,
        onValueChange = { next ->
            field = next
            if (next.text != value) onValueChange(next.text)
        },
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(nudge.value.dp.roundToPx(), 0) }
            .focusRequester(focus)
            // The label is the placeholder below, read while the field is empty; a description would hide what was typed.
            .semantics { if (bad) error("$label chưa đúng") },
        textStyle = textStyle.copy(color = colors.onSurface),
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        singleLine = true,
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { inner ->
            Box(
                Modifier
                    .drawBehind {
                        val spread = 4.dp.toPx()
                        drawRoundRect(halo, Offset(-spread, -spread), Size(size.width + 2 * spread, size.height + 2 * spread), CornerRadius(15.dp.toPx() + spread))
                    }
                    .clip(shape)
                    .background(box)
                    .border(1.5.dp, ring, shape)
                    .height(52.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = if (textStyle.textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterStart
            ) {
                if (shown.text.isEmpty()) Text(label, style = textStyle.copy(color = colors.onSurfaceVariant, letterSpacing = 0.sp, fontWeight = FontWeight.Normal))
                inner()
            }
        }
    )
}

/**
 * The card's button: ink on the page colour, or a quiet filled one when [ghost]. A [locked] button stays focusable
 * and announced, but dimmed and deaf to taps; a [loading] one shows progress instead of its label.
 */
@Composable
internal fun EnvelopeButton(
    text: String,
    onClick: () -> Unit,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    ghost: Boolean = false,
    loading: Boolean = false,
    locked: Boolean = false,
    icon: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, motion(reducedMotion) { tween(300, easing = Spring) }, label = "press")
    val dim by animateFloatAsState(if (locked) 0.55f else 1f, motion(reducedMotion) { tween(300) }, label = "locked")
    val shape = RoundedCornerShape(16.dp)
    val content = if (ghost) colors.onSurface else colors.background
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = dim
            }
            .focusRing(focused, colors.primary, 16.dp)
            .clip(shape)
            .background(if (ghost) colors.surfaceContainer else colors.onSurface)
            .clickable(interactionSource = interaction, indication = null, enabled = !loading, role = Role.Button) { if (!locked) onClick() }
            .semantics {
                if (locked) disabled()
                // While busy the label gives way to a spinner; the button keeps its name for TalkBack.
                if (loading) {
                    contentDescription = text
                    stateDescription = "Đang xử lý"
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.5.dp)
        } else {
            icon?.invoke()
            Text(text, style = ButtonStyle, color = content)
        }
    }
}

/** "Send a new code", locked while its ring fills: the backend takes one request a minute per address. */
@Composable
internal fun ResendButton(sentAt: Long, onResend: () -> Unit, loading: Boolean, reducedMotion: Boolean) {
    // The clock moves every frame but is read only while drawing the ring; the label changes once a second.
    val clock = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var left by remember { mutableIntStateOf(resendSecondsLeft(sentAt, clock.longValue)) }
    LaunchedEffect(sentAt) {
        do {
            val now = withFrameMillis { SystemClock.elapsedRealtime() }
            clock.longValue = now
            left = resendSecondsLeft(sentAt, now)
        } while (left > 0)
    }
    val track = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.primary
    EnvelopeButton(
        if (left > 0) "Gửi lại mã · ${left}s" else "Gửi lại mã", onResend, reducedMotion,
        ghost = true, loading = loading, locked = left > 0,
        icon = { Canvas(Modifier.size(20.dp)) { drawRing(resendProgress(sentAt, clock.longValue), track, fill) } }
    )
}

/** A quiet underlined action under the buttons. */
@Composable
internal fun EnvelopeLink(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .focusRing(focused, colors.primary, 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = LinkStyle, color = colors.onSurfaceVariant)
    }
}

// ---- The envelope ----

@Composable
private fun EnvelopeArt(phase: Seal, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val sealed = phase != Seal.OPEN
    val flying = phase == Seal.FLYING
    val sent = phase == Seal.SENT
    // Off to the top right, shrinking and fading as it goes; back on a spring once the code is out.
    val away by animateFloatAsState(if (flying) 1f else 0f, motion(reducedMotion) { tween(700, easing = if (sent) Spring else FlyAway) }, label = "away")
    val shown by animateFloatAsState(if (flying) 0f else 1f, motion(reducedMotion) { tween(if (sent) 400 else 500) }, label = "shown")
    val letterDrop by animateFloatAsState(if (sealed) LETTER_DROP else 0f, motion(reducedMotion) { tween(600, easing = Ease) }, label = "letter")
    // The flap closes only once the letter is nearly in.
    val flapAngle by animateFloatAsState(if (sealed) 0f else 180f, motion(reducedMotion) { tween(500, delayMillis = if (sealed) 450 else 0, easing = Ease) }, label = "flap")
    val stamp by animateFloatAsState(if (sent) 1f else 0f, motion(reducedMotion) { tween(500, delayMillis = 300, easing = Spring) }, label = "stamp")

    Box(
        modifier.size(180.dp, 150.dp).graphicsLayer {
            translationX = 170.dp.toPx() * away
            translationY = -160.dp.toPx() * away
            rotationZ = 18f * away
            scaleX = 1f - 0.6f * away
            scaleY = 1f - 0.6f * away
            alpha = shown
        }
    ) {
        // The envelope sits at the bottom; its pocket reaches above it so the letter can stick out.
        Box(Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter, unbounded = true).size(ENVELOPE_WIDTH, POCKET_HEIGHT)) {
            Pocket { letterDrop }
            Canvas(Modifier.align(Alignment.BottomCenter).size(ENVELOPE_WIDTH, ENVELOPE_HEIGHT)) { drawFront() }
            Canvas(
                Modifier.offset(y = HEADROOM).size(ENVELOPE_WIDTH, 64.dp).graphicsLayer {
                    rotationX = flapAngle
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    cameraDistance = PERSPECTIVE_DP / POINTS_PER_INCH * density
                }
            ) { drawFlap() }
            Stamp(Modifier.offset(x = ENVELOPE_WIDTH - 34.dp, y = HEADROOM - 10.dp).graphicsLayer {
                scaleX = stamp
                scaleY = stamp
            })
        }
    }
}

/** The back of the envelope and the letter, cut off where the envelope ends. */
@Composable
private fun Pocket(letterDrop: () -> Float) {
    val paper = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(Modifier.fillMaxSize()) {
        val corner = CornerRadius(12.dp.toPx())
        val pocket = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius.Zero, CornerRadius.Zero, corner, corner)) }
        clipPath(pocket) {
            drawRoundRect(EnvelopeColors.Back, Offset(0f, HEADROOM.toPx()), Size(size.width, ENVELOPE_HEIGHT.toPx()), corner)
            val left = 16.dp.toPx()
            val top = (LETTER_TOP + letterDrop()).dp.toPx()
            val width = size.width - 2 * left
            val radius = 8.dp.toPx()
            paper.color = android.graphics.Color.WHITE
            paper.setShadowLayer(7.dp.toPx(), 0f, 4.dp.toPx(), EnvelopeColors.LetterShadow.toArgb())
            drawIntoCanvas { it.nativeCanvas.drawRoundRect(left, top, left + width, top + 118.dp.toPx(), radius, radius, paper) }
            val pad = 14.dp.toPx()
            val line = 7.dp.toPx()
            listOf(0.46f to EnvelopeColors.Heading, 1f to EnvelopeColors.Line, 0.7f to EnvelopeColors.Line).forEachIndexed { row, (share, color) ->
                drawRoundRect(color, Offset(left + pad, top + pad + row * 15.dp.toPx()), Size((width - 2 * pad) * share, line), CornerRadius(line / 2))
            }
        }
    }
}

/** The front of the envelope: a pocket with a V cut into its top edge. */
private fun DrawScope.drawFront() {
    val w = size.width
    val h = size.height
    val r = 12.dp.toPx()
    val front = Path().apply {
        moveTo(0f, 0f)
        lineTo(w / 2, h * 0.58f)
        lineTo(w, 0f)
        lineTo(w, h - r)
        arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
        lineTo(r, h)
        arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
        close()
    }
    // A gradient running 160° clockwise from "up", as CSS draws linear-gradient(160deg, …).
    val angle = Math.toRadians(160.0)
    val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
    val half = (abs(w * direction.x) + abs(h * direction.y)) / 2
    val middle = Offset(w / 2, h / 2)
    drawPath(front, Brush.linearGradient(listOf(EnvelopeColors.FrontLight, EnvelopeColors.FrontDeep), middle - direction * half, middle + direction * half))
}

/** The flap: a triangle hinged on the envelope's top edge, rounded where it meets the corners. */
private fun DrawScope.drawFlap() {
    val corner = CornerRadius(12.dp.toPx())
    val edge = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), corner, corner, CornerRadius.Zero, CornerRadius.Zero)) }
    val flap = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width / 2, size.height)
        close()
    }
    clipPath(edge) { drawPath(flap, EnvelopeColors.Flap) }
}

/** The tick stamped on the envelope once the code is out, ringed in the card colour. */
@Composable
private fun Stamp(modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.surface
    val fill = MaterialTheme.colorScheme.secondary
    val tick = MaterialTheme.colorScheme.onSecondary
    Canvas(modifier.size(40.dp)) {
        drawCircle(ring, radius = size.minDimension / 2 + 4.dp.toPx())
        drawCircle(fill)
        // The tick is drawn on a 20-unit grid, 20dp across and centred.
        val unit = 1.dp.toPx()
        val corner = Offset((size.width - 20 * unit) / 2, (size.height - 20 * unit) / 2)
        val path = Path().apply {
            moveTo(corner.x + 5 * unit, corner.y + 10.5f * unit)
            lineTo(corner.x + 8.5f * unit, corner.y + 14 * unit)
            lineTo(corner.x + 15 * unit, corner.y + 7 * unit)
        }
        drawPath(path, tick, style = Stroke(3 * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A circle that fills clockwise from the top as [progress] goes from 0 to 1. */
private fun DrawScope.drawRing(progress: Float, track: Color, fill: Color) {
    val unit = size.minDimension / 20f
    val stroke = 2.5f * unit
    val radius = 8f * unit
    drawCircle(track, radius, style = Stroke(stroke))
    if (progress > 0f) {
        drawArc(fill, -90f, 360f * progress, useCenter = false, topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** A 2dp ring drawn 3dp outside the element while it has keyboard focus. */
private fun Modifier.focusRing(visible: Boolean, color: Color, radius: Dp) = drawBehind {
    if (!visible) return@drawBehind
    val stroke = 2.dp.toPx()
    val inset = 3.dp.toPx() + stroke / 2
    drawRoundRect(color, Offset(-inset, -inset), Size(size.width + 2 * inset, size.height + 2 * inset), CornerRadius(radius.toPx() + inset), style = Stroke(stroke))
}

private fun <T> motion(reducedMotion: Boolean, spec: () -> AnimationSpec<T>): AnimationSpec<T> = if (reducedMotion) snap() else spec()

/**
 * The envelope keeps the same colours in both themes, as the paper does. They are the app's teal, lightened for the
 * back and deepened for the front, where the original element drew them in its own violet.
 */
private object EnvelopeColors {
    val Back = Color(0xFFB5E9EC)
    val FrontLight = Color(0xFF66D0D8)
    val FrontDeep = Color(0xFF0A93A3)
    val Flap = Color(0xFF3DB8C3)
    val Heading = Color(0xFF0A93A3)
    val Line = Color(0xFFDCEFF1)
    val LetterShadow = Color(0x330F2533)
}

private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val Spring = CubicBezierEasing(0.34f, 1.45f, 0.64f, 1f)
private val FlyAway = CubicBezierEasing(0.5f, -0.3f, 0.6f, 1f)

private val HeadingStyle @Composable get() = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.045).em)
private val BodyStyle @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 1.55.em)
private val ButtonStyle @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
private val LinkStyle @Composable get() = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
internal val FieldStyle @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

private val ART_AREA = 208.dp
private val ENVELOPE_WIDTH = 160.dp
private val ENVELOPE_HEIGHT = 104.dp
private val HEADROOM = 90.dp
private val POCKET_HEIGHT = 194.dp
/** Where the letter's top edge starts, from the top of the pocket, before it drops in by [LETTER_DROP]. */
private const val LETTER_TOP = 46f
private const val LETTER_DROP = 70f
private const val PERSPECTIVE_DP = 400f
private const val POINTS_PER_INCH = 72f
private const val HALO_ALPHA = 0.14f
private const val PANE_DELAY_MS = 150
private const val SEAL_MS = 950L
private const val FLY_MS = 700L
