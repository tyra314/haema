package com.williambl.haema

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.williambl.haema.component.VampireComponent
import com.williambl.haema.component.VampirePlayerComponent
import com.williambl.haema.craft.BookOfBloodRecipe
import com.williambl.haema.effect.SunlightSicknessEffect
import com.williambl.haema.effect.VampiricStrengthEffect
import com.williambl.haema.effect.VampiricWeaknessEffect
import com.williambl.haema.entity.VampireHunterEntity
import com.williambl.haema.entity.VampireHunterSpawner
import com.williambl.haema.item.EmptyVampireBloodInjectorItem
import com.williambl.haema.item.VampireBloodInjectorItem
import com.williambl.haema.util.*
import dev.onyxstudios.cca.api.v3.entity.EntityComponentFactory
import dev.onyxstudios.cca.api.v3.entity.EntityComponentFactoryRegistry
import io.netty.buffer.Unpooled
import nerdhub.cardinal.components.api.util.RespawnCopyStrategy
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricEntityTypeBuilder
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.gamerule.v1.CustomGameRuleCategory
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry
import net.fabricmc.fabric.api.loot.v1.FabricLootPoolBuilder
import net.fabricmc.fabric.api.loot.v1.FabricLootSupplierBuilder
import net.fabricmc.fabric.api.loot.v1.event.LootTableLoadingCallback
import net.fabricmc.fabric.api.loot.v1.event.LootTableLoadingCallback.LootTableSetter
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry
import net.fabricmc.fabric.api.tag.TagRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.block.BedBlock
import net.minecraft.block.DispenserBlock
import net.minecraft.block.dispenser.FallibleItemDispenserBehavior
import net.minecraft.block.enums.BedPart
import net.minecraft.client.item.TooltipContext
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.entity.EntityDimensions
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnGroup
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemGroup
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.loot.ConstantLootTableRange
import net.minecraft.loot.LootManager
import net.minecraft.loot.entry.ItemEntry
import net.minecraft.network.PacketByteBuf
import net.minecraft.particle.DustParticleEffect
import net.minecraft.resource.ResourceManager
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.text.TranslatableText
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPointer
import net.minecraft.util.math.Box
import net.minecraft.util.registry.Registry
import net.minecraft.village.TradeOffer
import net.minecraft.village.TradeOffers
import net.minecraft.village.VillagerProfession
import net.minecraft.world.GameRules
import net.minecraft.world.World
import org.apache.logging.log4j.LogManager

val bloodLevelPackeId = Identifier("haema:bloodlevelsync")

val goodBloodTag = TagRegistry.entityType(Identifier("haema:good_blood_sources"))
val mediumBloodTag = TagRegistry.entityType(Identifier("haema:medium_blood_sources"))
val poorBloodTag = TagRegistry.entityType(Identifier("haema:poor_blood_sources"))

val vampireEffectiveWeaponsTag = TagRegistry.item(Identifier("haema:vampire_weapons"))

val dungeonLootTable = Identifier("minecraft:chests/simple_dungeon")
val jungleTempleLootTable = Identifier("minecraft:chests/jungle_temple")
val desertPyramidLootTable = Identifier("minecraft:chests/desert_pyramid")

val haemaCategory = CustomGameRuleCategory(Identifier("haema:haema"), TranslatableText("gamerule.category.haema").formatted(Formatting.BOLD).formatted(Formatting.YELLOW))

lateinit var vampireHunterSpawner: VampireHunterSpawner

val logger = LogManager.getLogger("Haema")

