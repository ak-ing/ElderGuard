package com.elderguard.app.ui.family

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elderguard.app.AppContainer
import com.elderguard.app.container
import com.elderguard.app.privileged.AppAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** ViewModel 里执行维护处置，结果以一句话发给界面显示 */
class ActionRunner(private val c: AppContainer, private val scope: CoroutineScope) {
  private val _messages = Channel<String>(Channel.BUFFERED)
  val messages: Flow<String> = _messages.receiveAsFlow()

  /** 每完成一次处置 +1，用来刷新安装、冻结等状态 */
  private val _revision = MutableStateFlow(0)
  val revision = _revision.asStateFlow()

  fun run(pkg: String, label: String, action: AppAction, onSuccess: () -> Unit = {}) {
    scope.launch {
      val result = c.maintenance.perform(pkg, action)
      _revision.value++
      result.onSuccess { onSuccess() }
      _messages.send(result.fold({ "「$label」$it" }, { it.message ?: "执行失败" }))
    }
  }
}

/** 正在维护时 [pkg] 可执行的处置；不在维护时为空 */
@Composable
fun rememberMaintenanceActions(pkg: String, revision: Int): List<AppAction> {
  val maintenance = LocalContext.current.container.maintenance
  val endsAt by maintenance.endsAt.collectAsStateWithLifecycle()
  val active = endsAt != null
  // 只查一个包的信息，很快；同步算出来，面板弹出时内容就是完整的，不会再长高
  return remember(pkg, active, revision) { if (active) maintenance.actionsFor(pkg) else emptyList() }
}

@Composable
fun ShowMessages(messages: Flow<String>, snackbar: SnackbarHostState) {
  LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }
}
