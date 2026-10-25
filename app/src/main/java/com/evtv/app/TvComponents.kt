package com.evtv.app

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

object C {
    val bg = Color(0xFF0E1014)
    val panel = Color(0xFF171A21)
    val selected = Color(0xFF232A38)
    val focus = Color(0xFF2B6BFF)
    val accent = Color(0xFF4C8DFF)
    val text = Color(0xFFF2F4F8)
    val sub = Color(0xFF9AA3B2)
    val star = Color(0xFFFFC107)
    val error = Color(0xFFFF6B6B)
}

/**
 * Kumandayla tıklanabilir yapar.
 * Orta tuşa kısa basış = onClick, basılı tutma veya Menü tuşu = onLongClick.
 */
fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    this
        .onKeyEvent { e ->
            val k = e.nativeKeyEvent
            val isSelect = k.keyCode == AndroidKey.KEYCODE_DPAD_CENTER ||
                k.keyCode == AndroidKey.KEYCODE_ENTER ||
                k.keyCode == AndroidKey.KEYCODE_NUMPAD_ENTER
            when {
                isSelect && e.type == KeyEventType.KeyDown -> {
                    if (k.repeatCount == 0) {
                        pressed = true
                        longFired = false
                    } else if (k.repeatCount >= 3 && !longFired && longClick != null) {
                        longFired = true
                        longClick?.invoke()
                    }
                    true
                }
                isSelect && e.type == KeyEventType.KeyUp -> {
                    if (pressed && !longFired) click()
                    pressed = false
                    longFired = false
                    true
                }
                k.keyCode == AndroidKey.KEYCODE_MENU && e.type == KeyEventType.KeyUp && longClick != null -> {
                    longClick?.invoke()
                    true
                }
                else -> false
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { click() }, onLongPress = { longClick?.invoke() })
        }
        .focusable()
}

/** Odaklanınca mavi parlayan liste satırı. */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fill: Boolean = true,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        focused -> C.focus
        selected -> C.selected
        else -> Color.Transparent
    }
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .padding(vertical = 2.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onClick, onLongClick)
            .clip(shape)
            .background(bg)
            .then(if (selected && !focused) Modifier.border(1.dp, C.accent.copy(alpha = 0.5f), shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content(focused)
    }
}
