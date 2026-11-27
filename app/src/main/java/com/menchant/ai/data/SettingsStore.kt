package com.menchant.ai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.menchantStore by preferencesDataStore(name = "menchant")

class SettingsStore(context: Context) {
    private val ds = context.applicationContext.menchantStore

    private val keyModel = stringPreferencesKey("model")
    private val keyHistory = stringPreferencesKey("history")
    private val keyDemo = booleanPreferencesKey("demo_enabled")

    val model: Flow<ModelId> = ds.data.map { ModelId.fromId(it[keyModel]) }
    val history: Flow<List<Msg>> = ds.data.map { Msg.fromJson(it[keyHistory]) }
    val demoEnabled: Flow<Boolean> = ds.data.map { it[keyDemo] ?: false }

    suspend fun saveModel(m: ModelId) {
        ds.edit { it[keyModel] = m.id }
    }

    suspend fun saveHistory(list: List<Msg>) {
        ds.edit { it[keyHistory] = Msg.toJson(list.takeLast(200)) }
    }

    suspend fun setDemoEnabled(v: Boolean) {
        ds.edit { it[keyDemo] = v }
    }
}
