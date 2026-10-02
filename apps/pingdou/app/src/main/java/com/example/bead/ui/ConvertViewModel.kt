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
    val substitute: Boolean = true,
    val maxDist: Float = 80f,
    val busy: Boolean = false,
    val result: Pixelizer.ConvertResult? = null,
    /** 转换用的原图，确认时一并存档。 */
    val srcBitmap: Bitmap? = null,
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
                    // 只取「最近的调色板颜色」，不做抖动。
                    // 抖动本意是让渐变更自然，但实测在这个 48 色实物色板上恰恰相反：
                    // 局部混色误差只改善 ~10 分（38.7→27.5），而相邻格相异率从 3%
                    // 飙到 29~53% —— 实物拼豆时每一次换色都是真实操作，
                    // 这个代价远大于收益。详见 README 的 v1.7 说明。
                    val idx = Pixelizer.quantize(pixels, palette)
                    // 3. 按库存分配
                    val remaining = repo.snapshot()
                    val alloc = Pixelizer.allocate(
                        idx, palette, remaining, s.substitute, s.maxDist.toDouble()
                    )
                    // 4. 渲染预览
                    val preview = Pixelizer.render(alloc.indices, w, h, palette, 16, true)

                    // 统计：需要放的豆子总数 + 透明格数量
                    var empty = 0
                    for (i in alloc.indices) if (i == Pixelizer.EMPTY) empty++
                    val total = alloc.entries.sumOf { it.count }

                    Pixelizer.ConvertResult(
                        width = w, height = h,
                        indices = alloc.indices,
                        preview = preview,
                        entries = alloc.entries,
                        shortages = alloc.shortages,
                        totalBeads = total,
                        emptyCells = empty,
                    )
                }

                _state.update { it.copy(busy = false, result = result, srcBitmap = srcBmp) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "转换失败：${e.message}") }
            }
        }
    }

    /** 确认扣减库存，并把原图 + 转换图 + 总豆数存档 */
    fun commit() {
        val st = _state.value
        val r = st.result ?: return
        val name = st.imageName.ifBlank { "未命名" }
        viewModelScope.launch {
            try {
                repo.commitConversion(
                    imageName = name,
                    src = st.srcBitmap,
                    preview = r.preview,
                    cols = r.width,
                    rows = r.height,
                    totalBeads = r.totalBeads,
                    entries = r.entries,
                    indices = r.indices,
                )
                val extra = if (r.emptyCells > 0) "（跳过 ${r.emptyCells} 个透明格）" else ""
                _state.update {
                    it.copy(result = null, message = "已扣减并记录：共 ${r.totalBeads} 颗$extra")
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = "记录失败：${e.message}") }
            }
        }
    }
}
