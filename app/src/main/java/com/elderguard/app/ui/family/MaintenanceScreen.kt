package com.elderguard.app.ui.family

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Shape
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
import com.elderguard.app.core.Trust
import com.elderguard.app.privileged.PrivilegeStatus
import com.elderguard.app.privileged.ShizukuExecutor
import com.elderguard.app.theme.Motion
import com.elderguard.app.ui.common.AlignedLargeTopAppBar
import com.elderguard.app.ui.common.EmptyHint
import com.elderguard.app.ui.common.SheetInfoRow
import com.elderguard.app.ui.common.TonalIcon
import com.elderguard.app.ui.common.clockTime
import com.elderguard.app.ui.common.groupListItemColors
import com.elderguard.app.ui.common.segmentShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MaintenanceState(
  val status: PrivilegeStatus,
  val endsAt: Long?,
  /** 维护中值得处理的应用：被标记为流氓或近 7 天有骚扰、还没被冻结 */
  val pending: List<CulpritRow>,
)

class MaintenanceViewModel(private val c: AppContainer) : ViewModel() {
  val actions = ActionRunner(c, viewModelScope)
  private val _busy = MutableStateFlow(false)
  val busy = _busy

  private val pending =
    combine(c.guardRepository.culpritRanking(), c.guardRepository.verdicts, actions.revision) { ranking, verdicts, _ ->
        val ranked = ranking.map { it.culpritPkg }.toSet()
        val flaggedIdle =
          verdicts.filter { it.value == Trust.FLAGGED && it.key !in ranked }.keys.map { com.elderguard.app.data.CulpritStat(it, 0, 0) }
        (ranking + flaggedIdle)
          .filter { verdicts[it.culpritPkg] != Trust.TRUSTED && c.appInfo.isInstalled(it.culpritPkg) && !c.appInfo.isFrozen(it.culpritPkg) }
          .map { CulpritRow(it, c.appInfo.label(it.culpritPkg), verdicts[it.culpritPkg], installed = true, frozen = false) }
          .sortedByDescending { it.trust == Trust.FLAGGED }
      }
      .flowOn(Dispatchers.Default)

