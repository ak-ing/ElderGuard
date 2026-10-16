package com.elderguard.app.ui.family

import android.os.SystemClock
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elderguard.app.AppContainer
import com.elderguard.app.container
import com.elderguard.app.ui.common.PIN_LENGTH
import com.elderguard.app.ui.common.PinDots
import com.elderguard.app.ui.common.PinPad
import com.elderguard.app.ui.common.TonalIcon
import com.elderguard.app.ui.common.rememberPinHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PinGateState(val input: String = "", val error: String? = null, val errorTick: Int = 0)

class PinGateViewModel(private val c: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow(PinGateState())
  val state = _state.asStateFlow()

  fun onInput(value: String, onSuccess: () -> Unit) {
    val now = SystemClock.elapsedRealtime()
    if (now < lockedUntil) {
      _state.update { it.copy(input = "", error = "错误次数过多，请 ${(lockedUntil - now) / 1000 + 1} 秒后再试", errorTick = it.errorTick + 1) }
      return
    }
    _state.update { it.copy(input = value, error = null) }
    if (value.length < PIN_LENGTH) return
    viewModelScope.launch {
      if (c.settings.verifyPin(value)) {
        failures = 0
        onSuccess()
      } else {
        failures++
        if (failures >= MAX_FAILURES) {
          lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS
          failures = 0
        }
        _state.update { it.copy(input = "", error = "密码错误", errorTick = it.errorTick + 1) }
      }
    }
  }

  private companion object {
    // 放在进程级：密码页每次进入都是新的 ViewModel，退出重进不能清零
    var failures = 0
    var lockedUntil = 0L
    const val MAX_FAILURES = 5
    const val LOCK_MS = 30_000L
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinGateScreen(onSuccess: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  val container = LocalContext.current.container
  val vm = viewModel { PinGateViewModel(container) }
  val state by vm.state.collectAsStateWithLifecycle()
  val haptics = rememberPinHaptics()
  LaunchedEffect(state.errorTick) { if (state.errorTick > 0) haptics.error() }

  Scaffold(
    modifier,
    topBar = {
      TopAppBar(
        title = {},
        navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回") } },
      )
    },
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      TonalIcon(
        Icons.Rounded.Lock,
        size = 56.dp,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
      )
      Spacer(Modifier.height(16.dp))
      Text("输入家人密码", style = MaterialTheme.typography.headlineSmall)
      Spacer(Modifier.height(24.dp))
      PinDots(state.input.length, state.errorTick, isError = state.error != null)
      Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(state.error != null, enter = fadeIn(), exit = fadeOut()) {
          Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
      }
      PinPad(
        state.input,
        {
          vm.onInput(it) {
            haptics.success()
            onSuccess()
          }
        },
      )
    }
  }
}
