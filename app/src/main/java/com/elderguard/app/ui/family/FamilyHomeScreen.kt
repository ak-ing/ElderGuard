package com.elderguard.app.ui.family

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elderguard.app.AppContainer
import com.elderguard.app.container
import com.elderguard.app.core.GuardMode
import com.elderguard.app.core.Trust
import com.elderguard.app.data.CulpritStat
import com.elderguard.app.data.GuardEvent
import com.elderguard.app.guard.GuardAccessibilityService
import com.elderguard.app.privileged.PrivilegeStatus
import com.elderguard.app.system.PermissionKind
import com.elderguard.app.system.PermissionState
import com.elderguard.app.system.SettingsNavigator
import com.elderguard.app.theme.AppTheme
import com.elderguard.app.ui.common.AppIcon
import com.elderguard.app.ui.common.BadgeTone
import com.elderguard.app.ui.common.PermissionRow
import com.elderguard.app.ui.common.SectionHeader
import com.elderguard.app.ui.common.StatusBadge
import com.elderguard.app.ui.common.TonalIcon
import com.elderguard.app.ui.common.groupListItemColors
import com.elderguard.app.ui.common.icon
import com.elderguard.app.ui.common.rememberPermissionOpener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CulpritRow(val stat: CulpritStat, val label: String, val trust: Trust?, val installed: Boolean)

data class EventRow(val event: GuardEvent, val culpritLabel: String, val targetLabel: String)

data class FamilyStats(val guardRunning: Boolean, val weekTotal: Int, val todayHandled: Int, val flagged: Int)

data class FamilyState(
  val stats: FamilyStats,
  val permissions: List<PermissionState>,
  val mode: GuardMode,
  val culprits: List<CulpritRow>,
  val events: List<EventRow>,
  val privilege: PrivilegeStatus,
) {
  val requiredMissing: Boolean
    get() = permissions.any { it.kind.required && it.granted != true }
}

class FamilyViewModel(private val c: AppContainer) : ViewModel() {
  private val permissions = MutableStateFlow(c.permissions.snapshot())
  private val privilege = MutableStateFlow(c.privileged.status())

  private val culprits =
    combine(c.guardRepository.culpritRanking(), c.guardRepository.verdicts) { ranking, verdicts ->
      // 被标记过但最近没有骚扰的 App 也要列出来，方便撤销结论
      val ranked = ranking.map { it.culpritPkg }.toSet()
      val idle = verdicts.keys.filterNot { it in ranked }.map { CulpritStat(it, 0, 0) }
      (ranking + idle).map {
        CulpritRow(it, c.appInfo.label(it.culpritPkg), verdicts[it.culpritPkg], c.appInfo.isInstalled(it.culpritPkg))
      }
    }

  private val stats =
    combine(permissions, GuardAccessibilityService.running, c.guardRepository.todayHandled(), culprits) {
      perms,
      running,
      handled,
      rows ->
      val enabled = perms.first { it.kind == PermissionKind.ACCESSIBILITY }.granted == true
      FamilyStats(enabled && running, rows.sumOf { it.stat.count }, handled, rows.count { it.trust == Trust.FLAGGED })
    }

  val state =
    combine(stats, permissions, c.settings.guardMode, culprits, combine(c.guardRepository.recentEvents(), privilege, ::Pair)) {
        stats,
        perms,
        mode,
        rows,
        (events, priv) ->
        FamilyState(
          stats = stats,
          permissions = perms,
          mode = mode,
          culprits = rows,
          events = events.map { EventRow(it, c.appInfo.label(it.culpritPkg), c.appInfo.label(it.targetPkg)) },
          privilege = priv,
        )
      }
      // 应用名、是否已安装都要查 PackageManager，放到后台线程组装
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun refresh() {
    permissions.value = c.permissions.snapshot()
    privilege.value = c.privileged.status()
  }

  fun setMode(mode: GuardMode) = viewModelScope.launch { c.settings.setGuardMode(mode) }

  fun setTrust(pkg: String, trust: Trust?) = viewModelScope.launch { c.guardRepository.setTrust(pkg, trust) }

  fun requestPrivilege() = c.privileged.requestPermission()
}