  val state =
    combine(c.privileged.status, c.maintenance.endsAt, pending) { status, endsAt, rows -> MaintenanceState(status, endsAt, rows) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun refresh() = c.privileged.refresh()

  fun requestPermission() = c.privileged.requestPermission()

  fun start() = withBusy { c.maintenance.start() }

  fun end() = withBusy { c.maintenance.end() }

  fun setTrust(pkg: String, trust: Trust?) = viewModelScope.launch { c.guardRepository.setTrust(pkg, trust) }

  private fun withBusy(block: suspend () -> Unit) {
    if (_busy.value) return
    viewModelScope.launch {
      _busy.value = true
      try {
        block()
      } finally {
        _busy.value = false
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val container = context.container
  val vm = viewModel { MaintenanceViewModel(container) }
  val state by vm.state.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  LifecycleResumeEffect(Unit) {
    vm.refresh()
    onPauseOrDispose {}
  }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  val snackbar = remember { SnackbarHostState() }
  ShowMessages(vm.actions.messages, snackbar)
  var selected by remember { mutableStateOf<CulpritRow?>(null) }

  BoxWithConstraints(modifier.fillMaxSize()) {
    Scaffold(
      Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
      topBar = { AlignedLargeTopAppBar("维护模式", onBack, maxWidth, ContentMaxWidth + 32.dp, scrollBehavior) },
      snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
      val s = state ?: return@Scaffold
      val active = s.endsAt != null
      LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
          PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        item(key = "hero") {
          Hero(s, busy, onStart = vm::start, onEnd = vm::end, modifier = ItemModifier.animateItem())
        }

        if (active) {
          sectionHeader("pendingHeader", "待处理的应用", "点一下即可冻结、卸载或收回权限")
          if (s.pending.isEmpty()) {
            item(key = "pendingEmpty") { EmptyHint("没有需要处理的应用", ItemModifier.animateItem()) }
          } else {
            itemsIndexed(s.pending, key = { _, row -> "pending-${row.stat.culpritPkg}" }) { i, row ->
              CulpritItem(row, segmentShape(i, s.pending.size), onClick = { selected = row }, modifier = ItemModifier.animateItem())
            }
          }
        } else {
          sectionHeader("stepsHeader", "准备", "只需在第一次使用时完成")
          val steps = steps(s.status)
          itemsIndexed(steps, key = { _, step -> "step-${step.title}" }) { i, step ->
            StepItem(
              index = i + 1,
              step = step,
              shape = segmentShape(i, steps.size),
              onAction = {
                when (i) {
                  0 -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                  1 -> context.packageManager.getLaunchIntentForPackage(ShizukuExecutor.SHIZUKU_PACKAGE)?.let(context::startActivity)
                  else -> vm.requestPermission()
                }
              },
              modifier = ItemModifier.animateItem(),
            )
          }
        }

        sectionHeader("safetyHeader", "用完即关")
        item(key = "safety") {
          Surface(ItemModifier.animateItem(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(vertical = 8.dp)) {
              SheetInfoRow(Icons.Rounded.WifiOff, "结束时自动关闭无线调试", "避免同一网络里的人连接这台手机")
              SheetInfoRow(Icons.Rounded.Timer, "30 分钟没有操作自动结束", "忘了结束也没关系")
              SheetInfoRow(Icons.Rounded.GppGood, "平时拒绝一切 Shizuku 授权", "不在维护时，有应用索要高级权限会被自动挡掉并记录")
            }
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
}

@Composable
private fun Hero(s: MaintenanceState, busy: Boolean, onStart: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val active = s.endsAt != null
  Surface(
    modifier,
    shape = MaterialTheme.shapes.extraLarge,
    color = if (active) scheme.primaryContainer else scheme.surfaceContainerHigh,
    contentColor = if (active) scheme.onPrimaryContainer else scheme.onSurface,
  ) {
    Column(Modifier.padding(24.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        TonalIcon(
          Icons.Rounded.Build,
          container = if (active) scheme.primary else scheme.secondaryContainer,
          content = if (active) scheme.onPrimary else scheme.onSecondaryContainer,
          size = 48.dp,
        )
        Spacer(Modifier.width(16.dp))
        AnimatedContent(active, transitionSpec = { fadeIn(tween(Motion.DURATION_MEDIUM)) togetherWith fadeOut() }, label = "heroText") {
          Column {
            Text(if (it) "正在维护" else "直接处理流氓应用", style = MaterialTheme.typography.titleLarge)
            Text(
              if (s.endsAt != null) "${clockTime(s.endsAt)} 自动结束，每次操作都会顺延"
              else "临时获得高级权限，直接冻结、卸载流氓应用，收回它的悬浮窗、通知和后台运行",
              style = MaterialTheme.typography.bodyMedium,
              color = if (it) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
          }
        }
      }
      Spacer(Modifier.height(20.dp))
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        AnimatedContent(
          targetState = Triple(active, busy, s.status == PrivilegeStatus.READY),
          transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f)) },
          label = "heroButton",
        ) { (isActive, isBusy, ready) ->
          when {
            isBusy -> CircularProgressIndicator(Modifier.size(40.dp).padding(4.dp))
            isActive -> FilledTonalButton(onClick = onEnd) { Text("结束维护") }
            else ->
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!ready) {
                  Text("先完成下面的准备", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                Button(onClick = onStart, enabled = ready) { Text("开始维护") }
              }
          }
        }
      }
    }
  }
}

private data class Step(val title: String, val supporting: String, val done: Boolean, val current: Boolean, val action: String)

private fun steps(status: PrivilegeStatus): List<Step> {
  val installed = status != PrivilegeStatus.NOT_INSTALLED
  val running = status == PrivilegeStatus.NEEDS_PERMISSION || status == PrivilegeStatus.READY
  val granted = status == PrivilegeStatus.READY
  return listOf(
    Step("安装 Shizuku", "开源工具，让本 App 临时获得 adb 权限", installed, !installed, "去下载"),
    Step(
      "启动 Shizuku",
      "打开开发者选项里的「无线调试」，再在 Shizuku 里按提示启动；手机重启后需要重新启动",
      running,
      installed && !running,
      "打开",
    ),
    Step("授权给长辈守护", "在弹出的窗口里点「允许」，只需一次", granted, running && !granted, "授权"),
  )
}

@Composable
private fun StepItem(index: Int, step: Step, shape: Shape, onAction: () -> Unit, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = scheme.surfaceContainerLow) {
    ListItem(
      colors = groupListItemColors(),
      leadingContent = {
        AnimatedContent(step.done, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }, label = "stepIcon") { done ->
          Surface(
            Modifier.size(40.dp),
            shape = CircleShape,
            color = if (done || step.current) scheme.primary else scheme.surfaceContainerHighest,
            contentColor = if (done || step.current) scheme.onPrimary else scheme.onSurfaceVariant,
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (done) Icon(Icons.Rounded.Check, contentDescription = "已完成", modifier = Modifier.size(22.dp))
              else Text("$index", style = MaterialTheme.typography.titleMedium)
            }
          }
        }
      },
      headlineContent = { Text(step.title) },
      supportingContent = { Text(step.supporting) },
      trailingContent =
        if (step.current) {
          { Button(onClick = onAction) { Text(step.action) } }
        } else null,
    )
  }
}

private const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
