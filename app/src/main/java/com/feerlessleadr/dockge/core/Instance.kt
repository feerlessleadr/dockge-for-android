package com.feerlessleadr.dockge.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One dockge-enhanced server. Holds nothing secret: the session token lives in the [TokenVault] under [id]. */
@Serializable
data class Instance(
    val id: String,
    val name: String,
    /** ARGB color, shown as a dot next to the name. */
    val color: Long,
    /** Canonical address from [BaseUrl.value]. */
    val baseUrl: String,
)

object InstanceColors {
    val palette: List<Long> = listOf(
        0xFF1D63ED, 0xFF2E7D32, 0xFFC62828, 0xFFEF6C00,
        0xFF6A1B9A, 0xFF00838F, 0xFF4E342E, 0xFF546E7A,
    )
}

/** Persists the instance list so it survives closing the app and restarting the phone. */
interface InstanceStore {
    fun load(): List<Instance>
    fun save(instances: List<Instance>)
}

class PrefsInstanceStore(context: Context) : InstanceStore {
    private val prefs = context.applicationContext.getSharedPreferences("instances", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(Instance.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<Instance> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun save(instances: List<Instance>) {
        // commit(): the list must be on disk before the user can close the app.
        prefs.edit().putString(KEY, json.encodeToString(serializer, instances)).commit()
    }

    private companion object {
        const val KEY = "list_v1"
    }
}
