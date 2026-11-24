package com.chadmorph

/**
 * Нормализованные параметры морфинга одной стороны лица (все 0..1; 0 = выкл, 1 = максимум из [MorphLimits]).
 * Лево/право независимы. Если задан [ReferenceProfile], параметр означает долю пути «исходник → референс»,
 * ограниченную тем же максимумом.
 */
data class SideParams(
    val jaw: Float = 0f,        // ширина нижней челюсти + «квадратизация»
    val gonial: Float = 0f,     // резкость угла челюсти (gonion)
    val hollow: Float = 0f,     // впалость щеки (геометрия контура щеки)
    val bone: Float = 0f,       // скуловая кость: подъём/вынос
    val tilt: Float = 0f,       // canthal tilt: внешний угол глаза вверх
    val lidDrop: Float = 0f,    // верхнее веко вниз (hunter eyes), пропорционально текущему раскрытию глаза
    val lowerLid: Float = 0f    // нижнее веко вверх
) {
    fun clamped() = SideParams(
        jaw.coerceIn(0f, 1f), gonial.coerceIn(0f, 1f), hollow.coerceIn(0f, 1f), bone.coerceIn(0f, 1f),
        tilt.coerceIn(0f, 1f), lidDrop.coerceIn(0f, 1f), lowerLid.coerceIn(0f, 1f)
    )

    companion object {
        val NONE = SideParams()
        val ALL = SideParams(1f, 1f, 1f, 1f, 1f, 1f, 1f)
    }
}

/** Физические максимумы при параметре = 1 (доли масштаба лица S, у век — доли раскрытия глаза). */
data class MorphLimits(
    val jaw: Float = 0.060f,
    val jawSquare: Float = 0.032f,
    val gonial: Float = 0.035f,
    val chinProjection: Float = 0.075f,
    val chinWidth: Float = 0.030f,
    val hollow: Float = 0.050f,
    val bone: Float = 0.030f,
    val tilt: Float = 0.140f,       // × ширина глаза
    val lidDrop: Float = 0.300f,    // × текущее раскрытие глаза
    val lowerLid: Float = 0.200f,   // × текущее раскрытие глаза
    val symmetry: Float = 0.040f
)

data class MorphConfig(
    val left: SideParams = SideParams.NONE,
    val right: SideParams = SideParams.NONE,
    val chinProjection: Float = 0f,
    val chinWidth: Float = 0f,
    /** 0..1: насколько приближать парные точки к зеркальной симметрии (только при почти анфас). */
    val symmetry: Float = 0f,
    /** 0..1 общий множитель. */
    val strength: Float = 1f,
    val limits: MorphLimits = MorphLimits(),
    /** Допустимое отношение площади треугольника сетки «источник/назначение»: защита от складок и растяжения. */
    val minAreaRatio: Float = 0.35f,
    val maxAreaRatio: Float = 2.5f
) {
    fun clamped() = copy(
        left = left.clamped(), right = right.clamped(),
        chinProjection = chinProjection.coerceIn(0f, 1f), chinWidth = chinWidth.coerceIn(0f, 1f),
        symmetry = symmetry.coerceIn(0f, 1f), strength = strength.coerceIn(0f, 1f)
    )

    fun isIdentity(): Boolean {
        val c = clamped()
        return c.strength == 0f || (c.left == SideParams.NONE && c.right == SideParams.NONE &&
            c.chinProjection == 0f && c.chinWidth == 0f && c.symmetry == 0f)
    }

    companion object {
        val OFF = MorphConfig()
        val STRONG = MorphConfig(
            left = SideParams(0.7f, 0.6f, 0.6f, 0.5f, 0.5f, 0.5f, 0.4f),
            right = SideParams(0.7f, 0.6f, 0.6f, 0.5f, 0.5f, 0.5f, 0.4f),
            chinProjection = 0.6f, chinWidth = 0.4f, symmetry = 0.3f
        )
        /** Максимум по всем параметрам. Реально применённая сила ограничивается защитой от складок. */
        val EXTREME_HUNTER = MorphConfig(
            left = SideParams.ALL, right = SideParams.ALL,
            chinProjection = 1f, chinWidth = 1f, symmetry = 0.5f
        )
    }
}
