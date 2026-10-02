package com.example.bead.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bead.BeadApplication
import com.example.bead.data.ConversionRecord
import com.example.bead.data.InventoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BeadApplication).repository

    val inventory: StateFlow<List<InventoryEntity>> = repo.inventory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val records: StateFlow<List<ConversionRecord>> = repo.records
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTotal(name: String, total: Int) =
        viewModelScope.launch { repo.setTotal(name, total) }

    fun addTotal(name: String, delta: Int) =
        viewModelScope.launch { repo.addTotal(name, delta) }

    fun initAll(n: Int) =
        viewModelScope.launch { repo.initAll(n) }

    fun resetUsed() =
        viewModelScope.launch { repo.resetUsed() }

    fun deleteRecord(id: Long) = repo.deleteRecord(id)

    fun clearRecords() = repo.clearRecords()

    /** 读取记录里的存档图片。 */
    fun loadImage(path: String): Bitmap? = repo.loadImage(path)
}
