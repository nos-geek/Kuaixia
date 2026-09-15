package com.kuaixia.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kuaixia.app.core.model.UiText

/** 在 Composable 中解析 [UiText] 为当前语言文本。 */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is UiText.Dynamic -> value
}
