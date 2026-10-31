package com.pinpin.app.capture

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Manual diagnostic against a local screen-recording frame. Skipped unless the
 * frame exists, so normal unit-test runs do not depend on machine-local files.
 */
class ImageRegionDetectorScratchTest {
    @Test fun inspectRecordingFrame() {
        val frame = File("/tmp/pinpin-rednote/frame-8.png")
        assumeTrue("Local recording frame not present: $frame", frame.isFile)
        val image = ImageIO.read(frame)
        val w = 480
        val h = image.height * w / image.width
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            pixels[y * w + x] = image.getRGB(x * image.width / w, y * image.height / h)
        }
        val result = ImageRegionDetector.detect(pixels, w, h,
            1332 * w / image.width, 448 * h / image.height)
        println("recording detector result=$result size=${image.width}x${image.height} seed=${1332 * w / image.width},${448 * h / image.height}")
    }
}
