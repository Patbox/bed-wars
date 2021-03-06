package xyz.nucleoid.bedwars.game;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.bedwars.BedWars;
import xyz.nucleoid.bedwars.custom.ShopVillagerEntity;
import xyz.nucleoid.bedwars.game.active.BwActive;
import xyz.nucleoid.bedwars.game.active.BwItemGenerator;
import xyz.nucleoid.bedwars.game.active.ItemGeneratorPool;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.map.template.MapTemplateMetadata;
import xyz.nucleoid.plasmid.map.template.TemplateRegion;
import xyz.nucleoid.plasmid.util.BlockBounds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BwMap {
    private ChunkGenerator chunkGenerator;

    private final Map<GameTeam, TeamSpawn> teamSpawns = new HashMap<>();
    private final Map<GameTeam, TeamRegions> teamRegions = new HashMap<>();

    private final Collection<BwItemGenerator> itemGenerators = new ArrayList<>();

    private final List<BlockBounds> illegalBounds = new ArrayList<>();

    private BlockPos centerSpawn = BlockPos.ORIGIN;

    private final LongSet protectedBlocks = new LongOpenHashSet();

    public void setChunkGenerator(ChunkGenerator chunkGenerator) {
        this.chunkGenerator = chunkGenerator;
    }

    public void addDiamondGenerator(BlockBounds bounds) {
        this.itemGenerators.add(new BwItemGenerator(bounds)
                .setPool(ItemGeneratorPool.DIAMOND)
                .maxItems(6)
                .addTimerText()
        );

        this.addProtectedBlocks(bounds);
    }

    public void addEmeraldGenerator(BlockBounds bounds) {
        this.itemGenerators.add(new BwItemGenerator(bounds)
                .setPool(ItemGeneratorPool.EMERALD)
                .maxItems(3)
                .addTimerText()
        );

        this.addProtectedBlocks(bounds);
    }

    public void addTeamRegions(GameTeam team, TeamRegions regions) {
        this.teamRegions.put(team, regions);

        if (regions.spawn != null) {
            TeamSpawn teamSpawn = new TeamSpawn(regions.spawn);
            this.teamSpawns.put(team, teamSpawn);
            this.itemGenerators.add(teamSpawn.generator);
        } else {
            BedWars.LOGGER.warn("Missing spawn for {}", team.getKey());
        }
    }

    public void setCenterSpawn(BlockPos pos) {
        this.centerSpawn = pos;
    }

    public void addProtectedBlock(long pos) {
        this.protectedBlocks.add(pos);
    }

    public void addProtectedBlocks(BlockBounds bounds) {
        for (BlockPos pos : bounds) {
            this.protectedBlocks.add(pos.asLong());
        }
    }

    public void addIllegalRegion(BlockBounds bounds) {
        this.illegalBounds.add(bounds);
    }

    public void spawnShopkeepers(ServerWorld world, BwActive game, BwConfig config) {
        for (GameTeam team : config.teams) {
            TeamRegions regions = this.getTeamRegions(team);

            if (regions.teamShop != null) {
                this.trySpawnEntity(ShopVillagerEntity.team(world, game), regions.teamShop, regions.teamShopDirection);
            } else {
                BedWars.LOGGER.warn("Missing team shop for {}", team.getDisplay());
            }

            if (regions.itemShop != null) {
                this.trySpawnEntity(ShopVillagerEntity.item(world, game), regions.itemShop, regions.itemShopDirection);
            } else {
                BedWars.LOGGER.warn("Missing item shop for {}", team.getDisplay());
            }
        }
    }

    private void trySpawnEntity(Entity entity, BlockBounds bounds, Direction direction) {
        Vec3d center = bounds.getCenter();

        float yaw = direction.asRotation();
        entity.refreshPositionAndAngles(center.x, bounds.getMin().getY(), center.z, yaw, 0.0F);

        if (entity instanceof MobEntity) {
            MobEntity mob = (MobEntity) entity;

            LocalDifficulty difficulty = entity.world.getLocalDifficulty(mob.getBlockPos());
            mob.initialize((ServerWorld) entity.world, difficulty, SpawnReason.COMMAND, null, null);

            mob.headYaw = yaw;
            mob.bodyYaw = yaw;
        }

        // force-load the chunk before trying to spawn
        entity.world.getChunk(MathHelper.floor(center.x) >> 4, MathHelper.floor(center.z) >> 4);
        entity.world.spawnEntity(entity);
    }

    @Nullable
    public TeamSpawn getTeamSpawn(GameTeam team) {
        return this.teamSpawns.get(team);
    }

    @NotNull
    public TeamRegions getTeamRegions(GameTeam team) {
        return this.teamRegions.getOrDefault(team, TeamRegions.EMPTY);
    }

    public Map<GameTeam, TeamRegions> getAllTeamRegions() {
        return this.teamRegions;
    }

    public Collection<BwItemGenerator> getItemGenerators() {
        return this.itemGenerators;
    }

    public boolean isProtectedBlock(BlockPos pos) {
        return this.protectedBlocks.contains(pos.asLong());
    }

    public boolean isLegalAt(BlockPos pos) {
        for (BlockBounds bounds : this.illegalBounds) {
            if (bounds.contains(pos)) {
                return false;
            }
        }
        return true;
    }

    public BlockPos getCenterSpawn() {
        return this.centerSpawn;
    }

    public ChunkGenerator getChunkGenerator() {
        return this.chunkGenerator;
    }

    public static class TeamSpawn {
        public static final int MAX_LEVEL = 3;

        private final BlockBounds region;
        private final BwItemGenerator generator;

        private int level = 1;

        TeamSpawn(BlockBounds region) {
            this.region = region;
            this.generator = new BwItemGenerator(region)
                    .setPool(poolForLevel(this.level))
                    .maxItems(64)
                    .allowDuplication();
        }

        public void placePlayer(ServerPlayerEntity player, ServerWorld world) {
            player.fallDistance = 0.0F;

            Vec3d center = this.region.getCenter();
            player.teleport(world, center.x, center.y + 0.5, center.z, 0.0F, 0.0F);
        }

        public void setLevel(int level) {
            this.level = Math.max(level, this.level);
            this.generator.setPool(poolForLevel(this.level));
        }

        public int getLevel() {
            return this.level;
        }

        private static ItemGeneratorPool poolForLevel(int level) {
            if (level == 1) {
                return ItemGeneratorPool.TEAM_LVL_1;
            } else if (level == 2) {
                return ItemGeneratorPool.TEAM_LVL_2;
            } else if (level == 3) {
                return ItemGeneratorPool.TEAM_LVL_3;
            }
            return ItemGeneratorPool.TEAM_LVL_1;
        }
    }

    public static class TeamRegions {
        public static final TeamRegions EMPTY = new TeamRegions(null, null, null, null, null, null, Direction.NORTH, Direction.NORTH);

        public final BlockBounds base;
        public final BlockBounds spawn;
        public final BlockBounds bed;
        public final BlockBounds itemShop;
        public final BlockBounds teamShop;
        public final BlockBounds teamChest;
        public final Direction itemShopDirection;
        public final Direction teamShopDirection;

        public TeamRegions(BlockBounds spawn, BlockBounds bed, BlockBounds base, BlockBounds teamChest, BlockBounds itemShop, BlockBounds teamShop, Direction itemShopDirection, Direction teamShopDirection) {
            this.spawn = spawn;
            this.bed = bed;
            this.base = base;
            this.teamChest = teamChest;
            this.itemShop = itemShop;
            this.teamShop = teamShop;
            this.itemShopDirection = itemShopDirection;
            this.teamShopDirection = teamShopDirection;
        }

        public static TeamRegions fromTemplate(GameTeam team, MapTemplateMetadata metadata) {
            String teamKey = team.getKey();

            BlockBounds base = metadata.getFirstRegionBounds(teamKey + "_base");
            BlockBounds spawn = metadata.getFirstRegionBounds(teamKey + "_spawn");
            BlockBounds bed = metadata.getFirstRegionBounds(teamKey + "_bed");
            BlockBounds teamChest = metadata.getFirstRegionBounds(teamKey + "_chest");

            BlockBounds itemShop = null;
            Direction itemShopDirection = Direction.NORTH;
            TemplateRegion itemShopRegion = metadata.getFirstRegion(teamKey + "_item_shop");
            if (itemShopRegion != null) {
                itemShop = itemShopRegion.getBounds();
                itemShopDirection = getDirectionForRegion(itemShopRegion);
            }

            BlockBounds teamShop = null;
            Direction teamShopDirection = Direction.NORTH;
            TemplateRegion teamShopRegion = metadata.getFirstRegion(teamKey + "_team_shop");
            if (teamShopRegion != null) {
                teamShop = teamShopRegion.getBounds();
                teamShopDirection = getDirectionForRegion(teamShopRegion);
            }

            return new TeamRegions(spawn, bed, base,  teamChest, itemShop, teamShop,itemShopDirection, teamShopDirection);
        }

        private static Direction getDirectionForRegion(TemplateRegion region) {
            String key = region.getData().getString("direction");
            for (Direction direction : Direction.values()) {
                if (direction.getName().equalsIgnoreCase(key)) {
                    return direction;
                }
            }
            return Direction.NORTH;
        }
    }
}
