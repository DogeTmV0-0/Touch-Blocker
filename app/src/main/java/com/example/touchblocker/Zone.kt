package com.example.touchblocker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One dead zone, in absolute pixel coordinates for a given orientation.
 * Coordinates are stored per-orientation because a rectangle drawn in
 * portrait doesn't make sense after a 90-degree rotation.
 */
data class Zone(
    val id: String = UUID.randomUUID().toString(),
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var orientation: Orientation,
    var enabled: Boolean = true,
    var label: String = "Zone"
) {
    enum class Orientation { PORTRAIT, LANDSCAPE }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("x", x)
        put("y", y)
        put("width", width)
        put("height", height)
        put("orientation", orientation.name)
        put("enabled", enabled)
        put("label", label)
    }

    companion object {
        fun fromJson(obj: JSONObject): Zone = Zone(
            id = obj.getString("id"),
            x = obj.getInt("x"),
            y = obj.getInt("y"),
            width = obj.getInt("width"),
            height = obj.getInt("height"),
            orientation = Orientation.valueOf(obj.getString("orientation")),
            enabled = obj.optBoolean("enabled", true),
            label = obj.optString("label", "Zone")
        )
    }
}

/**
 * Very small persistence layer. SharedPreferences is fine here since we're
 * only ever storing a handful of small rectangles, not a real dataset.
 */
class ZoneRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getZones(): MutableList<Zone> {
        val raw = prefs.getString(KEY_ZONES, null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { i -> Zone.fromJson(arr.getJSONObject(i)) }
    }

    fun saveZones(zones: List<Zone>) {
        val arr = JSONArray()
        zones.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ZONES, arr.toString()).apply()
    }

    fun addZone(zone: Zone) {
        val zones = getZones()
        zones.add(zone)
        saveZones(zones)
    }

    fun removeZone(id: String) {
        saveZones(getZones().filterNot { it.id == id })
    }

    fun setBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, enabled).apply()
    }

    fun isBlockingEnabled(): Boolean = prefs.getBoolean(KEY_BLOCKING_ENABLED, false)

    /** Paused = service stays alive (notification still there) but no overlays are drawn. */
    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    /** Last dragged position of the floating pause/resume button, so it stays where you left it. */
    fun setControlButtonPosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_CTRL_X, x).putInt(KEY_CTRL_Y, y).apply()
    }

    fun getControlButtonPosition(): Pair<Int, Int>? {
        if (!prefs.contains(KEY_CTRL_X)) return null
        return prefs.getInt(KEY_CTRL_X, 0) to prefs.getInt(KEY_CTRL_Y, 0)
    }

    /** Debug aid: tints active zones with a visible border/fill instead of staying fully invisible. */
    fun setDebugVisibleZones(visible: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_VISIBLE, visible).apply()
    }

    fun isDebugVisibleZones(): Boolean = prefs.getBoolean(KEY_DEBUG_VISIBLE, false)

    companion object {
        private const val PREFS_NAME = "touch_blocker_prefs"
        private const val KEY_ZONES = "zones_json"
        private const val KEY_BLOCKING_ENABLED = "blocking_enabled"
        private const val KEY_PAUSED = "blocking_paused"
        private const val KEY_CTRL_X = "control_button_x"
        private const val KEY_CTRL_Y = "control_button_y"
        private const val KEY_DEBUG_VISIBLE = "debug_visible_zones"
    }
}
