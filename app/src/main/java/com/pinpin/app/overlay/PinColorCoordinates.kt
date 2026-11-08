package com.pinpin.app.overlay

internal object PinColorCoordinates {
    fun raw(x: Int, y: Int, width: Int, height: Int, orientation: Int): Pair<Int, Int> = when (orientation) {
        2 -> width - 1 - x to y
        3 -> width - 1 - x to height - 1 - y
        4 -> x to height - 1 - y
        5 -> y to x
        6 -> y to height - 1 - x
        7 -> width - 1 - y to height - 1 - x
        8 -> width - 1 - y to x
        else -> x to y
    }

    fun imageDelta(x: Int, y: Int, rotation: Int, flipped: Boolean): Pair<Int, Int> {
        val fx = if (flipped) -x else x
        return when (((rotation % 360) + 360) % 360) {
            90 -> y to -fx
            180 -> -fx to -y
            270 -> -y to fx
            else -> fx to y
        }
    }
}