private val ContentMaxWidth = 720.dp
private val ItemModifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyHomeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val container = context.container
  val vm = viewModel { FamilyViewModel(container) }
  val state by vm.state.collectAsStateWithLifecycle()
  LifecycleResumeEffect(Unit) {
    vm.refresh()
    onPauseOrDispose {}
  }
  val openPermission = rememberPermissionOpener(onResult = vm::refresh)
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  var selected by remember { mutableStateOf<CulpritRow?>(null) }

  // 宽屏下顶栏背景铺满，但用左右 inset 把标题推到与列表左边缘对齐
  BoxWithConstraints(modifier.fillMaxSize()) {
  val sidePad = ((maxWidth - ContentMaxWidth - 32.dp) / 2).coerceAtLeast(0.dp)
  Scaffold(
    Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      LargeTopAppBar(
        title = { Text("家人页") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } },
        windowInsets = TopAppBarDefaults.windowInsets.add(WindowInsets(left = sidePad, right = sidePad)),
        scrollBehavior = scrollBehavior,
      )
    },
  ) { padding ->
    val s = state ?: return@Scaffold
    LazyColumn(
      Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item(key = "overview") {
        Overview(s.stats, onFix = { openPermission(PermissionKind.ACCESSIBILITY) }, modifier = ItemModifier.animateItem())
      }

      sectionHeader("culpritsHeader", "可疑应用", "近 7 天。点一下可以标记或处理")
      if (s.culprits.isEmpty()) {
        item(key = "culpritsEmpty") { EmptyHint("还没有发现可疑应用", ItemModifier.animateItem()) }
      } else {
        itemsIndexed(s.culprits, key = { _, row -> "culprit-${row.stat.culpritPkg}" }) { i, row ->
          CulpritItem(row, segmentShape(i, s.culprits.size), onClick = { selected = row }, modifier = ItemModifier.animateItem())
        }
      }

      sectionHeader("modeHeader", "守护方式")
      item(key = "mode") { ModeSelector(s.mode, vm::setMode, ItemModifier.animateItem()) }

      sectionHeader("permHeader", "权限")
      item(key = "perms") {
        PermissionGroup(s.permissions, startExpanded = s.requiredMissing, onOpen = openPermission, modifier = ItemModifier.animateItem())
      }

      sectionHeader("maintHeader", "维护模式")
      item(key = "maint") { MaintenanceItem(s.privilege, vm::requestPrivilege, ItemModifier.animateItem()) }

      sectionHeader("eventsHeader", "最近记录")
      if (s.events.isEmpty()) {
        item(key = "eventsEmpty") { EmptyHint("暂无记录", ItemModifier.animateItem()) }
      } else {
        itemsIndexed(s.events, key = { _, row -> "event-${row.event.id}" }) { i, row ->
          EventItem(row, segmentShape(i, s.events.size), ItemModifier.animateItem())
        }
      }
    }
  }
  }

  selected?.let { row ->
    CulpritSheet(
      row = row,
      onDismiss = { selected = null },
      onTrust = { vm.setTrust(row.stat.culpritPkg, it) },
      onUninstall = { SettingsNavigator.requestUninstall(context, row.stat.culpritPkg) },
      onDetails = { SettingsNavigator.openAppDetails(context, row.stat.culpritPkg) },
    )
  }
}

private fun LazyListScope.sectionHeader(key: String, title: String, supporting: String? = null) {
  item(key = key) { SectionHeader(title, ItemModifier.padding(top = 16.dp).animateItem(), supporting) }
}

