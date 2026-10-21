// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Picking a cover out of the device's photo library.
 *
 * The camera path already existed ([CoverCaptureOverlay]); this is the other
 * half of what the web client offers for a hand-made book, and the half a
 * reader needs when the cover is a screenshot or a photo they already took.
 *
 * Port of `CoverCaptureSheet`'s picker branch
 * (`librarium-ios-main/Librarium/Redesigned/CoverCaptureSheet.swift:136`):
 * `PhotosPicker(matching: .images)`, read the bytes, downscale to a 1600 px
 * long edge, re-encode as JPEG, then hand the result up. The downscale is not
 * cosmetic — the server refuses anything over 10 MB
 * (`internal/api/uploads/sniff.go:MaxImageBytes`) and a modern phone camera
 * writes well past that.
 *
 * `PickVisualMedia` rather than `GetContent`/`OpenDocument`: it resolves to the
 * system photo picker, which shows only images and needs no runtime permission
 * at all, so the manifest stays without `READ_MEDIA_IMAGES` — the grant is per
 * picked item.
 */
@Composable
fun rememberCoverPhotoPicker(
    onPicked: (ByteArray) -> Unit,
    onFailure: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        // Null is the picker being dismissed, not a failure: nothing to say.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = readCoverJpeg(context, uri)
            if (jpeg == null) onFailure(UNREADABLE_IMAGE) else onPicked(jpeg)
        }
    }
    return {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
}

/** Long edge the upload is shrunk to, matching `CoverCaptureSheet.swift:37`. */
internal const val MAX_EDGE = 1600

/** Re-encode quality. Visually lossless for a cover, far under the size cap. */
private const val JPEG_QUALITY = 88

internal const val UNREADABLE_IMAGE = "That image could not be read."

/**
 * The chosen cover, as an image.
 *
 * Decoded from the JPEG bytes the form is holding rather than from a URL,
 * because on the create path the book does not exist yet and so has no cover
 * URL to load — the preview is the only evidence the picture the reader picked
 * is the picture that will be uploaded.
 *
 * The decode runs off the main thread through [produceState]: the bytes are
 * already downscaled to [MAX_EDGE], but a couple of milliseconds of JPEG work
 * on every recomposition of a scrolling form is still worth not doing.
 */
@Composable
fun CoverPreview(jpeg: ByteArray, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, jpeg) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
        }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = "Cover",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

/**
 * Reads [uri] and returns JPEG bytes sized for the cover endpoint, or null when
 * the item is not a decodable image.
 *
 * Three steps that each need the whole byte array in hand: sample down while
 * decoding (so a 50 MP photo never becomes a 50 MP `Bitmap`), honour the EXIF
 * orientation (a portrait photo of a cover is stored rotated with a flag, and
 * ignoring it uploads the book on its side), then scale the long edge and
 * compress.
 */
internal suspend fun readCoverJpeg(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        val source = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext null

        // Bounds only: `inJustDecodeBounds` fills the size in without pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            },
        ) ?: return@withContext null

        // Each helper recycles the bitmap it replaces, so exactly one large
        // buffer is alive per step; `decoded` and the rotated/scaled results are
        // never both retained.
        val oriented = applyExifOrientation(decoded, source)
        val scaled = scaleToFit(oriented)
        val out = ByteArrayOutputStream()
        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        } finally {
            scaled.recycle()
        }
        out.toByteArray()
    }

/**
 * Largest power of two that still leaves the long edge at or above [MAX_EDGE];
 * decoding at that ratio plus [scaleToFit] lands within a pixel or two of the
 * target without ever allocating the full-resolution image.
 *
 * `internal` so a unit test can pin it: a wrong value here does not fail
 * visibly, it either leaves the image blockier than it should be (sample too
 * high) or allocates the full-size bitmap and risks an OOM on a 50 MP photo.
 */
internal fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= MAX_EDGE) {
        longest /= 2
        sample *= 2
    }
    return sample
}

/** Applies the EXIF rotation/flip, recycling [bitmap] when it produces a copy. */
private fun applyExifOrientation(bitmap: Bitmap, source: ByteArray): Bitmap {
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/** Shrinks the long edge to [MAX_EDGE], recycling [bitmap] when it produces a copy. */
private fun scaleToFit(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= MAX_EDGE) return bitmap
    val ratio = MAX_EDGE.toFloat() / longest
    val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
    val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}
