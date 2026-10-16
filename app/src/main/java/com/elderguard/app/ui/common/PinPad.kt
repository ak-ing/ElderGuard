package com.elderguard.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

const val PIN_LENGTH = 6

/** 密码圆点：输入时弹性放大填充，[errorTick] 变化时整行抖动 */
@Composable
fun PinDots(length: Int, errorTick: Int, isError: Boolean, modifier: Modifier = Modifier) {
  Row(modifier.shakeOn(errorTick), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    repeat(PIN_LENGTH) { i ->
      val filled = i < length
      val color by
        animateColorAsState(
          when {
            isError -> MaterialTheme.colorScheme.error
            filled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
          },
          label = "dotColor",
        )
      val scale by
        animateFloatAsState(
          if (filled) 1f else 0.7f,
          spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
          label = "dotScale",
        )
      // 动画值只在绘制阶段读取：弹跳和变色期间不重组整行
      Box(
        Modifier.size(14.dp)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .drawBehind { drawCircle(color) }
      )
    }
  }
}

/** 圆形数字键盘，每按一下震一下 */
@Composable
fun PinPad(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
  val haptics = rememberPinHaptics()
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        row.forEach { key ->
          when (key) {
            "" -> Spacer(Modifier.size(KEY_SIZE))
            "⌫" ->
              PadKey(
                onClick = {
                  haptics.key()
                  onValueChange(value.dropLast(1))
                },
                container = Color.Transparent,
              ) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "删除")
              }
            else ->
              PadKey(
                onClick = {
                  haptics.key()
                  if (value.length < PIN_LENGTH) onValueChange(value + key)
                }
              ) {
                Text(key, style = MaterialTheme.typography.headlineSmall)
              }
          }
        }
      }
    }
    Spacer(Modifier.height(4.dp))
  }
}

private val KEY_SIZE = 72.dp

@Composable
private fun PadKey(
  onClick: () -> Unit,
  container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  content: @Composable () -> Unit,
) {
  Surface(onClick = onClick, shape = CircleShape, color = container, modifier = Modifier.size(KEY_SIZE)) {
    Box(contentAlignment = Alignment.Center) { content() }
  }
}
