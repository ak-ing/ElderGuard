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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.elderguard.app.privileged.AppAction
import com.elderguard.app.ui.common.clockTime
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
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
import com.elderguard.app.ui.common.AlignedLargeTopAppBar
import com.elderguard.app.ui.common.AppIcon
import com.elderguard.app.ui.common.AppSheet
import com.elderguard.app.ui.common.BadgeTone
import com.elderguard.app.ui.common.EmptyHint
import com.elderguard.app.ui.common.IconLabel
import com.elderguard.app.ui.common.PermissionTiers
import com.elderguard.app.system.PermissionTier
import com.elderguard.app.data.InstalledApp
import com.elderguard.app.scan.RiskSignal
import com.elderguard.app.theme.AppTheme
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ReportProblem
import com.elderguard.app.ui.common.SectionHeader
import com.elderguard.app.ui.common.SheetInfoRow
import com.elderguard.app.ui.common.SheetSectionTitle
import com.elderguard.app.ui.common.StatusBadge
import com.elderguard.app.ui.common.TonalIcon
import com.elderguard.app.ui.common.VerdictLabel
import com.elderguard.app.ui.common.formatTime
import com.elderguard.app.ui.common.groupListItemColors
import com.elderguard.app.ui.common.icon
import com.elderguard.app.ui.common.rememberPermissionOpener
import com.elderguard.app.ui.common.segmentShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CulpritRow(val stat: CulpritStat, val label: String, val trust: Trust?, val installed: Boolean, val frozen: Boolean)

data class EventRow(val event: GuardEvent, val culpritLabel: String, val targetLabel: String)

/** 新装的应用；[source] 为 null 表示系统没记录来源；[signals] 为空表示已卸载或没有风险信号 */
data class InstallRow(val app: InstalledApp, val culprit: CulpritRow, val source: String?, val signals: List<RiskSignal>) {
  val risky: Boolean
    get() = signals.any { it.strong }
}

data class FamilyStats(val guardRunning: Boolean, val weekTotal: Int, val todayHandled: Int, val flagged: Int)

data class FamilyState(
  val stats: FamilyStats,
  val permissions: List<PermissionState>,
  val mode: GuardMode,
  /** 家人规则：挡掉快应用 */
  val blockQuickApps: Boolean,
  val culprits: List<CulpritRow>,
  val installs: List<InstallRow>,
  val events: List<EventRow>,
  val privilege: PrivilegeStatus,
  /** 维护模式自动结束时间；null 表示未在维护 */
  val maintenanceEndsAt: Long?,
) {
  val requiredMissing: Boolean
    get() = permissions.any { it.kind.required && it.granted != true }
}

class FamilyViewModel(private val c: AppContainer) : ViewModel() {
  private val permissions = MutableStateFlow(c.permissions.snapshot())
  val actions = ActionRunner(c, viewModelScope)

