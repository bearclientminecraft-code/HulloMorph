package com.chadmorph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Debug-визуализация геометрии (до/после). Слева — исходный кадр (анализ-разрешение):
 * красная сетка = откуда каждый узел выходной сетки берёт пиксели (то есть поле деформации), жёлтые точки =
 * контрольные landmarks, их смещение — короткие линии. Справа — результат морфинга (реальный рендер на GPU)
 * с регулярной зелёной сеткой.
 */
object MeshDebugOverlay {
    fun compose(
        before: Bitmap, after: Bitmap, mesh: DeformationMesh, field: MorphField, frameW: Int, frameH: Int
    ): Bitmap {
        val aw = before.width; val ah = before.height
        val sx = aw.toFloat() / frameW; val sy = ah.toFloat() / frameH
        val out = Bitmap.createBitmap(aw * 2, ah, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(before, 0f, 0f, null)
        c.drawBitmap(after, aw.toFloat(), 0f, null)

        val red = Paint().apply { color = Color.argb(200, 255, 60, 60); strokeWidth = 1f; isAntiAlias = true }
        val green = Paint().apply { color = Color.argb(200, 60, 255, 90); strokeWidth = 1f; isAntiAlias = true }
        val yellow = Paint().apply { color = Color.YELLOW; strokeWidth = 2f; isAntiAlias = true }
        val step = 4
        val nx = mesh.nx; val ny = mesh.ny
        for (i in 0 until ny step step) for (j in 0 until nx - 1) {
            val a = i * nx + j; val b = a + 1
            c.drawLine(mesh.srcX[a] * sx, mesh.srcY[a] * sy, mesh.srcX[b] * sx, mesh.srcY[b] * sy, red)
            c.drawLine(mesh.gx[a] * sx + aw, mesh.gy[a] * sy, mesh.gx[b] * sx + aw, mesh.gy[b] * sy, green)
        }
        for (j in 0 until nx step step) for (i in 0 until ny - 1) {
            val a = i * nx + j; val b = a + nx
            c.drawLine(mesh.srcX[a] * sx, mesh.srcY[a] * sy, mesh.srcX[b] * sx, mesh.srcY[b] * sy, red)
            c.drawLine(mesh.gx[a] * sx + aw, mesh.gy[a] * sy, mesh.gx[b] * sx + aw, mesh.gy[b] * sy, green)
        }
        for (k in 0 until field.count) {
            val x = field.cx[k] * sx; val y = field.cy[k] * sy
            c.drawCircle(x, y, 2.5f, yellow)
            c.drawLine(x, y, x + field.dx[k] * sx * 3f, y + field.dy[k] * sy * 3f, yellow) // ×3 для наглядности
        }
        return out
    }
}
