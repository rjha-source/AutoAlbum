package com.autoalbum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides which album a photo belongs to, fully on-device.
 * Returns a folder name under Pictures/ (e.g. "Documents/Citizenship"), or null = leave in place.
 */
object Classifier {

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val latinOcr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val nepaliOcr by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    // ---- EDIT THESE to tune document types. Checked in order; first match wins. ----
    private val documentTypes: List<Pair<String, List<String>>> = listOf(
        "Citizenship" to listOf("citizenship", "नागरिकता"),
        "Passport" to listOf("passport", "p<npl", "पासपोर्ट"),
        "National ID" to listOf(
            "national identity", "national id", "राष्ट्रिय परिचय", "परिचयपत्र", "nin"
        ),
        "License" to listOf(
            "driving licen", "driver licen", "सवारी चालक", "चालक अनुमति"
        ),
        "Bluebook" to listOf(
            "bluebook", "blue book", "vehicle registration", "chassis", "engine no",
            "transport management", "सवारी दर्ता", "सवारी साधन", "दर्ता प्रमाणपत्र"
        ),
        "Academic" to listOf(
            "transcript", "marksheet", "mark sheet", "grade sheet", "grade-sheet",
            "university", "examinations board", "examination board", "school leaving",
            "secondary education", "provisional certificate", "character certificate",
            "bachelor", "master of", "gpa", "slc", "college", "विश्वविद्यालय", "अंकपत्र"
        )
    )

    private val docLabels = setOf("paper", "document", "text", "handwriting", "receipt", "menu")
    private val foodLabels = setOf(
        "food", "dish", "cuisine", "meal", "pizza", "fruit", "vegetable",
        "dessert", "cake", "drink", "coffee", "breakfast", "snack"
    )
    private val petLabels = setOf("dog", "cat", "pet", "puppy", "kitten", "bird")
    private val natureLabels = setOf(
        "sky", "mountain", "flower", "plant", "tree", "beach", "sea", "lake",
        "sunset", "sunrise", "cloud", "forest", "nature", "river", "grass", "waterfall"
    )

    suspend fun classify(context: Context, uri: Uri): String? {
        val bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(uri, Size(1024, 1024), null)
            } catch (e: Exception) {
                null
            }
        } ?: return null

        val image = InputImage.fromBitmap(bitmap, 0)

        // 1. Read text (English + Nepali) and look for known document types
        val latin = try { latinOcr.process(image).await().text } catch (e: Exception) { "" }
        val nepali = try { nepaliOcr.process(image).await().text } catch (e: Exception) { "" }
        val text = "$latin $nepali".lowercase()
        val textChars = text.count { it.isLetterOrDigit() }

        if (textChars >= 15) {
            for ((name, keywords) in documentTypes) {
                if (keywords.any { text.contains(it) }) return "Documents/$name"
            }
        }

        val labels = try {
            labeler.process(image).await().map { it.text.lowercase() }
        } catch (e: Exception) { emptyList() }

        // 2. Some other text-heavy document
        if (textChars >= 80 || (textChars >= 20 && labels.any { it in docLabels })) {
            return "Documents/Others"
        }

        // 3. Faces
        val faces = try { faceDetector.process(image).await() } catch (e: Exception) { emptyList() }
        if (faces.size == 1 && looksLikePassportPhoto(bitmap, faces[0])) {
            return "Documents/Passport Photos"
        }
        if (faces.size >= 2) return "Family"
        if (faces.size == 1) return "Personal"

        // 4. Scenes
        if (labels.any { it in foodLabels }) return "Food"
        if (labels.any { it in petLabels }) return "Pets"
        if (labels.any { it in natureLabels }) return "Nature"
        return null
    }

    /**
     * Passport-size photo: portrait shape, one big centred front-facing face,
     * and a plain (low-variation) background around the head.
     */
    private fun looksLikePassportPhoto(bmp: Bitmap, face: Face): Boolean {
        val w = bmp.width
        val h = bmp.height
        val ratio = w.toFloat() / h
        if (ratio < 0.65f || ratio > 0.95f) return false

        val box = face.boundingBox
        val faceFrac = box.width().toFloat() / w
        if (faceFrac < 0.30f || faceFrac > 0.65f) return false

        val cx = box.centerX().toFloat() / w
        if (cx < 0.38f || cx > 0.62f) return false
        if (abs(face.headEulerAngleY) > 15f || abs(face.headEulerAngleZ) > 12f) return false

        // Background check: top strip + side strips of the upper 60% of the image
        val samples = ArrayList<Int>()
        val stripW = (w * 0.08f).toInt().coerceAtLeast(2)
        val stripH = (h * 0.06f).toInt().coerceAtLeast(2)
        val stepX = (w / 40).coerceAtLeast(1)
        val stepY = (h / 40).coerceAtLeast(1)
        fun lum(p: Int) = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
        for (y in 0 until stripH step stepY) for (x in 0 until w step stepX) samples.add(lum(bmp.getPixel(x, y)))
        for (y in 0 until (h * 0.6f).toInt() step stepY) {
            for (x in 0 until stripW step stepX) samples.add(lum(bmp.getPixel(x, y)))
            for (x in (w - stripW) until w step stepX) samples.add(lum(bmp.getPixel(x, y)))
        }
        if (samples.isEmpty()) return false
        val mean = samples.average()
        val std = sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
        return std < 22.0
    }
}
