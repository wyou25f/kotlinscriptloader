package ru.privateserver.ksl.std.inventory

import be.seeseemelk.mockbukkit.MockBukkit
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class InventoryExtensionsTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun startServer() { MockBukkit.mock() }

        @JvmStatic
        @AfterAll
        fun stopServer() { MockBukkit.unmock() }
    }

    @Test
    fun capacityCountsPartialSimilarStacksAndEmptySlots() {
        val inventory = Bukkit.createInventory(null, 9)
        inventory.setItem(0, ItemStack(Material.STONE, 60))
        assertEquals(516, inventory.spaceFor(ItemStack(Material.STONE, 1)))
        assertTrue(inventory.canFit(ItemStack(Material.STONE, 1), 516))
        assertFalse(inventory.canFit(ItemStack(Material.STONE, 1), 517))
        assertEquals(512, inventory.spaceFor(ItemStack(Material.DIAMOND, 1)))
    }

    @Test
    fun emptyOrInvalidInputItemsAreRejected() {
        val inventory = Bukkit.createInventory(null, 9)
        assertThrows(IllegalArgumentException::class.java) {
            inventory.spaceFor(ItemStack(Material.AIR))
        }
        assertThrows(IllegalArgumentException::class.java) {
            inventory.canFit(ItemStack(Material.STONE), -1)
        }
    }

    @Test
    fun similarityAndPredicateHelpersIgnoreAirStacks() {
        val inventory = Bukkit.createInventory(null, 9)
        inventory.setItem(0, ItemStack(Material.AIR, 1))
        inventory.setItem(1, ItemStack(Material.STONE, 2))

        assertEquals(listOf(1), inventory.slotsMatching { true })
        assertEquals(2, inventory.countSimilar(ItemStack(Material.STONE, 1)))
        assertEquals(0, inventory.countSimilar(ItemStack(Material.AIR, 1)))
        assertEquals(0, inventory.removeSimilar(ItemStack(Material.AIR, 1), 1))
        assertEquals(0, inventory.removeMatching(1) { it.type.isAir })
    }

    @Test
    fun predicateHelpersIgnoreEmptySlots() {
        val inventory = Bukkit.createInventory(null, 9)
        inventory.setItem(2, ItemStack(Material.DIRT, 3))
        inventory.setItem(5, ItemStack(Material.DIRT, 2))
        assertEquals(2, inventory.countMatching { it.type == Material.DIRT })
        assertEquals(2, inventory.firstMatchingSlot { it.type == Material.DIRT })
    }
}