  private val culprits =
    combine(c.guardRepository.culpritRanking(), c.guardRepository.verdicts, actions.revision) { ranking, verdicts, _ ->
      // 被标记过但最近没有骚扰的 App 也要列出来，方便撤销结论
      val ranked = ranking.map { it.culpritPkg }.toSet()
      val idle = verdicts.keys.filterNot { it in ranked }.map { CulpritStat(it, 0, 0) }
      (ranking + idle).map {
        val pkg = it.culpritPkg
        CulpritRow(it, c.appInfo.label(pkg), verdicts[pkg], c.appInfo.isInstalled(pkg), c.appInfo.isFrozen(pkg))
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

  private val maintenance = combine(c.privileged.status, c.maintenance.endsAt, ::Pair)

  private val installs =
    combine(c.guardRepository.recentInstalls(), c.guardRepository.verdicts, actions.revision) { list, verdicts, _ ->
      val signals = c.scanner.assess(list.map { it.pkg })
      list.map { app ->
        val pkg = app.pkg
        val row = CulpritRow(CulpritStat(pkg, 0, 0), c.appInfo.label(pkg), verdicts[pkg], c.appInfo.isInstalled(pkg), c.appInfo.isFrozen(pkg))
        InstallRow(app, row, c.appInfo.sourceLabel(app), signals[pkg].orEmpty())
      }
    }

  private val rules = combine(c.settings.guardMode, c.settings.blockQuickApps, ::Pair)

  val state =
    combine(stats, permissions, rules, combine(culprits, installs, ::Pair), combine(c.guardRepository.recentEvents(), maintenance, ::Pair)) {
        stats,
        perms,
        (mode, blockQuickApps),
        (rows, installed),
        (events, maint) ->
        FamilyState(
          stats = stats,
          permissions = perms,
          mode = mode,
          blockQuickApps = blockQuickApps,
          culprits = rows,
          installs = installed,
          events = events.map { EventRow(it, c.appInfo.label(it.culpritPkg), c.appInfo.label(it.targetPkg)) },
          privilege = maint.first,
          maintenanceEndsAt = maint.second,
        )
      }
      // 应用名、是否已安装都要查 PackageManager，放到后台线程组装
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun refresh() {
    permissions.value = c.permissions.snapshot()
    c.privileged.refresh()
    // 安装广播在部分厂商系统上会丢，每次打开家人页按系统记的首次安装时间补齐
    viewModelScope.launch(Dispatchers.Default) { GuardAccessibilityService.syncInstalls(c) }
  }

  fun setMode(mode: GuardMode) = viewModelScope.launch { c.settings.setGuardMode(mode) }

  fun setBlockQuickApps(block: Boolean) = viewModelScope.launch { c.settings.setBlockQuickApps(block) }

  fun setTrust(pkg: String, trust: Trust?) = viewModelScope.launch { c.guardRepository.setTrust(pkg, trust) }
}

internal val ContentMaxWidth = 720.dp
internal val ItemModifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()

/** 宽屏上内容居中，悬浮按钮右移到和内容右边缘对齐，而不是贴着屏幕边 */
internal fun fabEndPadding(screenWidth: Dp): Dp = ((screenWidth - ContentMaxWidth - 32.dp) / 2).coerceAtLeast(0.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyHomeScreen(
  onBack: () -> Unit,
  onOpenScan: () -> Unit,
  onOpenMaintenance: () -> Unit,
  onOpenReport: () -> Unit,
  onOpenSelfTest: () -> Unit,
  modifier: Modifier = Modifier,
) {
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
  var selectedInstall by remember { mutableStateOf<InstallRow?>(null) }
  val snackbar = remember { SnackbarHostState() }
  ShowMessages(vm.actions.messages, snackbar)

  BoxWithConstraints(modifier.fillMaxSize()) {
    Scaffold(
      Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
      topBar = {
        AlignedLargeTopAppBar("家人页", onBack, maxWidth, ContentMaxWidth + 32.dp, scrollBehavior) {
          IconButton(onClick = onOpenReport) { Icon(Icons.Rounded.Share, contentDescription = "分享报告") }
        }
      },
      snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      val s = state ?: return@Scaffold
      LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
          PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        item(key = "overview") {
          Overview(s.stats, onFix = { openPermission(PermissionKind.ACCESSIBILITY) }, modifier = ItemModifier.animateItem())
        }
        item(key = "scan") {
          EntryItem(
            Icons.Rounded.HealthAndSafety,
            "手机体检",
            "找出可能在弹广告、难卸载的应用",
            segmentShape(0, 2),
            onOpenScan,
            ItemModifier.padding(top = 12.dp).animateItem(),
          )
        }
        item(key = "selfTest") {
          EntryItem(Icons.AutoMirrored.Rounded.FactCheck, "守护自检", "试一试守护在这台手机上能不能挡掉快应用", segmentShape(1, 2), onOpenSelfTest, ItemModifier.animateItem())
        }

        sectionHeader("culpritsHeader", "需要留意的应用", "近 7 天弹过悬浮窗、发过可疑通知等。点一下可以标记或处理")
        if (s.culprits.isEmpty()) {
          item(key = "culpritsEmpty", contentType = "empty") { EmptyHint("近 7 天没有记录", ItemModifier.animateItem()) }
        } else {
          itemsIndexed(s.culprits, key = { _, row -> "culprit-${row.stat.culpritPkg}" }, contentType = { _, _ -> "culprit" }) { i, row ->
            CulpritItem(row, segmentShape(i, s.culprits.size), onClick = { selected = row }, modifier = ItemModifier.animateItem())
          }
        }

        sectionHeader("installsHeader", "新装的应用", "近 30 天。不认识的可以卸载或冻结")
        if (s.installs.isEmpty()) {
          item(key = "installsEmpty", contentType = "empty") { EmptyHint("近 30 天没有新装应用", ItemModifier.animateItem()) }
        } else {
          itemsIndexed(s.installs, key = { _, row -> "install-${row.app.pkg}" }, contentType = { _, _ -> "install" }) { i, row ->
            InstallItem(row, segmentShape(i, s.installs.size), onClick = { selectedInstall = row }, modifier = ItemModifier.animateItem())
          }
        }

        sectionHeader("modeHeader", "守护方式")
        item(key = "mode") { ModeSelector(s.mode, vm::setMode, ItemModifier.padding(vertical = 1.dp).animateItem(), segmentShape(0, 2)) }
        item(key = "quickAppRule") {
          QuickAppRule(s.blockQuickApps, vm::setBlockQuickApps, ItemModifier.padding(vertical = 1.dp).animateItem(), segmentShape(1, 2))
        }

        sectionHeader("permHeader", "权限")
        item(key = "perms") {
          PermissionGroup(s.permissions, startExpanded = s.requiredMissing, onOpen = openPermission, modifier = ItemModifier.animateItem())
        }

        sectionHeader("maintHeader", "维护模式")
        item(key = "maint") { MaintenanceItem(s.privilege, s.maintenanceEndsAt, onOpenMaintenance, ItemModifier.animateItem()) }

        sectionHeader("eventsHeader", "最近记录")
        if (s.events.isEmpty()) {
          item(key = "eventsEmpty", contentType = "empty") { EmptyHint("暂无记录", ItemModifier.animateItem()) }
        } else {
          itemsIndexed(s.events, key = { _, row -> "event-${row.event.id}" }, contentType = { _, _ -> "event" }) { i, row ->
            EventItem(row, segmentShape(i, s.events.size), ItemModifier.animateItem())
          }
        }
      }
    }
  }

  selected?.let { row ->
    val revision by vm.actions.revision.collectAsStateWithLifecycle()
    CulpritSheet(
      row,
      maintenanceActions = rememberMaintenanceActions(row.stat.culpritPkg, revision),
      onDismiss = { selected = null },
      onTrust = { vm.setTrust(row.stat.culpritPkg, it) },
      onAction = { vm.actions.run(row.stat.culpritPkg, row.label, it) },
    )
  }

  selectedInstall?.let { picked ->
    // 用最新状态：标记、冻结后面板里的结论跟着变
    val install = state?.installs?.find { it.app.pkg == picked.app.pkg } ?: picked
    val row = install.culprit
    val revision by vm.actions.revision.collectAsStateWithLifecycle()
    CulpritSheet(
      row,
      maintenanceActions = rememberMaintenanceActions(row.stat.culpritPkg, revision),
      onDismiss = { selectedInstall = null },
      onTrust = { vm.setTrust(row.stat.culpritPkg, it) },
      onAction = { vm.actions.run(row.stat.culpritPkg, row.label, it) },
      install = install,
    )
  }
}

internal fun LazyListScope.sectionHeader(key: String, title: String, supporting: String? = null) {
  item(key = key, contentType = "header") { SectionHeader(title, ItemModifier.padding(top = 16.dp).animateItem(), supporting) }
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
private fun EntryItem(icon: ImageVector, title: String, supporting: String, shape: Shape, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      colors = groupListItemColors(),
      leadingContent = {
        TonalIcon(icon, container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
      },
      headlineContent = { Text(title) },
      supportingContent = { Text(supporting) },
      trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
    )
  }
}

@Composable
internal fun CulpritItem(row: CulpritRow, shape: Shape, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
      trailingContent = { VerdictLabel(row.trust, row.installed, row.frozen) },
    )
  }
}

