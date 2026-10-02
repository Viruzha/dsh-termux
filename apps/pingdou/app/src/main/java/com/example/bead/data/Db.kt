package com.example.bead.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** 一种颜色的库存。 */
data class InventoryEntity(
    val name: String,
    val hex: String,
    val total: Int = 0,
    val used: Int = 0,
)

/** 记录里的一条分色用量。 */
data class RecordEntry(
    val color: String,
    val count: Int,
    val substitutedFrom: String? = null,
)

/**
 * 一次转换的记录。
 *
 * 除了用量，还存下**原图**与**转换后图片**的本地文件路径 ——
 * 实际拼豆时最需要的就是这两张图（对着原图看效果、对着像素图数格子）。
 */
data class ConversionRecord(
    val id: Long,
    val timestamp: Long,
    val imageName: String,
    val srcPath: String,
    val outPath: String,
    val cols: Int,
    val rows: Int,
    val totalBeads: Int,
    val entries: List<RecordEntry>,
    /**
     * 每格调色板下标的游程编码（见 [encodeIndices]）。
     *
     * 存下来是为了让**历史页也能点格查色号** —— 只存图片的话，
     * 渲染图上有网格线和透明棋盘格，没法可靠地反推出是哪一号色。
     */
    val indicesRle: String = "",
) {
    /** 还原成下标数组；长度不足时用 EMPTY 补齐。 */
    fun indices(): IntArray = decodeIndices(indicesRle, cols * rows)
}

/** 下标数组 → 游程编码。像素画大片同色，压缩率很高。 */
fun encodeIndices(a: IntArray): String {
    val sb = StringBuilder()
    var i = 0
    while (i < a.size) {
        val v = a[i]
        var n = 1
        while (i + n < a.size && a[i + n] == v) n++
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(v).append(':').append(n)
        i += n
    }
    return sb.toString()
}

fun decodeIndices(s: String, size: Int): IntArray {
    val out = IntArray(size)
    var p = 0
    if (s.isNotEmpty()) {
        for (part in s.split(',')) {
            val c = part.indexOf(':')
            if (c <= 0) continue
            val v = part.substring(0, c).toIntOrNull() ?: continue
            val n = part.substring(c + 1).toIntOrNull() ?: continue
            var k = 0
            while (k < n && p < size) { out[p++] = v; k++ }
        }
    }
    while (p < size) out[p++] = -1   // Pixelizer.EMPTY，避免 data 层依赖 domain
    return out
}

/**
 * 库存与转换记录的存储。
 *
 * 为什么不用原方案的 Room：Room 依赖 KSP，而 KSP 用 `kotlin.sourceSets`
 * 注入生成源码，与 AGP 9 的内置 Kotlin 冲突 —— 实测报
 * `kspDebugKotlin FAILED: unexpected jvm signature V`。
 *
 * 这里的存储需求很轻（一份颜色库存 + 一串转换记录），
 * 用内存 StateFlow + JSON 持久化完全够用，还省掉一整个注解处理器。
 */
class BeadStore(private val context: Context) {

    private val sp = context.getSharedPreferences("bead", Context.MODE_PRIVATE)

    private val _inventory = MutableStateFlow<List<InventoryEntity>>(emptyList())
    val inventory: StateFlow<List<InventoryEntity>> = _inventory.asStateFlow()

    private val _records = MutableStateFlow<List<ConversionRecord>>(emptyList())
    val records: StateFlow<List<ConversionRecord>> = _records.asStateFlow()

    private var nextRecordId = 1L

    init { load() }

