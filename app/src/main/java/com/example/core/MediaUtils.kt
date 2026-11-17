package com.example.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class EncodedFileAttachment(
    val dataUri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)

object MediaUtils {

    suspend fun encodeImageUriToDataUri(
        context: Context,
        uri: Uri,
        maxDimension: Int,
        maxStringLen: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxDimension * 2 ||
                boundsOpts.outHeight / sampleSize > maxDimension * 2
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: error("Unable to read selected image.")

            val scale = minOf(
                maxDimension.toFloat() / rawBitmap.width.coerceAtLeast(1),
                maxDimension.toFloat() / rawBitmap.height.coerceAtLeast(1),
                1f
            )
            val scaledBitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    rawBitmap,
                    (rawBitmap.width * scale).toInt().coerceAtLeast(1),
                    (rawBitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                rawBitmap
            }

            var quality = 82
            var encoded: String
            do {
                val out = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                encoded = "data:image/jpeg;base64,$b64"
                quality -= 12
            } while (encoded.length > maxStringLen && quality >= 30)

            if (scaledBitmap !== rawBitmap) scaledBitmap.recycle()
            rawBitmap.recycle()

            if (encoded.length > maxStringLen) {
                error("Image is too large even after compression.")
            }
            encoded
        }
    }

    suspend fun encodeFileUriToDataUri(
        context: Context,
        uri: Uri
    ): Result<EncodedFileAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            var fileName = "attachment"
            var fileSize = 0L

            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: "attachment"
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }

            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to open selected file.")

            if (fileSize <= 0L) fileSize = bytes.size.toLong()
            if (bytes.size > SnrConstants.MAX_RAW_FILE_BYTES) {
                error("File exceeds 400 KB inline sync limit. Please choose a smaller file.")
            }

            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:$mimeType;base64,$b64"
            EncodedFileAttachment(
                dataUri = dataUri,
                fileName = fileName.take(150),
                mimeType = mimeType.take(90),
                sizeBytes = fileSize
            )
        }
    }

    private val bitmapCache: android.util.LruCache<String, Bitmap> by lazy {
        val maxKb = ((Runtime.getRuntime().maxMemory() / 1024L) / 12L)
            .toInt()
            .coerceIn(4 * 1024, 24 * 1024)
        object : android.util.LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    private fun cacheKey(dataUriOrUrl: String, maxDimension: Int): String =
        "${maxDimension}_${dataUriOrUrl.length}_${dataUriOrUrl.hashCode()}"

    fun getCachedBase64Bitmap(dataUriOrUrl: String, maxDimension: Int = 0): Bitmap? {
        if (!dataUriOrUrl.startsWith("data:image")) return null
        val bmp = bitmapCache.get(cacheKey(dataUriOrUrl, maxDimension))
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun decodeBase64Bitmap(dataUriOrUrl: String, maxDimension: Int = 0): Bitmap? {
        if (!dataUriOrUrl.startsWith("data:image")) return null
        val key = cacheKey(dataUriOrUrl, maxDimension)
        bitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        val commaIdx = dataUriOrUrl.indexOf(',')
        if (commaIdx < 0) return null
        return try {
            val bytes = Base64.decode(dataUriOrUrl.substring(commaIdx + 1), Base64.DEFAULT)
            val decoded = if (maxDimension > 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > maxDimension * 2 ||
                    bounds.outHeight / sampleSize > maxDimension * 2
                ) {
                    sampleSize *= 2
                }
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }
                )
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (decoded != null) {
                bitmapCache.put(key, decoded)
            }
            decoded
        } catch (_: Exception) {
            null
        }
    }

    fun shareOrOpenAttachment(
        context: Context,
        dataUri: String,
        fileName: String,
        mimeType: String
    ): Result<Unit> = runCatching {
        val commaIdx = dataUri.indexOf(',')
        require(commaIdx >= 0) { "Invalid attachment payload." }
        val bytes = Base64.decode(dataUri.substring(commaIdx + 1), Base64.DEFAULT)
        val cacheDir = File(context.cacheDir, "shared_attachments").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
        val outFile = File(cacheDir, safeName.ifBlank { "attachment.bin" })
        FileOutputStream(outFile).use { it.write(bytes) }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(openIntent, "Open $fileName")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
