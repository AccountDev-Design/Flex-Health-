package com.flexhealth.heartmonitor.receiver.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayGeometryTest {
    private val g = OverlayGeometry(density = 2f) // xhdpi: 1 dp = 2 px
    private val sw = 1080; private val sh = 2340

    @Test fun presetsMatchSpecification() {
        assertEquals(190 to 65, OverlaySize.COMPACT.widthDp to OverlaySize.COMPACT.heightDp)
        assertEquals(230 to 76, OverlaySize.NORMAL.widthDp to OverlaySize.NORMAL.heightDp)
        assertEquals(280 to 100, OverlaySize.LARGE.widthDp to OverlaySize.LARGE.heightDp)
    }

    @Test fun sizeIsClampedToLimits() {
        assertEquals(g.minW to g.minH, g.clampSize(10, 10, sw, sh))
        assertEquals(g.maxW to g.maxH, g.clampSize(5000, 5000, sw, sh))
        assertEquals(380 to 130, g.clampSize(380, 130, sw, sh))
        // Pantalla más pequeña que el máximo: nunca más grande que la pantalla.
        assertEquals(600 to g.maxH, g.clampSize(5000, 5000, 600, 2000))
    }

    @Test fun positionNeverLeavesScreen() {
        assertEquals(0 to 0, g.clampPosition(-50, -50, 380, 130, sw, sh))
        assertEquals((sw - 380) to (sh - 130), g.clampPosition(5000, 5000, 380, 130, sw, sh))
        assertEquals(100 to 200, g.clampPosition(100, 200, 380, 130, sw, sh))
    }

    @Test fun fractionsSurviveRotation() {
        val w = 380; val h = 130
        val fx = g.toFraction(sw - w, w, sw) // pegado a la derecha en vertical
        val fy = g.toFraction(0, h, sh)
        // Tras rotar a horizontal (2340 × 1080) sigue pegado a la derecha y arriba.
        assertEquals(2340 - w, g.fromFraction(fx, w, 2340))
        assertEquals(0, g.fromFraction(fy, h, 1080))
        assertEquals(0f, g.toFraction(10, 2000, 1000))
    }

    @Test fun resizeFromCornersAndEdgesKeepsOppositeEdge() {
        val start = PxRect(100, 100, 380, 130)
        val br = g.resize(start, ResizeHandle.BOTTOM_RIGHT, 40, 20, sw, sh)
        assertEquals(PxRect(100, 100, 420, 150), br)
        val tl = g.resize(start, ResizeHandle.TOP_LEFT, -20, -10, sw, sh)
        assertEquals(PxRect(80, 90, 400, 140), tl)
        // Reducir por debajo del mínimo se detiene en el mínimo y el borde derecho no se mueve.
        val left = g.resize(start, ResizeHandle.LEFT, 1000, 0, sw, sh)
        assertEquals(g.minW, left.w); assertEquals(480, left.x + left.w)
        // Crecer más allá del máximo se detiene en el máximo.
        val right = g.resize(start, ResizeHandle.RIGHT, 5000, 0, sw * 2, sh)
        assertEquals(g.maxW, right.w)
        // No puede salir por el borde izquierdo de la pantalla.
        val out = g.resize(PxRect(10, 10, 300, 100), ResizeHandle.LEFT, -500, 0, sw, sh)
        assertEquals(0, out.x)
    }

    @Test fun handleHitTesting() {
        assertEquals(ResizeHandle.TOP_LEFT, g.hitHandle(2f, 2f, 380, 130, 24))
        assertEquals(ResizeHandle.BOTTOM_RIGHT, g.hitHandle(378f, 128f, 380, 130, 24))
        assertEquals(ResizeHandle.RIGHT, g.hitHandle(370f, 60f, 380, 130, 24))
        assertNull(g.hitHandle(190f, 65f, 380, 130, 24))
    }
}
