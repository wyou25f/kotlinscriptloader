package ru.privateserver.ksl

import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe

/**
 * Configures one real villager and its native merchant offers.
 * Right-clicking the villager opens Minecraft's built-in trade screen.
 */
class KSLShopkeeperBuilder internal constructor(private val villager: Villager) {

    var profession: Villager.Profession = Villager.Profession.LIBRARIAN
    var villagerType: Villager.Type = Villager.Type.PLAINS
    var level: Int = 5
    var experience: Int = 1
    var aiEnabled: Boolean = false
    var invulnerable: Boolean = true
    var silent: Boolean = true

    private val trades = mutableListOf<MerchantRecipe>()

    /** Adds a one- or two-input trade. ItemStack amounts are preserved. */
    fun trade(
        cost: ItemStack,
        result: ItemStack,
        secondCost: ItemStack? = null,
        maxUses: Int = Int.MAX_VALUE,
        rewardExperience: Boolean = false,
        villagerExperience: Int = 0,
        priceMultiplier: Float = 0f,
        ignoreDiscounts: Boolean = true
    ) {
        require(trades.size < MAX_TRADES) { "У одного shopkeeper может быть не больше $MAX_TRADES сделок" }
        trades += KSLShopkeeperTradeFactory.create(
            cost = cost,
            result = result,
            secondCost = secondCost,
            maxUses = maxUses,
            rewardExperience = rewardExperience,
            villagerExperience = villagerExperience,
            priceMultiplier = priceMultiplier,
            ignoreDiscounts = ignoreDiscounts
        )
    }

    internal fun apply(): KSLShopkeeperBuilder {
        require(level in 1..5) { "Уровень жителя должен быть от 1 до 5" }
        require(experience >= 1) { "Опыт жителя должен быть не меньше 1, иначе Minecraft может сбросить профессию" }
        require(trades.isNotEmpty()) { "Shopkeeper должен содержать хотя бы одну сделку: добавь trade(cost, result)" }

        villager.setProfession(profession)
        villager.setVillagerType(villagerType)
        villager.setVillagerLevel(level)
        villager.setVillagerExperience(experience)
        villager.setAI(aiEnabled)
        villager.isInvulnerable = invulnerable
        villager.isSilent = silent
        villager.isPersistent = true
        villager.setRemoveWhenFarAway(false)
        villager.setRecipes(trades.map { MerchantRecipe(it) })
        return this
    }

    private companion object {
        const val MAX_TRADES = 64
    }
}

/** Pure recipe construction kept separate so trade validation can be unit-tested. */
internal object KSLShopkeeperTradeFactory {

    fun create(
        cost: ItemStack,
        result: ItemStack,
        secondCost: ItemStack?,
        maxUses: Int,
        rewardExperience: Boolean,
        villagerExperience: Int,
        priceMultiplier: Float,
        ignoreDiscounts: Boolean
    ): MerchantRecipe {
        validateStack(cost, "cost")
        validateStack(result, "result")
        secondCost?.let { validateStack(it, "secondCost") }
        require(maxUses > 0) { "maxUses должен быть больше нуля" }
        require(villagerExperience >= 0) { "villagerExperience должен быть неотрицательным" }
        require(priceMultiplier.isFinite() && priceMultiplier >= 0f) {
            "priceMultiplier должен быть конечным неотрицательным числом"
        }

        return MerchantRecipe(result.clone(), maxUses).apply {
            setIngredients(listOfNotNull(cost.clone(), secondCost?.clone()))
            setUses(0)
            setDemand(0)
            setSpecialPrice(0)
            setExperienceReward(rewardExperience)
            setVillagerExperience(villagerExperience)
            setPriceMultiplier(priceMultiplier)
            setIgnoreDiscounts(ignoreDiscounts)
        }
    }

    private fun validateStack(stack: ItemStack, label: String) {
        require(!stack.type.isAir) { "$label не должен быть AIR" }
        require(stack.amount in 1..stack.maxStackSize) {
            "$label amount должен быть от 1 до ${stack.maxStackSize}"
        }
    }
}
