package ru.privateserver.ksl.std.item

import be.seeseemelk.mockbukkit.MockBukkit
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class KSLCustomEnchantmentsTest {
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

    private fun enchant(
        id: String = "frost",
        key: String = "frost",
        targets: Set<Material> = setOf(Material.DIAMOND_SWORD),
        conflicts: Set<NamespacedKey> = emptySet(),
        maxLevel: Int = 3
    ) = KSLCustomEnchantment(
        id = id,
        namespacedId = "test:$id",
        displayName = Component.text("Frost"),
        maxLevel = maxLevel,
        targets = targets,
        conflictKeys = conflicts,
        storageKey = NamespacedKey.minecraft(key),
        owner = "test",
        hitHandler = null,
        useHandler = null,
        breakHandler = null,
        killHandler = null
    )

    @Test
    fun customEnchantIsStoredOnACloneAndLevelCanBeRead() {
        val definition = enchant()
        val original = ItemStack(Material.DIAMOND_SWORD)

        val applied = original.withCustomEnchant(definition, 2)

        assertFalse(original.hasCustomEnchant(definition))
        assertTrue(applied.hasCustomEnchant(definition))
        assertEquals(2, applied.customEnchantLevel(definition))
        assertEquals(1, original.amount)
    }

    @Test
    fun customEnchantCanBeRemovedWithoutChangingTheOriginal() {
        val definition = enchant()
        val original = ItemStack(Material.DIAMOND_SWORD).withCustomEnchant(definition, 2)

        val removed = original.withoutCustomEnchant(definition)

        assertFalse(removed.hasCustomEnchant(definition))
        assertEquals(2, original.customEnchantLevel(definition))
    }

    @Test
    fun customEnchantRejectsInvalidLevelsAndTargets() {
        val definition = enchant()
        val sword = ItemStack(Material.DIAMOND_SWORD)
        val pickaxe = ItemStack(Material.DIAMOND_PICKAXE)

        assertThrows(IllegalArgumentException::class.java) { sword.withCustomEnchant(definition, 0) }
        assertThrows(IllegalArgumentException::class.java) { sword.withCustomEnchant(definition, 4) }
        assertThrows(IllegalArgumentException::class.java) { pickaxe.withCustomEnchant(definition) }
    }

    @Test
    fun customEnchantRejectsConflictingEnchantments() {
        val frost = enchant()
        val flame = enchant(id = "flame", key = "flame", conflicts = setOf(frost.storageKey))
        val withFrost = ItemStack(Material.DIAMOND_SWORD).withCustomEnchant(frost, 1)

        assertThrows(IllegalArgumentException::class.java) {
            withFrost.withCustomEnchant(flame, 1)
        }
        assertTrue(flame.conflictKeys.contains(frost.storageKey))
    }
}
