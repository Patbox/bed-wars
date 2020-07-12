package net.gegy1000.bedwars.map;

import net.gegy1000.bedwars.BedWarsMod;
import net.gegy1000.bedwars.util.BlockBounds;
import net.gegy1000.bedwars.game.GameManager;
import net.gegy1000.bedwars.game.GameRegion;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.chunk.IdListPalette;
import net.minecraft.world.chunk.Palette;
import net.minecraft.world.chunk.PalettedContainer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class GameMapData {
    private static final Path MAP_ROOT = Paths.get(BedWarsMod.ID, "map");

    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private final Identifier identifier;

    final Map<Vec3i, MapChunk> chunks = new HashMap<>();
    final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
    final List<GameRegion> regions = new ArrayList<>();

    BlockBounds bounds = new BlockBounds(BlockPos.ORIGIN, BlockPos.ORIGIN);

    GameMapData(Identifier identifier) {
        this.identifier = identifier;
    }

    public static CompletableFuture<GameMapData> load(Identifier identifier) {
        return CompletableFuture.supplyAsync(() -> {
            Path path = getPathFor(identifier).resolve("map.nbt");

            try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                GameMapData map = new GameMapData(identifier);
                map.load(NbtIo.read(input));
                return map;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, GameManager.EXECUTOR);
    }

    private void load(CompoundTag root) {
        ListTag chunkList = root.getList("chunks", 10);
        for (int i = 0; i < chunkList.size(); i++) {
            CompoundTag chunkRoot = chunkList.getCompound(i);

            int[] posArray = chunkRoot.getIntArray("pos");
            if (posArray.length != 3) {
                BedWarsMod.LOGGER.warn("Invalid chunk pos key: {}", posArray);
                continue;
            }

            Vec3i pos = new Vec3i(posArray[0], posArray[1], posArray[2]);
            MapChunk chunk = MapChunk.deserialize(chunkRoot);

            this.chunks.put(pos, chunk);
        }

        ListTag regionList = root.getList("regions", 10);
        for (int i = 0; i < regionList.size(); i++) {
            CompoundTag regionRoot = regionList.getCompound(i);
            this.regions.add(GameRegion.deserialize(regionRoot));
        }

        ListTag blockEntityList = root.getList("block_entities", 10);
        for (int i = 0; i < blockEntityList.size(); i++) {
            CompoundTag blockEntity = blockEntityList.getCompound(i);
            BlockPos pos = new BlockPos(
                    blockEntity.getInt("x"),
                    blockEntity.getInt("y"),
                    blockEntity.getInt("z")
            );
            this.blockEntities.put(pos, blockEntity);
        }

        this.bounds = BlockBounds.deserialize(root.getCompound("bounds"));
    }

    public void save() throws IOException {
        CompoundTag root = new CompoundTag();

        ListTag chunkList = new ListTag();
        for (Map.Entry<Vec3i, MapChunk> entry : this.chunks.entrySet()) {
            CompoundTag chunkRoot = new CompoundTag();
            Vec3i pos = entry.getKey();
            MapChunk chunk = entry.getValue();

            chunkRoot.putIntArray("pos", new int[] { pos.getX(), pos.getY(), pos.getZ() });
            chunk.serialize(chunkRoot);

            chunkList.add(chunkRoot);
        }

        root.put("chunks", chunkList);

        ListTag regionList = new ListTag();
        for (GameRegion region : this.regions) {
            regionList.add(region.serialize(new CompoundTag()));
        }
        root.put("regions", regionList);

        ListTag blockEntityList = new ListTag();
        blockEntityList.addAll(this.blockEntities.values());
        root.put("block_entities", blockEntityList);

        root.put("bounds", this.bounds.serialize(new CompoundTag()));

        Path parent = getPathFor(this.identifier);
        Files.createDirectories(parent);

        Path path = parent.resolve("map.nbt");

        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            NbtIo.write(root, output);
        }
    }

    void setBlockState(BlockPos pos, BlockState state) {
        Vec3i chunkPos = new Vec3i(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        MapChunk chunk = this.chunks.computeIfAbsent(chunkPos, p -> new MapChunk());
        chunk.set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
    }

    BlockState getBlockState(BlockPos pos) {
        Vec3i chunkPos = new Vec3i(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        MapChunk chunk = this.chunks.get(chunkPos);
        if (chunk != null) {
            return chunk.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        return AIR;
    }

    private static Path getPathFor(Identifier identifier) {
        return MAP_ROOT.resolve(identifier.getNamespace()).resolve(identifier.getPath());
    }

    public GameMap addToWorld(ServerWorld world, BlockPos origin) {
        GameMapBuilder builder = GameMapBuilder.open(world, origin, this.bounds);

        for (BlockPos pos : this.bounds.iterate()) {
            BlockState state = this.getBlockState(pos);
            builder.setBlockState(pos, state);

            CompoundTag beTag = this.blockEntities.get(pos);
            if (beTag != null) {
                BlockEntity blockEntity = BlockEntity.createFromTag(state, beTag);
                if (blockEntity != null) {
                    builder.setBlockEntity(pos, blockEntity);
                }
            }
        }

        for (GameRegion region : this.regions) {
            builder.addRegion(region);
        }

        return builder.build();
    }

    public BlockBounds getBounds() {
        return this.bounds;
    }

    private static class MapChunk {
        private static final Palette<BlockState> PALETTE = new IdListPalette<>(Block.STATE_IDS, Blocks.AIR.getDefaultState());

        private final PalettedContainer<BlockState> container = new PalettedContainer<>(
                PALETTE, Block.STATE_IDS,
                NbtHelper::toBlockState, NbtHelper::fromBlockState,
                Blocks.AIR.getDefaultState()
        );

        public void set(int x, int y, int z, BlockState state) {
            this.container.set(x, y, z, state);
        }

        public BlockState get(int x, int y, int z) {
            return this.container.get(x, y, z);
        }

        public void serialize(CompoundTag tag) {
            this.container.write(tag, "palette", "block_states");
        }

        public static MapChunk deserialize(CompoundTag tag) {
            MapChunk chunk = new MapChunk();
            chunk.container.read(tag.getList("palette", 10), tag.getLongArray("block_states"));
            return chunk;
        }
    }
}
