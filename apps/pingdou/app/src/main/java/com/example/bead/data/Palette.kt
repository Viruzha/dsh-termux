package com.example.bead.data

data class BeadColor(val name: String, val r: Int, val g: Int, val b: Int) {
    val hex: String get() = "#%02X%02X%02X".format(r, g, b)
    fun argb(): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

object Palette {
    val colors: List<BeadColor> = listOf(
        BeadColor("白", 255, 255, 255),
        BeadColor("奶白", 247, 240, 214),
        BeadColor("米色", 235, 215, 180),
        BeadColor("卡其", 200, 185, 150),
        BeadColor("浅黄", 255, 242, 150),
        BeadColor("黄", 255, 215, 0),
        BeadColor("柠檬黄", 230, 235, 90),
        BeadColor("青柠", 200, 230, 80),
        BeadColor("金", 215, 175, 60),
        BeadColor("浅橙", 255, 190, 110),
        BeadColor("橙", 250, 145, 30),
        BeadColor("橙红", 240, 90, 40),
        BeadColor("珊瑚", 250, 120, 100),
        BeadColor("浅粉", 255, 199, 206),
        BeadColor("粉", 255, 140, 160),
        BeadColor("玫红", 230, 60, 110),
        BeadColor("紫红", 200, 50, 140),
        BeadColor("红", 215, 0, 30),
        BeadColor("深红", 150, 0, 50),
        BeadColor("酒红", 130, 20, 40),
        BeadColor("肤色", 250, 220, 190),
        BeadColor("浅棕", 205, 170, 125),
        BeadColor("棕", 140, 90, 50),
        BeadColor("咖啡", 110, 75, 60),
        BeadColor("深棕", 85, 55, 35),
        BeadColor("赭石", 190, 130, 80),
        BeadColor("橄榄", 110, 120, 50),
        BeadColor("黄绿", 170, 210, 70),
        BeadColor("浅绿", 140, 215, 140),
        BeadColor("薄荷", 170, 230, 200),
        BeadColor("绿", 30, 170, 80),
        BeadColor("深绿", 0, 110, 60),
        BeadColor("青绿", 0, 175, 165),
        BeadColor("湖蓝", 30, 140, 180),
        BeadColor("浅蓝", 150, 215, 240),
        BeadColor("天蓝", 60, 175, 230),
        BeadColor("蓝", 0, 110, 200),
        BeadColor("深蓝", 10, 45, 120),
        BeadColor("藏青", 30, 50, 90),
        BeadColor("淡紫", 190, 170, 225),
        BeadColor("紫", 130, 80, 180),
        BeadColor("深紫", 75, 45, 120),
        BeadColor("浅灰蓝", 175, 190, 205),
        BeadColor("浅灰", 200, 200, 200),
        BeadColor("银", 185, 190, 195),
        BeadColor("灰", 140, 140, 140),
        BeadColor("深灰", 80, 80, 80),
        BeadColor("黑", 25, 25, 25),
    )

    private val map = colors.associateBy { it.name }

    fun byName(name: String): BeadColor = map[name] ?: colors.first()
    fun indexOf(name: String): Int = colors.indexOfFirst { it.name == name }
}
