package com.elderguard.app.ui.elder

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elderguard.app.AppContainer
import com.elderguard.app.container
import com.elderguard.app.guard.GuardAccessibilityService
import com.elderguard.app.theme.AppTheme
import com.elderguard.app.theme.Motion
import com.elderguard.app.theme.Theme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class GuardStatus(val title: String, val hint: String, val icon: ImageVector) {
  ON("守护中", "放心用手机，快应用和家人标记的广告会被自动挡掉", Icons.Rounded.VerifiedUser),
  STOPPED("守护已暂停", "手机把守护关掉了，请让家人帮忙打开", Icons.Rounded.GppMaybe),
  OFF("守护未开启", "请让家人帮忙打开守护", Icons.Rounded.GppBad),
}

data class ElderHomeState(val status: GuardStatus, val todayCount: Int, val todayHandled: Int)

class ElderHomeViewModel(private val c: AppContainer) : ViewModel() {
  private val accessibilityEnabled = MutableStateFlow(c.permissions.isAccessibilityEnabled())

  val state =
    combine(
        accessibilityEnabled,
        GuardAccessibilityService.running,
        c.guardRepository.todayCount(),
        c.guardRepository.todayHandled(),
      ) { enabled, running, count, handled ->
        val status =
          when {
            enabled && running -> GuardStatus.ON
            enabled -> GuardStatus.STOPPED
            else -> GuardStatus.OFF
          }
        ElderHomeState(status, count, handled)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  fun refresh() {
    accessibilityEnabled.value = c.permissions.isAccessibilityEnabled()
  }
}

@Composable
fun ElderHomeScreen(onFamilyEntry: () -> Unit, modifier: Modifier = Modifier) {
  val container = LocalContext.current.container
  val vm = viewModel { ElderHomeViewModel(container) }
  val state by vm.state.collectAsStateWithLifecycle()
  LifecycleResumeEffect(Unit) {
    vm.refresh()
    onPauseOrDispose {}
  }
  state?.let { ElderHomeContent(it, onFamilyEntry, modifier) }
}

private data class StatusColors(val accent: Color, val container: Color, val onContainer: Color)

@Composable
private fun GuardStatus.colors(): StatusColors {
  val scheme = MaterialTheme.colorScheme
  val ext = AppTheme.extendedColors
  return when (this) {
    GuardStatus.ON -> StatusColors(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer)
    GuardStatus.STOPPED -> StatusColors(ext.warning, ext.warningContainer, ext.onWarningContainer)
    GuardStatus.OFF -> StatusColors(scheme.error, scheme.errorContainer, scheme.onErrorContainer)
  }
}

@Composable
private fun ElderHomeContent(state: ElderHomeState, onFamilyEntry: () -> Unit, modifier: Modifier = Modifier) {
  // 首次进入：主体缩放淡入，统计卡片自下而上
  val appear = remember { MutableTransitionState(false).apply { targetState = true } }

  Scaffold(modifier) { padding ->
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
      val wide = maxWidth > 720.dp && maxWidth > maxHeight
      val hero =
        @Composable {
          AnimatedVisibility(
            appear,
            enter = fadeIn(tween(Motion.DURATION_LONG)) + scaleIn(tween(Motion.DURATION_LONG, easing = Motion.EmphasizedDecelerate), 0.85f),
          ) {
            StatusHero(state.status)
          }
        }
      val details =
        @Composable {
          AnimatedVisibility(
            appear,
            enter =
              fadeIn(tween(Motion.DURATION_LONG, delayMillis = 120)) +
                slideInVertically(tween(Motion.DURATION_LONG, delayMillis = 120, easing = Motion.EmphasizedDecelerate)) { it / 4 },
          ) {
            Details(state, onFamilyEntry)
          }
        }
      if (wide) {
        // 两栏定宽、整体居中，左右留白对称
        Row(
          Modifier.fillMaxSize().padding(horizontal = 48.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(72.dp, Alignment.CenterHorizontally),
        ) {
          Box(Modifier.width(400.dp), contentAlignment = Alignment.Center) { hero() }
          Box(Modifier.width(400.dp)) { details() }
        }
      } else {
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          hero()
          Spacer(Modifier.height(40.dp))
          Box(Modifier.widthIn(max = 480.dp)) { details() }
        }
      }
    }
  }
}

@Composable
private fun StatusHero(status: GuardStatus) {
  val colors = status.colors()
  val accent by animateColorAsState(colors.accent, tween(Motion.DURATION_LONG), label = "accent")
  val container by animateColorAsState(colors.container, tween(Motion.DURATION_LONG), label = "container")

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
      if (status == GuardStatus.ON) PulseRings(accent)
      Surface(Modifier.size(144.dp), shape = CircleShape, color = container) {
        Box(contentAlignment = Alignment.Center) {
          AnimatedContent(
            targetState = status,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f)) },
            label = "statusIcon",
          ) {
            Icon(it.icon, contentDescription = null, tint = accent, modifier = Modifier.size(72.dp))
          }
        }
      }
    }
    AnimatedContent(targetState = status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "statusText") {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(it.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
          it.hint,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/** 守护中时，盾牌外两圈向外扩散的光环 */
@Composable
private fun PulseRings(color: Color) {
  val transition = rememberInfiniteTransition(label = "pulse")
  val progress by
    transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "pulseProgress")
  Canvas(Modifier.fillMaxSize()) {
    val base = 72.dp.toPx()
    val max = size.minDimension / 2
    for (i in 0 until 2) {
      val p = (progress + i * 0.5f) % 1f
      drawCircle(color.copy(alpha = 0.22f * (1f - p)), radius = base + (max - base) * p)
    }
  }
}

@Composable
private fun Details(state: ElderHomeState, onFamilyEntry: () -> Unit) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      StatCard("今日拦截", state.todayHandled, Modifier.weight(1f))
      StatCard("今日发现", state.todayCount, Modifier.weight(1f))
    }
    Spacer(Modifier.height(24.dp))
    if (state.status != GuardStatus.ON) {
      Button(onClick = onFamilyEntry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("请家人修复") }
      Spacer(Modifier.height(8.dp))
    }
    TextButton(onClick = onFamilyEntry) {
      Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.size(8.dp))
      Text("家人入口")
    }
  }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      AnimatedContent(
        targetState = value,
        transitionSpec = {
          val up = targetState > initialState
          (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
        },
        label = "statValue",
      ) {
        Text("$it", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
      }
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ElderHomeOnPreview() {
  Theme { ElderHomeContent(ElderHomeState(GuardStatus.ON, 5, 3), {}) }
}

@Preview(showBackground = true, widthDp = 1200, heightDp = 720)
@Composable
private fun ElderHomeOffWidePreview() {
  Theme { ElderHomeContent(ElderHomeState(GuardStatus.OFF, 0, 0), {}) }
}
