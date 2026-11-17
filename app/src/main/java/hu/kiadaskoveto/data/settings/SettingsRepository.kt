package hu.kiadaskoveto.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import hu.kiadaskoveto.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val appLock: Boolean = false,
    val ocrLanguages: String = "hun",
    val budgetThresholds: Set<Int> = setOf(50, 75, 90, 100),
    val budgetNotifications: Boolean = true,
    val autoBackup: Boolean = false,
    val autoBackupIntervalDays: Int = 7,
    val autoBackupFolder: String? = null,
    val lastBackupAt: Long = 0,
    val lastBackupResult: String? = null,
    val encryptedDatabase: Boolean = false,
    val includeDraftsInLists: Boolean = true,
    val localAiEnabled: Boolean = false,
    val autoCrop: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val lock = booleanPreferencesKey("app_lock")
        val ocrLang = stringPreferencesKey("ocr_languages")
        val thresholds = stringPreferencesKey("budget_thresholds")
        val budgetNotif = booleanPreferencesKey("budget_notifications")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val autoBackupDays = intPreferencesKey("auto_backup_days")
        val autoBackupFolder = stringPreferencesKey("auto_backup_folder")
        val lastBackup = longPreferencesKey("last_backup_at")
        val lastBackupResult = stringPreferencesKey("last_backup_result")
        val encrypted = booleanPreferencesKey("encrypted_db")
        val drafts = booleanPreferencesKey("include_drafts")
        val localAi = booleanPreferencesKey("local_ai")
        val autoCrop = booleanPreferencesKey("auto_crop")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.onboarding] ?: false,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: false,
            appLock = p[Keys.lock] ?: false,
            ocrLanguages = p[Keys.ocrLang] ?: "hun",
            budgetThresholds = p[Keys.thresholds]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(50, 75, 90, 100),
            budgetNotifications = p[Keys.budgetNotif] ?: true,
            autoBackup = p[Keys.autoBackup] ?: false,
            autoBackupIntervalDays = p[Keys.autoBackupDays] ?: 7,
            autoBackupFolder = p[Keys.autoBackupFolder],
            lastBackupAt = p[Keys.lastBackup] ?: 0,
            lastBackupResult = p[Keys.lastBackupResult],
            encryptedDatabase = p[Keys.encrypted] ?: false,
            includeDraftsInLists = p[Keys.drafts] ?: true,
            localAiEnabled = p[Keys.localAi] ?: false,
            autoCrop = p[Keys.autoCrop] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.onboarding] = done }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamic] = on }
    suspend fun setAppLock(on: Boolean) = context.dataStore.edit { it[Keys.lock] = on }
    suspend fun setOcrLanguages(value: String) = context.dataStore.edit { it[Keys.ocrLang] = value }
    suspend fun setBudgetThresholds(values: Set<Int>) = context.dataStore.edit { it[Keys.thresholds] = values.sorted().joinToString(",") }
    suspend fun setBudgetNotifications(on: Boolean) = context.dataStore.edit { it[Keys.budgetNotif] = on }
    suspend fun setAutoBackup(on: Boolean) = context.dataStore.edit { it[Keys.autoBackup] = on }
    suspend fun setAutoBackupIntervalDays(days: Int) = context.dataStore.edit { it[Keys.autoBackupDays] = days }
    suspend fun setAutoBackupFolder(uri: String?) = context.dataStore.edit { if (uri == null) it.remove(Keys.autoBackupFolder) else it[Keys.autoBackupFolder] = uri }
    suspend fun setLastBackup(at: Long, result: String) = context.dataStore.edit { it[Keys.lastBackup] = at; it[Keys.lastBackupResult] = result }
    suspend fun setEncryptedDatabase(on: Boolean) = context.dataStore.edit { it[Keys.encrypted] = on }
    suspend fun setIncludeDrafts(on: Boolean) = context.dataStore.edit { it[Keys.drafts] = on }
    suspend fun setLocalAiEnabled(on: Boolean) = context.dataStore.edit { it[Keys.localAi] = on }
    suspend fun setAutoCrop(on: Boolean) = context.dataStore.edit { it[Keys.autoCrop] = on }
    suspend fun clear() = context.dataStore.edit { it.clear() }
}
