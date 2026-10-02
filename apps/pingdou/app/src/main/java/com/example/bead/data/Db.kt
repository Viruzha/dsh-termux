package com.example.bead.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 一种颜色的库存。字段与原 Room 实体保持一致，UI 无需改动。 */
data class InventoryEntity(
    val name: String,
    val hex: String,
    val total: Int = 0,
    val used: Int = 0,
)

/** 一次用量记录（确认扣减时写入）。 */
data class UsageLogEntity(
    val id: Long = 0,
    val timestamp: Long,
    val imageName: String,
    val color: String,
    val count: Int,
    val substitutedFrom: String? = null,
)

/**
 * 库存与用量记录的存储。
 *
 * 为什么不用原方案的 Room：Room 依赖 KSP，而 KSP 用 `kotlin.sourceSets`
 * 注入生成源码，与 AGP 9 的内置 Kotlin 冲突 —— 实测报
 * `kspDebugKotlin FAILED: unexpected jvm signature V`。
 *
 * 这里的存储需求很轻（一份颜色库存 + 一条用量日志），
 * 用内存 StateFlow + JSON 持久化完全够用，还省掉一整个注解处理器。
 * 对外接口与原 DAO 等价，所以 BeadRepository 与 UI 都不受影响。
 */
class BeadStore(context: Context) {

    private val sp = context.getSharedPreferences("bead", Context.MODE_PRIVATE)

    private val _inventory = MutableStateFlow<List<InventoryEntity>>(emptyList())
    val inventory: StateFlow<List<InventoryEntity>> = _inventory.asStateFlow()

    private val _logs = MutableStateFlow<List<UsageLogEntity>>(emptyList())
    val logs: StateFlow<List<UsageLogEntity>> = _logs.asStateFlow()

    private var nextLogId = 1L

    init { load() }

    // ---- 持久化 ----------------------------------------------------------
    private fun load() {
        val inv = sp.getString("inventory", null)
        if (inv != null) {
            val arr = JSONArray(inv)
            val list = ArrayList<InventoryEntity>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += InventoryEntity(
                    name = o.getString("name"),
                    hex = o.optString("hex"),
                    total = o.optInt("total"),
                    used = o.optInt("used"),
                )
            }
            _inventory.value = list.sortedBy { it.name }
        }

        val lg = sp.getString("logs", null)
        if (lg != null) {
            val arr = JSONArray(lg)
            val list = ArrayList<UsageLogEntity>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += UsageLogEntity(
                    id = o.optLong("id"),
                    timestamp = o.optLong("ts"),
                    imageName = o.optString("img"),
                    color = o.optString("color"),
                    count = o.optInt("n"),
                    substitutedFrom = if (o.isNull("sub")) null else o.optString("sub"),
                )
            }
            _logs.value = list.sortedByDescending { it.timestamp }
            nextLogId = (list.maxOfOrNull { it.id } ?: 0L) + 1
        }
    }

    private fun persistInventory() {
        val arr = JSONArray()
        _inventory.value.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name); put("hex", it.hex)
                put("total", it.total); put("used", it.used)
            })
        }
        sp.edit().putString("inventory", arr.toString()).apply()
    }

    private fun persistLogs() {
        val arr = JSONArray()
        _logs.value.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("ts", it.timestamp); put("img", it.imageName)
                put("color", it.color); put("n", it.count)
                put("sub", it.substitutedFrom ?: JSONObject.NULL)
            })
        }
        sp.edit().putString("logs", arr.toString()).apply()
    }

    // ---- 与原 DAO 等价的接口 ---------------------------------------------
    fun getAll(): List<InventoryEntity> = _inventory.value

    fun insertAll(items: List<InventoryEntity>) {
        val byName = _inventory.value.associateBy { it.name }.toMutableMap()
        items.forEach { if (!byName.containsKey(it.name)) byName[it.name] = it }
        _inventory.value = byName.values.sortedBy { it.name }
        persistInventory()
    }

    fun setTotal(name: String, total: Int) = update(name) { it.copy(total = total) }

    fun addTotal(name: String, delta: Int) = update(name) { it.copy(total = it.total + delta) }

    fun addUsed(name: String, delta: Int) = update(name) { it.copy(used = it.used + delta) }

    private inline fun update(name: String, f: (InventoryEntity) -> InventoryEntity) {
        _inventory.value = _inventory.value.map { if (it.name == name) f(it) else it }
        persistInventory()
    }

    fun resetUsed() {
        _inventory.value = _inventory.value.map { it.copy(used = 0) }
        persistInventory()
    }

    fun insertLogs(items: List<UsageLogEntity>) {
        val withIds = items.map { it.copy(id = nextLogId++) }
        _logs.value = (withIds + _logs.value).sortedByDescending { it.timestamp }
        persistLogs()
    }

    fun clearLog() {
        _logs.value = emptyList()
        persistLogs()
    }
}