@Composable
internal fun CulpritSheet(
  row: CulpritRow,
  maintenanceActions: List<AppAction>,
  onDismiss: () -> Unit,
  onTrust: (Trust?) -> Unit,
  onAction: (AppAction) -> Unit,
  /** 从「新装的应用」打开时，显示怎么装上的、有哪些风险信号 */
  install: InstallRow? = null,
) {
  val context = LocalContext.current
  val events by
    remember(row.stat.culpritPkg) { context.container.guardRepository.eventsOf(row.stat.culpritPkg) }
      .collectAsStateWithLifecycle(emptyList())
  AppSheet(
    pkg = row.stat.culpritPkg,
    label = row.label,
    subtitle =
      when {
        install != null ->
          install.source?.let { "${formatTime(install.app.time)} 从「$it」装上" } ?: "${formatTime(install.app.time)} 装上 · 来源未知"
        row.stat.count > 0 -> "近 7 天 ${row.stat.count} 次"
        else -> row.stat.culpritPkg
      },
    trust = row.trust,
    installed = row.installed,
    onDismiss = onDismiss,
    onTrust = onTrust,
    onUninstall = { SettingsNavigator.requestUninstall(context, row.stat.culpritPkg) },
    onDetails = { SettingsNavigator.openAppDetails(context, row.stat.culpritPkg) },
    frozen = row.frozen,
    maintenanceActions = maintenanceActions,
    onAction = onAction,
  ) {
    if (install != null && install.signals.isNotEmpty()) {
      SheetSectionTitle("它能做什么")
      install.signals.forEach { SheetInfoRow(it.icon, it.title, it.detail) }
    }
    if (events.isNotEmpty()) {
      SheetSectionTitle("它做了什么")
      events.forEach { e ->
        SheetInfoRow(
          e.type.icon,
          e.type.description,
          "${formatTime(e.time)} · ${if (e.handled) "已自动处理" else "仅记录"}" + (e.detail?.let { " · $it" } ?: ""),
        )
      }
    }
  }
}

