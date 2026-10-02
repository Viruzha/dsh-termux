package com.example.bead.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.bead.data.ConversionRecord
import com.example.bead.data.InventoryEntity
import com.example.bead.data.Palette
import com.example.bead.domain.Pixelizer
import kotlin.math.roundToInt

// ------------------------------------------------------------
// 底部导航 + 路由
// ------------------------------------------------------------
@Composable
fun BeadApp() {
    val nav = rememberNavController()
    val tabs = listOf(
        Triple("convert", "转换", Icons.Default.PhotoCamera),
        Triple("inventory", "库存", Icons.Default.Inventory2),
        Triple("logs", "记录", Icons.Default.History),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val entry by nav.currentBackStackEntryAsState()
                val cur = entry?.destination?.route
                tabs.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = cur == route,
                        onClick = {
                            nav.navigate(route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "convert", modifier = Modifier.padding(pad)) {
            composable("convert") { ConvertScreen() }
            composable("inventory") { InventoryScreen() }
            composable("logs") { LogScreen() }
        }
    }
}

// ------------------------------------------------------------
// 转换页
// ------------------------------------------------------------
@Composable
fun ConvertScreen(vm: ConvertViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResultCompat { uri -> vm.loadImage(uri) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = picker,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.imageUri == null) "选择图片" else "换一张图片")
        }

        state.imageUri?.let { uri ->
            Card(Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        Text("宽度 ${state.width} 颗", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = state.width.toFloat(),
            onValueChange = { vm.setWidth(it.roundToInt()) },
            valueRange = 16f..160f,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("抖动（渐变更自然）", Modifier.weight(1f))
            Switch(checked = state.dither, onCheckedChange = vm::setDither)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("库存不足时用相近色替代", Modifier.weight(1f))
            Switch(checked = state.substitute, onCheckedChange = vm::setSubstitute)
        }

        if (state.substitute) {
            Text("最大色差 ${state.maxDist.roundToInt()}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = state.maxDist,
                onValueChange = vm::setMaxDist,
                valueRange = 20f..200f,
            )
        }

        Button(
            onClick = vm::convert,
            enabled = state.imageUri != null && !state.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.busy) "转换中…" else "开始转换")
        }

        state.message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        state.result?.let { r ->
            ResultCard(r, onCommit = vm::commit, onDiscard = vm::discard)
        }
    }
}

@Composable
private fun ResultCard(
    r: Pixelizer.ConvertResult,
    onCommit: () -> Unit,
    onDiscard: () -> Unit,
) {
    var fullScreen by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "网格 ${r.width} × ${r.height}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildString {
                    append("需要 ${r.totalBeads} 颗豆")
                    if (r.emptyCells > 0) append("　·　跳过 ${r.emptyCells} 个透明格")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 卡片里只放静态预览 —— 这里宽度有限，像素图会小到看不清。
            // 点一下开全屏查看器，那才是真正用来数格子的界面。
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { fullScreen = true },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = remember(r) { r.preview.asImageBitmap() },
                    contentDescription = "预览，点开全屏",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                "点图片全屏查看（可缩放、可查色号）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            HorizontalDivider()
            Text("用色清单", style = MaterialTheme.typography.titleSmall)

            r.entries.sortedByDescending { it.count }.forEach { e ->
                val c = Palette.byName(e.color)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(c.argb()))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${e.color} × ${e.count}", Modifier.weight(1f))
                    if (e.substitutedFrom != null) {
                        Text(
                            "替代 ${e.substitutedFrom}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            if (r.shortages.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "库存不足",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
                r.shortages.forEach { (k, v) ->
                    Text("$k 缺 $v 颗", color = MaterialTheme.colorScheme.error)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCommit, modifier = Modifier.weight(1f)) {
                    Text("确认扣减库存")
                }
                OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                    Text("放弃")
                }
            }
        }
    }

    if (fullScreen) {
        FullScreenPattern(
            title = "转换预览",
            subtitle = "共 ${r.totalBeads} 颗豆　·　${r.width} × ${r.height}" +
                    if (r.emptyCells > 0) "　·　跳过 ${r.emptyCells} 个透明格" else "",
            bmp = remember(r) { r.preview.asImageBitmap() },
            indices = r.indices,
            cols = r.width,
            rows = r.height,
            onClose = { fullScreen = false },
        )
    }
}

/**
 * 可缩放、可点按取色的像素图。
 *
 * 实际拼豆时是对着屏幕数格子的，所以需要：
 *  - **双指缩放 + 拖动**：放大到能看清每一格
 *  - **点某一格显示颜色名**：直接告诉你是哪种豆，不用去猜
 *
 * 坐标换算的关键：图像用固定尺寸铺满，**不用 ContentScale.Fit**
 * （Fit 会留黑边，点按坐标就对不上了）。这样内容坐标与图像坐标是线性关系。
 */
