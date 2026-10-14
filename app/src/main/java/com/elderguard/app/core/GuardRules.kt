package com.elderguard.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 判定规则，来自 assets/rules.json。包名清单需要在各厂商真机上持续校准。 */
@Serializable
data class GuardRules(
  val version: Int,
  /** 前台切换前这么久内有过点击/输入，视为用户主动打开 */
  val interactionWindowMs: Long,
  /** 解锁后这么久内出现的非主动界面，判为解锁弹窗 */
  val unlockWindowMs: Long,
  /** 同一元凶两次自动处理的最小间隔，防止和它拉锯 */
  val autoActionCooldownMs: Long,
  val quickAppEngines: Set<String>,
  val appStores: Set<String>,
  val protectedPackages: Set<String>,
  val suspiciousKeywords: List<String>,
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): GuardRules =
      context.assets.open("rules.json").bufferedReader().use { json.decodeFromString(serializer(), it.readText()) }
  }
}
