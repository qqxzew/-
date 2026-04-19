package com.meemaw.assist.showme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.meemaw.assist.data.LLMRepository
import com.meemaw.assist.ui.MessageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

class ShowMeAnalyzer(
    private val repository: LLMRepository
) {

    suspend fun analyze(
        context: Context,
        photoUri: Uri,
        userHint: String?,
        history: List<MessageItem>
    ): ShowMeAnalysisResult = withContext(Dispatchers.IO) {
        val bitmap = ShowMeImageStore.loadScaledBitmap(context, photoUri)
        val ocrText = extractText(context, photoUri)
        val imageDataUrl = ShowMeImageStore.bitmapToDataUrl(bitmap)
        val response = repository.analyzeShowMe(
            imageDataUrl = imageDataUrl,
            ocrText = ocrText,
            userHint = userHint,
            history = history
        )

        val annotatedPath: String? = response.annotationInstruction?.let { instruction ->
            val bytes = repository.annotateImage(imageDataUrl, instruction)
            if (bytes != null) {
                ShowMeImageStore.saveAnnotated(context, bytes)
            } else null
        }

        ShowMeAnalysisResult(
            message = response.message,
            annotatedImagePath = annotatedPath
        )
    }

    private suspend fun extractText(context: Context, photoUri: Uri): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromFilePath(context, photoUri)
        return try {
            recognizer.process(image).await().text.trim()
        } finally {
            recognizer.close()
        }
    }
}

object ShowMeImageStore {

    private const val TAG = "ShowMeImageStore"
    private const val CAPTURE_DIRECTORY_NAME = "show_me_capture"
    private const val MAX_DIMENSION = 1920

    fun createCaptureTarget(context: Context): PendingShowMeCapture {
        cleanupCaptureFiles(context)
        val file = createCaptureFile(context, prefix = "capture_")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        Log.d(TAG, "Created capture target path=${file.absolutePath} uri=$uri")
        return PendingShowMeCapture(
            filePath = file.absolutePath,
            uriString = uri.toString()
        )
    }

    fun loadScaledBitmap(context: Context, photoUri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, photoUri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = true
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val size = info.size
                val largestSide = max(size.width, size.height)
                if (largestSide > MAX_DIMENSION) {
                    val scale = MAX_DIMENSION.toFloat() / largestSide.toFloat()
                    decoder.setTargetSize(
                        (size.width * scale).roundToInt(),
                        (size.height * scale).roundToInt()
                    )
                }
            }
        } else {
            decodeBitmapLegacy(context, photoUri)
        }
    }

    fun bitmapToDataUrl(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * Persist annotated image bytes returned by nano-banana into the app's
     * cache directory and return the absolute file path.
     */
    fun saveAnnotated(context: Context, bytes: ByteArray): String {
        val dir = File(context.cacheDir, CAPTURE_DIRECTORY_NAME).apply { mkdirs() }
        val file = File.createTempFile("annotated_", ".png", dir)
        file.writeBytes(bytes)
        Log.d(TAG, "Saved annotated image to ${file.absolutePath} (${bytes.size} bytes)")
        return file.absolutePath
    }

    private fun createCaptureFile(context: Context, prefix: String): File {
        val dir = File(context.cacheDir, CAPTURE_DIRECTORY_NAME).apply { mkdirs() }
        return File.createTempFile(prefix, ".jpg", dir)
    }

    private fun cleanupCaptureFiles(context: Context) {
        val dir = File(context.cacheDir, CAPTURE_DIRECTORY_NAME)
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(8).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun decodeBitmapLegacy(context: Context, photoUri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(photoUri).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = calculateInSampleSize(options, MAX_DIMENSION, MAX_DIMENSION)
        val decoded = context.contentResolver.openInputStream(photoUri).use { stream: InputStream? ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        return requireNotNull(decoded) { "Failed to decode captured photo" }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
                halfHeight = height / 2
                halfWidth = width / 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
