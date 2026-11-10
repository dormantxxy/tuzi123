package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import me.rerere.rikkahub.ui.context.LocalPowerSavingMode
import kotlin.math.roundToInt

@Composable
fun rememberAvatarShape(loading: Boolean): Shape {
    // 非加载态或省电模式直接返回静态圆形，不创建无限动画。
    if (!loading || LocalPowerSavingMode.current) return CircleShape
    val infiniteTransition = rememberInfiniteTransition()
    val rotateAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = LinearEasing
            ),
        )
    )
    return MaterialShapes.Cookie6Sided.toShape(rotateAngle.value.roundToInt())
}
