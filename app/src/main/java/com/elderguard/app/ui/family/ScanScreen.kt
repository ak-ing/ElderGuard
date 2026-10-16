package com.elderguard.app.ui.family

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.elderguard.app.privileged.AppAction
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elderguard.app.AppContainer
import com.elderguard.app.container
import com.elderguard.app.core.Trust
import com.elderguard.app.scan.RiskLevel
import com.elderguard.app.scan.RiskSignal
import com.elderguard.app.scan.RiskyApp
import com.elderguard.app.scan.ScanResult
import com.elderguard.app.scan.SystemCheck
import com.elderguard.app.system.SettingsNavigator
import com.elderguard.app.theme.AppTheme
import com.elderguard.app.theme.Motion
import com.elderguard.app.ui.common.AlignedLargeTopAppBar
import com.elderguard.app.ui.common.AppIcon
import com.elderguard.app.ui.common.AppSheet
import com.elderguard.app.ui.common.IconLabel
import com.elderguard.app.ui.common.SheetInfoRow
import com.elderguard.app.ui.common.SheetSectionTitle
import com.elderguard.app.ui.common.TonalIcon
import com.elderguard.app.ui.common.groupListItemColors
import com.elderguard.app.ui.common.segmentShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanViewModel(private val c: AppContainer) : ViewModel() {
  /** null 表示正在扫描 */
  private val _result = MutableStateFlow<ScanResult?>(null)
  val result = _result.asStateFlow()
  val actions = ActionRunner(c, viewModelScope)

  fun perform(app: RiskyApp, action: AppAction) {
    actions.run(app.pkg, app.label, action) {
      // 冻结、卸载后它已经无害，从结果里移除
      if (action == AppAction.FREEZE || action == AppAction.UNINSTALL) {
        _result.value = _result.value?.let { r -> r.copy(apps = r.apps.filterNot { it.pkg == app.pkg }) }
      }
    }
  }

  init {
    scan()
  }

  fun scan() {
    _result.value = null
    viewModelScope.launch {
      val start = System.currentTimeMillis()
      val r = c.scanner.scan()
      // 扫描很快时也让过程可见，避免结果「闪一下」出来
      delay((MIN_SCAN_MS - (System.currentTimeMillis() - start)).coerceAtLeast(0))
      _result.value = r
    }
  }

  /** 维护模式下冻结快应用引擎等系统项里的应用，完成后重新体检 */
  fun freeze(pkgs: List<String>) {
    pkgs.forEachIndexed { i, pkg ->
      actions.run(pkg, c.appInfo.label(pkg), AppAction.FREEZE) { if (i == pkgs.lastIndex) scan() }
    }
  }

  fun setTrust(pkg: String, trust: Trust?) {
    viewModelScope.launch { c.guardRepository.setTrust(pkg, trust) }
    // 本地同步结论：标记为正常后从结果里移除
    _result.value =
      _result.value?.let { r ->
        r.copy(apps = if (trust == Trust.TRUSTED) r.apps.filterNot { it.pkg == pkg } else r.apps.map { if (it.pkg == pkg) it.copy(trust = trust) else it })
      }
  }

  private companion object {
    const val MIN_SCAN_MS = 900L
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val container = LocalContext.current.container
  val vm = viewModel { ScanViewModel(container) }
  val result by vm.result.collectAsStateWithLifecycle()
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  var selected by remember { mutableStateOf<RiskyApp?>(null) }
  val snackbar = remember { SnackbarHostState() }
  ShowMessages(vm.actions.messages, snackbar)

  BoxWithConstraints(modifier.fillMaxSize()) {
    Scaffold(
      Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
      topBar = {
        AlignedLargeTopAppBar("手机体检", onBack, maxWidth, ContentMaxWidth + 32.dp, scrollBehavior) {
          IconButton(onClick = vm::scan, enabled = result != null) { Icon(Icons.Rounded.Refresh, contentDescription = "重新体检") }
        }
      },
      snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      AnimatedContent(
        targetState = result,
        contentKey = { it == null },
        transitionSpec = { fadeIn(tween(Motion.DURATION_MEDIUM)) togetherWith fadeOut(tween(Motion.DURATION_SHORT)) },
        label = "scan",
        modifier = Modifier.padding(top = padding.calculateTopPadding()),
      ) { r ->
        if (r == null) Scanning()
        else Results(r, bottomPadding = padding.calculateBottomPadding(), onSelect = { selected = it }, onFreeze = vm::freeze)
      }
    }
  }

  selected?.let { app ->
    val revision by vm.actions.revision.collectAsStateWithLifecycle()
    RiskyAppSheet(
      app,
      onDismiss = { selected = null },
      onTrust = { vm.setTrust(app.pkg, it) },
      maintenanceActions = rememberMaintenanceActions(app.pkg, revision),
      onAction = { vm.perform(app, it) },
    )
  }
}

/** 风险应用详情：为什么可疑 + 处理；体检页和设置向导共用 */
@Composable
fun RiskyAppSheet(
  app: RiskyApp,
  onDismiss: () -> Unit,
  onTrust: (Trust?) -> Unit,
  maintenanceActions: List<AppAction> = emptyList(),
  onAction: (AppAction) -> Unit = {},
) {
  val context = LocalContext.current
  AppSheet(
    pkg = app.pkg,
    label = app.label,
    subtitle = app.level.label,
    trust = app.trust,
    installed = true,
    onDismiss = onDismiss,
    onTrust = onTrust,
    onUninstall = { SettingsNavigator.requestUninstall(context, app.pkg) },
    onDetails = { SettingsNavigator.openAppDetails(context, app.pkg) },
    maintenanceActions = maintenanceActions,
    onAction = onAction,
  ) {
    SheetSectionTitle("为什么可疑")
    app.signals.forEach { s ->
      val detail = if (s == RiskSignal.HARASSMENT) "近 7 天 ${app.harassCount} 次。${s.detail}" else s.detail
      SheetInfoRow(s.icon, s.title, detail)
    }
  }
}

@Composable
private fun Scanning() {
  Column(
    Modifier.fillMaxSize().padding(bottom = 96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(contentAlignment = Alignment.Center) {
      CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 4.dp)
      Icon(Icons.Rounded.HealthAndSafety, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text("正在检查已安装的应用…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun Results(
  r: ScanResult,
  bottomPadding: androidx.compose.ui.unit.Dp,
  onSelect: (RiskyApp) -> Unit,
  onFreeze: (List<String>) -> Unit,
) {
  val maintaining = LocalContext.current.container.maintenance.endsAt.collectAsStateWithLifecycle().value != null
  LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item(key = "summary") { ScanSummary(r,ItemModifier.animateItem()) }

    if (r.apps.isNotEmpty()) {
      sectionHeader("appsHeader", "风险应用", "点一下查看原因并处理")
      itemsIndexed(r.apps, key = { _, a -> "app-${a.pkg}" }, contentType = { _, _ -> "app" }) { i, app ->
        RiskyAppItem(app, segmentShape(i, r.apps.size), onClick = { onSelect(app) }, modifier = ItemModifier.animateItem())
      }
    }

    sectionHeader("checksHeader", "系统检查")
    itemsIndexed(r.checks, key = { _, c -> "check-${c.title}" }, contentType = { _, _ -> "check" }) { i, check ->
      CheckItem(
        check,
        segmentShape(i, r.checks.size),
        ItemModifier.animateItem(),
        onFreeze = if (maintaining && check.freezable.isNotEmpty()) ({ onFreeze(check.freezable) }) else null,
      )
    }
  }
}

@Composable
fun ScanSummary(r: ScanResult, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val ext = AppTheme.extendedColors
  val high = r.apps.count { it.level == RiskLevel.HIGH }
  val medium = r.apps.size - high
  val (container, onContainer, icon) =
    when {
      high > 0 -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Rounded.Error)
      medium > 0 -> Triple(ext.warningContainer, ext.onWarningContainer, Icons.Rounded.ReportProblem)
      else -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Rounded.VerifiedUser)
    }
  val title =
    when {
      high > 0 && medium > 0 -> "$high 个高风险，$medium 个需留意"
      high > 0 -> "发现 $high 个高风险应用"
      medium > 0 -> "发现 $medium 个需留意的应用"
      else -> "没有发现可疑应用"
    }
  Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = container, contentColor = onContainer) {
    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
      AnimatedContent(icon, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }, label = "summaryIcon") {
        Icon(it, contentDescription = null, modifier = Modifier.size(40.dp))
      }
      Spacer(Modifier.width(16.dp))
      Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("检查了 ${r.scannedCount} 个应用", style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
fun RiskyAppItem(app: RiskyApp, shape: Shape, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      modifier = Modifier.clickable(onClick = onClick),
      colors = groupListItemColors(),
      leadingContent = { AppIcon(app.pkg) },
      headlineContent = { Text(app.label) },
      supportingContent = { Text(app.signals.joinToString("、") { it.title }, maxLines = 1) },
      trailingContent = { LevelLabel(app) },
    )
  }
}

@Composable
private fun LevelLabel(app: RiskyApp) {
  when {
    app.trust == Trust.FLAGGED -> IconLabel(Icons.Rounded.Error, "已拦截", MaterialTheme.colorScheme.error)
    app.level == RiskLevel.HIGH -> IconLabel(Icons.Rounded.Error, app.level.label, MaterialTheme.colorScheme.error)
    else -> IconLabel(Icons.Rounded.ReportProblem, app.level.label, AppTheme.extendedColors.warning)
  }
}

@Composable
fun CheckItem(check: SystemCheck, shape: Shape, modifier: Modifier = Modifier, onFreeze: (() -> Unit)? = null) {
  val color: Color = if (check.ok) MaterialTheme.colorScheme.primary else AppTheme.extendedColors.warning
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      colors = groupListItemColors(),
      leadingContent = {
        TonalIcon(
          if (check.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ReportProblem,
          container = MaterialTheme.colorScheme.surfaceContainerHighest,
          content = color,
        )
      },
      headlineContent = { Text(check.title) },
      supportingContent = { Text(check.detail) },
      trailingContent = onFreeze?.let { { FilledTonalButton(onClick = it) { Text("冻结") } } },
    )
  }
}

internal val RiskSignal.icon: ImageVector
  get() =
    when (this) {
      RiskSignal.HARASSMENT -> Icons.Rounded.Campaign
      RiskSignal.ACCESSIBILITY -> Icons.Rounded.Accessibility
      RiskSignal.DEVICE_ADMIN -> Icons.Rounded.AdminPanelSettings
      RiskSignal.HIDDEN_ICON -> Icons.Rounded.VisibilityOff
      RiskSignal.TOOL_NAME -> Icons.Rounded.CleaningServices
      RiskSignal.OVERLAY -> Icons.Rounded.Layers
      RiskSignal.INSTALL_PACKAGES -> Icons.Rounded.InstallMobile
      RiskSignal.NOTIFICATION_ACCESS -> Icons.Rounded.NotificationsActive
      RiskSignal.SHIZUKU -> Icons.Rounded.Key
      RiskSignal.BOOT_START -> Icons.Rounded.PowerSettingsNew
    }
