package com.williambl.haema.common.capability

import com.williambl.haema.common.util.VampireAbilities
import net.minecraft.nbt.CompoundNBT
import net.minecraft.nbt.INBT
import net.minecraft.nbt.NBTBase
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.Direction
import net.minecraft.util.EnumFacing
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.CapabilityInject
import net.minecraftforge.common.capabilities.ICapabilitySerializable
import net.minecraftforge.common.util.LazyOptional
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

interface ICapabilityVampirism {
    fun getBloodLevel(): Float
    fun setBloodLevel(input: Float)
    fun addBloodLevel(input: Float)

    fun isVampire(): Boolean
    fun setIsVampire(input: Boolean)

    fun getPowerMultiplier(): Float
    fun getInversePowerMultiplier(): Float

    fun getAbilities(): Int
    fun hasAbility(ability: VampireAbilities): Boolean
}

class CapabilityVampirismImpl: ICapabilityVampirism {

    private var bloodLevel: Float = 0.0f
    private var vampire: Boolean = false

    override fun getBloodLevel(): Float {
        return bloodLevel
    }

    override fun setBloodLevel(input: Float) {
        bloodLevel = input
        bloodLevel = min(bloodLevel, 1.0f)
        bloodLevel = max(bloodLevel, 0.0f)
    }

    override fun addBloodLevel(input: Float) {
        bloodLevel += input
        bloodLevel = min(bloodLevel, 1.0f)
        bloodLevel = max(bloodLevel, 0.0f)
    }

    override fun isVampire(): Boolean {
        return vampire
    }

    override fun setIsVampire(input: Boolean) {
        vampire = input
    }

    override fun getPowerMultiplier(): Float {
        return 10.0f * bloodLevel.pow(2)
    }

    override fun getInversePowerMultiplier(): Float {
        return if (bloodLevel > 0.1f)
            (0.1f / bloodLevel.pow(2))
        else
            10.0f
    }

    override fun getAbilities(): Int {
        var value = 0
        if (bloodLevel < 0.1 ) { value = value or VampireAbilities.WEAKNESS.flag }
        if (bloodLevel > 0.5 ) { value = value or VampireAbilities.STRENGTH.flag }
        if (bloodLevel > 0.6 ) { value = value or VampireAbilities.VISION.flag }
        if (bloodLevel > 0.7) {
            value = value or VampireAbilities.CHARISMA.flag
        }
        if (bloodLevel > 0.75 ) { value = value or VampireAbilities.FLIGHT.flag }
        if (bloodLevel > 0.95 ) { value = value or VampireAbilities.INVISIBILITY.flag }

        return value
    }

    override fun hasAbility(ability: VampireAbilities): Boolean {
        return getAbilities() and ability.flag != 0
    }

}

class VampirismStorage: Capability.IStorage<ICapabilityVampirism> {

    override fun readNBT(capability: Capability<ICapabilityVampirism>?, instance: ICapabilityVampirism?, side: Direction?, nbt: INBT?) {
        nbt as CompoundNBT
        instance?.setBloodLevel(nbt.getFloat("bloodLevel"))
        instance?.setIsVampire(nbt.getBoolean("isVampire"))
    }

    override fun writeNBT(capability: Capability<ICapabilityVampirism>?, instance: ICapabilityVampirism?, side: Direction?): INBT? {
        instance ?: return null

        val compound = CompoundNBT()
        compound.putFloat("bloodLevel", instance.getBloodLevel())
        compound.putBoolean("isVampire", instance.isVampire())
        return compound
    }

}

class VampirismProvider : ICapabilitySerializable<INBT> {

    companion object {
        @CapabilityInject(ICapabilityVampirism::class)
        val vampirism: Capability<ICapabilityVampirism>? = null
    }

    private val instance: ICapabilityVampirism = vampirism!!.defaultInstance!!

    override fun <T> getCapability(capability: Capability<T>, facing: Direction?): LazyOptional<T> {
        return vampirism!!.orEmpty(capability, LazyOptional.of {-> instance})
    }

    override fun deserializeNBT(nbt: INBT?) {
        vampirism!!.storage.readNBT(vampirism, instance, null, nbt)
    }

    override fun serializeNBT(): INBT {
        return vampirism!!.storage.writeNBT(vampirism, instance, null)!!
    }

}