/** 分段列表：同组首尾大圆角、中间小圆角，项间留 2dp 缝 */
private fun segmentShape(index: Int, count: Int): Shape {
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
private fun Overview(stats: FamilyStats, onFix: () -> Unit, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val container = if (stats.guardRunning) scheme.primaryContainer else scheme.errorContainer
  val onContainer = if (stats.guardRunning) scheme.onPrimaryContainer else scheme.onErrorContainer
  Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = container, contentColor = onContainer) {
    Column(Modifier.padding(24.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (stats.guardRunning) Icons.Rounded.VerifiedUser else Icons.Rounded.GppBad, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(if (stats.guardRunning) "守护运行中" else "守护未运行", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (!stats.guardRunning) Button(onClick = onFix) { Text("去开启") }
      }
      Spacer(Modifier.height(20.dp))
      Row(Modifier.fillMaxWidth()) {
        Stat("近 7 天发现", stats.weekTotal, Modifier.weight(1f))
        Stat("今日拦截", stats.todayHandled, Modifier.weight(1f))
        Stat("已标记流氓", stats.flagged, Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
  Column(modifier) {
    AnimatedContent(value, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stat") {
      Text("$it", style = MaterialTheme.typography.headlineMedium)
    }
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.Inbox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.width(12.dp))
      Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** 家人结论：图标 + 着色文字，比胶囊徽章更轻 */
@Composable
private fun VerdictLabel(row: CulpritRow) {
  val scheme = MaterialTheme.colorScheme
  val (icon, text, color) =
    when {
      !row.installed -> Triple(Icons.Rounded.DeleteOutline, "已卸载", scheme.onSurfaceVariant)
      row.trust == Trust.FLAGGED -> Triple(Icons.Rounded.Block, "已拦截", scheme.error)
      row.trust == Trust.TRUSTED -> Triple(Icons.Rounded.CheckCircle, "已信任", scheme.primary)
      else -> Triple(Icons.AutoMirrored.Rounded.HelpOutline, "待确认", AppTheme.extendedColors.warning)
    }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
  }
}

@Composable
private fun CulpritItem(row: CulpritRow, shape: Shape, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      colors = groupListItemColors(),
      leadingContent = { AppIcon(row.stat.culpritPkg) },
      headlineContent = { Text(row.label) },
      supportingContent = {
        // 一行说清：做了什么 · 几次 · 最近何时（保持两行列表项，左右内容垂直居中）
        val behaviors = row.stat.eventTypes.joinToString("、") { it.label }
        Text(
          if (row.stat.count > 0) listOf(behaviors, "${row.stat.count} 次", formatTime(row.stat.lastTime)).filter { it.isNotEmpty() }.joinToString(" · ")
          else "近 7 天没有出现",
          maxLines = 1,
        )
      },
      trailingContent = { VerdictLabel(row) },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(selected: GuardMode, onSelect: (GuardMode) -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(16.dp)) {
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        GuardMode.entries.forEachIndexed { i, mode ->
          SegmentedButton(
            selected = mode == selected,
            onClick = { onSelect(mode) },
            shape = SegmentedButtonDefaults.itemShape(i, GuardMode.entries.size),
          ) {
            Text(mode.label)
          }
        }
      }
      Spacer(Modifier.height(12.dp))
      AnimatedContent(selected, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "modeDesc") {
        Text(it.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun PermissionGroup(
  permissions: List<PermissionState>,
  startExpanded: Boolean,
  onOpen: (PermissionKind) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by rememberSaveable { mutableStateOf(startExpanded) }
  val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expandIcon")
  val granted = permissions.count { it.granted == true }
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column {
      ListItem(
        modifier = Modifier.clickable { expanded = !expanded },
        colors = groupListItemColors(),
        leadingContent = { TonalIcon(Icons.Rounded.Settings) },
        headlineContent = { Text("守护权限") },
        supportingContent = { Text("已开启 $granted / ${permissions.size} 项") },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) },
      )
      AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.padding(bottom = 8.dp)) { permissions.forEach { PermissionRow(it, onOpen) } }
      }
    }
  }
}

@Composable
private fun MaintenanceItem(status: PrivilegeStatus, onRequest: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      modifier = if (status == PrivilegeStatus.NEEDS_PERMISSION) Modifier.clickable(onClick = onRequest) else Modifier,
      colors = groupListItemColors(),
      leadingContent = { TonalIcon(Icons.Rounded.Build) },
      headlineContent = { Text("一键清理（开发中）") },
      supportingContent = { Text("借助 Shizuku 直接收回悬浮窗、冻结流氓应用，用完自动关闭无线调试") },
      trailingContent = { StatusBadge(status.label, if (status == PrivilegeStatus.READY) BadgeTone.GOOD else BadgeTone.NEUTRAL) },
    )
  }
}

@Composable
private fun EventItem(row: EventRow, shape: Shape, modifier: Modifier = Modifier) {
  val handled = row.event.handled
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      colors = groupListItemColors(),
      leadingContent = {
        TonalIcon(
          row.event.type.icon,
          container = if (handled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
          content = if (handled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      headlineContent = {
        val target = if (row.event.targetPkg != row.event.culpritPkg) " → ${row.targetLabel}" else ""
        Text("${row.culpritLabel}$target")
      },
      supportingContent = {
        Text("${formatTime(row.event.time)} · ${row.event.type.label}" + (row.event.detail?.let { " · $it" } ?: ""), maxLines = 2)
      },
      trailingContent = {
        Text(
          if (handled) "已拦截" else "已记录",
          style = MaterialTheme.typography.labelMedium,
          color = if (handled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CulpritSheet(
  row: CulpritRow,
  onDismiss: () -> Unit,
  onTrust: (Trust?) -> Unit,
  onUninstall: () -> Unit,
  onDetails: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  fun act(block: () -> Unit) {
    block()
    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
  }

  val container = LocalContext.current.container
  val events by
    remember(row.stat.culpritPkg) { container.guardRepository.eventsOf(row.stat.culpritPkg) }
      .collectAsStateWithLifecycle(emptyList())

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(Modifier.padding(bottom = 24.dp)) {
      ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { AppIcon(row.stat.culpritPkg, 48.dp) },
        headlineContent = { Text(row.label, style = MaterialTheme.typography.titleLarge) },
        supportingContent = { Text(if (row.stat.count > 0) "近 7 天 ${row.stat.count} 次" else row.stat.culpritPkg) },
        trailingContent = { VerdictLabel(row) },
      )
      if (events.isNotEmpty()) {
        Text(
          "它做了什么",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        )
        events.forEach { BehaviorRow(it) }
      }
      Spacer(Modifier.height(16.dp))
      Text(
        "处理",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
      )
      if (row.installed) {
        if (row.trust != Trust.FLAGGED) {
          SheetAction(Icons.Rounded.Block, "标记为流氓应用", "以后它的弹窗会被自动拦截") { act { onTrust(Trust.FLAGGED) } }
        }
        if (row.trust != Trust.TRUSTED) {
          SheetAction(Icons.Rounded.CheckCircle, "标记为正常应用", "不再记录它") { act { onTrust(Trust.TRUSTED) } }
        }
        if (row.trust != null) {
          SheetAction(Icons.AutoMirrored.Rounded.Undo, "撤销标记", "恢复为待确认") { act { onTrust(null) } }
        }
        SheetAction(Icons.Rounded.Settings, "应用设置", "关闭通知、悬浮窗等权限") { act(onDetails) }
        SheetAction(Icons.Rounded.DeleteOutline, "卸载", null, tint = MaterialTheme.colorScheme.error) { act(onUninstall) }
      } else {
        SheetAction(Icons.AutoMirrored.Rounded.Undo, "从列表移除", null) { act { onTrust(null) } }
      }
    }
  }
}

/** 一条行为说明：类型图标 + 通俗描述 + 时间和处理结果 */
@Composable
private fun BehaviorRow(event: GuardEvent) {
  ListItem(
    colors = groupListItemColors(),
    leadingContent = {
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(event.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
      }
    },
    headlineContent = { Text(event.type.description, style = MaterialTheme.typography.bodyLarge) },
    supportingContent = {
      Text("${formatTime(event.time)} · ${if (event.handled) "已自动返回" else "仅记录"}" + (event.detail?.let { " · $it" } ?: ""))
    },
  )
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, supporting: String?, tint: Color = Color.Unspecified, onClick: () -> Unit) {
  val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
  ListItem(
    modifier = Modifier.clickable(onClick = onClick),
    colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint),
    // 与表头 48dp 应用图标同宽，图标中心和文字起点都对齐
    leadingContent = {
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
      }
    },
    headlineContent = { Text(title) },
    supportingContent = supporting?.let { { Text(it) } },
  )
}

private val timeOfDay = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dateTime = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private fun formatTime(time: Long): String {
  val now = Calendar.getInstance()
  val then = Calendar.getInstance().apply { timeInMillis = time }
  val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
  return if (sameDay) "今天 ${timeOfDay.format(Date(time))}" else dateTime.format(Date(time))
}
