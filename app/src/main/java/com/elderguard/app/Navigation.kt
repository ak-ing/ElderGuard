package com.elderguard.app

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.elderguard.app.theme.pageTransition
import com.elderguard.app.ui.elder.ElderHomeScreen
import com.elderguard.app.ui.family.FamilyHomeScreen
import com.elderguard.app.ui.family.MaintenanceScreen
import com.elderguard.app.ui.family.PinGateScreen
import com.elderguard.app.ui.family.ReportScreen
import com.elderguard.app.ui.family.ScanScreen
import com.elderguard.app.ui.family.SelfTestScreen
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

/** 手机体检：风险应用 + 系统检查 */
@Serializable data object Scan : NavKey

/** 维护模式：借助 Shizuku 直接处置 */
@Serializable data object Maintenance : NavKey

/** 分享报告图片 */
@Serializable data object Report : NavKey

/** 守护自检：模拟广告跳转，检查守护是否有效，生成报告 */
@Serializable data object SelfTest : NavKey

/** 离开 App 超过这么久，家人页自动锁上 */
private const val FAMILY_LOCK_MS = 5 * 60 * 1000L

@Composable
fun AppNavigation(setupDone: Boolean) {
  val backStack = rememberNavBackStack(if (setupDone) ElderHome else Setup)

  // 家人离开后过一会儿，家人页自动锁上，免得长辈再打开时停在家人页。
  // 去系统设置、Shizuku 里操作一下再回来不用重输密码；进程被杀后恢复的返回栈同样要锁
  var stoppedAt by rememberSaveable { mutableLongStateOf(-1L) }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stoppedAt = SystemClock.elapsedRealtime() }
  LifecycleEventEffect(Lifecycle.Event.ON_START) {
    val away = SystemClock.elapsedRealtime() - stoppedAt
    if (stoppedAt >= 0 && away !in 0..FAMILY_LOCK_MS && backStack.firstOrNull() == ElderHome && backStack.size > 1) {
      backStack.retainAll { it == ElderHome }
    }
  }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    // 每个页面独立的 ViewModel：出栈即销毁，再次进入是全新状态（如密码页不残留输入）
    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
    // 和车机桌面一样：新页面放大淡入、旧页面放大淡出；返回（含预测性返回手势）同样
    transitionSpec = { pageTransition() },
    popTransitionSpec = { pageTransition() },
    predictivePopTransitionSpec = { pageTransition() },
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
        entry<FamilyHome> {
          FamilyHomeScreen(
            onBack = { backStack.removeLastOrNull() },
            onOpenScan = { backStack.add(Scan) },
            onOpenMaintenance = { backStack.add(Maintenance) },
            onOpenReport = { backStack.add(Report) },
            onOpenSelfTest = { backStack.add(SelfTest) },
          )
        }
        entry<SelfTest> { SelfTestScreen(onBack = { backStack.removeLastOrNull() }) }
        entry<Scan> { ScanScreen(onBack = { backStack.removeLastOrNull() }) }
        entry<Maintenance> { MaintenanceScreen(onBack = { backStack.removeLastOrNull() }) }
        entry<Report> { ReportScreen(onBack = { backStack.removeLastOrNull() }) }
      },
  )
}
