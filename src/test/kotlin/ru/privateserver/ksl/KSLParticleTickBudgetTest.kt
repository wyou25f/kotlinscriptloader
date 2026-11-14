package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLParticleTickBudgetTest {
    @Test
    fun `effects share one global tick budget`() {
        val budget = KSLParticleTickBudget(maxParticlesPerTick = 10, maxParticlesPerEffect = 8)
        val first = budget.allocate(42, 2, 3)
        val second = budget.allocate(42, 2, 3)
        val third = budget.allocate(42, 2, 3)
        val exhausted = budget.allocate(42, 2, 3)

        assertEquals(6, first.particles)
        assertEquals(3, second.particles)
        assertEquals(1, third.particles)
        assertEquals(0, exhausted.particles)
        assertEquals(10, budget.emittedInTick(42))
    }

    @Test
    fun `budget resets on next server tick`() {
        val budget = KSLParticleTickBudget(maxParticlesPerTick = 5, maxParticlesPerEffect = 5)
        assertEquals(5, budget.allocate(10, 1, 5).particles)
        assertEquals(0, budget.allocate(10, 1, 5).particles)
        assertEquals(5, budget.allocate(11, 1, 5).particles)
    }

    @Test
    fun `zero count preserves Bukkit single particle mode`() {
        val budget = KSLParticleTickBudget(maxParticlesPerTick = 10, maxParticlesPerEffect = 10)
        val allocation = budget.allocate(7, 0, 4)
        assertEquals(4, allocation.pointsToEmit)
        assertEquals(0, allocation.countPerPoint)
        assertEquals(4, allocation.particles)
    }
}
