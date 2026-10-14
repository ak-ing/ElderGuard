package com.elderguard.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.elderguard.app.theme.sharedAxisBackward
import com.elderguard.app.theme.sharedAxisForward
import com.elderguard.app.ui.elder.ElderHomeScreen
import com.elderguard.app.ui.family.FamilyHomeScreen
import com.elderguard.app.ui.family.PinGateScreen
import com.elderguard.app.ui.setup.SetupScreen
import kotlinx.serialization.Serializable

/** 长辈首页：长辈唯一会看到的页面 */
@Serializable data object ElderHome : NavKey

/** 首次设置向导 */
@Serializable data object Setup : NavKey

/** 家人密码校验，通过后进入家人页 */
@Serializable data object PinGate : NavKey

/** 家人页：总览、可疑应用、守护方式、权限、记录 */
@Serializable data object FamilyHome : NavKey

@Composable
fun AppNavigation(setupDone: Boolean) {
  val backStack = rememberNavBackStack(if (setupDone) ElderHome else Setup)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    // 每个页面独立的 ViewModel：出栈即销毁，再次进入是全新状态（如密码页不残留输入）
    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
    // Material 共享轴 X：前进从右滑入，返回（含预测性返回手势）从左滑入
    transitionSpec = { sharedAxisForward() },
    popTransitionSpec = { sharedAxisBackward() },
    predictivePopTransitionSpec = { sharedAxisBackward() },
    entryProvider =
      entryProvider {
        entry<Setup> {
          SetupScreen(
            onFinished = {
              backStack.add(ElderHome)
              backStack.removeAt(0)
            }
          )
        }
        entry<ElderHome> { ElderHomeScreen(onFamilyEntry = { backStack.add(PinGate) }) }
        entry<PinGate> {
          PinGateScreen(
            onSuccess = {
              // 密码页不留在返回栈里，返回直接回长辈首页
              backStack.add(FamilyHome)
              backStack.remove(PinGate)
            },
            onCancel = { backStack.removeLastOrNull() },
          )
        }
        entry<FamilyHome> { FamilyHomeScreen(onBack = { backStack.removeLastOrNull() }) }
      },
  )
}
