package app.scrcpy.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A remembered mirror target (one entry per host+port). */
data class SavedTarget(
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val lastUsed: Long
)

/** Small SharedPreferences-backed store for previously used targets. */
object TargetsStore {
    private const val PREFS = "targets"
    private const val KEY = "targets_v1"

    fun load(context: Context): List<SavedTarget> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        SavedTarget(
                            name = obj.optString("name"),
                            host = obj.getString("host"),
                            port = obj.getInt("port"),
                            tls = obj.optBoolean("tls", true),
                            lastUsed = obj.optLong("lastUsed", 0)
                        )
                    )
                }
            }.sortedByDescending { it.lastUsed }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun upsert(context: Context, target: SavedTarget) {
        val current = load(context).filterNot { it.host == target.host && it.port == target.port }
        save(context, listOf(target) + current)
    }

    fun delete(context: Context, target: SavedTarget) {
        save(context, load(context).filterNot { it.host == target.host && it.port == target.port })
    }

    private fun save(context: Context, targets: List<SavedTarget>) {
        val array = JSONArray()
        for (target in targets) {
            array.put(
                JSONObject()
                    .put("name", target.name)
                    .put("host", target.host)
                    .put("port", target.port)
                    .put("tls", target.tls)
                    .put("lastUsed", target.lastUsed)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
