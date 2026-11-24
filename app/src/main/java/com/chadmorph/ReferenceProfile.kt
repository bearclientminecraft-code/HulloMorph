package com.chadmorph

/**
 * Безразмерные пропорции лица (2D, в системе координат лица с учётом roll). NaN = неизвестно.
 * Индекс 0 = левая сторона на изображении, 1 = правая.
 * ВАЖНО: измеряются по 2D-проекции; достоверны только при почти фронтальном положении головы.
 */
class FaceMetrics {
    val jawHalf = floatArrayOf(Float.NaN, Float.NaN)   // |u(gonion)| / (половина bizygomatic)
    val gonial = floatArrayOf(Float.NaN, Float.NaN)    // угол в gonion, рад
    val cheek = floatArrayOf(Float.NaN, Float.NaN)     // |u(контур щеки)| / половина bizygomatic
    val tilt = floatArrayOf(Float.NaN, Float.NaN)      // canthal tilt, рад (+ = внешний угол выше)
    val open = floatArrayOf(Float.NaN, Float.NaN)      // раскрытие глаза / ширина глаза
    var chinHeight = Float.NaN                         // |губа–подбородок| / высота лица
    var chinWidth = Float.NaN                          // ширина подбородка / bizygomatic
}

/**
 * Референс: целевые пропорции (из фото-образца или заданные вручную). Передаётся только геометрия
 * пропорций — не текстура и не идентичность. [mirror]=true усредняет лево/право референса.
 */
class ReferenceProfile(val target: FaceMetrics, val mirror: Boolean = true) {
    private fun pick(a: FloatArray, side: Int): Float {
        if (!mirror) return a[side]
        val x = a[0]; val y = a[1]
        return when {
            x.isNaN() -> y
            y.isNaN() -> x
            else -> (x + y) * 0.5f
        }
    }

    fun jawHalf(side: Int) = pick(target.jawHalf, side)
    fun gonial(side: Int) = pick(target.gonial, side)
    fun cheek(side: Int) = pick(target.cheek, side)
    fun tilt(side: Int) = pick(target.tilt, side)
    fun open(side: Int) = pick(target.open, side)
}
