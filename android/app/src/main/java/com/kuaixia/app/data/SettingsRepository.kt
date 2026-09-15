package com.kuaixia.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kuaixia.app.core.model.AppLanguage
import com.kuaixia.app.core.model.ParseMode
import com.kuaixia.app.core.model.ParserServerConfig
import com.kuaixia.app.core.model.ServerSettings
import com.kuaixia.app.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kuaixia_settings")

/**
 * 服务器配置持久化（DataStore）。
 * Phase 1 用 DataStore 存服务器列表；下载历史等结构化数据后续用 Room。
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val serversKey = stringPreferencesKey("parser_servers")
    private val defaultKey = stringPreferencesKey("default_server_id")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val languageKey = stringPreferencesKey("app_language")
    private val parseModeKey = stringPreferencesKey("parse_mode")
    private val debugLoggingKey = booleanPreferencesKey("debug_logging")
    private val clipboardAutoParseKey = booleanPreferencesKey("clipboard_auto_parse")
    private val clipboardLastAutoKey = stringPreferencesKey("clipboard_last_auto_url")

    /** 详细日志开关（null = 用户未设置，保持构建默认：debug 构建含 DEBUG，release 仅 INFO+）。 */
    val debugLogging: Flow<Boolean?> = appContext.dataStore.data.map { prefs ->
        prefs[debugLoggingKey]
    }

    suspend fun setDebugLogging(enabled: Boolean) {
        appContext.dataStore.edit { prefs -> prefs[debugLoggingKey] = enabled }
    }

    /** 「自动解析剪贴板链接」（默认关闭，避免无谓消耗解析资源）。 */
    val clipboardAutoParse: Flow<Boolean> = appContext.dataStore.data.map { prefs ->
        prefs[clipboardAutoParseKey] ?: false
    }

    suspend fun setClipboardAutoParse(enabled: Boolean) {
        appContext.dataStore.edit { prefs -> prefs[clipboardAutoParseKey] = enabled }
    }

    /** 最近一次「自动解析」过的剪贴板 URL（持久化：App 重启不重复自动解析同一旧链接）。 */
    val clipboardLastAutoUrl: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[clipboardLastAutoKey].orEmpty()
    }

    /**
     * 最近一次「自动解析」过的**作品身份**（BUG-002：真正被读取的持久化去重 key）。
     *
     * 旧键 `clipboard_last_auto_url` 只存 URL 字符串，短链/长链不同形，无法跨形态去重；
     * 这里存 `douyin:aweme:<id>` 这类身份，App 重启后仍能命中同一作品。
     */
    private val clipboardLastIdentityKey = stringPreferencesKey("clipboard_last_auto_identity")

    /** 短链 canonical → 作品身份 的别名表（短链自身不含作品 ID，解析成功后登记）。 */
    private val clipboardIdentityAliasesKey = stringSetPreferencesKey("clipboard_identity_aliases")

    /** 自动剪贴板相关设置的原子快照（单次 DataStore 读 = 等待就绪；无“默认 false 提前决策”窗口）。 */
    data class ClipboardPrefs(
        val autoParse: Boolean,
        val lastAutoParsedUrl: String,
        /** 最近一次自动解析的作品身份（空串 = 从未解析过）。 */
        val lastAutoParsedIdentity: String = "",
        /** 短链别名表（canonical → identity）。 */
        val identityAliases: Set<String> = emptySet(),
    )

    suspend fun clipboardPrefs(): ClipboardPrefs {
        val prefs = appContext.dataStore.data.first()
        return ClipboardPrefs(
            autoParse = prefs[clipboardAutoParseKey] ?: false,
            lastAutoParsedUrl = prefs[clipboardLastAutoKey].orEmpty(),
            lastAutoParsedIdentity = prefs[clipboardLastIdentityKey].orEmpty(),
            identityAliases = prefs[clipboardIdentityAliasesKey].orEmpty(),
        )
    }

    /**
     * 写入作品身份去重状态（BUG-002：与读取 [clipboardPrefs] 成对，保证重启后仍生效）。
     */
    suspend fun updateClipboardIdentity(lastIdentity: String, aliases: Set<String>) {
        appContext.dataStore.edit { prefs ->
            prefs[clipboardLastIdentityKey] = lastIdentity
            prefs[clipboardIdentityAliasesKey] = aliases
        }
    }

    suspend fun setClipboardLastAutoUrl(url: String) {
        appContext.dataStore.edit { prefs -> prefs[clipboardLastAutoKey] = url }
    }

    /** 服务器配置流（列表 + 默认服务器 id）。 */
    val serverSettings: Flow<ServerSettings> = appContext.dataStore.data.map { prefs ->
        ServerSettings(
            servers = readServers(prefs),
            defaultServerId = prefs[defaultKey],
        )
    }

    /** 外观模式流。 */
    val themeMode: Flow<ThemeMode> = appContext.dataStore.data.map { prefs ->
        prefs[themeModeKey]?.let { mode ->
            ThemeMode.entries.firstOrNull { it.name == mode }
        } ?: ThemeMode.LIGHT
    }

    /** 解析方式流（默认本地优先）。 */
    val parseMode: Flow<ParseMode> = appContext.dataStore.data.map { prefs ->
        prefs[parseModeKey]?.let { mode ->
            ParseMode.entries.firstOrNull { it.name == mode }
        } ?: ParseMode.LOCAL_FIRST
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.dataStore.edit { prefs ->
            prefs[themeModeKey] = mode.name
        }
    }

    /** 应用语言流（默认简体中文）。 */
    val language: Flow<AppLanguage> = appContext.dataStore.data.map { prefs ->
        prefs[languageKey]?.let { code ->
            AppLanguage.entries.firstOrNull { it.code == code }
        } ?: AppLanguage.ZH
    }

    suspend fun setLanguage(lang: AppLanguage) {
        appContext.dataStore.edit { prefs -> prefs[languageKey] = lang.code }
    }

    suspend fun setParseMode(mode: ParseMode) {
        appContext.dataStore.edit { prefs ->
            prefs[parseModeKey] = mode.name
        }
    }

    suspend fun addServer(config: ParserServerConfig) {
        appContext.dataStore.edit { prefs ->
            val list = readServers(prefs).toMutableList()
            list.add(config)
            prefs[serversKey] = json.encodeToString(list)
            // 第一个添加的服务器自动设为默认
            if (prefs[defaultKey] == null) prefs[defaultKey] = config.id
        }
    }

    suspend fun updateServer(config: ParserServerConfig) {
        appContext.dataStore.edit { prefs ->
            val list = readServers(prefs).map { if (it.id == config.id) config else it }
            prefs[serversKey] = json.encodeToString(list)
        }
    }

    suspend fun deleteServer(id: String) {
        appContext.dataStore.edit { prefs ->
            val list = readServers(prefs).filterNot { it.id == id }
            prefs[serversKey] = json.encodeToString(list)
            if (prefs[defaultKey] == id) prefs.remove(defaultKey)
        }
    }

    suspend fun setServerEnabled(id: String, enabled: Boolean) {
        appContext.dataStore.edit { prefs ->
            val list = readServers(prefs).map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            prefs[serversKey] = json.encodeToString(list)
        }
    }

    suspend fun setDefaultServer(id: String?) {
        appContext.dataStore.edit { prefs ->
            if (id == null) prefs.remove(defaultKey) else prefs[defaultKey] = id
        }
    }

    private fun readServers(prefs: Preferences): List<ParserServerConfig> {
        val raw = prefs[serversKey] ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<ParserServerConfig>>(raw)
        }.getOrDefault(emptyList())
    }
}
