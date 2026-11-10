package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.context.LocalPowerSavingMode

/** 普通模式保留原动画；省电模式显示静态进度弧，不持续申请绘制帧。 */
@Composable
fun PowerAwareLoadingIndicator(
    modifier: Modifier = Modifier,
    powerSavingMode: Boolean = LocalPowerSavingMode.current,
) {
    if (powerSavingMode) {
        CircularProgressIndicator(
            progress = { 0.75f },
            modifier = modifier,
            strokeWidth = 3.dp,
        )
    } else {
        ContainedLoadingIndicator(modifier = modifier)
    }
}
