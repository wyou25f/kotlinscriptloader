package ru.privateserver.ksl

/** Pure validation for pagination slot layouts; kept independent from Bukkit for regression tests. */
internal object KSLGuiLayout {
    fun validationError(size: Int, slots: List<Int>, prevSlot: Int, nextSlot: Int): String? {
        if (size <= 0) return "неверный размер GUI: $size"
        if (slots.isEmpty()) return "список slots пуст"
        if (slots.any { it !in 0 until size }) return "слоты элементов должны находиться в диапазоне 0..${size - 1}"
        if (slots.distinct().size != slots.size) return "список slots содержит повторяющиеся номера"
        if (prevSlot < -1 || nextSlot < -1) return "слоты навигации должны быть -1 или находиться внутри GUI"
        if (prevSlot >= size || nextSlot >= size) return "слоты навигации должны находиться в диапазоне 0..${size - 1}"
        if (prevSlot >= 0 && prevSlot == nextSlot) return "prevSlot и nextSlot не могут совпадать"
        if (prevSlot in slots || nextSlot in slots) return "слоты навигации не должны пересекаться со слотами элементов"
        return null
    }
}
