package com.chadmorph

/**
 * Индексы MediaPipe Face Landmarker (478 точек, включая радужки), которые использует движок.
 * "L" = слева на изображении (правая сторона лица субъекта), "R" = справа на изображении.
 * Чистый Kotlin, без Android-зависимостей.
 */
object LM {
    const val STRIDE = 3          // x, y, z на точку; x,y — нормализованные, z — масштаб как у x
    const val COUNT = 478

    val JAW_L = intArrayOf(58, 172, 136, 150, 149, 176)
    val JAW_R = intArrayOf(288, 397, 365, 379, 378, 400)
    /** Профиль бокового смещения и «квадратизации» вниз вдоль контура (от скулы к подбородку). */
    val JAW_OUT = floatArrayOf(0.60f, 1.00f, 1.00f, 0.70f, 0.30f, 0.00f)
    val JAW_SQUARE = floatArrayOf(0.00f, 0.00f, 0.30f, 0.70f, 0.80f, 0.50f)

    class Eye(
        val outer: Int, val inner: Int,
        val upper: IntArray, val lower: IntArray,
        val upperMid: Int, val lowerMid: Int
    )

    val EYE_L = Eye(33, 133, intArrayOf(161, 160, 159, 158, 157), intArrayOf(163, 144, 145, 153, 154), 159, 145)
    val EYE_R = Eye(263, 362, intArrayOf(388, 387, 386, 385, 384), intArrayOf(390, 373, 374, 380, 381), 386, 374)
    val LID_PROFILE = floatArrayOf(0.55f, 0.85f, 1.00f, 0.85f, 0.55f)

    /** Пары для коррекции симметрии: только «скелетные» точки, без век и губ (чтобы не менять мимику). */
    val SYMMETRY_PAIRS: Array<IntArray> = arrayOf(
        intArrayOf(33, 263), intArrayOf(133, 362), intArrayOf(234, 454), intArrayOf(93, 323),
        intArrayOf(132, 361), intArrayOf(58, 288), intArrayOf(172, 397), intArrayOf(136, 365),
        intArrayOf(150, 379), intArrayOf(149, 378), intArrayOf(176, 400), intArrayOf(148, 377),
        intArrayOf(116, 345), intArrayOf(123, 352)
    )

    /** Контрольные точки деформации в фиксированном порядке (порядок важен для временного сглаживания). */
    val CTRL: IntArray = run {
        val l = ArrayList<Int>()
        for (i in JAW_L) l.add(i)
        for (i in JAW_R) l.add(i)
        l.addAll(listOf(152, 175, 148, 377, 205, 425, 132, 361, 116, 345, 123, 352, 234, 454, 93, 323))
        for (e in listOf(EYE_L, EYE_R)) {
            l.add(e.outer); l.add(e.inner)
            for (i in e.upper) l.add(i)
            for (i in e.lower) l.add(i)
        }
        l.addAll(listOf(0, 17, 61, 291)) // «якоря» рта: не даём челюсти/подбородку тянуть губы
        l.toIntArray()
    }

    /** landmark id -> индекс в CTRL (или -1). */
    val CTRL_INDEX: IntArray = IntArray(COUNT) { -1 }.also { a -> CTRL.forEachIndexed { n, id -> a[id] = n } }

    /** Все индексы, которые нужно сглаживать/читать из MediaPipe. */
    val USED_IDS: IntArray = run {
        val s = LinkedHashSet<Int>()
        for (i in CTRL) s.add(i)
        for (i in intArrayOf(10, 168, 1, 152, 0, 17, 33, 263, 133, 362, 234, 454)) s.add(i)
        for (p in SYMMETRY_PAIRS) { s.add(p[0]); s.add(p[1]) }
        s.toIntArray()
    }
}
