package com.pinpin.app.overlay

import com.pinpin.app.overlay.DrawingOverlayView.Point
import com.pinpin.app.overlay.DrawingOverlayView.RecognizedShape
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import java.util.Random

class ShapeRecognizerTest {
    private fun p(x: Float, y: Float) = Point(x, y, 0.7f)
    private fun polygon(vararg vertices: Point): List<Point> = vertices.indices.flatMap { i ->
        val a = vertices[i]
        val b = vertices[(i + 1) % vertices.size]
        (0 until 30).map { j ->
            val t = j / 30f
            p(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }.let { it + it.first() }
    private fun oval(rx: Float, ry: Float, turns: Float = 1f) = (0..180).map {
        val angle = it * Math.PI * 2 * turns / 180
        p(200 + rx * cos(angle).toFloat(), 200 + ry * sin(angle).toFloat())
    }
    private fun rotate(points: List<Point>, angle: Float) = points.map {
        p(300 + it.x * cos(angle) - it.y * sin(angle), 300 + it.x * sin(angle) + it.y * cos(angle))
    }
    private fun shifted(points: List<Point>, offset: Int): List<Point> {
        val ring = points.dropLast(1)
        val result = ring.drop(offset) + ring.take(offset)
        return result + result.first()
    }
    private fun expect(shape: RecognizedShape, points: List<Point>) {
        val result = ShapeRecognizer.recognize(points)
        assertEquals(shape, result.shape)
        if (shape == RecognizedShape.NONE) assertEquals(points, result.points)
        else assertTrue(result.points.all { it.pressure == 0.7f || kotlin.math.abs(it.pressure - 0.7f) < 0.0001f })
    }

    @Test fun linesIncludeTwoPointFastGesturesAndRejectCurves() {
        expect(RecognizedShape.LINE, listOf(p(10f, 20f), p(200f, 120f)))
        expect(RecognizedShape.LINE, (0..100).map { p(it * 2f, sin(it.toFloat()) * 1.5f) })
        expect(RecognizedShape.NONE, (0..100).map { p(it * 2f, sin(it / 100f * Math.PI).toFloat() * 35f) })
        expect(RecognizedShape.NONE, listOf(p(0f, 0f), p(100f, 0f), p(0f, 0f)))
    }

    @Test fun rectanglesAreIndependentOfStartDirectionAndRotation() {
        val rectangle = polygon(p(0f, 0f), p(180f, 0f), p(180f, 100f), p(0f, 100f))
        for (angle in listOf(0f, 0.3f, 0.78f, 1.5f)) {
            for (offset in listOf(0, 15, 30, 49, 90)) {
                for (points in listOf(shifted(rectangle, offset), shifted(rectangle, offset).reversed())) {
                    val result = ShapeRecognizer.recognize(rotate(points, angle))
                    assertEquals("angle=$angle offset=$offset", RecognizedShape.RECTANGLE, result.shape)
                    val a = result.points[0]
                    val b = result.points[1]
                    val c = result.points[2]
                    assertEquals(0f, (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y), 0.1f)
                    assertEquals(result.points.first(), result.points.last())
                }
            }
        }
    }

    @Test fun trianglesKeepVerticesRegardlessOfStartPoint() {
        val triangle = polygon(p(0f, 0f), p(160f, 20f), p(50f, 140f))
        for (offset in listOf(0, 10, 30, 60)) {
            expect(RecognizedShape.TRIANGLE, shifted(triangle, offset))
            expect(RecognizedShape.TRIANGLE, shifted(triangle, offset).reversed())
        }
    }

    @Test fun circlesAndRotatedEllipsesKeepTheirProportions() {
        expect(RecognizedShape.CIRCLE, oval(90f, 90f))
        expect(RecognizedShape.CIRCLE, oval(90f, 90f).reversed())
        for (angle in listOf(0f, 0.4f, 1.2f)) {
            val result = ShapeRecognizer.recognize(rotate(oval(140f, 55f), angle))
            assertEquals(RecognizedShape.ELLIPSE, result.shape)
            val major = hypot(result.points[0].x - result.points[32].x, result.points[0].y - result.points[32].y)
            val minor = hypot(result.points[16].x - result.points[48].x, result.points[16].y - result.points[48].y)
            assertEquals(140f / 55f, maxOf(major, minor) / minOf(major, minor), 0.08f)
        }
    }

    @Test fun mildJitterAndUnevenSamplingStillRecognize() {
        val rectangle = polygon(p(0f, 0f), p(180f, 0f), p(180f, 100f), p(0f, 100f))
        val jitter = rectangle.mapIndexed { i, it -> it.copy(x = it.x + sin(i * 1.7f) * 1.5f, y = it.y + cos(i * 1.3f) * 1.5f) }
        expect(RecognizedShape.RECTANGLE, jitter)
        val circle = oval(90f, 90f)
        expect(RecognizedShape.CIRCLE, circle.flatMapIndexed { i, point -> List(if (i < 40) 20 else 1) { point } })
        expect(RecognizedShape.CIRCLE, circle.dropLast(5))
    }

    @Test fun doodlesAndOpenArcsAreNotForcedIntoShapes() {
        expect(RecognizedShape.NONE, oval(90f, 90f, 2f))
        expect(RecognizedShape.NONE, oval(90f, 90f, 0.7f))
        expect(RecognizedShape.NONE, polygon(p(0f, 0f), p(140f, 140f), p(0f, 140f), p(140f, 0f)))
        expect(RecognizedShape.NONE, polygon(p(0f, 0f), p(150f, 0f), p(70f, 50f), p(150f, 150f), p(0f, 150f)))
    }

    @Test fun handDrawnPolygonsTolerateWavyEdgesAndRoundedCorners() {
        val shapes = listOf(
            RecognizedShape.TRIANGLE to arrayOf(p(0f, 0f), p(170f, 15f), p(60f, 150f)),
            RecognizedShape.RECTANGLE to arrayOf(p(0f, 0f), p(180f, 0f), p(180f, 110f), p(0f, 110f)),
            RecognizedShape.RECTANGLE to arrayOf(p(0f, 0f), p(130f, 0f), p(130f, 130f), p(0f, 130f)),
        )
        for ((shape, vertices) in shapes) {
            val ring = polygon(*vertices).dropLast(1)
            val rounded = ring.indices.map { i ->
                val neighbors = (-3..3).map { ring[(i + it + ring.size) % ring.size] }
                p(neighbors.map { it.x }.average().toFloat() + sin(i * 1.7f) * 4f,
                    neighbors.map { it.y }.average().toFloat() + cos(i * 1.3f) * 4f)
            }.let { it + it.first() }
            for (angle in listOf(0f, 0.6f, 1.3f)) {
                expect(shape, rotate(rounded, angle))
                expect(shape, rotate(shifted(rounded, 13).reversed(), angle))
            }
        }
    }

    @Test fun trapezoidsAndParallelogramsAreNotRecognizedAsRectangles() {
        for (vertices in listOf(
            arrayOf(p(0f, 0f), p(180f, 0f), p(120f, 100f), p(50f, 100f)),
            arrayOf(p(40f, 0f), p(180f, 0f), p(140f, 100f), p(0f, 100f)),
        )) {
            for (angle in listOf(0f, 0.7f)) {
                expect(RecognizedShape.NONE, rotate(polygon(*vertices), angle))
            }
        }
    }

    @Test fun nearSquaresBecomeEqualSidedWithoutSquaringLongRectangles() {
        for (width in listOf(120f, 126f, 180f)) {
            for (angle in listOf(0f, 0.7f)) {
                val result = ShapeRecognizer.recognize(rotate(
                    polygon(p(0f, 0f), p(width, 0f), p(width, 120f), p(0f, 120f)), angle))
                assertEquals(RecognizedShape.RECTANGLE, result.shape)
                val a = result.points[0]
                val b = result.points[1]
                val c = result.points[2]
                val firstSide = hypot(a.x - b.x, a.y - b.y)
                val secondSide = hypot(b.x - c.x, b.y - c.y)
                if (width < 180f) assertEquals(firstSide, secondSide, 0.001f)
                else assertEquals(1.5f, maxOf(firstSide, secondSide) / minOf(firstSide, secondSide), 0.03f)
                assertEquals(0f, (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y), 0.1f)
            }
        }
    }

    @Test fun imperfectCirclesTolerateJitterAndSmallClosureGaps() {
        val circle = oval(95f, 86f).mapIndexed { i, point ->
            point.copy(x = point.x + sin(i * 1.7f) * 4f,
                y = point.y + cos(i * 1.3f) * 4f)
        }
        for (angle in listOf(0f, 0.5f, 1.2f)) {
            expect(RecognizedShape.CIRCLE, rotate(circle, angle))
            expect(RecognizedShape.CIRCLE, rotate(circle.dropLast(9).reversed(), angle))
        }
        expect(RecognizedShape.NONE, polygon(p(0f, 0f), p(100f, -30f), p(180f, 50f),
            p(140f, 150f), p(20f, 140f)))
    }

    @Test fun fittingDoesNotConvertOtherPolygonsOrRetracedLoops() {
        for (count in listOf(5, 6)) {
            val vertices = (0 until count).map {
                val angle = it * Math.PI * 2 / count
                p(100f * cos(angle).toFloat(), 100f * sin(angle).toFloat())
            }
            expect(RecognizedShape.NONE, polygon(*vertices.toTypedArray()))
        }
        expect(RecognizedShape.NONE, oval(90f, 90f, 1.15f))
        expect(RecognizedShape.NONE, oval(90f, 90f, 0.8f))
        val triangle = polygon(p(0f, 0f), p(170f, 15f), p(60f, 150f))
        expect(RecognizedShape.NONE, triangle + triangle.drop(1))
    }

    @Test fun tinyRepeatedAndInvalidInputIsUnchanged() {
        expect(RecognizedShape.NONE, emptyList())
        expect(RecognizedShape.NONE, List(100) { p(10f, 10f) })
        expect(RecognizedShape.NONE, oval(3f, 3f))
        expect(RecognizedShape.NONE, listOf(p(Float.NaN, 0f), p(100f, 100f)))
        assertEquals(RecognizedShape.NONE, ShapeRecognizer.recognize(listOf(p(0f, 0f), p(30f, 0f)), 3f).shape)
    }

    @Test fun denseInputRemainsBoundedAndClosed() {
        val dense = (0..20000).map {
            val angle = it * Math.PI * 2 / 20000
            p(200 + 100 * cos(angle).toFloat(), 200 + 100 * sin(angle).toFloat())
        }
        val result = ShapeRecognizer.recognize(dense)
        assertEquals(RecognizedShape.CIRCLE, result.shape)
        assertEquals(65, result.points.size)
        assertEquals(result.points.first(), result.points.last())
    }

    @Test fun fastSparsePolygonsAreRecognized() {
        val triangle = listOf(p(0f, 0f), p(160f, 0f), p(50f, 140f), p(0f, 0f))
        val rectangle = listOf(p(0f, 0f), p(180f, 0f), p(180f, 100f), p(0f, 100f), p(0f, 0f))
        for ((shape, points) in listOf(RecognizedShape.TRIANGLE to triangle,
            RecognizedShape.RECTANGLE to rectangle)) {
            for (angle in listOf(0f, 0.4f, 1.2f)) {
                expect(shape, rotate(points, angle))
                expect(shape, rotate(points.reversed(), angle))
            }
        }
    }

    @Test fun almostClosedShapesDoNotRequirePreciseEndpointAlignment() {
        val rectangle = polygon(p(0f, 0f), p(180f, 0f), p(180f, 110f), p(0f, 110f))
        val triangle = polygon(p(0f, 0f), p(170f, 10f), p(60f, 150f))
        for ((shape, points) in listOf(RecognizedShape.RECTANGLE to rectangle.dropLast(12),
            RecognizedShape.TRIANGLE to triangle.dropLast(7),
            RecognizedShape.CIRCLE to oval(90f, 90f, 0.9f))) {
            for (angle in listOf(0f, 0.7f)) {
                expect(shape, rotate(points, angle))
                expect(shape, rotate(points.reversed(), angle))
            }
        }
    }

    @Test fun slightlySkewedHandDrawnBoxesBecomeRightAngled() {
        val points = polygon(p(0f, 0f), p(180f, 14f), p(172f, 118f), p(9f, 108f))
        for (angle in listOf(0f, 0.4f, 1.1f)) {
            val result = ShapeRecognizer.recognize(rotate(points, angle))
            assertEquals(RecognizedShape.RECTANGLE, result.shape)
            val a = result.points[0]
            val b = result.points[1]
            val c = result.points[2]
            assertEquals(0f, (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y), 0.1f)
        }
    }

    @Test fun handDrawnCasesStayStableAcrossScaleDirectionAndSampling() {
        val rectangle = polygon(p(0f, 0f), p(180f, 14f), p(172f, 118f), p(9f, 108f))
        val triangle = polygon(p(0f, 0f), p(170f, 10f), p(60f, 150f))
        val circle = oval(90f, 90f).mapIndexed { i, point ->
            point.copy(x = point.x + 3f * sin(i * 0.14f),
                y = point.y + 3f * cos(i * 0.19f))
        }
        for ((shape, base) in listOf(RecognizedShape.RECTANGLE to rectangle,
            RecognizedShape.TRIANGLE to triangle, RecognizedShape.CIRCLE to circle)) {
            for (scale in listOf(0.5f, 1f, 3f)) for (offset in listOf(0, 17, 43)) {
                val points = shifted(base, offset).mapIndexed { i, point ->
                    p(1500f + scale * (point.x + sin(i * 1.7f)),
                        1800f + scale * (point.y + cos(i * 1.3f)))
                }
                expect(shape, points)
                expect(shape, points.reversed())
                expect(shape, points.flatMapIndexed { i, point -> List(if (i % 7 == 0) 12 else 1) { point } })
            }
        }
    }

    @Test fun closureToleranceDoesNotCompleteMissingSidesOrLargeArcs() {
        expect(RecognizedShape.NONE, listOf(p(0f, 0f), p(180f, 0f), p(180f, 120f), p(0f, 120f)))
        expect(RecognizedShape.NONE, oval(90f, 90f, 0.85f))
        expect(RecognizedShape.NONE, oval(90f, 90f, 1.1f))
    }

    @Test fun unevenEdgeSamplingAndCornerOvershootKeepPolygonIdentity() {
        fun edge(a: Point, b: Point, count: Int): List<Point> = (0 until count).map { i ->
            val t = i / count.toFloat()
            p(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }

        val rectangleVertices = listOf(p(0f, 0f), p(220f, 0f), p(220f, 120f), p(0f, 120f))
        val rectangle = rectangleVertices.indices.flatMap { i ->
            edge(rectangleVertices[i], rectangleVertices[(i + 1) % rectangleVertices.size],
                listOf(120, 8, 36, 14)[i])
        } + rectangleVertices.first()
        expect(RecognizedShape.RECTANGLE, rectangle)

        val triangleVertices = listOf(p(0f, 0f), p(190f, 14f), p(58f, 165f))
        val triangle = triangleVertices.indices.flatMap { i ->
            edge(triangleVertices[i], triangleVertices[(i + 1) % triangleVertices.size],
                listOf(90, 7, 24)[i])
        } + triangleVertices.first()
        expect(RecognizedShape.TRIANGLE, triangle)
    }

    @Test fun smallCornerOverrunsAreAbsorbedWithoutAcceptingBrokenPolygons() {
        val rectangle = listOf(
            p(0f, 0f), p(92f, -5f), p(185f, 0f),
            p(181f, 56f), p(186f, 116f), p(90f, 111f),
            p(-4f, 118f), p(3f, 60f), p(-5f, 0f), p(0f, 0f),
        )
        expect(RecognizedShape.RECTANGLE, rectangle)

        val broken = listOf(
            p(0f, 0f), p(180f, 0f), p(120f, 52f),
            p(180f, 120f), p(0f, 120f), p(0f, 0f),
        )
        expect(RecognizedShape.NONE, broken)
    }

    @Test fun noisyPolygonsRemainStableAcrossSamplingAndOrientation() {
        val random = Random(292L)
        val cases = listOf(
            RecognizedShape.TRIANGLE to listOf(p(0f, 0f), p(170f, 18f), p(54f, 150f)),
            RecognizedShape.RECTANGLE to listOf(p(0f, 0f), p(150f, 0f), p(150f, 120f), p(0f, 120f)),
            RecognizedShape.RECTANGLE to listOf(p(0f, 0f), p(210f, 0f), p(210f, 105f), p(0f, 105f)),
        )
        repeat(120) { iteration ->
            val (shape, vertices) = cases[iteration % cases.size]
            val scale = listOf(0.65f, 1f, 1.8f)[iteration % 3]
            val jitter = 1.6f * scale
            val points = vertices.indices.flatMap { edgeIndex ->
                val a = vertices[edgeIndex]
                val b = vertices[(edgeIndex + 1) % vertices.size]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val edgeLength = hypot(dx, dy)
                val nx = -dy / edgeLength
                val ny = dx / edgeLength
                val count = 8 + random.nextInt(45)
                (0 until count).map { sampleIndex ->
                    val t = sampleIndex / count.toFloat()
                    val noise = (random.nextFloat() * 2f - 1f) * jitter
                    p((a.x + dx * t + nx * noise) * scale,
                        (a.y + dy * t + ny * noise) * scale)
                }
            } + vertices.first()
            val angle = random.nextFloat() * 2f * Math.PI.toFloat()
            val transformed = rotate(points, angle)
            val withClosureGap = if (iteration % 4 == 0) transformed.dropLast(2) else transformed
            val result = ShapeRecognizer.recognize(withClosureGap)
            assertEquals("iteration=$iteration shape=$shape", shape, result.shape)
        }
    }
}
