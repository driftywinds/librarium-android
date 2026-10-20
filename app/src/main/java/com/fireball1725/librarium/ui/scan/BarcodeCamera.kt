// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BarcodeCamera"

/**
 * Bare camera + barcode detector.
 *
 * Port of `HeadlessBarcodeScanner` (`RedesignedScanFlow.swift:575-589`) and the
 * format policy from `BarcodeScannerView.swift`: **exactly** `.ean13, .ean8,
 * .code128` and nothing else, because QR codes and non-ISBN symbologies hand
 * back strings that look like one and every one of those is a wasted lookup.
 *
 * CameraX + ML Kit stand in for `AVCaptureSession` + `AVCaptureMetadataOutput`.
 * As on iOS there is no frame crop — `rectOfInterest` is never set there either,
 * so the highlighted frame on screen is cosmetic.
 *
 * Two things here are load-bearing and easy to get wrong:
 *
 * 1. **The [androidx.camera.core.ImageProxy] must stay open until ML Kit is
 *    done.** `process()` is asynchronous and reads pixels lazily; closing the
 *    proxy first hands the frame straight back to the camera to be overwritten,
 *    which on a fast sensor means the detector sees a torn buffer or nothing at
 *    all. The symptom is a live preview that never recognises anything. So the
 *    close lives in `addOnCompleteListener`, not right after `fromMediaImage`.
 * 2. **One [com.google.mlkit.vision.barcode.BarcodeScanner] for the screen, not
 *    one per frame.** Constructing a detector per frame drops frames on the
 *    analysis thread and leaks native handles if a future is cancelled.
 *
 * Single-shot semantics: the first barcode wins and analysis stops. [hasFiredRef]
 * is an atomic rather than the Compose state because ML Kit's callbacks arrive on
 * another thread and two frames can complete out of order.
 *
 * [resetKey] re-arms the detector. Without it a screen that stays composed
 * (the scanner sits under the result sheet) would stay fired forever after the
 * first successful scan, so "Scan another" would return to a dead camera.
 */
@Composable
fun BarcodeCamera(
    modifier: Modifier = Modifier,
    resetKey: Any? = Unit,
    onScan: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) return

    val hasFiredRef = remember { AtomicBoolean(false) }

    // Re-arm whenever the caller says a new scan is starting.
    LaunchedEffect(resetKey) { hasFiredRef.set(false) }

    val executor = remember { Executors.newSingleThreadExecutor() }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_CODE_128,
                )
                .build()
        )
    }

    val analyzer = remember(scanner) {
        ImageAnalysis.Analyzer { proxy ->
            val mediaImage = proxy.image
            if (mediaImage == null || hasFiredRef.get()) {
                proxy.close()
                return@Analyzer
            }
            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    Log.d(TAG, "saw ${codes.size} barcode(s)")
                    val hit = codes.firstOrNull()?.rawValue
                    if (hit != null && hasFiredRef.compareAndSet(false, true)) {
                        onScan(hit)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "detect failed: ${e.message}")
                }
                // Only now is the buffer safe to hand back. See (1) above.
                .addOnCompleteListener { proxy.close() }
        }
    }

    DisposableEffect(scanner) {
        onDispose {
            hasFiredRef.set(true)
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // FILL_CENTER is the counterpart of `.scaledToFill()` plus
                // `clipped()` on iOS: full-bleed camera, no letterboxing.
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // 640x480 (CameraX's default analysis size) is too coarse for a
                // 40 mm ISBN barcode held at arm's length: the bars alias into
                // noise and the detector reports nothing. 720p is the sweet spot
                // — enough resolution, still fast enough to analyse every frame.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }

                try {
                    provider.unbindAll()
                    // Back camera only: a book barcode read by a front camera
                    // cannot happen, and offering the flip is handset noise.
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    Log.d(TAG, "camera bound")
                } catch (e: Exception) {
                    // Another component holds the camera, or the device refused
                    // the resolution. Logged rather than swallowed so a silent
                    // black preview has something behind it.
                    Log.e(TAG, "camera bind failed: ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        onRelease = { hasFiredRef.set(true) },
    )
}