/**
 * 可缩放、可点按取色的像素图。
 *
 * 在**给定空间内等比适配**：宽、高两个方向各算一个缩放比，取小的那个
 * （否则宽高比不匹配时会溢出）。所以它既能塞进卡片，也能铺满全屏。
 *
 * 坐标换算的关键：图像用精确尺寸铺满、**不用 `ContentScale.Fit`** ——
 * Fit 会留黑边，点按坐标就对不上了。
 */
@Composable
private fun ZoomablePattern(
    bmp: ImageBitmap,
    cols: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    onPick: (Int, Int) -> Unit = { _, _ -> },
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val availW = maxWidth
        val availH = maxHeight
        val aspect = cols.toFloat() / rows.toFloat()
        val w: Dp
        val h: Dp
        if (availH <= 0.dp || availW / availH > aspect) {
            h = availH; w = availH * aspect
        } else {
            w = availW; h = availW / aspect
        }

        Image(
            bitmap = bmp,
            contentDescription = "像素图，可缩放",
            modifier = Modifier
                .size(w, h)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .graphicsLayer(translationX = offset.x, translationY = offset.y)
                // ⚠️ 缩放与点按必须在**同一个** pointerInput 里处理。
                // 拆成两个 pointerInput 时，detectTransformGestures 会吃掉事件，
                // 后面的 detectTapGestures 收不到 —— 实测点按完全没反应。
                .pointerInput(cols, rows) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        var moved = false

                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) break

                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                if (!moved &&
                                    (pan.getDistance() > viewConfiguration.touchSlop || zoom != 1f)
                                ) moved = true
                                if (moved) {
                                    val ns = (scale * zoom).coerceIn(1f, 16f)
                                    scale = ns
                                    offset = if (ns <= 1f) Offset.Zero else offset + pan
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        }

                        // 没拖动过 → 当作点按
                        if (!moved) {
                            val cx = (downPos.x / size.width * cols).toInt()
                            val cy = (downPos.y / size.height * rows).toInt()
                            if (cx in 0 until cols && cy in 0 until rows) onPick(cx, cy)
                        }
                    }
                },
        )
    }
}

/** 把某一格的查色结果渲染成一行。 */
@Composable
private fun PickedLabel(cell: Pair<Int, Int>?, indices: IntArray, cols: Int, rows: Int) {
    if (cell == null) {
        Text(
            "双指缩放看细节 · 点某一格查颜色",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val ci = if (cell.first in 0 until cols && cell.second in 0 until rows)
        indices[cell.second * cols + cell.first] else Pixelizer.EMPTY
    val valid = ci in Palette.colors.indices
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (valid) Color(Palette.colors[ci].argb())
                    else MaterialTheme.colorScheme.outline
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (valid) "第 ${cell.first + 1} 列 ${cell.second + 1} 行：${Palette.colors[ci].name}"
            else "第 ${cell.first + 1} 列 ${cell.second + 1} 行：透明（不放豆）",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 全屏查看器。
 *
 * 卡片的宽度会把像素图压得很小，实际对着屏幕数格子时根本看不清 ——
 * 所以单独给一个铺满屏幕的查看界面，顶栏给标题、底栏常驻显示选中的格子。
 */
@Composable
private fun FullScreenPattern(
    title: String,
    subtitle: String,
    bmp: ImageBitmap,
    indices: IntArray,
    cols: Int,
    rows: Int,
    onClose: () -> Unit,
) {
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Dialog(
        onDismissRequest = onClose,
        // 少了这个，Dialog 会按平台默认宽度（约 90%）收窄，白费屏幕
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0F14)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9AA7B4),
                        )
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                ZoomablePattern(
                    bmp = bmp,
                    cols = cols,
                    rows = rows,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp),
                    onPick = { x, y -> picked = x to y },
                )

                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PickedLabel(picked, indices, cols, rows)
                }
            }
        }
    }
}

