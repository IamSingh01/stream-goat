package io.github.sherazi5110.cloudstream.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Liquid glass, after Apple's: glass is the layer of controls that floats over the content (bars, sheets, menus,
 * dialogs), never the content itself. It blurs what's behind it, carries a dark tint so white text always reads,
 * has a thin bright rim (brighter at the top, like light on glass) and a soft highlight, and floats on a shadow.
 * Other themes get their usual solid panels from the same calls.
 */
object Glass {
    /** The page behind, for in-window blur (set by AppRoot around the page). */
    val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

    /** Real blur needs Android 12 (RenderEffect); older devices get a darker tint instead. */
    val canBlur: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Glass tint over the blur: deep ink, strong enough for white text over any poster. */
    val tint: Color get() = Color(0xFF120E24).copy(alpha = if (canBlur) 0.58f else 0.90f)
    /** For surfaces that can't blur what's behind them (another window, the video): nearly opaque. */
    val solid: Color get() = Color(0xFF17132B).copy(alpha = 0.95f)
}

/** The rim and highlight on glass: a gradient edge and a soft light across the top. */
private fun Modifier.glassRim(shape: Shape): Modifier = this
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))), shape)
    .drawWithContent {
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.White.copy(alpha = 0.02f), 1f to Color.Transparent))
        drawContent()
    }

/**
 * A glass panel (sheet, menu, dialog, bar). In the glass theme: blur of the page behind (when [blur] and the page
 * is a haze source), dark tint, rim, highlight and shadow. In other themes: the theme's solid surface.
 */
fun Modifier.glassPanel(shape: Shape, blur: Boolean = true, elevation: Int = 18): Modifier = composed {
    if (!Palette.isGlass) return@composed this.background(Palette.panel, shape)
    val haze = Glass.LocalHaze.current
    val base = this.shadow(elevation.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
    val filled = if (blur && haze != null && Glass.canBlur)
        base.hazeEffect(haze, HazeStyle(backgroundColor = Palette.background, tint = HazeTint(Glass.tint), blurRadius = 28.dp, noiseFactor = 0.06f))
    else base.background(if (blur) Glass.tint.copy(alpha = 0.92f) else Glass.solid)
    filled.glassRim(shape)
}

/**
 * Inside a Dialog: in the glass theme, blurs everything behind the dialog window (Android 12+) and dims it a little,
 * so the dialog reads as glass over the app rather than a white or see-through box.
 */
@Composable
fun DialogGlass() {
    if (!Palette.isGlass) return
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 48 }
        }
        window.setDimAmount(0.35f)
    }
}
