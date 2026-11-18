package com.shipyard.deepchat.net

import com.shipyard.deepchat.data.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 配置备份：服务商、提示词、记忆、外观都在 settings 里 */
@Serializable
data class Backup(
    val version: Int = 1,
    val settings: Settings
)

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(b: Backup): String = json.encodeToString(Backup.serializer(), b)

    fun decode(s: String): Backup = json.decodeFromString(Backup.serializer(), s)
}