// ------------------------------------------------------------
// 库存页
// ------------------------------------------------------------
@Composable
fun InventoryScreen(vm: InventoryViewModel = viewModel()) {
    val list by vm.inventory.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<InventoryEntity?>(null) }
    var showInit by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showInit = true },
                modifier = Modifier.weight(1f)
            ) { Text("批量设置库存") }

            OutlinedButton(
                onClick = vm::resetUsed,
                modifier = Modifier.weight(1f)
            ) { Text("清零已用") }
        }

        val total = list.sumOf { it.total }
        val used = list.sumOf { it.used }
        Text(
            "总量 $total　已用 $used　剩余 ${total - used}",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.name }) { item ->
                InventoryRow(item, onClick = { editing = item })
                HorizontalDivider()
            }
        }
    }

    editing?.let { item ->
        EditStockDialog(
            item = item,
            onDismiss = { editing = null },
            onSet = { v -> vm.setTotal(item.name, v); editing = null },
            onAdd = { v -> vm.addTotal(item.name, v); editing = null },
        )
    }

    if (showInit) {
        var text by remember { mutableStateOf("500") }
        AlertDialog(
            onDismissRequest = { showInit = false },
            title = { Text("所有颜色库存设为") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    label = { Text("数量") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { vm.initAll(it) }
                    showInit = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showInit = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InventoryRow(item: InventoryEntity, onClick: () -> Unit) {
    val remain = (item.total - item.used).coerceAtLeast(0)
    val progress = if (item.total == 0) 0f
    else (item.used.toFloat() / item.total).coerceIn(0f, 1f)

    Row(
        Modifier
            .fillMaxWidth()
            .clickableRow(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(Palette.byName(item.name).argb()))
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text("剩 $remain", fontWeight = FontWeight.Medium)
            Text(
                "总 ${item.total} / 用 ${item.used}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditStockDialog(
    item: InventoryEntity,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
    onAdd: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val remain = item.total - item.used

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.name}　剩余 $remain") },
        text = {
            Column {
                Text("总量 ${item.total}　已用 ${item.used}",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    label = { Text("数量") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.toIntOrNull() ?: 0
                if (v > 0) onSet(v) else onDismiss()
            }) { Text("设为") }
        },
        dismissButton = {
            TextButton(onClick = {
                val v = text.toIntOrNull() ?: 0
                if (v > 0) onAdd(v) else onDismiss()
            }) { Text("追加") }
        },
    )
}

// ------------------------------------------------------------
// 记录页
// ------------------------------------------------------------
@Composable
fun LogScreen(vm: InventoryViewModel = viewModel()) {
    val records by vm.records.collectAsStateWithLifecycle()

    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无使用记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(records, key = { it.id }) { rec -> RecordCard(rec, vm) }
    }
}

/**
 * 一条转换记录：原图 + 转换后的像素图 + 总豆数 + 分色明细。
 *
 * 存两张图是刻意的 —— 实际拼豆时是「对着原图看效果、对着像素图数格子」，
 * 少了哪一张都不方便。
 */
@Composable
private fun RecordCard(rec: ConversionRecord, vm: InventoryViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val src = remember(rec.id, rec.srcPath) { vm.loadImage(rec.srcPath) }
    val out = remember(rec.id, rec.outPath) { vm.loadImage(rec.outPath) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 点缩略图后全屏放大；只有「转换后」的图能查色号（原图没有格点信息）
    var zoomOut by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rec.imageName.ifBlank { "未命名" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(rec.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Thumb("原图", src, Modifier.weight(1f)) { }
                Thumb("转换后", out, Modifier.weight(1f)) { zoomOut = true }
            }

            Text(
                buildString {
                    append("共 ${rec.totalBeads} 颗豆")
                    append("　·　${rec.cols} × ${rec.rows}")
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起分色明细" else "展开分色明细（${rec.entries.size} 色）")
            }

            if (expanded) {
                rec.entries.sortedByDescending { it.count }.forEach { e ->
                    val c = Palette.byName(e.color)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(c.argb()))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${e.color} × ${e.count}", Modifier.weight(1f))
                        if (e.substitutedFrom != null) {
                            Text(
                                "替代 ${e.substitutedFrom}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("原图与转换图会一并删除，且无法恢复。库存扣减不会回滚。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRecord(rec.id); confirmDelete = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }

    if (zoomOut && out != null) {
        FullScreenPattern(
            title = rec.imageName.ifBlank { "未命名" },
            subtitle = "共 ${rec.totalBeads} 颗豆　·　${rec.cols} × ${rec.rows}",
            bmp = remember(rec.id) { out.asImageBitmap() },
            indices = remember(rec.id) { rec.indices() },
            cols = rec.cols,
            rows = rec.rows,
            onClose = { zoomOut = false },
        )
    }
}

/** 记录里的一张小图；点一下可全屏放大看。 */
@Composable
private fun Thumb(
    label: String,
    bmp: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("（图片已丢失）", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (onClick != null) {
            Text(
                "点一下放大",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ------------------------------------------------------------
// 小工具
// ------------------------------------------------------------
@Composable
private fun rememberLauncherForActivityResultCompat(onPick: (android.net.Uri) -> Unit): () -> Unit {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onPick) }
    return { launcher.launch("image/*") }
}

@Composable
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickableCompat(onClick)
    )

/**
 * 统一的可点击修饰符。
 *
 * 不用 `rememberRipple()` —— 它在 Compose 1.7 起已废弃（且是 error 级）。
 * `Modifier.clickable(onClick = ...)` 这个重载会自动取 `LocalIndication`
 * （即当前主题的涟漪），不用手动传 interactionSource / indication。
 */
@Composable
private fun Modifier.clickableCompat(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
