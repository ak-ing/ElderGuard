package com.elderguard.app.ui.family

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elderguard.app.container
import com.elderguard.app.core.GuardMode
import com.elderguard.app.selftest.CheckId
import com.elderguard.app.selftest.CheckResult
import com.elderguard.app.selftest.CheckStatus
import com.elderguard.app.selftest.Handler
import com.elderguard.app.selftest.LaunchTarget
import com.elderguard.app.selftest.SelfTester
import com.elderguard.app.selftest.label
import com.elderguard.app.ui.common.AlignedLargeTopAppBar
import com.elderguard.app.ui.common.AppIcon
import com.elderguard.app.ui.common.BadgeTone
import com.elderguard.app.ui.common.EmptyHint
import com.elderguard.app.ui.common.StatusBadge
import com.elderguard.app.ui.common.TonalIcon
import com.elderguard.app.ui.common.groupListItemColors
import com.elderguard.app.ui.common.segmentShape
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelfTestState(
  val results: List<CheckResult> = CheckId.entries.map { CheckResult(it) },
  val running: Boolean = false,
  val finished: Boolean = false,
  val quickApps: List<Handler> = emptyList(),
)

class SelfTestViewModel(private val tester: SelfTester) : ViewModel() {
  private val _state = MutableStateFlow(SelfTestState())
  val state = _state.asStateFlow()

  /** 自检页是否在前台：打开快应用后要等守护把它挡掉、回到本页再继续 */
  val resumed = MutableStateFlow(false)

  init {
    viewModelScope.launch(Dispatchers.Default) {
      val quickApps = tester.quickAppHandlers()
      _state.update { it.copy(quickApps = quickApps) }
    }
  }

  fun start() {
    if (_state.value.running) return
    _state.update { SelfTestState(running = true, quickApps = it.quickApps) }
    viewModelScope.launch {
      val guardOk = tester.guardRunning()
      set(CheckId.GUARD, if (guardOk) CheckStatus.PASSED else CheckStatus.FAILED, if (guardOk) "无障碍服务已连接" else "无障碍服务没有运行，请在家人页开启")
      set(
        CheckId.NOTIFICATION,
        when {
          !tester.notificationEnabled() -> CheckStatus.SKIPPED
          tester.notificationConnected() -> CheckStatus.PASSED
          else -> CheckStatus.FAILED
        },
        when {
          !tester.notificationEnabled() -> "未开启通知使用权（可选）"
          tester.notificationConnected() -> "已连接，可以发现可疑通知"
          else -> "已开启但没有连上，可能被系统省电策略限制"
        },
      )
      if (tester.blockQuickApps()) {
        runLaunch(CheckId.QUICK_APP, guardOk, tester.quickAppTarget()) { "这台手机没有名单里的快应用引擎，或已冻结" }
      } else {
        set(CheckId.QUICK_APP, CheckStatus.SKIPPED, "家人关掉了「挡掉快应用」")
      }
      _state.update { it.copy(running = false, finished = true) }
    }
  }

  suspend fun report(): String = tester.buildReport(_state.value.results)

  /** 延时打开 [target]，模拟广告在没有点击时自动跳转，看守护能否挡掉 */
  private suspend fun runLaunch(id: CheckId, guardOk: Boolean, target: LaunchTarget?, missing: () -> String) {
    if (!guardOk) return set(id, CheckStatus.SKIPPED, "守护没有运行，无法测试")
    if (target == null) return set(id, CheckStatus.SKIPPED, missing())
    for (s in COUNTDOWN_S downTo 1) {
      set(id, CheckStatus.RUNNING, "$s 秒后打开「${target.label}」，请不要触碰屏幕")
      delay(1_000)
    }
    val since = System.currentTimeMillis()
    runCatching { tester.launch(target) }
      .onFailure {
        return set(id, CheckStatus.FAILED, "打不开「${target.label}」：${it.message}")
      }
    set(id, CheckStatus.RUNNING, "已打开「${target.label}」，等待守护处理…")
    val event = tester.awaitEvent(target.pkg, since, WAIT_MS)
    when {
      event == null -> set(id, CheckStatus.FAILED, "${WAIT_MS / 1000} 秒内守护没有发现「${target.label}」，请手动返回本页")
      event.handled ->
        set(id, CheckStatus.PASSED, "已自动挡掉「${target.label}」，用时 ${String.format(Locale.CHINA, "%.1f", (event.time - since) / 1000f)} 秒")
      tester.guardMode() == GuardMode.RECORD_ONLY -> set(id, CheckStatus.FAILED, "发现了，但当前是「仅记录」方式，没有挡掉")
      else -> set(id, CheckStatus.FAILED, "发现了但没有挡掉（判为${event.type.label}）")
    }
    // 回到本页再继续下一项；没挡掉时需要家人手动返回
    resumed.first { it }
    delay(SETTLE_MS)
    tester.clearTestEvents(target.pkg, since)
  }

  private fun set(id: CheckId, status: CheckStatus, detail: String) =
    _state.update { s -> s.copy(results = s.results.map { if (it.id == id) CheckResult(id, status, detail) else it }) }

