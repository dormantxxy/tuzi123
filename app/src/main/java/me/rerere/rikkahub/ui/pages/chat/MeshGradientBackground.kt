package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import me.rerere.rikkahub.ui.hooks.rememberAppLifecycleState
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TWO_PI = 2f * PI.toFloat()

/**
 * Gemini 风格动态渐变背景。
 *
 * 原理:
 *  1. 底层一个线性渐变(顶部偏蓝、底部偏白)。
 *  2. 上面叠几个 radialGradient 光斑(中心有色 → 边缘透明),天生就是柔的。
 *  3. 每个光斑用一个独立周期的动画,沿正弦/余弦轨迹缓慢漂移。
 *
 * 动画只在界面处于前台(RESUMED)时推进,切到后台自动暂停,避免后台空转发热。
 * 不依赖 Modifier.blur,全 API 级别可用。
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        arrayOf(
            0.0f to Color(0xFF1B2A45),
            0.22f to Color(0xFF15223A),
            0.45f to Color(0xFF0D1626),
            0.65f to Color(0xFF0A0F18),
            1.0f to Color(0xFF080B12),
        )
    } else {
        arrayOf(
            0.0f to Color(0xFFAFD0F2),
            0.22f to Color(0xFFCBE0F6),
            0.45f to Color(0xFFF1F7FD),
            0.65f to Color(0xFFFFFFFF),
            1.0f to Color(0xFFFFFFFF),
        )
    }

    val blobBlue = if (dark) Color(0xFF3E6FB0) else Color(0xFF9EC5F0)
    val blobTeal = if (dark) Color(0xFF2E7D74) else Color(0xFFA8E6E0)
    val blobLightBlue = if (dark) Color(0xFF4A6E96) else Color(0xFFB6D7F2)
    val blobWarm = if (dark) Color(0xFF7C5F9E) else Color(0xFFFFC8D2)
    val alphaBlue = if (dark) 0.56f else 0.72f
    val alphaTeal = if (dark) 0.44f else 0.56f
    val alphaLightBlue = if (dark) 0.48f else 0.62f
    val alphaWarm = if (dark) 0.32f else 0.42f

    // 一个帧时钟统一推进四个相位。相位仍按原来的周期计算，画面轨迹不变，
    // 但每帧只写一次 Compose State，不再由四个 Animatable 分别调度。
    val elapsedNanos = remember { mutableLongStateOf(0L) }
    val lifecycleState by rememberAppLifecycleState()
    val running = animate && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var previousFrameNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanos ->
                elapsedNanos.longValue += (frameNanos - previousFrameNanos).coerceAtLeast(0L)
                previousFrameNanos = frameNanos
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = maxOf(w, h)
            val elapsedMillis = elapsedNanos.longValue / 1_000_000L
            val p1 = animationPhase(elapsedMillis, durationMillis = 110_000L, turns = 20f)
            val p2 = animationPhase(elapsedMillis, durationMillis = 7_000L, turns = 1f)
            val p3 = animationPhase(elapsedMillis, durationMillis = 85_000L, turns = 10f)
            val p4 = animationPhase(elapsedMillis, durationMillis = 62_000L, turns = 10f)

            drawBlob(
                center = Offset(
                    w * 0.48f + sin(p1) * w * 0.38f,
                    h * 0.08f + cos(p1 * 1.15f) * h * 0.18f,
                ),
                radius = r * 0.36f,
                color = blobBlue,
                centerAlpha = alphaBlue,
            )
            drawBlob(
                center = Offset(
                    w * 0.18f + sin(p2 + PI.toFloat() * 0.55f) * w * 0.30f,
                    h * 0.24f + cos(p2) * h * 0.20f,
                ),
                radius = r * 0.28f,
                color = blobTeal,
                centerAlpha = alphaTeal,
            )
            drawBlob(
                center = Offset(
                    w * 0.82f + sin(p3 + PI.toFloat() * 0.9f) * w * -0.34f,
                    h * 0.12f + cos(p3 * 0.9f) * h * 0.18f,
                ),
                radius = r * 0.30f,
                color = blobLightBlue,
                centerAlpha = alphaLightBlue,
            )
            drawBlob(
                center = Offset(
                    w * 0.58f + sin(p4 + PI.toFloat() * 1.25f) * w * 0.28f,
                    h * 0.34f + cos(p4 * 1.1f) * h * 0.16f,
                ),
                radius = r * 0.26f,
                color = blobWarm,
                centerAlpha = alphaWarm,
            )
        }

        content()
    }
}

private fun animationPhase(elapsedMillis: Long, durationMillis: Long, turns: Float): Float =
    (elapsedMillis % durationMillis).toFloat() / durationMillis.toFloat() * TWO_PI * turns

/** 画一个柔光斑:中心有色,向外渐隐到透明。 */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    color: Color,
    centerAlpha: Float = 0.75f,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = centerAlpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
