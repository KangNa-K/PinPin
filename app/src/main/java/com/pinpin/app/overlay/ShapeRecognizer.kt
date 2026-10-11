package com.pinpin.app.overlay

import com.pinpin.app.overlay.DrawingOverlayView.Point
import com.pinpin.app.overlay.DrawingOverlayView.RecognizedShape
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry-only recognition. Uncertain input is returned unchanged, never guessed. */
internal object ShapeRecognizer {
    data class Result(val shape: RecognizedShape, val points: List<Point>)

    fun recognize(input: List<Point>, density: Float = 1f): Result {
        fun unchanged() = Result(RecognizedShape.NONE, input.toList())
        if (input.size < 2 || input.any { !it.x.isFinite() || !it.y.isFinite() }) return unchanged()
        val clean = ArrayList<Point>()
        for (p in input) {
            if (clean.isEmpty() || distance(clean.last(), p) > 0.01f) clean.add(p)
        }
        if (clean.size < 2) return unchanged()
        val diagonal = hypot(clean.maxOf { it.x } - clean.minOf { it.x },
            clean.maxOf { it.y } - clean.minOf { it.y })
        if (diagonal < 18f * density.coerceAtLeast(0.1f)) return unchanged()
        val length = clean.zipWithNext { a, b -> distance(a, b) }.sum()
        val gap = distance(clean.first(), clean.last())
        val pressure = clean.map { it.pressure }.filter { it.isFinite() }
            .average().toFloat().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0.5f }
        val samples = resample(clean, 128)

        val nearlyClosed = gap <= diagonal * 0.28f && gap <= length * 0.12f
        if (!nearlyClosed) {
            val errors = samples.map { segmentDistance(it, clean.first(), clean.last()) }
            if (gap / length >= 0.88f && errors.max() / gap < 0.055f &&
                sqrt(errors.sumOf { (it * it).toDouble() } / samples.size) / gap < 0.025) {
                return Result(RecognizedShape.LINE, listOf(
                    clean.first().copy(pressure = pressure), clean.last().copy(pressure = pressure),
                ))
            }
            return unchanged()
        }
        if (clean.size < 4 || length < diagonal * 1.8f) return unchanged()
        val ring = resample(clean + clean.first(), 256).dropLast(1)
        // Smooth by arc length, not event count, so slow pen movement and duplicate
        // touch samples cannot bias the fit or inflate the measured perimeter.
        val smoothed = ring.indices.map { i ->
            val previous = ring[(i + ring.size - 1) % ring.size]
            val current = ring[i]
            val next = ring[(i + 1) % ring.size]
            Point((previous.x + current.x * 2f + next.x) / 4f,
                (previous.y + current.y * 2f + next.y) / 4f, pressure)
        }
        val fittedLength = smoothed.indices.sumOf {
            distance(smoothed[it], smoothed[(it + 1) % smoothed.size]).toDouble()
        }.toFloat()
        val closed = smoothed
        val hull = convexHull(closed)
        if (hull.size < 3) return unchanged()
        val box = minimumBox(hull, pressure)
        val width = distance(box[0], box[1])
        val height = distance(box[1], box[2])
        val shortSide = minOf(width, height)
        if (shortSide < 10f * density || shortSide / maxOf(width, height) < 0.12f) return unchanged()
        val area = abs(signedArea(closed))
        val hullArea = abs(signedArea(hull))
        // Reject retraced strokes, concave doodles and multiple loops.
        if (area / hullArea !in 0.86f..1.08f) return unchanged()

