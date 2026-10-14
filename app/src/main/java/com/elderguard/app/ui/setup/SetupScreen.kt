package com.elderguard.app.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elderguard.app.AppContainer
import com.elderguard.app.container
import com.elderguard.app.core.GuardMode
import com.elderguard.app.system.PermissionKind
import com.elderguard.app.system.PermissionState
import com.elderguard.app.theme.Motion
import com.elderguard.app.ui.common.CenteredContent
import com.elderguard.app.ui.common.GroupCard
import com.elderguard.app.ui.common.PIN_LENGTH
import com.elderguard.app.ui.common.PermissionRow
import com.elderguard.app.ui.common.PinDots
import com.elderguard.app.ui.common.PinPad
import com.elderguard.app.ui.common.TonalIcon
import com.elderguard.app.ui.common.groupListItemColors
import com.elderguard.app.ui.common.rememberPermissionOpener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { INTRO, SET_PIN, CONFIRM_PIN, PERMISSIONS, MODE }

data class SetupState(
  val step: SetupStep = SetupStep.INTRO,
  val pinInput: String = "",
  val firstPin: String = "",
  val pinError: String? = null,
  /** 每次密码出错 +1，驱动抖动动画 */
  val errorTick: Int = 0,
  val permissions: List<PermissionState> = emptyList(),
  val mode: GuardMode = GuardMode.AUTO,
) {
  val requiredGranted: Boolean
    get() = permissions.filter { it.kind.required }.all { it.granted == true }
}

class SetupViewModel(private val c: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow(SetupState(permissions = c.permissions.snapshot()))
  val state = _state.asStateFlow()

  fun goTo(step: SetupStep) = _state.update { it.copy(step = step, pinInput = "", pinError = null) }

  /** 返回上一步；确认密码返回时回到设置密码 */
  fun back(): Boolean {
    val step = _state.value.step
    if (step == SetupStep.INTRO) return false
    goTo(if (step == SetupStep.PERMISSIONS) SetupStep.SET_PIN else SetupStep.entries[step.ordinal - 1])
    return true
  }

  fun onPinInput(value: String) {
    val s = _state.value
    if (value.length < PIN_LENGTH) {
      _state.update { it.copy(pinInput = value, pinError = null) }
      return
    }
    when (s.step) {
      SetupStep.SET_PIN -> _state.update { it.copy(step = SetupStep.CONFIRM_PIN, firstPin = value, pinInput = "") }
      SetupStep.CONFIRM_PIN ->
        if (value == s.firstPin) {
          viewModelScope.launch { c.settings.setPin(value) }
          goTo(SetupStep.PERMISSIONS)
        } else {
          _state.update {
            it.copy(pinInput = "", firstPin = "", step = SetupStep.SET_PIN, pinError = "两次输入不一致，请重新设置", errorTick = it.errorTick + 1)
          }
        }
      else -> Unit
    }
  }

  fun refreshPermissions() = _state.update { it.copy(permissions = c.permissions.snapshot()) }

  fun selectMode(mode: GuardMode) = _state.update { it.copy(mode = mode) }

  fun finish(onDone: () -> Unit) {
    viewModelScope.launch {
      c.settings.setGuardMode(_state.value.mode)
      c.settings.completeSetup()
      onDone()
    }
  }
}

/** 首次设置向导：由家人陪同完成 */
@Composable
fun SetupScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
  val container = LocalContext.current.container
  val vm = viewModel { SetupViewModel(container) }
  val state by vm.state.collectAsStateWithLifecycle()
  LifecycleResumeEffect(Unit) {
    vm.refreshPermissions()
    onPauseOrDispose {}
  }
  BackHandler(enabled = state.step != SetupStep.INTRO) { vm.back() }
  val openPermission = rememberPermissionOpener(onResult = vm::refreshPermissions)
  val progress by
    animateFloatAsState(
      (state.step.ordinal + 1f) / SetupStep.entries.size,
      tween(Motion.DURATION_LONG, easing = Motion.Emphasized),
      label = "progress",
    )

  Scaffold(
    modifier,
    topBar = { SetupTopBar(state.step, progress, onBack = { vm.back() }) },
    bottomBar = {
      BottomAction(state, onNext = {
        when (state.step) {
          SetupStep.INTRO -> vm.goTo(SetupStep.SET_PIN)
          SetupStep.PERMISSIONS -> vm.goTo(SetupStep.MODE)
          SetupStep.MODE -> vm.finish(onFinished)
          else -> Unit
        }
      })
    },
  ) { padding ->
    AnimatedContent(
      targetState = state.step,
      transitionSpec = { Motion.sharedAxisX(forward = targetState.ordinal > initialState.ordinal) },
      contentKey = { if (it == SetupStep.CONFIRM_PIN) SetupStep.SET_PIN else it },
      label = "setupStep",
      modifier = Modifier.padding(padding),
    ) { step ->
      CenteredContent(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        when (step) {
          SetupStep.INTRO -> IntroStep()
          SetupStep.SET_PIN, SetupStep.CONFIRM_PIN -> PinStep(state, vm::onPinInput)
          SetupStep.PERMISSIONS -> PermissionStep(state.permissions, openPermission)
          SetupStep.MODE -> ModeStep(state.mode, vm::selectMode)
        }
      }
    }
  }
}

