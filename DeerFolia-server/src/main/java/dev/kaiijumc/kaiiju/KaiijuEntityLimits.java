package dev.kaiijumc.kaiiju;

import com.google.common.base.Throwables;
import com.mojang.logging.LogUtils;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

@SuppressWarnings("unused")
public class KaiijuEntityLimits {
    private static final Logger LOGGER = LogUtils.getLogger();

    protected static final String HEADER =
            "Per region entity limits for Kaiiju.\n"
                    + "If there are more of particular entity type in a region than limit, entity ticking will be throttled.\n"
                    + "Example: for WitherBoss limit 100 & 300 WitherBosss in a region -> 100 WitherBosss tick every tick & every WitherBoss ticks every 3 ticks.\n"
                    + "\n"
                    + "Available entities: AbstractArrow, AbstractBoat, AbstractChestBoat, AbstractChestedHorse, AbstractFish, \n"
                    + "AbstractGolem, AbstractHorse, AbstractHurtingProjectile, AbstractIllager, AbstractMinecart, AbstractMinecartContainer\n"
                    + "AbstractPiglin, AbstractSchoolingFish, AbstractSkeleton, AbstractVillager, AbstractWindCharge, AgeableMob\n"
                    + "AgeableWaterCreature, Allay, AmbientCreature, Animal, AreaEffectCloud, Armadillo, ArmorStand, Arrow, Axolotl\n"
                    + "Bat, Bee, Blaze, BlockAttachedEntity, BlockDisplay, Boat, Bogged, Breeze, BreezeWindCharge, Camel, Cat\n"
                    + "CaveSpider, ChestBoat, ChestRaft, Chicken, Cod, Cow, Creaking, Creeper, Display, Dolphin, Donkey, DragonFireball\n"
                    + "Drowned, ElderGuardian, EndCrystal, EnderDragon, EnderDragonPart, EnderMan, Endermite, Entity, Evoker\n"
                    + "EvokerFangs, ExperienceOrb, EyeOfEnder, FallingBlockEntity, Fireball, FireworkRocketEntity, FishingHook\n"
                    + "FlyingMob, Fox, Frog, Ghast, Giant, GlowItemFrame, GlowSquid, Goat, Guardian, HangingEntity, Hoglin, Horse\n"
                    + "Husk, Illusioner, Interaction, IronGolem, ItemDisplay, ItemEntity, ItemFrame, LargeFireball, LeashFenceKnotEntity\n"
                    + "LightningBolt, LivingEntity, Llama, LlamaSpit, MagmaCube, Marker, Minecart, MinecartChest, MinecartCommandBlock\n"
                    + "MinecartFurnace, MinecartHopper, MinecartSpawner, MinecartTNT, Mob, Monster, Mule, MushroomCow, Ocelot\n"
                    + "OminousItemSpawner, Painting, Panda, Parrot, PathfinderMob, PatrollingMonster, Phantom, Pig, Piglin, PiglinBrute\n"
                    + "Pillager, Player, PolarBear, PrimedTnt, Projectile, Pufferfish, Rabbit, Raft, Raider, Ravager, Salmon, Sheep\n"
                    + "ShoulderRidingEntity, Shulker, ShulkerBullet, Silverfish, Skeleton, SkeletonHorse, Slime, SmallFireball, Sniffer\n"
                    + "SnowGolem, Snowball, SpectralArrow, SpellcasterIllager, Spider, Squid, Stray, Strider, Tadpole, TamableAnimal\n"
                    + "TextDisplay, ThrowableItemProjectile, ThrowableProjectile, ThrownEgg, ThrownEnderpearl, ThrownExperienceBottle\n"
                    + "ThrownPotion, ThrownTrident, TraderLlama, TropicalFish, Turtle, VehicleEntity, Vex, Villager, Vindicator\n"
                    + "WanderingTrader, Warden, WaterAnimal, WindCharge, Witch, WitherBoss, WitherSkeleton, WitherSkull, Wolf, Zoglin\n"
                    + "Zombie, ZombieHorse, ZombieVillager, ZombifiedPiglin";
    protected static File ENTITY_LIMITS_FILE;
    public static YamlConfiguration entityLimitsConfig;

