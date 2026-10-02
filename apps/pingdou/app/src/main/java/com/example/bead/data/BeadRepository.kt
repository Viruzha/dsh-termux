package com.example.bead.data

import android.graphics.Bitmap
import com.example.bead.domain.Pixelizer
import kotlinx.coroutines.flow.Flow

class BeadRepository(private val dao: BeadStore) {

    val inventory: Flow<List<InventoryEntity>> = dao.inventory

    /** 历次转换记录（含原图与转换后图片的路径）。 */
    val records: Flow<List<ConversionRecord>> = dao.records

    fun loadImage(path: String): Bitmap? = dao.loadImage(path)

    fun deleteRecord(id: Long) = dao.deleteRecord(id)

    fun clearRecords() = dao.clearRecords()

    /** 首次启动把色板灌进数据库（已存在则忽略） */
    suspend fun ensureSeeded() {
        dao.insertAll(Palette.colors.map { InventoryEntity(it.name, it.hex, 0, 0) })
    }

    suspend fun setTotal(name: String, total: Int) =
        dao.setTotal(name, total.coerceAtLeast(0))

    suspend fun addTotal(name: String, delta: Int) =
        dao.addTotal(name, delta)

    /**
     * 批量设置每种颜色的库存总量，并把已用清零。
     *
     * ⚠️ **不动历史记录** —— 库存是「现在有多少豆」，历史是「以前做过什么」，
     * 两者无关。原本这里跟着原方案写了 `clearRecords()`，但那时"日志"是
     * 每色一行的用量明细、和 used 计数器绑定；改成带图片的转换记录之后，
     * 它已经是独立历史了，再一起清就是误删。
     */
    suspend fun initAll(n: Int) {
        Palette.colors.forEach { dao.setTotal(it.name, n) }
        dao.resetUsed()
    }

    /** 把「已用」计数清零（不改总量，更不碰历史记录）。 */
    suspend fun resetUsed() {
        dao.resetUsed()
    }

    /** 当前剩余库存快照 {颜色: 剩余} */
    suspend fun snapshot(): Map<String, Int> =
        dao.getAll().associate { it.name to (it.total - it.used) }

    /**
     * 一次性提交本次转换：扣库存 + 存档（原图、转换图、总豆数、分色明细）。
     *
     * 图片存进应用私有目录，记录里只留路径 —— 不然 JSON 会被 base64 撑爆。
     */
    suspend fun commitConversion(
        imageName: String,
        src: Bitmap?,
        preview: Bitmap,
        cols: Int,
        rows: Int,
        totalBeads: Int,
        entries: List<Pixelizer.UsageEntry>,
        indices: IntArray,
    ): ConversionRecord {
        entries.forEach { dao.addUsed(it.color, it.count) }
        return dao.addRecord(
            imageName = imageName,
            src = src,
            preview = preview,
            cols = cols,
            rows = rows,
            totalBeads = totalBeads,
            entries = entries.map { RecordEntry(it.color, it.count, it.substitutedFrom) },
            indices = indices,
        )
    }
}
