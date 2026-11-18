package ru.privateserver.ksl

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

class KSLShopkeeperTradeFactoryTest {

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
    fun `creates a fixed price two input trade and clones item stacks`() {
        val firstCost = ItemStack(Material.EMERALD, 8)
        val secondCost = ItemStack(Material.PAPER, 2)
        val result = ItemStack(Material.DIAMOND, 1)

        val recipe = KSLShopkeeperTradeFactory.create(
            cost = firstCost,
            result = result,
            secondCost = secondCost,
            maxUses = 64,
            rewardExperience = false,
            villagerExperience = 0,
            priceMultiplier = 0f,
            ignoreDiscounts = true
        )

        firstCost.amount = 1
        secondCost.amount = 1
        result.amount = 4

        assertEquals(listOf(8, 2), recipe.ingredients.map { it.amount })
        assertEquals(1, recipe.result.amount)
        assertEquals(64, recipe.maxUses)
        assertEquals(0, recipe.uses)
        assertEquals(0f, recipe.priceMultiplier)
        assertFalse(recipe.hasExperienceReward())
        assertEquals(0, recipe.villagerExperience)
        assertTrue(recipe.shouldIgnoreDiscounts())
    }

    @Test
    fun `rejects air stacks and invalid trade settings`() {
        val validCost = ItemStack(Material.EMERALD, 1)
        val validResult = ItemStack(Material.DIAMOND, 1)

        assertThrows(IllegalArgumentException::class.java) {
            KSLShopkeeperTradeFactory.create(
                ItemStack(Material.AIR), validResult, null, 1, false, 0, 0f, true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLShopkeeperTradeFactory.create(
                validCost, validResult, null, 0, false, 0, 0f, true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLShopkeeperTradeFactory.create(
                validCost, validResult, null, 1, false, 0, Float.NaN, true
            )
        }
    }
}
