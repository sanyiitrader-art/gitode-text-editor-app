package com.gitode.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- basics

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = G.c.text,
    size: TextUnit = 15.sp,
    weight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text, modifier = modifier, color = color, fontSize = size, fontWeight = weight,
        maxLines = maxLines, overflow = overflow, fontFamily = fontFamily, textAlign = textAlign,
        lineHeight = lineHeight,
    )
}

/** Full-screen base: themed background plus system-bar insets. */
@Composable
fun Modifier.screenBase(): Modifier =
    this.fillMaxSize().background(G.c.bg).statusBarsPadding().navigationBarsPadding()

/** Clickable without a ripple (blank-area taps). */
fun Modifier.noRippleClick(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

fun vibrate(context: Context, ms: Long) {
    try {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {
        // No vibrator or vibration not allowed: the visual feedback is enough.
    }
}

/**
 * Soft accent glow behind a component, drawn as stacked translucent rounded shapes
 * (larger and fainter towards the outside), so it needs no blur or special graphics API.
 */
fun Modifier.glow(color: Color, radius: Dp = 18.dp, corner: Dp = 14.dp, alpha: Float = 0.55f): Modifier =
    this.drawBehind {
        val steps = 8
        val reach = radius.toPx()
        val base = corner.toPx()
        val layerAlpha = (alpha / steps) * 0.9f
        for (i in steps downTo 1) {
            val grow = reach * i / steps
            drawRoundRect(
                color = color.copy(alpha = layerAlpha),
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + grow * 2f, size.height + grow * 2f),
                cornerRadius = CornerRadius(base + grow),
            )
        }
    }

@Composable
fun IconBtn(
    ic: Ic,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = G.c.text,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ico(ic, if (enabled) tint else tint.copy(alpha = 0.35f), 24.dp)
    }
}

@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Ic? = null,
) {
    val c = G.c
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .then(if (enabled) Modifier.glow(c.accent, 16.dp, 14.dp, 0.5f) else Modifier)
            .clip(shape)
            .background(if (enabled) c.accent else c.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Ico(icon, if (enabled) c.onAccent else c.dim, 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Txt(text, color = if (enabled) c.onAccent else c.dim, weight = FontWeight.SemiBold)
    }
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = G.c.dim,
    enabled: Boolean = true,
    bold: Boolean = false,
) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text, color = if (enabled) color else color.copy(alpha = 0.4f),
            weight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun DialogShell(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = G.c
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth()
                .clip(RoundedCornerShape(22.dp)).background(c.card)
                .border(1.dp, c.outline, RoundedCornerShape(22.dp))
                .padding(24.dp),
            content = content,
        )
    }
}

