package io.github.mangi.eta.agent.display

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only remembers an app entry point, never an observation, gesture or deep link to replay. */
internal class VirtualScreenResumeStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("virtual_screen_resume", Context.MODE_PRIVATE)

    fun component(owner: String): String? = synchronized(lock) {
        entries().firstOrNull { it.optString("owner") == owner }?.optString("component")
    }

    fun remember(owner: String, component: String): Boolean = synchronized(lock) {
        if (owner.isBlank() || owner.length > 512 || !COMPONENT.matches(component)) return@synchronized false
        val next = entries().filterNot { it.optString("owner") == owner }.takeLast(MAX_ENTRIES - 1) +
            JSONObject().put("owner", owner).put("component", component)
        // A successful launch must survive immediate process death, so do not defer this write.
        preferences.edit().putString("apps", JSONArray(next).toString()).commit()
    }

    fun forget(owner: String) = synchronized(lock) {
        preferences.edit().putString("apps", JSONArray(entries().filterNot { it.optString("owner") == owner }).toString()).commit()
    }

    fun clear() = synchronized(lock) { preferences.edit().clear().commit() }

    private fun entries(): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString("apps", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter {
            it.optString("owner").let { owner -> owner.isNotBlank() && owner.length <= 512 } &&
                COMPONENT.matches(it.optString("component"))
        }.takeLast(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    private companion object {
        val lock = Any()
        const val MAX_ENTRIES = 32
        val COMPONENT = Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+")
    }
}