@Composable
private fun InstallItem(row: InstallRow, shape: Shape, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      colors = groupListItemColors(),
      leadingContent = { AppIcon(row.app.pkg) },
      headlineContent = { Text(row.culprit.label) },
      supportingContent = { Text("${row.source ?: "来源未知"} · ${formatTime(row.app.time)}", maxLines = 1) },
      trailingContent = {
        val c = row.culprit
        // 没有结论又带强风险信号的，提醒家人看一眼；其余显示结论
        if (c.trust == null && c.installed && !c.frozen && row.risky) {
          IconLabel(Icons.Rounded.ReportProblem, "需留意", AppTheme.extendedColors.warning)
        } else if (c.trust == null && c.installed && !c.frozen) {
          IconLabel(Icons.Rounded.CheckCircle, "无风险信号", MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          VerdictLabel(c.trust, c.installed, c.frozen)
        }
      },
    )
  }
}

@Composable
private fun QuickAppRule(block: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, shape: Shape) {
  Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      modifier = Modifier.toggleable(block, role = Role.Switch, onValueChange = onChange),
      colors = groupListItemColors(),
      leadingContent = { TonalIcon(Icons.Rounded.Bolt) },
      headlineContent = { Text("挡掉快应用") },
      supportingContent = { Text("广告常拉起快应用；长辈很少主动用。关掉后快应用不再被挡") },
      trailingContent = { Switch(checked = block, onCheckedChange = null) },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(selected: GuardMode, onSelect: (GuardMode) -> Unit, modifier: Modifier = Modifier, shape: Shape) {
  Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
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
  // 只数需要和建议开启的：「守护经常被关掉时再开」那几项平时不用开
  val main = permissions.filter { it.kind.tier != PermissionTier.IF_STOPPED }
  val granted = main.count { it.granted == true }
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column {
      ListItem(
        modifier = Modifier.clickable { expanded = !expanded },
        colors = groupListItemColors(),
        leadingContent = { TonalIcon(Icons.Rounded.Settings) },
        headlineContent = { Text("守护权限") },
        supportingContent = { Text("已开启 $granted / ${main.size} 项") },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = rotation }) },
      )
      AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.padding(bottom = 8.dp)) { PermissionTiers(permissions, onOpen) }
      }
    }
  }
}

@Composable
private fun MaintenanceItem(status: PrivilegeStatus, endsAt: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val active = endsAt != null
  Surface(
    modifier,
    shape = MaterialTheme.shapes.large,
    color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      colors = groupListItemColors(),
      leadingContent = {
        // 维护中卡片本身是 secondaryContainer，图标底色换成 primary 才看得出来
        if (active) TonalIcon(Icons.Rounded.Build, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
        else TonalIcon(Icons.Rounded.Build)
      },
      headlineContent = { Text(if (active) "正在维护" else "直接处理流氓应用") },
      supportingContent = {
        Text(
          if (endsAt != null) "点开可疑应用即可冻结、卸载 · ${clockTime(endsAt)} 自动结束"
          else "借助 Shizuku 直接冻结、卸载、收回权限，用完自动关闭无线调试"
        )
      },
      trailingContent = {
        if (active) StatusBadge("维护中", BadgeTone.GOOD)
        else StatusBadge(status.label, if (status == PrivilegeStatus.READY) BadgeTone.GOOD else BadgeTone.NEUTRAL)
      },
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
