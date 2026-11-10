package me.rerere.rikkahub.ui.context

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 省电模式下停用聊天正文的内联文本选择注册。
 *
 * SelectionContainer 会让每个 Markdown 文本节点在滚动时持续上报坐标并排序，
 * 超长回复会因此明显掉帧。操作菜单中的“选择并复制”仍可用于文本选择。
 */
@Composable
internal fun ChatSelectionContainer(content: @Composable () -> Unit) {
    if (LocalPowerSavingMode.current) {
        content()
    } else {
        SelectionContainer(content = content)
    }
}

/** 省电模式下内容尺寸直接更新，避免流式长文本持续启动布局动画。 */
@Composable
internal fun Modifier.chatAnimateContentSize(): Modifier =
    if (LocalPowerSavingMode.current) this else animateContentSize()
