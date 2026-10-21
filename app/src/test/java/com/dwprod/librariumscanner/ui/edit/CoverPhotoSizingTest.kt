// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 FireBall1725 (Adaléa)

package com.dwprod.librariumscanner.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cover downscale arithmetic.
 *
 * This is the one piece of the photo picker that a compile cannot check and
 * that fails quietly: too small a sample size leaves the full-resolution bitmap
 * in memory (a 50 MP photo is ~200 MB of ARGB_8888, which is an OOM on most
 * phones), and too large a one uploads something visibly blockier than the
 * 1600 px the sheet promises. The rounding edge is the interesting part — the
 * loop only halves while the *halved* edge is still at or above the target, so
 * the decoded image never falls below it.
 */
class CoverPhotoSizingTest {

    @Test
    fun smallImagesAreNotSampled() {
        // Every edge already inside the target: no downscale is worth a decode.
        assertEquals(1, sampleSizeFor(1600, 2400))
        assertEquals(1, sampleSizeFor(800, 600))
        assertEquals(1, sampleSizeFor(1600, 1600))
    }

    @Test
    fun sampleHalvesWhileTheEdgeStaysAtOrAboveTheTarget() {
        // 3200 -> 1600 is still at the target, so one halving is allowed.
        assertEquals(2, sampleSizeFor(3200, 3200))
        // 3199 halves to 1599 (integer division), under the target, so it must
        // not be halved.
        assertEquals(1, sampleSizeFor(3199, 3199))
        // The long edge is what counts: 6400 halves twice and lands on 1600.
        assertEquals(4, sampleSizeFor(6400, 3200))
        assertEquals(4, sampleSizeFor(6400, 6400))
        assertEquals(8, sampleSizeFor(12800, 12800))
    }

    @Test
    fun longestEdgeDecidesTheSample() {
        // A tall panorama is sampled for its long edge, not its short one.
        assertEquals(4, sampleSizeFor(6400, 900))
        assertEquals(4, sampleSizeFor(900, 6400))
    }

    @Test
    fun decodedEdgeNeverDropsBelowTheTarget() {
        // The property the loop exists to guarantee, over a spread of shapes:
        // decode at the returned ratio and the long edge is still >= 1600, so
        // `scaleToFit` only ever shrinks a little instead of blurring an
        // already-small image back up.
        val shapes = listOf(
            1601 to 1601, 1602 to 3200, 4000 to 3000, 6000 to 900,
            8000 to 6000, 12000 to 12000, 100 to 100, 50 to 10000,
        )
        for ((w, h) in shapes) {
            val sample = sampleSizeFor(w, h)
            assertTrue(
                "sample $sample too high for ${w}x$h",
                maxOf(w, h) / sample >= MAX_EDGE || sample == 1,
            )
            assertTrue("sample $sample must be a power of two", sample and (sample - 1) == 0)
        }
    }
}
