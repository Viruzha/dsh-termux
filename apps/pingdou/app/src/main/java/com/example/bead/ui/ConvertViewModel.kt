package com.example.bead.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bead.BeadApplication
import com.example.bead.data.Palette
import com.example.bead.domain.Pixelizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

data class ConvertUiState(
    val imageUri: Uri? = null,
    val imageName: String = "",
    val width: Int = 50,
    val dither: Boolean = false,
    val substitute: Boolean = true,
    val maxDist: Float = 80f,
    val busy: Boolean = false,
    val result: Pixelizer.ConvertResult? = null,
    val message: String? = null,
)

class ConvertViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BeadApplication).repository
    private val palette = Palette.colors

    private val _state = MutableStateFlow(ConvertUiState())
    val state: StateFlow<ConvertUiState> = _state.asStateFlow()

    fun loadImage(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        _state.update { it.copy(imageUri = uri, imageName = name, result = null, message = null) }
    }

    fun setWidth(v: Int) = _state.update { it.copy(width = v.coerceIn(8, 200)) }
    fun setDither(v: Boolean) = _state.update { it.copy(dither = v) }
    fun setSubstitute(v: Boolean) = _state.update { it.copy(substitute = v) }
    fun setMaxDist(v: Float) = _state.update { it.copy(maxDist = v) }

    fun discard() = _state.update { it.copy(result = null, message = null) }

    fun convert() {
        val s = _state.value
        val uri = s.imageUri ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, result = null, message = null) }
            try {
                val ctx = getApplication<Application>()
                val srcBmp = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } ?: throw IllegalStateException("无法读取图片")

                val w = s.width
                val h = max(1, (srcBmp.height.toDouble() / srcBmp.width * w).roundToInt())

                val result = withContext(Dispatchers.Default) {
                    // 1. 缩放
                    val pixels = Pixelizer.downsample(srcBmp, w, h)
                    // 2. 匹配色板
                    val idx = if (s.dither)
                        Pixelizer.quantizeWithDither(pixels, w, h, palette)
                    else
                        Pixelizer.quantize(pixels, palette)
                    // 3. 按库存分配
                    val remaining = repo.snapshot()
                    val alloc = Pixelizer.allocate(
                        idx, palette, remaining, s.substitute, s.maxDist.toDouble()
                    )
                    // 4. 渲染预览
                    val preview = Pixelizer.render(alloc.indices, w, h, palette, 16, true)

                    Pixelizer.ConvertResult(
                        width = w, height = h,
                        indices = alloc.indices,
                        preview = preview,
                        entries = alloc.entries,
                        shortages = alloc.shortages,
                    )
                }

                _state.update { it.copy(busy = false, result = result) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "转换失败：${e.message}") }
            }
        }
    }

    /** 确认扣减库存并写日志 */
    fun commit() {
        val r = _state.value.result ?: return
        val name = _state.value.imageName.ifBlank { "未命名" }
        viewModelScope.launch {
            repo.applyUsage(name, r.entries)
            _state.update {
                it.copy(result = null, message = "已扣减库存并记录用量")
            }
        }
    }
}
