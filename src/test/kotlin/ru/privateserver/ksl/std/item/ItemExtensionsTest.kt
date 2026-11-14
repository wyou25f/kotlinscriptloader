package ru.privateserver.ksl.std.item

import be.seeseemelk.mockbukkit.MockBukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ItemExtensionsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun startMockServer() {
            MockBukkit.mock()
        }

        @JvmStatic
        @AfterAll
        fun stopMockServer() {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `copy with amount preserves original stack`() {
        val original = ItemStack(Material.STONE, 4)
        val copy = original.copyWithAmount(12)

        assertEquals(4, original.amount)
        assertEquals(12, copy.amount)
        assertFalse(original === copy)
    }

    @Test
    fun `zero amount and air are empty stacks`() {
        val zero = ItemStack(Material.STONE, 1).copyWithAmount(0)

        assertTrue(zero.isEmptyStack())
        assertTrue(ItemStack(Material.AIR).isEmptyStack())
        assertFalse(ItemStack(Material.STONE).isEmptyStack())
    }

    @Test
    fun `negative copy amount is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ItemStack(Material.STONE).copyWithAmount(-1)
        }
    }
}
