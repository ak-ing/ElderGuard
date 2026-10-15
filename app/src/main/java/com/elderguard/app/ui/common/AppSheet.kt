package com.elderguard.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.elderguard.app.privileged.AppAction
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elderguard.app.core.Trust
import com.elderguard.app.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** 宽屏下顶栏背景铺满，用左右 inset 把标题推到与 [contentWidth] 宽的居中内容左边缘对齐 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlignedLargeTopAppBar(
  title: String,
  onBack: () -> Unit,
  screenWidth: Dp,
  contentWidth: Dp,
  scrollBehavior: TopAppBarScrollBehavior,
  actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
  val sidePad = ((screenWidth - contentWidth) / 2).coerceAtLeast(0.dp)
  LargeTopAppBar(
    title = { Text(title) },
    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } },
    actions = actions,
    windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = sidePad, right = sidePad)),
    scrollBehavior = scrollBehavior,
  )
}

/** 分段列表：同组首尾大圆角、中间小圆角 */
fun segmentShape(index: Int, count: Int): Shape {
  val large = 24.dp
  val small = 6.dp
  return RoundedCornerShape(
    topStart = if (index == 0) large else small,
    topEnd = if (index == 0) large else small,
    bottomStart = if (index == count - 1) large else small,
    bottomEnd = if (index == count - 1) large else small,
  )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.Inbox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** 图标 + 着色文字的轻量状态标签 */
@Composable
fun IconLabel(icon: ImageVector, text: String, color: Color) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
  }
}

/** 家人对某个 App 的结论 */
@Composable
fun VerdictLabel(trust: Trust?, installed: Boolean, frozen: Boolean = false) {
  val scheme = MaterialTheme.colorScheme
  when {
    !installed -> IconLabel(Icons.Rounded.DeleteOutline, "已卸载", scheme.onSurfaceVariant)
    frozen -> IconLabel(Icons.Rounded.AcUnit, "已冻结", scheme.onSurfaceVariant)
    trust == Trust.FLAGGED -> IconLabel(Icons.Rounded.Block, "已拦截", scheme.error)
    trust == Trust.TRUSTED -> IconLabel(Icons.Rounded.CheckCircle, "已信任", scheme.primary)
    else -> IconLabel(Icons.AutoMirrored.Rounded.HelpOutline, "待确认", AppTheme.extendedColors.warning)
  }
}

@Composable
fun SheetSectionTitle(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
  )
}

/** 底部面板里的一行说明：与表头 48dp 应用图标同宽，图标中心和文字起点都对齐 */
@Composable
fun SheetInfoRow(icon: ImageVector, title: String, supporting: String?, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
  ListItem(
    colors = groupListItemColors(),
    leadingContent = {
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
      }
    },
    headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
    supportingContent = supporting?.let { { Text(it) } },
  )
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, supporting: String?, tint: Color = Color.Unspecified, onClick: () -> Unit) {
  val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
  ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    colors =
      ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
      ),
    leadingContent = {
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
      }
    },
    headlineContent = { Text(title) },
    supportingContent = supporting?.let { { Text(it) } },
  )
}