@Composable
private fun SetupTopBar(step: SetupStep, progress: Float, onBack: () -> Unit) {
  // 与正文同宽、同边距，进度条两端和内容左右边缘对齐
  CenteredContent(
    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .padding(horizontal = 24.dp)
  ) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
      // 图标按钮有 12dp 内边距，左移让箭头与内容左边缘对齐
      Box(Modifier.size(48.dp).offset(x = (-12).dp)) {
        androidx.compose.animation.AnimatedVisibility(step != SetupStep.INTRO, enter = fadeIn(), exit = fadeOut()) {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "上一步") }
        }
      }
      Spacer(Modifier.weight(1f))
      Text("${step.ordinal + 1} / ${SetupStep.entries.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
  }
}

@Composable
private fun BottomAction(state: SetupState, onNext: () -> Unit) {
  val label =
    when (state.step) {
      SetupStep.INTRO -> "开始设置"
      SetupStep.PERMISSIONS -> if (state.requiredGranted) "继续" else "请先开启无障碍服务"
      SetupStep.MODE -> "完成"
      else -> null
    }
  Box(
    Modifier.fillMaxWidth()
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
      .padding(horizontal = 24.dp, vertical = 16.dp),
    contentAlignment = Alignment.Center,
  ) {
    // 输密码时不显示按钮，满位自动进入下一步
    AnimatedVisibility(label != null, enter = fadeIn(), exit = fadeOut()) {
      Button(
        onClick = onNext,
        enabled = state.step != SetupStep.PERMISSIONS || state.requiredGranted,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().height(52.dp),
      ) {
        AnimatedContent(label ?: "", transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "actionLabel") { Text(it) }
      }
    }
  }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, body: String) {
  TonalIcon(
    icon,
    size = 56.dp,
    container = MaterialTheme.colorScheme.primaryContainer,
    content = MaterialTheme.colorScheme.onPrimaryContainer,
  )
  Spacer(Modifier.height(20.dp))
  Text(title, style = MaterialTheme.typography.headlineMedium)
  Spacer(Modifier.height(8.dp))
  Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(24.dp))
}

@Composable
private fun IntroStep() {
  StepHeader(Icons.Rounded.Shield, "长辈守护", "帮家里长辈挡掉自动弹出的广告、快应用和诱导跳转。设置过程请由家人完成。")
  GroupCard {
    Feature(Icons.Rounded.Block, "自动拦截", "快应用、跳转应用商店一出现就自动返回")
    Feature(Icons.AutoMirrored.Rounded.ManageSearch, "找出元凶", "记录每一次没点却自己弹出的界面，定位是哪个 App")
    Feature(Icons.Rounded.CloudOff, "只在本机", "不联网、不上传，代码开源可审计")
  }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
  ListItem(
    colors = groupListItemColors(),
    leadingContent = { TonalIcon(icon) },
    headlineContent = { Text(title) },
    supportingContent = { Text(body) },
  )
}

@Composable
private fun PinStep(state: SetupState, onInput: (String) -> Unit) {
  val haptics = LocalHapticFeedback.current
  LaunchedEffect(state.errorTick) {
    if (state.errorTick > 0) haptics.performHapticFeedback(HapticFeedbackType.Reject)
  }
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    AnimatedContent(state.step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pinTitle") { step ->
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (step == SetupStep.SET_PIN) "设置家人密码" else "再输入一次", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
          if (step == SetupStep.SET_PIN) "$PIN_LENGTH 位数字，用于进入家人页和修改设置" else "确认两次输入一致",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.height(32.dp))
    PinDots(state.pinInput.length, state.errorTick, isError = state.pinError != null && state.pinInput.isEmpty())
    Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
      androidx.compose.animation.AnimatedVisibility(state.pinError != null, enter = fadeIn(), exit = fadeOut()) {
        Text(state.pinError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
      }
    }
    PinPad(state.pinInput, onInput)
  }
}

@Composable
private fun PermissionStep(permissions: List<PermissionState>, onOpen: (PermissionKind) -> Unit) {
  StepHeader(Icons.Rounded.Key, "开启守护权限", "点「开启」会跳到系统设置，打开后返回这里，状态会自动更新。只有无障碍服务是必须的。")
  GroupCard { permissions.forEach { PermissionRow(it, onOpen) } }
}

@Composable
private fun ModeStep(selected: GuardMode, onSelect: (GuardMode) -> Unit) {
  StepHeader(Icons.Rounded.Tune, "选择守护方式", "之后可以在家人页随时修改。")
  GuardModeOptions(selected, onSelect)
}

val GuardMode.icon: ImageVector
  get() =
    when (this) {
      GuardMode.RECORD_ONLY -> Icons.Rounded.Description
      GuardMode.AUTO -> Icons.Rounded.Shield
    }

/** 两张可选卡片，选中态颜色平滑过渡 */
@Composable
fun GuardModeOptions(selected: GuardMode, onSelect: (GuardMode) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    GuardMode.entries.forEach { mode ->
      val isSelected = mode == selected
      val container by
        animateColorAsState(
          if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
          label = "modeContainer",
        )
      val border by
        animateColorAsState(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow, label = "modeBorder")
      Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        border = BorderStroke(2.dp, border),
        modifier = Modifier.fillMaxWidth().selectable(isSelected, role = Role.RadioButton) { onSelect(mode) },
      ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          TonalIcon(mode.icon, container = MaterialTheme.colorScheme.surface, content = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.width(16.dp))
          Column(Modifier.weight(1f)) {
            Text(mode.label, style = MaterialTheme.typography.titleMedium)
            Text(mode.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          RadioButton(selected = isSelected, onClick = null)
        }
      }
    }
  }
}
