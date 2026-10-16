package com.elderguard.app.core

/**
 * 只记录确定的事实，不推测「是不是长辈点的」（系统拿不到启动的发起者，点击信号也不完整）。
 * [label] 用于标签，[description] 用于向家人解释这个 App 做了什么
 */
enum class EventType(val label: String, val description: String) {
  QUICK_APP_LAUNCH("快应用", "用它的时候出现了快应用，已按规则挡掉"),
  FLAGGED_LAUNCH("流氓应用出现", "已被标为流氓，一出现就挡掉"),
  OVERLAY_POPUP("悬浮窗", "不在前台时，在别的应用上面弹出了悬浮窗"),
  SUSPICIOUS_NOTIFICATION("可疑通知", "发出了全屏通知，或假病毒、假中奖之类的通知"),
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
  AUTO("自动挡掉", "快应用、被标记的流氓应用一出现就自动返回"),
}

/** 判定引擎的输出：一次骚扰事件 */
data class Detection(
  val type: EventType,
  /** 记在谁名下。快应用记在当时正在用的 App 名下，只作参考 */
  val culpritPkg: String,
  /** 出现的 App（快应用场景下是快应用引擎） */
  val targetPkg: String,
  val targetClass: String?,
  /** 是否属于可以自动处理的类型 */
  val autoBlockable: Boolean,
  val detail: String? = null,
)
