package com.elderguard.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.elderguard.app.core.GuardMode
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
  private val store = context.dataStore

  val setupDone: Flow<Boolean> = store.data.map { it[SETUP_DONE] ?: false }

  val guardMode: Flow<GuardMode> =
    store.data.map { prefs -> prefs[GUARD_MODE]?.let { runCatching { GuardMode.valueOf(it) }.getOrNull() } ?: GuardMode.AUTO }

  suspend fun setGuardMode(mode: GuardMode) {
    store.edit { it[GUARD_MODE] = mode.name }
  }

  suspend fun completeSetup() {
    store.edit { it[SETUP_DONE] = true }
  }

  suspend fun setPin(pin: String) {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val hashed = hash(pin, salt)
    store.edit {
      it[PIN_SALT] = salt.encode()
      it[PIN_HASH] = hashed.encode()
    }
  }

  suspend fun hasPin(): Boolean = store.data.first()[PIN_HASH] != null

  suspend fun verifyPin(pin: String): Boolean {
    val prefs = store.data.first()
    val salt = prefs[PIN_SALT]?.decode() ?: return false
    val expected = prefs[PIN_HASH]?.decode() ?: return false
    return java.security.MessageDigest.isEqual(hash(pin, salt), expected)
  }

  /** PBKDF2 12 万次迭代在低端芯片上要几百毫秒，必须离开主线程 */
  private suspend fun hash(pin: String, salt: ByteArray): ByteArray =
    withContext(Dispatchers.Default) {
      SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256))
        .encoded
    }

  private fun ByteArray.encode(): String = Base64.getEncoder().encodeToString(this)

  private fun String.decode(): ByteArray = Base64.getDecoder().decode(this)

  private companion object {
    val SETUP_DONE = booleanPreferencesKey("setup_done")
    val GUARD_MODE = stringPreferencesKey("guard_mode")
    val PIN_HASH = stringPreferencesKey("pin_hash")
    val PIN_SALT = stringPreferencesKey("pin_salt")
  }
}