    // ---- 持久化 ----------------------------------------------------------
    private fun load() {
        sp.getString("inventory", null)?.let { raw ->
            val arr = JSONArray(raw)
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

        sp.getString("records", null)?.let { raw ->
            val arr = JSONArray(raw)
            val list = ArrayList<ConversionRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val es = o.optJSONArray("entries") ?: JSONArray()
                val entriesList = ArrayList<RecordEntry>(es.length())
                for (j in 0 until es.length()) {
                    val e = es.getJSONObject(j)
                    entriesList += RecordEntry(
                        color = e.getString("c"),
                        count = e.optInt("n"),
                        substitutedFrom = if (e.isNull("s")) null else e.optString("s"),
                    )
                }
                list += ConversionRecord(
                    id = o.optLong("id"),
                    timestamp = o.optLong("ts"),
                    imageName = o.optString("name"),
                    srcPath = o.optString("src"),
                    outPath = o.optString("out"),
                    cols = o.optInt("cols"),
                    rows = o.optInt("rows"),
                    totalBeads = o.optInt("total"),
                    entries = entriesList,
                    indicesRle = o.optString("idx"),
                )
            }
            _records.value = list.sortedByDescending { it.timestamp }
            nextRecordId = (list.maxOfOrNull { it.id } ?: 0L) + 1
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

    private fun persistRecords() {
        val arr = JSONArray()
        _records.value.forEach { r ->
            val es = JSONArray()
            r.entries.forEach { e ->
                es.put(JSONObject().apply {
                    put("c", e.color); put("n", e.count)
                    put("s", e.substitutedFrom ?: JSONObject.NULL)
                })
            }
            arr.put(JSONObject().apply {
                put("id", r.id); put("ts", r.timestamp); put("name", r.imageName)
                put("src", r.srcPath); put("out", r.outPath)
                put("cols", r.cols); put("rows", r.rows)
                put("total", r.totalBeads); put("entries", es)
                put("idx", r.indicesRle)
            })
        }
        sp.edit().putString("records", arr.toString()).apply()
    }

    // ---- 图片存取 --------------------------------------------------------
    private fun imagesDir(): File =
        File(context.filesDir, "records").apply { if (!isDirectory) mkdirs() }

    /**
     * 把一张图存进私有目录，返回绝对路径。
     * 原图另存一份缩略图，避免把相册里的原图整份复制进来。
     */
    fun saveImage(id: Long, kind: String, bmp: Bitmap, maxSide: Int = 720): String {
        val f = File(imagesDir(), "rec${id}_$kind.png")
        val scaled = scaleDown(bmp, maxSide)
        FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = bmp.width.coerceAtLeast(bmp.height)
        if (s <= maxSide) return bmp
        val k = maxSide.toFloat() / s
        return Bitmap.createScaledBitmap(
            bmp, (bmp.width * k).toInt().coerceAtLeast(1),
            (bmp.height * k).toInt().coerceAtLeast(1), true
        )
    }

    fun loadImage(path: String): Bitmap? =
        if (path.isBlank()) null else BitmapFactory.decodeFile(path)

    /** 删除一条记录及其图片。 */
    fun deleteRecord(id: Long) {
        val r = _records.value.firstOrNull { it.id == id } ?: return
        runCatching { File(r.srcPath).delete() }
        runCatching { File(r.outPath).delete() }
        _records.value = _records.value.filterNot { it.id == id }
        persistRecords()
    }

    // ---- 与原 DAO 等价的库存接口 ------------------------------------------
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

    // ---- 记录 ------------------------------------------------------------
    /** 写入一次转换记录，返回新记录（含已保存的图片路径）。 */
    fun addRecord(
        imageName: String,
        src: Bitmap?,
        preview: Bitmap,
        cols: Int,
        rows: Int,
        totalBeads: Int,
        entries: List<RecordEntry>,
        indices: IntArray,
    ): ConversionRecord {
        val id = nextRecordId++
        val srcPath = src?.let { saveImage(id, "src", it) } ?: ""
        val outPath = saveImage(id, "out", preview)
        val rec = ConversionRecord(
            id = id,
            timestamp = System.currentTimeMillis(),
            imageName = imageName,
            srcPath = srcPath,
            outPath = outPath,
            cols = cols, rows = rows,
            totalBeads = totalBeads,
            entries = entries,
            indicesRle = encodeIndices(indices),
        )
        _records.value = (listOf(rec) + _records.value).sortedByDescending { it.timestamp }
        persistRecords()
        return rec
    }

    fun clearRecords() {
        _records.value.forEach {
            runCatching { File(it.srcPath).delete() }
            runCatching { File(it.outPath).delete() }
        }
        _records.value = emptyList()
        persistRecords()
    }
}