class DlgBtn(
    val text: String,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

/** Cancel sits bottom-left; the other actions sit bottom-right. */
@Composable
fun DialogButtons(left: DlgBtn, rights: List<DlgBtn>) {
    val c = G.c
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction(left.text, left.onClick, color = c.dim, enabled = left.enabled)
        Spacer(Modifier.weight(1f))
        for (b in rights) {
            TextAction(
                b.text, b.onClick,
                color = when {
                    b.destructive -> c.error
                    b.primary -> c.accent
                    else -> c.dim
                },
                enabled = b.enabled,
                bold = b.primary || b.destructive,
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    actionText: String,
    onCancel: () -> Unit,
    onAction: () -> Unit,
    message: String? = null,
    destructive: Boolean = false,
    cancelText: String = "Cancel",
) {
    DialogShell(onCancel) {
        Txt(title, size = 19.sp, weight = FontWeight.SemiBold)
        if (message != null) {
            Txt(message, Modifier.padding(top = 10.dp), color = G.c.dim, lineHeight = 21.sp)
        }
        DialogButtons(
            DlgBtn(cancelText, onCancel),
            listOf(DlgBtn(actionText, onAction, primary = !destructive, destructive = destructive)),
        )
    }
}

/** Cancel (left), middle action just left of the right action, right action. */
@Composable
fun ThreeButtonDialog(
    title: String,
    message: String,
    onCancel: () -> Unit,
    middleText: String,
    onMiddle: () -> Unit,
    rightText: String,
    onRight: () -> Unit,
) {
    DialogShell(onCancel) {
        Txt(title, size = 19.sp, weight = FontWeight.SemiBold)
        Txt(message, Modifier.padding(top = 10.dp), color = G.c.dim, lineHeight = 21.sp)
        DialogButtons(
            DlgBtn("Cancel", onCancel),
            listOf(DlgBtn(middleText, onMiddle), DlgBtn(rightText, onRight, primary = true)),
        )
    }
}

/** A dialog with a custom body and Cancel (left) / action (right) buttons. */
@Composable
fun FormDialog(
    title: String,
    actionText: String,
    onCancel: () -> Unit,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    DialogShell(onCancel) {
        Txt(title, Modifier.padding(bottom = 16.dp), size = 19.sp, weight = FontWeight.SemiBold)
        content()
        DialogButtons(DlgBtn("Cancel", onCancel), listOf(DlgBtn(actionText, onAction, primary = true, enabled = actionEnabled)))
    }
}

// ---------------------------------------------------------------- input

/**
 * Text input with the app's validation feedback: red border, explanation above the field,
 * a 0.5 s shake and a 0.5 s vibration whenever [errorTrigger] changes to a new positive value.
 */
@Composable
fun GitodeInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    error: String? = null,
    errorTrigger: Int = 0,
    singleLine: Boolean = true,
    autoFocus: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
    fontFamily: FontFamily? = null,
    minHeight: Dp = 52.dp,
) {
    val c = G.c
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shake = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(errorTrigger) {
        if (errorTrigger > 0) {
            vibrate(context, 500)
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = 500
                    14f at 60
                    -14f at 150
                    10f at 250
                    -10f at 350
                    5f at 430
                    0f at 500
                },
            )
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(80)
            focus.requestFocus()
            keyboard?.show()
        }
    }

    val hasError = error != null
    val borderColor = when {
        hasError -> c.error
        focused -> c.accent
        else -> c.outline
    }
    val shape = RoundedCornerShape(14.dp)

    Column(modifier) {
        if (error != null) {
            Txt(error, Modifier.padding(bottom = 6.dp), color = c.error, size = 12.sp, lineHeight = 16.sp)
        }
        if (label != null) {
            Txt(label, Modifier.padding(bottom = 6.dp), color = c.dim, size = 13.sp)
        }
        Box(
            Modifier
                .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                .fillMaxWidth().heightIn(min = minHeight)
                .clip(shape).background(c.surface).border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.text.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, color = c.dim)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
                singleLine = singleLine,
                textStyle = TextStyle(color = c.text, fontSize = 16.sp, fontFamily = fontFamily),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onDone?.invoke() },
                    onGo = { onDone?.invoke() },
                    onSearch = { onDone?.invoke() },
                ),
            )
        }
    }
}

// ---------------------------------------------------------------- screens and settings pieces

@Composable
fun ScreenBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    close: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(if (close) Ic.Close else Ic.Back, onBack)
        Spacer(Modifier.width(8.dp))
        Txt(
            title, Modifier.weight(1f), size = 20.sp, weight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** A spacious settings row: icon tile, title, optional subtitle and trailing control. */
@Composable
fun SettingCard(
    icon: Ic,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = G.c
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.outline, shape)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Ico(icon, c.accent, 24.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, size = 16.sp, weight = FontWeight.SemiBold)
            if (subtitle != null) Txt(subtitle, Modifier.padding(top = 3.dp), color = c.dim, size = 13.sp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun GitodeSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = G.c
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent, checkedTrackColor = c.accent, checkedBorderColor = c.accent,
            uncheckedThumbColor = c.dim, uncheckedTrackColor = c.surface, uncheckedBorderColor = c.outline,
        ),
    )
}

@Composable
fun GitodeSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val c = G.c
    Slider(
        value = value, onValueChange = onChange, modifier = modifier, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.outline,
            activeTickColor = c.onAccent, inactiveTickColor = c.dim,
        ),
    )
}

@Composable
fun GitodeCheckbox(checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = G.c
    Box(modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val r = CornerRadius(6.dp.toPx())
            if (checked) {
                drawRoundRect(c.accent, Offset.Zero, Size(size.width, size.height), r)
                val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val p = androidx.compose.ui.graphics.Path()
                p.moveTo(size.width * 0.24f, size.height * 0.52f)
                p.lineTo(size.width * 0.43f, size.height * 0.71f)
                p.lineTo(size.width * 0.77f, size.height * 0.31f)
                drawPath(p, c.onAccent, style = stroke)
            } else {
                drawRoundRect(c.dim, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), r, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}