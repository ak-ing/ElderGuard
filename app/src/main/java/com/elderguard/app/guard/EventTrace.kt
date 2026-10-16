package com.elderguard.app.guard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近收到的无障碍事件（只有类型和包名/类名，不含屏幕内容），只在内存里，写进自检报告。
 * 用来核对各厂商系统上「点了却没有点击事件」之类的误判。
 */
object EventTrace {
  private const val MAX = 80
  private val lines = ArrayDeque<String>()
  private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)

  @Synchronized
  fun add(text: String) {
    if (lines.size == MAX) lines.removeFirst()
    lines.addLast("${time.format(Date())} $text")
  }

  @Synchronized fun snapshot(): List<String> = lines.toList()
}
