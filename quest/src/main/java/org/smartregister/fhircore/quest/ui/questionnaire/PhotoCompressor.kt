/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ui.questionnaire

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.QuestionnaireResponse
import timber.log.Timber

/**
 * Shrinks photos in a response before it is saved: phone cameras produce 3–8 MB images, which
 * make forms slow to save and sync. Photos are scaled to at most [MAX_SIDE] pixels, turned the
 * right way up (EXIF orientation) and stored as JPEG — typically 200–400 KB, still sharp enough to
 * read a signboard.
 */
object PhotoCompressor {
  private const val MAX_SIDE = 1600
  private const val QUALITY = 80
  private const val MIN_BYTES = 400 * 1024 // smaller photos are left as they are

  /** Compresses every large photo answer in place; returns how many were compressed. */
  suspend fun compressPhotos(response: QuestionnaireResponse): Int =
    withContext(Dispatchers.Default) {
      var count = 0
      fun walk(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
        items.forEach { item ->
          item.answer.forEach { answer ->
            if (answer.hasValueAttachment() && compress(answer.valueAttachment)) count++
            walk(answer.item)
          }
          walk(item.item)
        }
      }
      walk(response.item)
      count
    }

  private fun compress(attachment: Attachment): Boolean {
    val type = attachment.contentType.orEmpty()
    val data = attachment.data ?: return false
    if (!type.startsWith("image/") || type == "image/gif" || data.size < MIN_BYTES) return false
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded =
          BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        val rotation =
          when (ExifInterface(data.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
          }
        if (rotation != 0f) matrix.postRotate(rotation)
        val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val bytes = ByteArrayOutputStream().use { stream ->
          output.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
          stream.toByteArray()
        }
        if (output !== decoded) output.recycle()
        decoded.recycle()
        if (bytes.size >= data.size) return false
        attachment.data = bytes
        attachment.size = bytes.size
        attachment.contentType = "image/jpeg"
        attachment.title = attachment.title?.replace(Regex("\\.(jpe?g|png|heic|webp)$", RegexOption.IGNORE_CASE), "") + ".jpg"
        true
      }
      .onFailure { Timber.w(it, "Could not compress a photo; keeping the original") }
      .getOrDefault(false)
  }
}
