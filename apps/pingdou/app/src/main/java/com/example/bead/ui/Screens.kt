package com.example.bead.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

            ZoomablePattern(r)

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
@Composable
private fun ZoomablePattern(r: Pixelizer.ConvertResult) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val bmp = remember(r) { r.preview.asImageBitmap() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val w = maxWidth
            val h = w * r.height / r.width

            Image(
                bitmap = bmp,
                contentDescription = "像素图，可缩放",
                modifier = Modifier
                    .size(w, h)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .graphicsLayer(translationX = offset.x, translationY = offset.y)
                    .pointerInput(r) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val ns = (scale * zoom).coerceIn(1f, 12f)
                            scale = ns
                            offset = if (ns <= 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(r, w, h) {
                        detectTapGestures { pos ->
                            // pos 在内容坐标系里；图像正好铺满 w × h
                            val cx = (pos.x / w.value * r.width).toInt()
                            val cy = (pos.y / h.value * r.height).toInt()
                            picked = if (cx in 0 until r.width && cy in 0 until r.height)
                                cx to cy else null
                        }
                    },
            )
        }

        val p = picked
        when {
            p == null -> Text(
                "双指缩放看细节 · 点某一格查颜色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val ci = Pixelizer.cellIndex(r.indices, r.width, r.height, p.first, p.second)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (ci == Pixelizer.EMPTY) MaterialTheme.colorScheme.outline
                                else Color(Palette.colors[ci].argb())
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ci == Pixelizer.EMPTY)
                            "第 ${p.first + 1} 列 ${p.second + 1} 行：透明（不放豆）"
                        else
                            "第 ${p.first + 1} 列 ${p.second + 1} 行：${Palette.colors[ci].name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
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
                Thumb("原图", src, Modifier.weight(1f))
                Thumb("转换后", out, Modifier.weight(1f))
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
}

/** 记录里的一张小图。 */
@Composable
private fun Thumb(label: String, bmp: android.graphics.Bitmap?, modifier: Modifier = Modifier) {
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
