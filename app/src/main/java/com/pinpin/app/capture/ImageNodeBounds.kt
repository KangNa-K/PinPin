package com.pinpin.app.capture

/** Widget bounds retain white margins that pixel segmentation cannot identify. */
internal object ImageNodeBounds {
    fun select(images: List<CaptureBounds>, window: CaptureBounds, x: Int, y: Int,
               minimumSize: Int): CaptureBounds? = images.mapNotNull { image ->
        val clipped = CaptureBounds(maxOf(image.left, window.left), maxOf(image.top, window.top),
            minOf(image.right, window.right), minOf(image.bottom, window.bottom))
        clipped.takeIf { it.width >= minimumSize && it.height >= minimumSize &&
            x in it.left until it.right && y in it.top until it.bottom }
    }.maxByOrNull { it.width.toLong() * it.height }
}
