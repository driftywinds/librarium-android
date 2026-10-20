// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.fireball1725.librarium.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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

/**
 * Bare camera + barcode detector.
 *
 * Port of `HeadlessBarcodeScanner` (`RedesignedScanFlow.swift:575-589`) and the
 * format policy from `BarcodeScannerView.swift`: **exactly** `.ean13, .ean8,
 * .code128` and nothing else. QR codes and barcodes that are not ISBN carriers
 * would happily hand back strings that look like one, and every one of those is
 * a lookup that wastes a provider round-trip to answer "no".
 *
 * CameraX + ML Kit stand in for `AVCaptureSession` + `AVCaptureMetadataOutput`.
 * Like the iOS version there is no frame crop — `rectOfInterest` is never set on
 * iOS either, so the highlighted frame on screen is cosmetic.
 *
 * Single-shot semantics: the first barcode wins and analysis stops immediately.
 * [hasFired] guards the debounce, because ML Kit fires on every frame and a
 * lookup per frame would hammer the server.
 */
@Composable
fun BarcodeCamera(
    modifier: Modifier = Modifier,
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

    var hasFired by remember { mutableStateOf(false) }

    val executor = remember { Executors.newSingleThreadExecutor() }

    val analyzer = remember {
        ImageAnalysis.Analyzer { proxy ->
            if (hasFired) {
                proxy.close()
                return@Analyzer
            }
            // ML Kit is happy with the default rotation metadata CameraX carries.
            val media = InputImage.fromMediaImage(proxy.image!!, proxy.imageInfo.rotationDegrees)
            proxy.close()
            val client = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_CODE_128,
                    )
                    .build()
            )
            client.process(media)
                .addOnSuccessListener { codes ->
                    val hit = codes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                    if (!hasFired) {
                        hasFired = true
                        onScan(hit)
                    }
                }
                .addOnCompleteListener { client.close() }
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
                val analysis = ImageAnalysis.Builder()
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
                } catch (_: Exception) {
                    // Another component holds the camera; the retry comes from
                    // recomposition rather than a crash loop.
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        onRelease = {
            hasFired = true
            executor.shutdown()
        },
    )
}
