package com.elderguard.app.ui.family

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elderguard.app.AppContainer
import com.elderguard.app.R
import com.elderguard.app.container
import com.elderguard.app.core.Trust
import com.elderguard.app.guard.GuardAccessibilityService
import com.elderguard.app.ui.common.AlignedLargeTopAppBar
import com.elderguard.app.ui.common.AppIcon
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportData(
  val from: Long,
  val to: Long,
  val guardRunning: Boolean,
  val total: Int,
  val handled: Int,
  val flagged: Int,
  val top: List<CulpritRow>,
)

class ReportViewModel(private val c: AppContainer) : ViewModel() {
  val report =
    combine(c.guardRepository.culpritRanking(), c.guardRepository.verdicts, c.guardRepository.weekHandled(), GuardAccessibilityService.running) {
        ranking,
        verdicts,
        handled,
        running ->
        val now = System.currentTimeMillis()
        ReportData(
          from = now - 6 * DAY_MS,
          to = now,
          guardRunning = running && c.permissions.isAccessibilityEnabled(),
          total = ranking.sumOf { it.count },
          handled = handled,
          flagged = verdicts.count { it.value == Trust.FLAGGED },
          top =
            ranking
              .filter { verdicts[it.culpritPkg] != Trust.TRUSTED }
              .take(TOP_COUNT)
              .map { CulpritRow(it, c.appInfo.label(it.culpritPkg), verdicts[it.culpritPkg], c.appInfo.isInstalled(it.culpritPkg), false) },
        )
      }
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  private companion object {
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val TOP_COUNT = 5
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val container = context.container
  val vm = viewModel { ReportViewModel(container) }
  val report by vm.report.collectAsStateWithLifecycle()
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  val scope = rememberCoroutineScope()
  // 报告卡片同时画到屏幕和这个图层上，分享时直接从图层取图
  val layer = rememberGraphicsLayer()

  BoxWithConstraints(modifier.fillMaxSize()) {
    Scaffold(
      Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
      topBar = { AlignedLargeTopAppBar("分享报告", onBack, maxWidth, ContentMaxWidth + 32.dp, scrollBehavior) },
      floatingActionButton = {
        AnimatedVisibility(report != null, enter = fadeIn() + scaleIn()) {
          ExtendedFloatingActionButton(
            onClick = {
              scope.launch {
                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                shareImage(context, bitmap)
              }
            },
            icon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            text = { Text("分享图片") },
          )
        }
      },
    ) { padding ->
      val r = report ?: return@Scaffold
      Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          "发到家人群，让大家了解长辈手机的情况",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 16.dp),
        )
        Box(
          Modifier.drawWithContent {
              layer.record { this@drawWithContent.drawContent() }
              drawLayer(layer)
            }
            // 图片带不透明底色，避免在聊天软件里圆角外变黑
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(32.dp))
            .padding(16.dp)
        ) {
          ReportCard(r)
        }
      }
    }
  }
}

@Composable
private fun ReportCard(r: ReportData) {
  val scheme = MaterialTheme.colorScheme
  Surface(Modifier.width(360.dp), shape = MaterialTheme.shapes.extraLarge, color = scheme.surface) {
    Column {
      Column(Modifier.fillMaxWidth().background(scheme.primary).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(painterResource(R.drawable.ic_shield_check), contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(36.dp))
          Spacer(Modifier.width(12.dp))
          Column {
            Text("长辈守护", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary.copy(alpha = 0.8f))
            Text("手机安全周报", style = MaterialTheme.typography.headlineSmall, color = scheme.onPrimary)
          }
        }
        Spacer(Modifier.height(16.dp))
        Text("${dayFormat.format(Date(r.from))} – ${dayFormat.format(Date(r.to))}", style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimary.copy(alpha = 0.8f))
      }

      Column(Modifier.padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            if (r.guardRunning) Icons.Rounded.VerifiedUser else Icons.Rounded.GppBad,
            contentDescription = null,
            tint = if (r.guardRunning) scheme.primary else scheme.error,
          )
          Spacer(Modifier.width(8.dp))
          Text(if (r.guardRunning) "守护正常运行中" else "守护没有运行，请尽快检查", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ReportStat("发现骚扰", r.total, Modifier.weight(1f))
          ReportStat("自动拦截", r.handled, Modifier.weight(1f))
          ReportStat("标记流氓", r.flagged, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Text("骚扰最多的应用", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
        Spacer(Modifier.height(8.dp))
        if (r.top.isEmpty()) {
          Text("这一周很安静，没有发现骚扰", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        } else {
          r.top.forEach { TopApp(it) }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = scheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
          "由「长辈守护」生成 · ${timeFormat.format(Date(r.to))}",
          style = MaterialTheme.typography.bodySmall,
          color = scheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ReportStat(label: String, value: Int, modifier: Modifier = Modifier) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
      Text("$value", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
      Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun TopApp(row: CulpritRow) {
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    AppIcon(row.stat.culpritPkg, 36.dp)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      val note =
        when {
          !row.installed -> "已卸载"
          row.trust == Trust.FLAGGED -> "已标记流氓，弹窗自动挡掉"
          else -> row.stat.eventTypes.joinToString("、") { it.label }
        }
      Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    Spacer(Modifier.width(12.dp))
    Text("${row.stat.count} 次", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private val dayFormat = SimpleDateFormat("M月d日", Locale.CHINA)
private val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

private suspend fun shareImage(context: Context, bitmap: Bitmap) {
  val file =
    withContext(Dispatchers.IO) {
      File(context.cacheDir, "share").apply { mkdirs() }.resolve("elderguard-report.png").also { f ->
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      }
    }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
  val send =
    Intent(Intent.ACTION_SEND)
      .setType("image/png")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      // 分享面板里显示图片预览
      .apply { clipData = ClipData.newRawUri(null, uri) }
  context.startActivity(Intent.createChooser(send, "分享报告").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
