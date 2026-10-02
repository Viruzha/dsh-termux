package com.example.bead.data

import com.example.bead.domain.Pixelizer
import kotlinx.coroutines.flow.Flow

class BeadRepository(private val dao: BeadStore) {

    val inventory: Flow<List<InventoryEntity>> = dao.inventory
    val logs: Flow<List<UsageLogEntity>> = dao.logs

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
        dao.clearLog()
    }

    suspend fun resetUsed() {
        dao.resetUsed()
        dao.clearLog()
    }

    /** 当前剩余库存快照 {颜色: 剩余} */
    suspend fun snapshot(): Map<String, Int> =
        dao.getAll().associate { it.name to (it.total - it.used) }

    /** 一次性提交本次用量：扣库存 + 写日志 */
    suspend fun applyUsage(image: String, entries: List<Pixelizer.UsageEntry>) {
        val now = System.currentTimeMillis()
        entries.forEach { dao.addUsed(it.color, it.count) }
        dao.insertLogs(entries.map {
            UsageLogEntity(
                timestamp = now,
                imageName = image,
                color = it.color,
                count = it.count,
                substitutedFrom = it.substitutedFrom,
            )
        })
    }
}
