package com.safestep.mobile

import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** OpenCV Zoo NanoDet-Plus 1.5x 416 model. Output geometry follows the Zoo decoder. */
class NanoDetDetector(context: Context) {
    data class Detection(
        val label: String,
        val confidence: Float,
        val centerX: Float,
        val centerY: Float,
        val area: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )
    private data class Box(val rect: Rect, val score: Float, val classId: Int)
    private data class Letterbox(val image: Mat, val scale: Double, val top: Int, val left: Int, val sourceWidth: Int, val sourceHeight: Int)

    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
        "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
        "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    private val net: Net
    @Volatile var lastDiagnostics: String = "Model loaded; waiting for first frame"
        private set

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native runtime could not be initialized" }
        val modelFile = File(context.filesDir, "object_detection_nanodet_2022nov.onnx")
        if (!modelFile.exists() || modelFile.length() < 500_000L) {
            context.assets.open("object_detection_nanodet_2022nov.onnx").use { input -> modelFile.outputStream().use { output -> input.copyTo(output) } }
        }
        net = Dnn.readNetFromONNX(modelFile.absolutePath)
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU)
    }

    @Synchronized
    fun detect(rgbFrame: Mat): List<Detection> {
        if (rgbFrame.empty()) return emptyList()
        val boxed = makeLetterbox(rgbFrame)
        val floatRgb = Mat()
        boxed.image.convertTo(floatRgb, org.opencv.core.CvType.CV_32FC3)
        val centered = Mat()
        Core.subtract(floatRgb, Scalar(103.53, 116.28, 123.675), centered)
        val normalized = Mat()
        Core.divide(centered, Scalar(57.375, 57.12, 58.395), normalized)
        val blob = Dnn.blobFromImage(normalized, 1.0, Size(INPUT.toDouble(), INPUT.toDouble()), Scalar(0.0), false, false)
        net.setInput(blob)
        val outputs = ArrayList<Mat>()
        net.forward(outputs, net.unconnectedOutLayersNames)
        val outputShapeSummary = outputs.joinToString(";") { mat ->
            (0 until mat.dims()).joinToString(prefix = "[", postfix = "]") { dimension -> mat.size(dimension).toString() }
        }
        val boxes = decode(outputs, boxed)
        val decodeDetails = lastDiagnostics
        lastDiagnostics = "shape=$outputShapeSummary decoded=${boxes.size}" +
            if (boxes.isEmpty() && decodeDetails.startsWith("maxScore")) " $decodeDetails" else ""
        Log.d("SafeStepDetector", lastDiagnostics)
        val result = boxes.map { box ->
            val x1 = box.rect.x.toFloat().coerceIn(0f, boxed.sourceWidth.toFloat())
            val y1 = box.rect.y.toFloat().coerceIn(0f, boxed.sourceHeight.toFloat())
            val x2 = (box.rect.x + box.rect.width).toFloat().coerceIn(0f, boxed.sourceWidth.toFloat())
            val y2 = (box.rect.y + box.rect.height).toFloat().coerceIn(0f, boxed.sourceHeight.toFloat())
            Detection(labels.getOrElse(box.classId) { "object" }, box.score,
                ((x1 + x2) / 2f / boxed.sourceWidth).coerceIn(0f, 1f),
                ((y1 + y2) / 2f / boxed.sourceHeight).coerceIn(0f, 1f),
                (((x2 - x1) * (y2 - y1)) / (boxed.sourceWidth * boxed.sourceHeight)).coerceIn(0f, 1f),
                (x1 / boxed.sourceWidth).coerceIn(0f, 1f),
                (y1 / boxed.sourceHeight).coerceIn(0f, 1f),
                (x2 / boxed.sourceWidth).coerceIn(0f, 1f),
                (y2 / boxed.sourceHeight).coerceIn(0f, 1f))
        }
        outputs.forEach { it.release() }
        boxed.image.release(); floatRgb.release(); centered.release(); normalized.release(); blob.release()
        return result
    }

    private fun makeLetterbox(source: Mat): Letterbox {
        val scale = min(INPUT.toDouble() / source.cols(), INPUT.toDouble() / source.rows())
        val width = max(1, (source.cols() * scale).toInt())
        val height = max(1, (source.rows() * scale).toInt())
        val resized = Mat()
        Imgproc.resize(source, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val left = (INPUT - width) / 2
        val top = (INPUT - height) / 2
        val output = Mat()
        Core.copyMakeBorder(resized, output, top, INPUT - height - top, left, INPUT - width - left, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        resized.release()
        return Letterbox(output, scale, top, left, source.cols(), source.rows())
    }

    private fun decode(outputs: List<Mat>, transform: Letterbox): List<Box> {
        if (outputs.size != STRIDES.size * 2) {
            lastDiagnostics = "Expected ${STRIDES.size * 2} paired tensors; got ${outputs.size}"
            return emptyList()
        }
        val boxes = ArrayList<Box>()
        var maxObservedScore = Float.NEGATIVE_INFINITY
        var acceptedAnchors = 0
        for (level in 0 until outputs.size / 2) {
            val scores = flatten(outputs[level * 2])
            val distances = flatten(outputs[level * 2 + 1])
            if (scores.size % CLASS_COUNT != 0 || distances.size % (4 * REG_MAX) != 0) {
                lastDiagnostics = "Bad tensor lengths: score=${scores.size}, box=${distances.size}"
                Log.e("SafeStepDetector", lastDiagnostics)
                continue
            }
            val count = min(scores.size / CLASS_COUNT, distances.size / (4 * REG_MAX))
            val stride = STRIDES.getOrElse(level) { return emptyList() }
            for (i in 0 until count) {
                var classId = 0
                var score = Float.NEGATIVE_INFINITY
                val classOffset = i * CLASS_COUNT
                for (c in 0 until CLASS_COUNT) if (scores[classOffset + c] > score) { score = scores[classOffset + c]; classId = c }
                if (score.isFinite()) maxObservedScore = max(maxObservedScore, score)
                if (!score.isFinite() || score < CONFIDENCE) continue
                acceptedAnchors++
                val gx = i % ceilDiv(INPUT, stride)
                val gy = i / ceilDiv(INPUT, stride)
                if (gy >= ceilDiv(INPUT, stride)) continue
                val cx = gx * stride + .5 * (stride - 1)
                val cy = gy * stride + .5 * (stride - 1)
                val offset = i * 4 * REG_MAX
                val d = DoubleArray(4)
                for (side in 0..3) {
                    var maxLogit = Double.NEGATIVE_INFINITY
                    for (k in 0 until REG_MAX) maxLogit = max(maxLogit, distances[offset + side * REG_MAX + k].toDouble())
                    var total = 0.0
                    var weighted = 0.0
                    for (k in 0 until REG_MAX) { val weight = exp((distances[offset + side * REG_MAX + k] - maxLogit).toDouble()); total += weight; weighted += weight * k }
                    d[side] = (weighted / total) * stride
                }
                val x1 = (cx - d[0]).coerceIn(0.0, INPUT.toDouble())
                val y1 = (cy - d[1]).coerceIn(0.0, INPUT.toDouble())
                val x2 = (cx + d[2]).coerceIn(0.0, INPUT.toDouble())
                val y2 = (cy + d[3]).coerceIn(0.0, INPUT.toDouble())
                val originalX1 = ((x1 - transform.left) / transform.scale).coerceIn(0.0, transform.sourceWidth.toDouble())
                val originalY1 = ((y1 - transform.top) / transform.scale).coerceIn(0.0, transform.sourceHeight.toDouble())
                val originalX2 = ((x2 - transform.left) / transform.scale).coerceIn(0.0, transform.sourceWidth.toDouble())
                val originalY2 = ((y2 - transform.top) / transform.scale).coerceIn(0.0, transform.sourceHeight.toDouble())
                val rect = Rect(originalX1.toInt(), originalY1.toInt(), max(1, (originalX2 - originalX1).toInt()), max(1, (originalY2 - originalY1).toInt()))
                boxes.add(Box(rect, score, classId))
            }
        }
        if (boxes.isEmpty()) {
            lastDiagnostics = "maxScore=${if (maxObservedScore.isFinite()) "%.3f".format(Locale.US, maxObservedScore) else "invalid"} accepted=$acceptedAnchors"
            return emptyList()
        }
        val selected = ArrayList<Box>()
        for (candidate in boxes.sortedByDescending { it.score }.take(1000)) {
            val overlaps = selected.any { it.classId == candidate.classId && intersectionOverUnion(it.rect, candidate.rect) > IOU_THRESHOLD }
            if (!overlaps) selected.add(candidate)
            if (selected.size >= 12) break
        }
        return selected
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val left = max(a.x, b.x); val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width); val bottom = min(a.y + a.height, b.y + b.height)
        val intersection = max(0, right - left) * max(0, bottom - top).toDouble()
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0.0) 0f else (intersection / union).toFloat()
    }

    private fun flatten(mat: Mat): FloatArray {
        val row = mat.reshape(1, 1)
        val result = FloatArray((row.total() * row.channels()).toInt())
        row.get(0, 0, result)
        row.release()
        return result
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    companion object {
        private const val INPUT = 416
        private const val CLASS_COUNT = 80
        private const val REG_MAX = 8
        private const val CONFIDENCE = 0.25f
        private const val IOU_THRESHOLD = 0.60f
        // This exported 416px model has three heads: 52x52, 26x26 and 13x13.
        // Their paired class/regression tensors total six outputs, not eight.
        private val STRIDES = intArrayOf(8, 16, 32)
    }
}