  private companion object {
    const val COUNTDOWN_S = 4
    const val WAIT_MS = 8_000L
    const val SETTLE_MS = 800L
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val container = context.container
  val vm = viewModel { SelfTestViewModel(SelfTester(context.applicationContext, container)) }
  val state by vm.state.collectAsStateWithLifecycle()
  LifecycleResumeEffect(Unit) {
    vm.resumed.value = true
    onPauseOrDispose { vm.resumed.value = false }
  }
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  val scope = rememberCoroutineScope()

  BoxWithConstraints(modifier.fillMaxSize()) {
    Scaffold(
      Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
      topBar = {
        AlignedLargeTopAppBar("守护自检", onBack, maxWidth, ContentMaxWidth + 32.dp, scrollBehavior) {
          AnimatedVisibility(state.finished, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = vm::start) { Icon(Icons.Rounded.Refresh, contentDescription = "重新自检") }
          }
        }
      },
      floatingActionButton = {
        AnimatedVisibility(!state.running, Modifier.padding(end = fabEndPadding(maxWidth)), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
          ExtendedFloatingActionButton(
            onClick = { if (state.finished) scope.launch { shareReport(context, vm.report()) } else vm.start() },
            icon = { Icon(if (state.finished) Icons.Rounded.Share else Icons.Rounded.PlayArrow, contentDescription = null) },
            text = { Text(if (state.finished) "分享报告" else "开始自检") },
          )
        }
      },
    ) { padding ->
      LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
          PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        item(key = "intro") { Intro(ItemModifier.animateItem()) }

        sectionHeader("checksHeader", "检测项")
        itemsIndexed(state.results, key = { _, r -> "check-${r.id}" }, contentType = { _, _ -> "check" }) { i, r ->
          ResultItem(r, segmentShape(i, state.results.size), ItemModifier.animateItem())
        }

        sectionHeader("quickHeader", "打开快应用的应用", "这台手机上处理快应用链接的应用")
        handlerItems("quick", state.quickApps, "没有发现快应用引擎")
      }
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.handlerItems(key: String, list: List<Handler>, empty: String) {
  if (list.isEmpty()) {
    item(key = "$key-empty", contentType = "empty") { EmptyHint(empty, ItemModifier.animateItem()) }
  } else {
    itemsIndexed(list, key = { _, h -> "$key-${h.pkg}" }, contentType = { _, _ -> "handler" }) { i, h -> HandlerItem(h, segmentShape(i, list.size), ItemModifier.animateItem()) }
  }
}

@Composable
private fun Intro(modifier: Modifier = Modifier) {
  Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
      TonalIcon(Icons.AutoMirrored.Rounded.FactCheck, container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary)
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
        Text("检查守护在这台手机上是否有效", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
          "会自动打开一次快应用，看守护能不能挡掉。开始后请不要触碰屏幕，大约 20 秒。完成后可以把报告发给开发者。",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

@Composable
private fun ResultItem(r: CheckResult, shape: Shape, modifier: Modifier = Modifier) {
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      colors = groupListItemColors(),
      leadingContent = {
        AnimatedContent(r.status, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }, label = "status") { StatusIcon(it) }
      },
      headlineContent = { Text(r.id.title) },
      supportingContent = { Text(r.detail) },
      trailingContent = {
        if (r.status != CheckStatus.PENDING && r.status != CheckStatus.RUNNING) {
          StatusBadge(
            r.status.label,
            when (r.status) {
              CheckStatus.PASSED -> BadgeTone.GOOD
              CheckStatus.FAILED -> BadgeTone.BAD
              else -> BadgeTone.NEUTRAL
            },
          )
        }
      },
    )
  }
}

@Composable
private fun StatusIcon(status: CheckStatus) {
  val scheme = MaterialTheme.colorScheme
  when (status) {
    CheckStatus.RUNNING ->
      Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
    CheckStatus.PASSED -> TonalIcon(Icons.Rounded.CheckCircle, container = scheme.primaryContainer, content = scheme.onPrimaryContainer)
    CheckStatus.FAILED -> TonalIcon(Icons.Rounded.Error, container = scheme.errorContainer, content = scheme.onErrorContainer)
    CheckStatus.SKIPPED ->
      TonalIcon(Icons.Rounded.RemoveCircleOutline, container = scheme.surfaceContainerHighest, content = scheme.onSurfaceVariant)
    CheckStatus.PENDING -> TonalIcon(Icons.Rounded.Schedule, container = scheme.surfaceContainerHighest, content = scheme.onSurfaceVariant)
  }
}

@Composable
private fun HandlerItem(h: Handler, shape: Shape, modifier: Modifier = Modifier) {
  Surface(modifier.padding(vertical = 1.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    ListItem(
      colors = groupListItemColors(),
      leadingContent = { AppIcon(h.pkg) },
      headlineContent = { Text(h.label) },
      supportingContent = { Text(h.pkg, maxLines = 1) },
      trailingContent = {
        if (h.inRules) StatusBadge("会拦截", BadgeTone.GOOD) else StatusBadge("不在名单", BadgeTone.WARN)
      },
    )
  }
}

private suspend fun shareReport(context: Context, text: String) {
  val file =
    withContext(Dispatchers.IO) {
      File(context.cacheDir, "share").apply { mkdirs() }.resolve("elderguard-selftest.txt").also { it.writeText(text) }
    }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
  val send =
    Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .putExtra(Intent.EXTRA_SUBJECT, "长辈守护自检报告")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  context.startActivity(Intent.createChooser(send, "分享自检报告").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
