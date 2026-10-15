package cn.lunadeer.mc.deerfolia.lithium;

import cn.lunadeer.mc.deerfolia.DeerFoliaConfiguration;
import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.Material;
import org.bukkit.craftbukkit.block.CraftCampfire;
import org.bukkit.inventory.ItemStack;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.spigotmc.SpigotWorldConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@VanillaFeature
class BlockEntitySleepingTest {
    private DeerFoliaConfiguration.LithiumOptimize previous;
    private ServerLevel level;

    @BeforeEach
    void setup() {
        this.previous = DeerFoliaConfiguration.lithiumOptimize;
        DeerFoliaConfiguration.lithiumOptimize = new DeerFoliaConfiguration.LithiumOptimize();
        DeerFoliaConfiguration.lithiumOptimize.blockEntitySleeping = true;
        this.level = mock(ServerLevel.class);
    }

    @AfterEach
    void restoreConfiguration() {
        DeerFoliaConfiguration.lithiumOptimize = this.previous;
    }

    @Test
    void crafterCountdownResumesAfterSetterAndNbtLoad() {
        CrafterBlockEntity crafter = new CrafterBlockEntity(BlockPos.ZERO, Blocks.CRAFTER.defaultBlockState());
        crafter.setLevel(this.level);
        CrafterBlockEntity.serverTick(this.level, BlockPos.ZERO, crafter.getBlockState(), crafter);
        assertTrue(crafter.deerfoliaSkipSleepingTick());
        crafter.setCraftingTicksRemaining(3);
        assertFalse(crafter.deerfoliaIsSleeping());
        CrafterBlockEntity.serverTick(this.level, BlockPos.ZERO, crafter.getBlockState(), crafter);
        assertEquals(2, crafter.craftingTicksRemaining);
        CrafterBlockEntity.serverTick(this.level, BlockPos.ZERO, crafter.getBlockState(), crafter);
        CrafterBlockEntity.serverTick(this.level, BlockPos.ZERO, crafter.getBlockState(), crafter);
        assertTrue(crafter.deerfoliaIsSleeping());

        CompoundTag tag = new CompoundTag();
        tag.putInt("crafting_ticks_remaining", 7);
        crafter.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, RegistryHelper.registryAccess(), tag));
        assertFalse(crafter.deerfoliaIsSleeping());
        CrafterBlockEntity.serverTick(this.level, BlockPos.ZERO, crafter.getBlockState(), crafter);
        assertEquals(6, crafter.craftingTicksRemaining);
    }

    @Test
    void campfireLiveApiSettersWakeWithoutSnapshotUpdate() {
        CampfireBlockEntity campfire = new CampfireBlockEntity(BlockPos.ZERO, Blocks.CAMPFIRE.defaultBlockState());
        campfire.setLevel(this.level);
        CraftCampfire live = new CraftCampfire(null, campfire, false);
        CampfireBlockEntity.cookTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire, null);
        assertTrue(campfire.deerfoliaIsSleeping());
        live.setItem(0, new ItemStack(Material.POTATO));
        assertFalse(campfire.deerfoliaIsSleeping());

        campfire.setBlockState(campfire.getBlockState().setValue(CampfireBlock.LIT, false));
        CampfireBlockEntity.cooldownTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire);
        assertTrue(campfire.deerfoliaIsSleeping());
        live.setCookTimeTotal(0, 10);
        assertFalse(campfire.deerfoliaIsSleeping());
        CampfireBlockEntity.cooldownTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire);
        live.setCookTime(0, 6);
        assertFalse(campfire.deerfoliaIsSleeping());
        CampfireBlockEntity.cooldownTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire);
        assertEquals(4, live.getCookTime(0));
        CampfireBlockEntity.cooldownTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire);
        CampfireBlockEntity.cooldownTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire);
        CampfireBlockEntity.cooldownTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire);
        assertTrue(campfire.deerfoliaIsSleeping());
        live.stopCooking(0);
        assertFalse(campfire.deerfoliaIsSleeping());
        CampfireBlockEntity.cooldownTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire);
        live.startCooking(0);
        assertFalse(campfire.deerfoliaIsSleeping());
    }

    @Test
    void emptyCampfireStackThatCanBeRevivedThroughAMirrorDoesNotSleep() {
        CampfireBlockEntity campfire = new CampfireBlockEntity(BlockPos.ZERO, Blocks.CAMPFIRE.defaultBlockState());
        campfire.setLevel(this.level);
        CraftCampfire live = new CraftCampfire(null, campfire, false);
        live.setItem(0, new ItemStack(Material.POTATO));
        live.setCookTimeTotal(0, 100);
        ItemStack mirror = live.getItem(0);
        mirror.setAmount(0);
        CampfireBlockEntity.cookTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire, null);
        assertFalse(campfire.deerfoliaIsSleeping());
        mirror.setAmount(1);
        CampfireBlockEntity.cookTick(this.level, BlockPos.ZERO, campfire.getBlockState(), campfire, null);
        assertEquals(1, live.getCookTime(0));
    }

    @Test
    void closedShulkerWakesForOpenEventAndLifecycleChanges() {
        ShulkerBoxBlockEntity shulker = new ShulkerBoxBlockEntity(BlockPos.ZERO, Blocks.SHULKER_BOX.defaultBlockState());
        shulker.setLevel(this.level);
        ShulkerBoxBlockEntity.tick(this.level, BlockPos.ZERO, shulker.getBlockState(), shulker);
        assertTrue(shulker.deerfoliaIsSleeping());
        shulker.triggerEvent(ShulkerBoxBlockEntity.EVENT_SET_OPEN_COUNT, 1);
        assertFalse(shulker.deerfoliaIsSleeping());
        assertEquals(ShulkerBoxBlockEntity.AnimationStatus.OPENING, shulker.getAnimationStatus());

        CrafterBlockEntity crafter = new CrafterBlockEntity(BlockPos.ZERO, Blocks.CRAFTER.defaultBlockState());
        crafter.setLevel(this.level);
        CrafterBlockEntity.serverTick(this.level, BlockPos.ZERO, crafter.getBlockState(), crafter);
        crafter.setRemoved();
        assertFalse(crafter.deerfoliaIsSleeping());
        assertTrue(crafter.isRemoved());
        crafter.clearRemoved();
        assertFalse(crafter.isRemoved());
    }

    @Test
    void disablingAnyConfigurationGateWakesExistingSleepers() {
        for (int gate = 0; gate < 3; ++gate) {
            DeerFoliaConfiguration.lithiumOptimize = new DeerFoliaConfiguration.LithiumOptimize();
            DeerFoliaConfiguration.lithiumOptimize.blockEntitySleeping = true;
            CrafterBlockEntity crafter = new CrafterBlockEntity(BlockPos.ZERO, Blocks.CRAFTER.defaultBlockState());
            crafter.setLevel(this.level);
            CrafterBlockEntity.serverTick(this.level, BlockPos.ZERO, crafter.getBlockState(), crafter);
            assertTrue(crafter.deerfoliaIsSleeping());
            if (gate == 0) DeerFoliaConfiguration.lithiumOptimize.enabled = false;
            if (gate == 1) DeerFoliaConfiguration.lithiumOptimize.world = false;
            if (gate == 2) DeerFoliaConfiguration.lithiumOptimize.blockEntitySleeping = false;
            assertFalse(crafter.deerfoliaSkipSleepingTick());
            assertFalse(crafter.deerfoliaIsSleeping());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 8, 17})
    void lockedHopperSleepMatchesOriginalTickForCustomCooldowns(int checkInterval) throws Exception {
        SpigotWorldConfig spigot = mock(SpigotWorldConfig.class);
        spigot.hopperCheck = checkInterval;
        spigot.hopperTransfer = 13;
        Field configField = Level.class.getField("spigotConfig");
        configField.setAccessible(true);
        configField.set(this.level, spigot);
        Field tickedTime = HopperBlockEntity.class.getDeclaredField("tickedGameTime");
        tickedTime.setAccessible(true);
        var state = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.ENABLED, false);
        for (int initialCooldown : new int[]{-1, 0, 1, 8, 23}) {
            HopperBlockEntity reference = new HopperBlockEntity(BlockPos.ZERO, state);
            HopperBlockEntity optimized = new HopperBlockEntity(BlockPos.ZERO, state);
            reference.setLevel(this.level);
            optimized.setLevel(this.level);
            reference.setCooldown(initialCooldown);
            optimized.setCooldown(initialCooldown);
            boolean slept = false;
            for (int tick = 0; tick < 80; ++tick) {
                when(this.level.getRedstoneGameTime()).thenReturn((long) tick);
                if (tick == 30) {
                    reference.setCooldown(13);
                    optimized.setCooldown(13);
                    assertFalse(optimized.deerfoliaIsSleeping());
                }
                DeerFoliaConfiguration.lithiumOptimize.blockEntitySleeping = false;
                HopperBlockEntity.pushItemsTick(this.level, BlockPos.ZERO, state, reference);
                DeerFoliaConfiguration.lithiumOptimize.blockEntitySleeping = true;
                boolean skipped = optimized.deerfoliaSkipSleepingTick();
                slept |= skipped;
                if (!skipped) HopperBlockEntity.pushItemsTick(this.level, BlockPos.ZERO, state, optimized);
                assertEquals(reference.cooldownTime, optimized.cooldownTime, "cooldown at tick " + tick);
                assertEquals(tickedTime.getLong(reference), tickedTime.getLong(optimized), "insertion phase at tick " + tick);
                if (tick == 50) {
                    reference.updateTicks(0, 211);
                    optimized.updateTicks(0, 211);
                    assertEquals(tickedTime.getLong(reference), tickedTime.getLong(optimized), "region clock shift");
                }
            }
            assertTrue(slept);
            optimized.setBlockState(state.setValue(HopperBlock.ENABLED, true));
            assertFalse(optimized.deerfoliaIsSleeping());
        }
    }
}
