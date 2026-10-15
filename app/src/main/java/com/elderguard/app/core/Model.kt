package com.elderguard.app.core

/** [label] 用于标签，[description] 用于向家人解释这个 App 做了什么 */
enum class EventType(val label: String, val description: String) {
  UNSOLICITED_LAUNCH("自动弹出", "没有任何点击，它自己跳到了屏幕最前面"),
  UNLOCK_POPUP("解锁弹窗", "刚解锁手机，它就弹了出来"),
  QUICK_APP_LAUNCH("拉起快应用", "正在用它时，突然被拉起了快应用"),
  STORE_REDIRECT("跳转商店", "正在用它时，突然被跳到了应用商店"),
  SUSPICIOUS_NOTIFICATION("可疑通知", "发出了假病毒、假中奖之类的通知"),
  PRIVILEGE_REQUEST("索要高级权限", "想通过 Shizuku 获得高级权限，拿到后可以静默安装、卸载应用"),
}

/** 家人对某个 App 的结论 */
enum class Trust {
  /** 家人确认是正常 App：不判定、不处理 */
  TRUSTED,
  /** 家人确认是流氓 App：它的所有骚扰都自动处理 */
  FLAGGED,
}

enum class GuardMode(val label: String, val description: String) {
  RECORD_ONLY("仅记录", "只记下发生了什么，不动手机"),
  AUTO("自动挡掉", "快应用、跳商店、被标记的流氓 App 一出现就自动返回"),
}

/** 判定引擎的输出：一次骚扰事件 */
data class Detection(
  val type: EventType,
  /** 元凶：应当为这次骚扰负责的 App */
  val culpritPkg: String,
  /** 出现在前台的 App（快应用场景下是快应用引擎） */
  val targetPkg: String,
  val targetClass: String?,
  /** 是否属于可以自动处理的类型 */
  val autoBlockable: Boolean,
  val detail: String? = null,
)