fun init() {
    UseEntityCallback.EVENT.register(UseEntityCallback { player, world, hand, entity, entityHitResult ->
        if ((player as Vampirable).isVampire && entity is LivingEntity && player.isSneaking)
            (player.hungerManager as VampireBloodManager).feed(entity, player)
        else ActionResult.PASS
    })

    UseBlockCallback.EVENT.register(UseBlockCallback { player, world, hand, blockHitResult ->
        val state = world.getBlockState(blockHitResult.blockPos)

        if (state.block !is BedBlock) {
            return@UseBlockCallback ActionResult.PASS
        }

        val pos = if (state.get(BedBlock.PART) == BedPart.HEAD)
            blockHitResult.blockPos
        else
            blockHitResult.blockPos.offset(state.get(BedBlock.FACING))
        val entities = world.getOtherEntities(player, Box(pos)) { it is LivingEntity && it.isSleeping }

        if (entities.isNotEmpty() && (player as Vampirable).isVampire && player.isSneaking) {
            (player.hungerManager as VampireBloodManager).feed(entities[0] as LivingEntity, player)
        } else ActionResult.PASS
    })

    ServerEntityEvents.ENTITY_LOAD.register(ServerEntityEvents.Load { entity, serverWorld ->
        if (entity is PlayerEntity) {
            val buf = PacketByteBuf(Unpooled.buffer())
            buf.writeInt(serverWorld.gameRules.get(dashCooldown).get())
            ServerSidePacketRegistry.INSTANCE.sendToPlayer(entity, Identifier("haema:updatedashcooldown"), buf)
        }
    })

    LootTableLoadingCallback.EVENT.register(LootTableLoadingCallback { resourceManager: ResourceManager?, lootManager: LootManager?, id: Identifier?, supplier: FabricLootSupplierBuilder, setter: LootTableSetter? ->
        if (id == dungeonLootTable || id == jungleTempleLootTable || id == desertPyramidLootTable) {
            val poolBuilder: FabricLootPoolBuilder = FabricLootPoolBuilder.builder()
                .rolls(ConstantLootTableRange.create(1))
                .withEntry(ItemEntry.builder(Registry.ITEM.get(Identifier("haema:vampire_blood")))
                    .weight(if (id == dungeonLootTable) 10 else 5)
                    .build()
                )
                .withEntry(ItemEntry.builder(Items.AIR)
                    .weight(10)
                    .build()
                )
            supplier.withPool(poolBuilder.build())
        }
    })



    ServerSidePacketRegistry.INSTANCE.register(Identifier("haema:dash")) { packetContext, packetByteBuf ->
        val player = packetContext.player
        val world = player.world
        val target = raytraceForDash(player)

        if (target != null) {
            val rand = world.random
            for (j in 0 until 3) {
                val x: Double = (target.x - player.x) * rand.nextDouble() + player.x - 0.5
                val y: Double = (target.y - player.y) * rand.nextDouble() + player.y + 1
                val z: Double = (target.z - player.z) * rand.nextDouble() + player.z - 0.5
                (world as ServerWorld).spawnParticles(
                    DustParticleEffect.RED,
                    x, y, z,
                    10,
                    0.5, 1.0, 0.5,
                    0.0
                )
            }
            world.playSound(null, target.x, target.y, target.z, SoundEvents.ENTITY_GHAST_SHOOT, SoundCategory.PLAYERS, 1f, 1.5f)
            player.teleport(target.x, target.y, target.z)
        }
    }

    Registry.register(
        Registry.STATUS_EFFECT,
        Identifier("haema:sunlight_sickness"),
        SunlightSicknessEffect.instance
    )

    Registry.register(
        Registry.STATUS_EFFECT,
        Identifier("haema:vampiric_strength"),
        VampiricStrengthEffect.instance
    )

    Registry.register(
        Registry.STATUS_EFFECT,
        Identifier("haema:vampiric_weakness"),
        VampiricWeaknessEffect.instance
    )

    Registry.register(
        Registry.ITEM,
        Identifier("haema:vampire_blood"),
        object : Item(Item.Settings().group(ItemGroup.MISC)) {
            override fun appendTooltip(stack: ItemStack?, world: World?, tooltip: MutableList<Text>, context: TooltipContext?) {
                super.appendTooltip(stack, world, tooltip, context)
                tooltip.add(TranslatableText("item.haema.vampire_blood.desc").formatted(Formatting.DARK_RED))
            }
        }
    )

    Registry.register(
        Registry.ITEM,
        Identifier("haema:vampire_blood_injector"),
        VampireBloodInjectorItem(Item.Settings().group(ItemGroup.TOOLS).maxCount(1))
    )

    Registry.register(
        Registry.ITEM,
        Identifier("haema:empty_vampire_blood_injector"),
        EmptyVampireBloodInjectorItem(Item.Settings().group(ItemGroup.TOOLS).maxCount(1))
    )

    Registry.register(
        Registry.RECIPE_SERIALIZER,
        Identifier("haema:book_of_blood"),
        BookOfBloodRecipe.Serializer
    )

    Registry.register(
        Registry.ENTITY_TYPE,
        Identifier("haema:vampire_hunter"),
        FabricEntityTypeBuilder.create<VampireHunterEntity>(SpawnGroup.CREATURE) {type, world -> VampireHunterEntity(type, world) }
            .dimensions(EntityDimensions.fixed(0.5f, 2f))
            .trackable(128, 3).spawnableFarFromPlayer().build()
    )

    @Suppress("UNCHECKED_CAST")
    FabricDefaultAttributeRegistry.register(
        Registry.ENTITY_TYPE.get(Identifier("haema:vampire_hunter")) as EntityType<out LivingEntity>?,
        HostileEntity.createHostileAttributes().add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3499999940395355)
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 5.0)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
    )

    @Suppress("UNCHECKED_CAST")
    vampireHunterSpawner = VampireHunterSpawner(Registry.ENTITY_TYPE.get(Identifier("haema:vampire_hunter")) as EntityType<out VampireHunterEntity>)

    addTradesToProfession(
        VillagerProfession.CLERIC,
        3,
        TradeOffers.Factory { _, _ ->
            TradeOffer(
                ItemStack(Items.EMERALD, 5),
                ItemStack(Registry.ITEM.get(Identifier("haema:vampire_blood"))),
                1,
                30,
                0.05f
            )
        }
    )

    DispenserBlock.registerBehavior(Registry.ITEM.get(Identifier("haema:vampire_blood_injector")), object : FallibleItemDispenserBehavior() {
        override fun dispenseSilently(pointer: BlockPointer, stack: ItemStack): ItemStack {
            val blockPos = pointer.blockPos.offset(pointer.blockState.get(DispenserBlock.FACING))
            val user = pointer.world.getEntitiesByClass(PlayerEntity::class.java, Box(blockPos), null)
                .firstOrNull() ?: return stack
            return if ((stack.item as VampireBloodInjectorItem).tryUse(user))
                ItemStack(Registry.ITEM.get(Identifier("haema:empty_vampire_blood_injector")))
            else
                stack
        }
    })

    DispenserBlock.registerBehavior(Registry.ITEM.get(Identifier("haema:empty_vampire_blood_injector")), object : FallibleItemDispenserBehavior() {
        override fun dispenseSilently(pointer: BlockPointer, stack: ItemStack): ItemStack {
            val blockPos = pointer.blockPos.offset(pointer.blockState.get(DispenserBlock.FACING))
            val user = pointer.world.getEntitiesByClass(PlayerEntity::class.java, Box(blockPos), null)
                .firstOrNull() ?: return stack
            return if ((stack.item as EmptyVampireBloodInjectorItem).tryUse(user))
                ItemStack(Registry.ITEM.get(Identifier("haema:vampire_blood_injector")))
            else
                stack
        }
    })

    CommandRegistrationCallback.EVENT.register { dispatcher, isDedicated ->
        dispatcher.register(
            literal("haema")
                .then(literal("convert")
                    .then(argument("targets", EntityArgumentType.players()).executes { context ->
                        EntityArgumentType.getPlayers(context, "targets").forEach(Vampirable.Companion::convert)
                        return@executes 1
                    })
                )
                .then(literal("deconvert")
                    .then(argument("targets", EntityArgumentType.players())).executes { context ->
                        EntityArgumentType.getPlayers(context, "targets").forEach {
                            if (!(it as Vampirable).isPermanentVampire) {
                                it.isVampire = false
                                it.kill()
                            }
                        }
                        return@executes 1
                    })
                .then(literal("blood")
                    .then(argument("targets", EntityArgumentType.players()).then(argument("amount", DoubleArgumentType.doubleArg(0.0, 20.0)).executes { context ->
                        EntityArgumentType.getPlayers(context, "targets").forEach {
                            if ((it as Vampirable).isVampire && it.hungerManager is VampireBloodManager) {
                                (it.hungerManager as VampireBloodManager).absoluteBloodLevel = DoubleArgumentType.getDouble(context, "amount")
                            }
                        }
                        return@executes 1
                    })))
        )
    }

    vampiresBurn = GameRuleRegistry.register("vampiresBurn", GameRules.Category.PLAYER, GameRuleFactory.createBooleanRule(true))
    feedCooldown = GameRuleRegistry.register("feedCooldown", GameRules.Category.PLAYER, GameRuleFactory.createIntRule(10, 0, 24000))
    dashCooldown = GameRuleRegistry.register("dashCooldown", GameRules.Category.PLAYER, GameRuleFactory.createIntRule(10, 0, 24000) { server, rule ->
        val buf = PacketByteBuf(Unpooled.buffer())
        buf.writeInt(rule.get())
        server.playerManager.sendToAll(ServerSidePacketRegistry.INSTANCE.toPacket(Identifier("haema:updatedashcooldown"), buf))
    })
    vampireHunterNoticeChance = GameRuleRegistry.register("vampireHunterNoticeChance", GameRules.Category.MOBS, GameRuleFactory.createDoubleRule(0.1, 0.0, 1.0))
    playerVampireConversion = GameRuleRegistry.register("playerVampireConversion", GameRules.Category.PLAYER, GameRuleFactory.createBooleanRule(!FabricLoader.getInstance().isModLoaded("origins")))

    logger.info("Everything registered. It's vampire time!")
}

fun registerEntityComponentFactories(registry: EntityComponentFactoryRegistry) {
    registry.registerForPlayers(VampireComponent.entityKey, EntityComponentFactory { VampirePlayerComponent() }, RespawnCopyStrategy.ALWAYS_COPY)
}

