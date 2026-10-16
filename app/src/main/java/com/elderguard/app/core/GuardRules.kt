package com.elderguard.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 判定规则，来自 assets/rules.json。包名清单需要在各厂商真机上持续校准。 */
@Serializable
data class GuardRules(
  val version: Int,
  /** 同一元凶两次自动处理的最小间隔，防止和它拉锯 */
  val autoActionCooldownMs: Long,
  val quickAppEngines: Set<String>,
  val protectedPackages: Set<String>,
  val suspiciousKeywords: List<String>,
  /** 聊天应用：通知是亲友发的消息，不做关键词判定 */
  val chatPackages: Set<String> = emptySet(),
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): GuardRules =
      context.assets.open("rules.json").bufferedReader().use { json.decodeFromString(serializer(), it.readText()) }
  }
}
