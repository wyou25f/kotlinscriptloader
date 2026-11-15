package ru.privateserver.ksl.dsl

import be.seeseemelk.mockbukkit.MockBukkit
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KSLDslItemTest {
    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun itemRejectsZeroOrNegativeAmounts() {
        assertThrows(IllegalArgumentException::class.java) { item(Material.DIAMOND, amount = 0) }
        assertThrows(IllegalArgumentException::class.java) { item(Material.DIAMOND, amount = -1) }
    }

    @Test
    fun itemPreservesValidAmount() {
        assertEquals(3, item(Material.DIAMOND, amount = 3).amount)
    }
}
