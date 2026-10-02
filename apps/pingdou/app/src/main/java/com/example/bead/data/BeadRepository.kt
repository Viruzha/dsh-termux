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

    suspend fun initAll(n: Int) {
        Palette.colors.forEach { dao.setTotal(it.name, n) }
        dao.resetUsed()
        dao.clearRecords()
    }

    suspend fun resetUsed() {
        dao.resetUsed()
        dao.clearRecords()
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