        var triangleFit: Pair<List<Point>, Float>? = null
        var quadrilateralFit: Pair<List<Point>, Float>? = null
        // Hand-drawn corners vary in sharpness; use the best acceptable fit instead of
        // making the entire classification depend on one polygon simplification threshold.
        for (tolerance in listOf(0.025f, 0.04f, 0.055f, 0.07f, 0.09f, 0.12f, 0.16f)) {
            val roughCorners = simplifyRing(hull, shortSide * tolerance)
            if (roughCorners.size !in 3..4) continue
            val corners = fitPolygonEdges(closed, roughCorners)
            val score = polygonFitScore(closed, corners, fittedLength, shortSide, area) ?: continue
            val candidate = corners.map { it.copy(pressure = pressure) } to score
            if (corners.size == 3 && (triangleFit == null || score < triangleFit.second)) {
                triangleFit = candidate
            } else if (corners.size == 4 && (quadrilateralFit == null || score < quadrilateralFit.second)) {
                quadrilateralFit = candidate
            }
        }
        if (triangleFit != null && (quadrilateralFit == null ||
                triangleFit.second < quadrilateralFit.second + 0.015f)) {
            val triangle = triangleFit.first
            return Result(RecognizedShape.TRIANGLE, triangle + triangle.first())
        }
        if (quadrilateralFit != null) {
            val corners = quadrilateralFit.first
            val rectangleBox = fitRectangle(corners, pressure)
            val rectangleError = abs(abs(signedArea(corners)) / abs(signedArea(rectangleBox)) - 1f)
            val rectangleAngleError = corners.indices.maxOf { i ->
                val previous = corners[(i + corners.size - 1) % corners.size]
                val current = corners[i]
                val next = corners[(i + 1) % corners.size]
                val ax = previous.x - current.x
                val ay = previous.y - current.y
                val bx = next.x - current.x
                val by = next.y - current.y
                abs((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)))
            }
            if (rectangleError < 0.12f && rectangleAngleError < 0.25f &&
                polygonFitScore(closed, rectangleBox, fittedLength, shortSide, area) != null) {
                val rectangleWidth = distance(rectangleBox[0], rectangleBox[1])
                val rectangleHeight = distance(rectangleBox[1], rectangleBox[2])
                val rectangle = if (minOf(rectangleWidth, rectangleHeight) /
                    maxOf(rectangleWidth, rectangleHeight) >= 0.9f) {
                    val cx = (rectangleBox[0].x + rectangleBox[2].x) / 2f
                    val cy = (rectangleBox[0].y + rectangleBox[2].y) / 2f
                    val halfSide = (rectangleWidth + rectangleHeight) / 4f
                    val ux = (rectangleBox[1].x - rectangleBox[0].x) / rectangleWidth
                    val uy = (rectangleBox[1].y - rectangleBox[0].y) / rectangleWidth
                    listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (x, y) ->
                        Point(cx + halfSide * (x * ux - y * uy),
                            cy + halfSide * (x * uy + y * ux), pressure)
                    }
                } else rectangleBox
                return Result(RecognizedShape.RECTANGLE, rectangle + rectangle.first())
            }
            return unchanged()
        }
        // A regular pentagon or hexagon can have low radial error, but its straight
        // edges are evidence against rounding it into an ellipse.
        val otherCorners = simplifyRing(hull, shortSide * 0.035f)
        if (otherCorners.size in 5..6 && polygonFitScore(closed, otherCorners,
                fittedLength, shortSide, area) != null) return unchanged()

        val cx = (box[0].x + box[2].x) / 2f
        val cy = (box[0].y + box[2].y) / 2f
        var sweep = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val ax = (a.x - cx).toDouble()
            val ay = (a.y - cy).toDouble()
            val bx = (b.x - cx).toDouble()
            val by = (b.y - cy).toDouble()
            sweep += atan2(ax * by - ay * bx, ax * bx + ay * by)
        }
        // A small endpoint gap may be an unfinished loop or an overdrawn loop.
        // Check the original stroke's sweep before accepting the artificial closing edge.
        if (abs(sweep) > 2 * Math.PI + 0.35) return unchanged()
        val ux = (box[1].x - box[0].x) / width
        val uy = (box[1].y - box[0].y) / width
        val rx = width / 2f
        val ry = height / 2f
        val radialErrors = closed.map {
            val dx = it.x - cx
            val dy = it.y - cy
            abs(hypot((dx * ux + dy * uy) / rx, (-dx * uy + dy * ux) / ry) - 1f)
        }
        val ellipseArea = (Math.PI * rx * ry).toFloat()
        val ellipsePerimeter = (Math.PI * (3 * (rx + ry) -
            sqrt((3 * rx + ry) * (rx + 3 * ry)))).toFloat()
        if (radialErrors.max() > 0.18f ||
            sqrt(radialErrors.sumOf { (it * it).toDouble() } / closed.size) > 0.075 ||
            area / ellipseArea !in 0.85f..1.12f ||
            fittedLength / ellipsePerimeter !in 0.88f..1.3f) return unchanged()
        val round = minOf(rx, ry) / maxOf(rx, ry) >= 0.88f
        val radius = (rx + ry) / 2f
        val output = (0 until 64).map {
            val angle = 2.0 * Math.PI * it / 64
            val x = (if (round) radius else rx) * cos(angle).toFloat()
            val y = (if (round) radius else ry) * sin(angle).toFloat()
            Point(cx + x * ux - y * uy, cy + x * uy + y * ux, pressure)
        }
        return Result(if (round) RecognizedShape.CIRCLE else RecognizedShape.ELLIPSE,
            output + output.first())
    }

    private fun fitRectangle(corners: List<Point>, pressure: Float): List<Point> {
        // Average all four edge directions modulo 90 degrees, rather than letting
        // one noisy edge determine the orientation and size of the entire box.
        var x = 0.0
        var y = 0.0
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val angle = atan2(b.y - a.y, b.x - a.x).toDouble()
            val weight = distance(a, b)
            x += cos(4 * angle) * weight
            y += sin(4 * angle) * weight
        }
        val angle = (atan2(y, x) / 4).toFloat()
        val c = cos(angle)
        val s = sin(angle)
        val cx = corners.map { it.x }.average().toFloat()
        val cy = corners.map { it.y }.average().toFloat()
        val local = corners.map { (it.x - cx) * c + (it.y - cy) * s to
            -(it.x - cx) * s + (it.y - cy) * c }
        val xs = local.map { it.first }.sorted()
        val ys = local.map { it.second }.sorted()
        val left = (xs[0] + xs[1]) / 2f
        val right = (xs[2] + xs[3]) / 2f
        val top = (ys[0] + ys[1]) / 2f
        val bottom = (ys[2] + ys[3]) / 2f
        return listOf(left to top, right to top, right to bottom, left to bottom).map { (u, v) ->
            Point(cx + u * c - v * s, cy + u * s + v * c, pressure)
        }
    }

    private fun polygonFitScore(
        samples: List<Point>, vertices: List<Point>, length: Float, scale: Float, area: Float,
    ): Float? {
        val errors = samples.map { p ->
            vertices.indices.minOf { segmentDistance(p, vertices[it], vertices[(it + 1) % vertices.size]) }
        }
        val perimeter = vertices.indices.sumOf {
            distance(vertices[it], vertices[(it + 1) % vertices.size]).toDouble()
        }.toFloat()
        val maxError = errors.max() / scale
        val rmsError = sqrt(errors.sumOf { (it * it).toDouble() } / samples.size).toFloat() / scale
        val areaError = abs(area / abs(signedArea(vertices)) - 1f)
        val perimeterError = abs(length / perimeter - 1f)
        if (maxError >= 0.12f || rmsError >= 0.045f || areaError > 0.14f ||
            perimeterError > 0.25f) return null
        return rmsError + maxError * 0.2f + areaError * 0.2f + perimeterError * 0.15f
    }

    private fun fitPolygonEdges(samples: List<Point>, corners: List<Point>): List<Point> {
        data class Edge(val center: Point, val dx: Float, val dy: Float)
        val groups = Array(corners.size) { ArrayList<Point>() }
        for (point in samples) {
            val edge = corners.indices.minBy {
                segmentDistance(point, corners[it], corners[(it + 1) % corners.size])
            }
            val a = corners[edge]
            val b = corners[(edge + 1) % corners.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy)
            // The middle of an edge carries its direction; rounded corners do not.
            if (t in 0.15f..0.85f) groups[edge].add(point)
        }
        val edges = corners.indices.map { i ->
            val group = groups[i]
            if (group.size < 4) return corners
            val cx = group.map { it.x }.average().toFloat()
            val cy = group.map { it.y }.average().toFloat()
            val xx = group.sumOf { ((it.x - cx) * (it.x - cx)).toDouble() }
            val yy = group.sumOf { ((it.y - cy) * (it.y - cy)).toDouble() }
            val xy = group.sumOf { ((it.x - cx) * (it.y - cy)).toDouble() }
            val angle = (atan2(2 * xy, xx - yy) / 2).toFloat()
            Edge(Point(cx, cy, corners[i].pressure), cos(angle), sin(angle))
        }
        val fitted = corners.indices.map { i ->
            val a = edges[(i + edges.size - 1) % edges.size]
            val b = edges[i]
            val determinant = a.dx * b.dy - a.dy * b.dx
            if (abs(determinant) < 0.15f) return corners
            val dx = b.center.x - a.center.x
            val dy = b.center.y - a.center.y
            val t = (dx * b.dy - dy * b.dx) / determinant
            Point(a.center.x + t * a.dx, a.center.y + t * a.dy, corners[i].pressure)
        }
        if (fitted.indices.any {
                cross(fitted[it], fitted[(it + 1) % fitted.size],
                    fitted[(it + 2) % fitted.size]) <= 0f
            }) return corners
        return fitted
    }

    private fun resample(points: List<Point>, count: Int): List<Point> {
        val lengths = FloatArray(points.size)
        for (i in 1 until points.size) lengths[i] = lengths[i - 1] + distance(points[i - 1], points[i])
        val result = ArrayList<Point>(count)
        var segment = 1
        for (i in 0 until count) {
            val target = lengths.last() * i / (count - 1)
            while (segment < points.lastIndex && lengths[segment] < target) segment++
            val a = points[segment - 1]
            val b = points[segment]
            val span = lengths[segment] - lengths[segment - 1]
            val t = if (span > 0f) ((target - lengths[segment - 1]) / span).coerceIn(0f, 1f) else 0f
            result.add(Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.pressure))
        }
        return result
    }

    private fun convexHull(points: List<Point>): List<Point> {
        val sorted = points.distinctBy { it.x to it.y }.sortedWith(compareBy<Point> { it.x }.thenBy { it.y })
        fun half(sequence: List<Point>): List<Point> {
            val result = ArrayList<Point>()
            for (p in sequence) {
                while (result.size >= 2 && cross(result[result.size - 2], result.last(), p) <= 0f) {
                    result.removeAt(result.lastIndex)
                }
                result.add(p)
            }
            return result.dropLast(1)
        }
        return half(sorted) + half(sorted.reversed())
    }

    private fun minimumBox(points: List<Point>, pressure: Float): List<Point> {
        var bestArea = Float.POSITIVE_INFINITY
        var best = emptyList<Point>()
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val angle = atan2(b.y - a.y, b.x - a.x)
            val c = cos(angle)
            val s = sin(angle)
            // Center first to avoid cancellation for large canvas coordinates.
            val local = points.map { (it.x - a.x) * c + (it.y - a.y) * s to
                -(it.x - a.x) * s + (it.y - a.y) * c }
            val left = local.minOf { it.first }
            val right = local.maxOf { it.first }
            val top = local.minOf { it.second }
            val bottom = local.maxOf { it.second }
            val area = (right - left) * (bottom - top)
            if (area < bestArea) {
                bestArea = area
                best = listOf(left to top, right to top, right to bottom, left to bottom).map { (x, y) ->
                    Point(a.x + x * c - y * s, a.y + x * s + y * c, pressure)
                }
            }
        }
        return best
    }

    private fun simplifyRing(points: List<Point>, epsilon: Float): List<Point> {
        // Split the closed ring at its diameter; an arbitrary pen-down point is not a corner.
        var first = 0
        var second = 1
        var longest = 0f
        for (i in points.indices) for (j in i + 1 until points.size) {
            val d = distance(points[i], points[j])
            if (d > longest) { longest = d; first = i; second = j }
        }
        fun simplify(chain: List<Point>): List<Point> {
            if (chain.size <= 2) return chain
            val index = (1 until chain.lastIndex).maxBy { segmentDistance(chain[it], chain.first(), chain.last()) }
            if (segmentDistance(chain[index], chain.first(), chain.last()) <= epsilon) {
                return listOf(chain.first(), chain.last())
            }
            return simplify(chain.subList(0, index + 1)).dropLast(1) +
                simplify(chain.subList(index, chain.size))
        }
        val one = points.subList(first, second + 1)
        val two = points.subList(second, points.size) + points.subList(0, first + 1)
        val result = (simplify(one).dropLast(1) + simplify(two).dropLast(1)).toMutableList()
        var changed = true
        while (changed && result.size > 3) {
            changed = false
            for (i in result.indices) {
                if (segmentDistance(result[i], result[(i + result.size - 1) % result.size],
                        result[(i + 1) % result.size]) <= epsilon) {
                    result.removeAt(i)
                    changed = true
                    break
                }
            }
        }
        return result
    }

    private fun signedArea(points: List<Point>): Float {
        val origin = points.first()
        return (points.indices.sumOf {
            val a = points[it]
            val b = points[(it + 1) % points.size]
            ((a.x - origin.x).toDouble() * (b.y - origin.y) -
                (a.y - origin.y).toDouble() * (b.x - origin.x))
        } / 2).toFloat()
    }

    private fun cross(a: Point, b: Point, c: Point) =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    internal fun segmentDistance(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        if (length == 0f) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }
}
