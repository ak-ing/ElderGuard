package com.elderguard.app.ui.common

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.elderguard.app.system.PermissionTier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NotificationAdd
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.animation.Crossfade
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elderguard.app.container
import com.elderguard.app.core.EventType
import com.elderguard.app.system.PermissionKind
import com.elderguard.app.system.PermissionState
import com.elderguard.app.system.SettingsNavigator
import com.elderguard.app.theme.AppTheme
import com.elderguard.app.theme.popSwap

/** 内容限宽居中：手机竖屏铺满，平板/车机横屏不会拉得过宽 */
@Composable
fun CenteredContent(modifier: Modifier = Modifier, maxWidth: Dp = 640.dp, content: @Composable ColumnScope.() -> Unit) {
  Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
  }
}

/** 图标放在色调圆底里 */
@Composable
fun TonalIcon(
  icon: ImageVector,
  modifier: Modifier = Modifier,
  container: Color = MaterialTheme.colorScheme.secondaryContainer,
  content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
  size: Dp = 40.dp,
  shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
  Surface(modifier.size(size), shape = shape, color = container, contentColor = content) {
    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.55f)) }
  }
}

enum class BadgeTone { NEUTRAL, GOOD, WARN, BAD }

@Composable
fun StatusBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
  val ext = AppTheme.extendedColors
  val scheme = MaterialTheme.colorScheme
  val (bg, fg) =
    when (tone) {
      BadgeTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
      BadgeTone.GOOD -> scheme.primaryContainer to scheme.onPrimaryContainer
      BadgeTone.WARN -> ext.warningContainer to ext.onWarningContainer
      BadgeTone.BAD -> scheme.errorContainer to scheme.onErrorContainer
    }
  Surface(modifier, color = bg, contentColor = fg, shape = CircleShape) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
  }
}

/** 分组标题 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, supporting: String? = null) {
  Column(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    supporting?.let {
      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** 分组容器：浅色调大圆角，内部放 ListItem */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(vertical = 8.dp), content = content)
  }
}

/** ListItem 在 GroupCard 里使用的透明底色 */
@Composable
fun groupListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
  val context = LocalContext.current
  val px = with(LocalDensity.current) { size.roundToPx() }
  // 已解码过的直接显示（滚回来、再次进入不闪白）；没有的后台解码，加载完成后淡入，不阻塞列表首帧
  val appInfo = context.container.appInfo
  val cached = remember(pkg, px) { appInfo.cachedIcon(pkg, px)?.asImageBitmap() }
  val bitmap by
    produceState<ImageBitmap?>(cached, pkg, px) {
      value = cached ?: withContext(Dispatchers.IO) { appInfo.icon(pkg, px)?.asImageBitmap() }
    }
  Crossfade(bitmap, modifier = Modifier.size(size), label = "appIcon") { image ->
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(size))
    else Spacer(Modifier.size(size))
  }
}

val PermissionKind.icon: ImageVector
  get() =
    when (this) {
      PermissionKind.ACCESSIBILITY -> Icons.Rounded.Accessibility
      PermissionKind.NOTIFICATION_LISTENER -> Icons.Rounded.NotificationsActive
      PermissionKind.POST_NOTIFICATIONS -> Icons.Rounded.NotificationAdd
      PermissionKind.BATTERY -> Icons.Rounded.BatteryChargingFull
      PermissionKind.AUTOSTART -> Icons.Rounded.RestartAlt
    }

val EventType.icon: ImageVector
  get() =
    when (this) {
      EventType.QUICK_APP_LAUNCH -> Icons.Rounded.Bolt
      EventType.FLAGGED_LAUNCH -> Icons.Rounded.Block
      EventType.OVERLAY_POPUP -> Icons.Rounded.Campaign
      EventType.SUSPICIOUS_NOTIFICATION -> Icons.Rounded.NotificationsActive
      EventType.PRIVILEGE_REQUEST -> Icons.Rounded.Key
    }

/** 打开某项权限：发送通知走运行时申请，其余跳系统设置页 */
@Composable
fun rememberPermissionOpener(onResult: () -> Unit): (PermissionKind) -> Unit {
  val context = LocalContext.current
  val notificationLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (!granted) SettingsNavigator.open(context, PermissionKind.POST_NOTIFICATIONS)
      onResult()
    }
  return { kind ->
    if (kind == PermissionKind.POST_NOTIFICATIONS && Build.VERSION.SDK_INT >= 33) {
      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      SettingsNavigator.open(context, kind)
    }
  }
}

/** 一行权限：已开启显示对勾，未开启显示按钮；状态切换时淡入缩放 */
@Composable
fun PermissionRow(state: PermissionState, onOpen: (PermissionKind) -> Unit) {
  ListItem(
    colors = groupListItemColors(),
    leadingContent = { TonalIcon(state.kind.icon) },
    headlineContent = { Text(state.kind.title) },
    supportingContent = { Text(state.kind.reason) },
    trailingContent = {
      AnimatedContent(
        targetState = state.granted == true,
        transitionSpec = { popSwap() },
        label = "permission",
      ) { granted ->
        when {
          granted -> Icon(Icons.Rounded.CheckCircle, contentDescription = "已开启", tint = MaterialTheme.colorScheme.primary)
          state.kind.required -> Button(onClick = { onOpen(state.kind) }) { Text("开启") }
          else -> FilledTonalButton(onClick = { onOpen(state.kind) }) { Text(if (state.granted == null) "去设置" else "开启") }
        }
      }
    },
  )
}

/**
 * 按分组列出权限：需要开启 / 建议开启 / 守护经常被关掉时再开（默认收起，有未开的必需项时不影响）。
 * 放在 [GroupCard] 等容器里使用；分组标题与 ListItem 的 16dp 内边距对齐
 */
@Composable
fun PermissionTiers(permissions: List<PermissionState>, onOpen: (PermissionKind) -> Unit) {
  Column {
    PermissionTier.entries.forEach { tier ->
      val items = permissions.filter { it.kind.tier == tier }
      if (items.isEmpty()) return@forEach
      if (tier == PermissionTier.IF_STOPPED) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "tierExpand")
        ListItem(
          modifier = Modifier.clickable { expanded = !expanded },
          colors = groupListItemColors(),
          headlineContent = { Text(tier.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
          supportingContent = { Text("部分手机省电时会关掉守护，遇到再开") },
          trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = rotation }) },
        )
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
          Column { items.forEach { PermissionRow(it, onOpen) } }
        }
      } else {
        Text(
          tier.title,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        items.forEach { PermissionRow(it, onOpen) }
      }
    }
  }
}

/** 左右抖动，用于密码错误；[trigger] 每变化一次抖一次 */
@Composable
fun Modifier.shakeOn(trigger: Int): Modifier {
  val offset = remember { Animatable(0f) }
  LaunchedEffect(trigger) {
    if (trigger == 0) return@LaunchedEffect
    for (x in listOf(-14f, 14f, -10f, 10f, -5f, 5f, 0f)) offset.animateTo(x, tween(45))
  }
  val density = LocalDensity.current.density
  return graphicsLayer { translationX = offset.value * density }
}
