package com.pinpin.app.overlay

internal object PinColorPanelGeometry {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun position(screen: Bounds, width: Int, height: Int): Pair<Int, Int> {
        val x = screen.left + ((screen.right - screen.left - width) / 2).coerceAtLeast(0)
        val y = (screen.bottom - height).coerceAtLeast(screen.top)
        return x to y
    }
}