/**
 * 应用处理面板：表头（图标、名称、结论）→ 说明区 [details]（「它做了什么」「为什么可疑」）→ 处理动作。
 * 可疑应用列表、体检结果、维护模式共用。[maintenanceActions] 非空表示正在维护，可以直接执行处置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
  pkg: String,
  label: String,
  subtitle: String,
  trust: Trust?,
  installed: Boolean,
  onDismiss: () -> Unit,
  onTrust: (Trust?) -> Unit,
  onUninstall: () -> Unit,
  onDetails: () -> Unit,
  frozen: Boolean = false,
  maintenanceActions: List<AppAction> = emptyList(),
  onAction: (AppAction) -> Unit = {},
  details: @Composable ColumnScope.() -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  var confirmUninstall by remember { mutableStateOf(false) }
  fun act(block: () -> Unit) {
    block()
    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    // 维护模式下动作较多，横屏时可能超出一屏
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
      ListItem(
        colors = groupListItemColors(),
        leadingContent = { AppIcon(pkg, 48.dp) },
        headlineContent = { Text(label, style = MaterialTheme.typography.titleLarge) },
        supportingContent = { Text(subtitle) },
        trailingContent = { VerdictLabel(trust, installed, frozen) },
      )
      // 维护中家人是来动手的：处置放在说明之前，不用先滚过一长串记录
      val maintaining = installed && maintenanceActions.isNotEmpty()
      if (!maintaining) {
        details()
        Spacer(Modifier.height(8.dp))
      }
      if (maintaining) {
        SheetSectionTitle("直接处理 · 维护模式")
        maintenanceActions.forEach { action ->
          val destructive = action == AppAction.UNINSTALL
          SheetAction(
            action.icon,
            action.title,
            action.supporting,
            tint = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
          ) {
            if (destructive) confirmUninstall = true else act { onAction(action) }
          }
        }
        Spacer(Modifier.height(8.dp))
      }
      SheetSectionTitle(if (maintaining) "标记" else "处理")
      if (installed) {
        if (trust != Trust.FLAGGED) {
          SheetAction(Icons.Rounded.Block, "标记为流氓应用", "以后它的弹窗会被自动拦截") { act { onTrust(Trust.FLAGGED) } }
        }
        if (trust != Trust.TRUSTED) {
          SheetAction(Icons.Rounded.CheckCircle, "标记为正常应用", "不再记录、不再提示") { act { onTrust(Trust.TRUSTED) } }
        }
        if (trust != null) {
          SheetAction(Icons.AutoMirrored.Rounded.Undo, "撤销标记", "恢复为待确认") { act { onTrust(null) } }
        }
        SheetAction(Icons.Rounded.Settings, "应用设置", "关闭通知、悬浮窗等权限") { act(onDetails) }
        if (maintenanceActions.isEmpty()) {
          SheetAction(Icons.Rounded.DeleteOutline, "卸载", null, tint = MaterialTheme.colorScheme.error) { act(onUninstall) }
        }
      } else {
        SheetAction(Icons.AutoMirrored.Rounded.Undo, "从列表移除", null) { act { onTrust(null) } }
      }
      if (maintaining) {
        Spacer(Modifier.height(8.dp))
        details()
      }
    }
  }

  if (confirmUninstall) {
    AlertDialog(
      onDismissRequest = { confirmUninstall = false },
      icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
      title = { Text("卸载「$label」？") },
      text = { Text("会直接卸载，不再弹出系统确认，它的数据也会一并删除。") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmUninstall = false
            act { onAction(AppAction.UNINSTALL) }
          },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text("卸载")
        }
      },
      dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("取消") } },
    )
  }
}

val AppAction.icon: ImageVector
  get() =
    when (this) {
      AppAction.REVOKE_OVERLAY -> Icons.Rounded.Layers
      AppAction.RESTRICT_BACKGROUND -> Icons.Rounded.Bedtime
      AppAction.REVOKE_NOTIFICATIONS -> Icons.Rounded.NotificationsOff
      AppAction.DISABLE_ACCESSIBILITY -> Icons.Rounded.Accessibility
      AppAction.FREEZE -> Icons.Rounded.AcUnit
      AppAction.UNFREEZE -> Icons.Rounded.WbSunny
      AppAction.UNINSTALL -> Icons.Rounded.DeleteForever
    }

private val timeOfDay = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dateTime = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

fun clockTime(time: Long): String = timeOfDay.format(Date(time))

fun formatTime(time: Long): String {
  val now = Calendar.getInstance()
  val then = Calendar.getInstance().apply { timeInMillis = time }
  val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
  return if (sameDay) "今天 ${timeOfDay.format(Date(time))}" else dateTime.format(Date(time))
}
