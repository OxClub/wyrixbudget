package com.wyrix.budget.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.wyrix.budget.data.security.KeystoreCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Receipt images are encrypted with the Keystore key before they touch app storage. */
@Singleton
class SecureFileStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir: File get() = File(context.filesDir, "receipts").apply { mkdirs() }

    fun save(plain: ByteArray): String {
        val f = File(dir, UUID.randomUUID().toString() + ".enc")
        f.writeBytes(KeystoreCrypto.encrypt(plain))
        return f.absolutePath
    }

    fun read(path: String): ByteArray = KeystoreCrypto.decrypt(File(path).readBytes())
    fun delete(path: String) { File(path).delete() }
    fun wipe() { dir.deleteRecursively() }

    fun captureFile(): File = File(File(context.cacheDir, "captures").apply { mkdirs() }, UUID.randomUUID().toString() + ".jpg")
}

object ImageUtils {
    /** Decodes, applies EXIF rotation, scales so the longest side is at most [maxSide], re-encodes as JPEG. */
    fun compressJpeg(bytes: ByteArray, maxSide: Int = 1600, quality: Int = 85): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Not a readable image")
        val rotation = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    fun decode(bytes: ByteArray, maxSide: Int = 800): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** On-device text recognition (ML Kit, bundled model). Nothing is sent anywhere. */
object ReceiptOcr {
    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
        } finally {
            recognizer.close()
        }
    }
}