    protected static Map<Class<? extends Entity>, EntityLimit> entityLimits;

    static final String ENTITY_PREFIX = "Entity";

    public static void init(File entityLimitsFile) {
        init(entityLimitsFile, true);
    }

    public static void reload(File entityLimitsFile) {
        init(entityLimitsFile, false);
    }

    private static void init(File entityLimitsFile, boolean setup) {
        ENTITY_LIMITS_FILE = entityLimitsFile;
        entityLimitsConfig = new YamlConfiguration();

        if (entityLimitsFile.exists()) {
            try {
                entityLimitsConfig.load(ENTITY_LIMITS_FILE);
            } catch (InvalidConfigurationException ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Could not load kaiiju_entity_limits.yml, please correct your syntax errors", ex);
                throw Throwables.propagate(ex);
            } catch (IOException ignore) {
            }
        } else {
            if (setup) {
                entityLimitsConfig.options().header(HEADER);
                entityLimitsConfig.options().copyDefaults(true);
                entityLimitsConfig.set("ZombifiedPiglin.limit", 1000);
                entityLimitsConfig.set("ZombifiedPiglin.removal", 3000);
                entityLimitsConfig.set("Axolotl.limit", 1000);
                entityLimitsConfig.set("Axolotl.removal", 3000);

                try {
                    entityLimitsConfig.save(ENTITY_LIMITS_FILE);
                } catch (IOException ex) {
                    Bukkit.getLogger().log(Level.SEVERE, "Could not save " + ENTITY_LIMITS_FILE, ex);
                }
            }
        }

        entityLimits = new Object2ObjectOpenHashMap<>();
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages("net.minecraft.world.entity").scan()) {
            Map<String, ClassInfo> entityClasses = new HashMap<>();
            for (ClassInfo classInfo : scanResult.getAllClasses()) {
                Class<?> entityClass = Class.forName(classInfo.getName());
                if (Entity.class.isAssignableFrom(entityClass)) {
                    String entityName = extractEntityName(entityClass.getSimpleName());
                    entityClasses.put(entityName, classInfo);
                }
            }
            LOGGER.info("kaiiju-entity-limits.yml available entities: " + entityClasses.keySet().stream().sorted().toList());

            for (String key : entityLimitsConfig.getKeys(false)) {
                if (!entityClasses.containsKey(key)) {
                    LOGGER.error("Unknown entity '" + key + "' in kaiiju-entity-limits.yml, skipping");
                    continue;
                }
                int limit = entityLimitsConfig.getInt(key + ".limit");
                int removal = entityLimitsConfig.getInt(key + ".removal");

                if (limit < 1) {
                    LOGGER.error(key + " has a limit less than the minimum of 1, ignoring");
                    continue;
                }
                if (removal <= limit && removal != -1) {
                    LOGGER.error(key + " has a removal limit that is less than or equal to its limit, setting removal to limit * 10");
                    removal = limit * 10;
                }

                entityLimits.put((Class<? extends Entity>) Class.forName(entityClasses.get(key).getName()), new EntityLimit(limit, removal));
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static EntityLimit getEntityLimit(Entity entity) {
        return entityLimits.get(entity.getClass());
    }

    private static String extractEntityName(String input) {
        int prefixLength = ENTITY_PREFIX.length();

        if (input.length() <= prefixLength || !input.startsWith(ENTITY_PREFIX)) {
            return input;
        } else {
            return input.substring(prefixLength);
        }
    }

    public record EntityLimit(int limit, int removal) {
        @Override
        public String toString() {
            return "EntityLimit{limit=" + limit + ", removal=" + removal + "}";
        }
    }
}