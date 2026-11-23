package com.bazarmod;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.mob.ZombieVillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructurePieceType;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BazarStructures {
    public static final String SPOT_PADIK = "padik";
    public static final String SPOT_KIOSK = "beer_kiosk";
    public static final String SPOT_GARAGE = "garage";

    public static StructureType<BazarSpotStructure> PADIK_TYPE;
    public static StructureType<BazarSpotStructure> KIOSK_TYPE;
    public static StructureType<BazarSpotStructure> GARAGE_TYPE;
    public static StructurePieceType SPOT_PIECE_TYPE;

    private record DeferredSpotSetup(
            RegistryKey<World> dimension,
            BlockPos origin,
            String spotKind,
            int retriesLeft
    ) {}

    private static final Queue<DeferredSpotSetup> DEFERRED_SETUPS = new ConcurrentLinkedQueue<>();

    public static void register() {
        SPOT_PIECE_TYPE = Registry.register(
                Registries.STRUCTURE_PIECE,
                Identifier.of(BazarMod.MOD_ID, "spot_piece"),
                SpotPiece::new
        );

        PADIK_TYPE = Registry.register(
                Registries.STRUCTURE_TYPE,
                Identifier.of(BazarMod.MOD_ID, SPOT_PADIK),
                () -> BazarSpotStructure.PADIK_CODEC
        );

        KIOSK_TYPE = Registry.register(
                Registries.STRUCTURE_TYPE,
                Identifier.of(BazarMod.MOD_ID, SPOT_KIOSK),
                () -> BazarSpotStructure.KIOSK_CODEC
        );

        GARAGE_TYPE = Registry.register(
                Registries.STRUCTURE_TYPE,
                Identifier.of(BazarMod.MOD_ID, SPOT_GARAGE),
                () -> BazarSpotStructure.GARAGE_CODEC
        );
    }

    /**
     * Executed safely on the main server thread every tick to finalize entities, signs, and chest loot
     * for newly generated structures without blocking or crashing worldgen worker threads.
     */
    public static void tickMainThread(MinecraftServer server) {
        if (DEFERRED_SETUPS.isEmpty()) return;

        int processed = 0;
        Iterator<DeferredSpotSetup> it = DEFERRED_SETUPS.iterator();
        while (it.hasNext() && processed < 4) {
            DeferredSpotSetup task = it.next();
            it.remove();
            processed++;

            ServerWorld world = server.getWorld(task.dimension());
            if (world == null) continue;

            if (!world.isChunkLoaded(task.origin())) {
                if (task.retriesLeft() > 0) {
                    DEFERRED_SETUPS.add(new DeferredSpotSetup(task.dimension(), task.origin(), task.spotKind(), task.retriesLeft() - 1));
                }
                continue;
            }

            finalizeSpotOnMainThread(world, task.origin(), task.spotKind());
        }
    }

    public static class BazarSpotStructure extends Structure {
        public static final MapCodec<BazarSpotStructure> PADIK_CODEC = createCodec(cfg -> new BazarSpotStructure(cfg, SPOT_PADIK));
        public static final MapCodec<BazarSpotStructure> KIOSK_CODEC = createCodec(cfg -> new BazarSpotStructure(cfg, SPOT_KIOSK));
        public static final MapCodec<BazarSpotStructure> GARAGE_CODEC = createCodec(cfg -> new BazarSpotStructure(cfg, SPOT_GARAGE));

        private final String spotKind;

        public BazarSpotStructure(Structure.Config config, String spotKind) {
            super(config);
            this.spotKind = spotKind;
        }

        @Override
        public Optional<StructurePosition> getStructurePosition(Context context) {
            int x = context.chunkPos().getCenterX();
            int z = context.chunkPos().getCenterZ();
            int y = context.chunkGenerator().getHeightOnGround(x, z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig());
            if (y < context.chunkGenerator().getSeaLevel()) {
                return Optional.empty();
            }
            BlockPos origin = new BlockPos(x, y, z);
            return Optional.of(new StructurePosition(origin, collector -> collector.addPiece(new SpotPiece(origin, spotKind))));
        }

        @Override
        public StructureType<?> getType() {
            return switch (spotKind) {
                case SPOT_PADIK -> PADIK_TYPE;
                case SPOT_KIOSK -> KIOSK_TYPE;
                default -> GARAGE_TYPE;
            };
        }
    }

    public static class SpotPiece extends StructurePiece {
        private final BlockPos origin;
        private final String spotKind;

        public SpotPiece(BlockPos origin, String spotKind) {
            super(SPOT_PIECE_TYPE, 0, new BlockBox(
                    origin.getX() - 8, origin.getY() - 6, origin.getZ() - 8,
                    origin.getX() + 8, origin.getY() + 10, origin.getZ() + 8
            ));
            this.origin = origin;
            this.spotKind = spotKind;
        }

        public SpotPiece(StructureContext context, NbtCompound nbt) {
            super(SPOT_PIECE_TYPE, nbt);
            this.origin = new BlockPos(nbt.getInt("OX"), nbt.getInt("OY"), nbt.getInt("OZ"));
            this.spotKind = nbt.getString("Kind");
        }

        @Override
        protected void writeNbt(StructureContext context, NbtCompound nbt) {
            nbt.putInt("OX", origin.getX());
            nbt.putInt("OY", origin.getY());
            nbt.putInt("OZ", origin.getZ());
            nbt.putString("Kind", spotKind);
        }

        @Override
        public void generate(StructureWorldAccess world, StructureAccessor structureAccessor, ChunkGenerator chunkGenerator,
                             Random random, BlockBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
            // 1. Place pure blocks safely in ChunkRegion
            placeSpotBlocks(world, chunkBox, origin, spotKind);

            // 2. When the center block of the spot is inside this chunkBox, defer entity/sign/chest setup to main thread!
            if (chunkBox.contains(origin)) {
                RegistryKey<World> dim = world.toServerWorld().getRegistryKey();
                DEFERRED_SETUPS.add(new DeferredSpotSetup(dim, origin, spotKind, 200));
            }
        }
    }

    /**
     * Immediate spawn helper for `/bazar spot <type>` (already runs on the main server thread).
     */
    public static void buildSpotImmediate(ServerWorld world, BlockPos origin, String spotKind) {
        BlockBox bounds = new BlockBox(
                origin.getX() - 8, origin.getY() - 8, origin.getZ() - 8,
                origin.getX() + 8, origin.getY() + 10, origin.getZ() + 8
        );
        placeSpotBlocks(world, bounds, origin, spotKind);
        finalizeSpotOnMainThread(world, origin, spotKind);
    }

    private static void placeSpotBlocks(ServerWorldAccess world, BlockBox bounds, BlockPos origin, String spotKind) {
        switch (spotKind) {
            case SPOT_PADIK -> placePadikBlocks(world, bounds, origin);
            case SPOT_KIOSK -> placeBeerKioskBlocks(world, bounds, origin);
            case SPOT_GARAGE -> placeGarageBlocks(world, bounds, origin);
        }
    }

    // =========================================================================
    // BLOCK PLACEMENT ONLY (100% Thread-Safe for ChunkRegion & ServerWorld)
    // =========================================================================

    private static void placePadikBlocks(ServerWorldAccess world, BlockBox bounds, BlockPos origin) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 3; dz++) {
                fillColumnDown(world, bounds, origin.add(dx, -1, dz), Blocks.SMOOTH_STONE.getDefaultState());
                clearAirAbove(world, bounds, origin.add(dx, 0, dz), 6);
                BlockState floor = (dz >= 0) ? Blocks.ANDESITE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
                setBlock(world, bounds, origin.add(dx, -1, dz), floor);
            }
        }

        for (int y = 0; y <= 4; y++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -4; dz <= -1; dz++) {
                    boolean isWall = (dx == -3 || dx == 3 || dz == -4 || dz == -1);
                    if (isWall) {
                        BlockState wallBlock = (y == 0) ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.BRICKS.getDefaultState();
                        setBlock(world, bounds, origin.add(dx, y, dz), wallBlock);
                    }
                }
            }
        }

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 0; dz++) {
                setBlock(world, bounds, origin.add(dx, 5, dz), Blocks.GRAY_CONCRETE.getDefaultState());
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            setBlock(world, bounds, origin.add(dx, 3, 0), Blocks.SMOOTH_STONE_SLAB.getDefaultState());
        }

        setBlock(world, bounds, origin.add(0, 0, -1), Blocks.AIR.getDefaultState());
        setBlock(world, bounds, origin.add(0, 1, -1), Blocks.AIR.getDefaultState());
        setBlock(world, bounds, origin.add(0, 2, 0), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));

        setBlock(world, bounds, origin.add(-2, 0, -3), Blocks.STONE_BRICK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
        setBlock(world, bounds, origin.add(-2, 1, -4), Blocks.STONE_BRICKS.getDefaultState());
        setBlock(world, bounds, origin.add(2, 0, -3), Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));

        setBlock(world, bounds, origin.add(-2, 0, 1), Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
        setBlock(world, bounds, origin.add(-1, 0, 1), Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
        setBlock(world, bounds, origin.add(2, 0, 0), Blocks.CAULDRON.getDefaultState());

        setBlock(world, bounds, origin.add(-1, 1, 0), Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.SOUTH));
    }

    private static void placeBeerKioskBlocks(ServerWorldAccess world, BlockBox bounds, BlockPos origin) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                fillColumnDown(world, bounds, origin.add(dx, -1, dz), Blocks.COBBLESTONE.getDefaultState());
                clearAirAbove(world, bounds, origin.add(dx, 0, dz), 6);
                setBlock(world, bounds, origin.add(dx, -1, dz), Blocks.STONE_BRICKS.getDefaultState());
            }
        }

        for (int y = 0; y <= 2; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -3; dz <= -1; dz++) {
                    boolean edge = (dx == -2 || dx == 2 || dz == -3 || dz == -1);
                    if (edge) {
                        BlockState mat = (y == 0) ? Blocks.GREEN_TERRACOTTA.getDefaultState() : Blocks.YELLOW_CONCRETE.getDefaultState();
                        setBlock(world, bounds, origin.add(dx, y, dz), mat);
                    }
                }
            }
        }

        for (int dx = -1; dx <= 1; dx++) {
            setBlock(world, bounds, origin.add(dx, 1, -1), Blocks.GLASS_PANE.getDefaultState());
            setBlock(world, bounds, origin.add(dx, 1, 0), Blocks.SPRUCE_TRAPDOOR.getDefaultState().with(TrapdoorBlock.HALF, BlockHalf.TOP).with(TrapdoorBlock.OPEN, false));
        }

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 0; dz++) {
                BlockState roof = (Math.abs(dx) % 2 == 0) ? Blocks.LIME_CONCRETE.getDefaultState() : Blocks.YELLOW_CONCRETE.getDefaultState();
                setBlock(world, bounds, origin.add(dx, 3, dz), roof);
            }
        }
        setBlock(world, bounds, origin.add(0, 2, 0), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));

        setBlock(world, bounds, origin.add(-1, 0, -2), Blocks.BARREL.getDefaultState());
        setBlock(world, bounds, origin.add(1, 0, -2), Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));

        setBlock(world, bounds, origin.add(-2, 0, 2), Blocks.OAK_FENCE.getDefaultState());
        setBlock(world, bounds, origin.add(-2, 1, 2), Blocks.OAK_PRESSURE_PLATE.getDefaultState());
        setBlock(world, bounds, origin.add(-1, 0, 2), Blocks.OAK_SLAB.getDefaultState());
        setBlock(world, bounds, origin.add(2, 0, 2), Blocks.OAK_FENCE.getDefaultState());
        setBlock(world, bounds, origin.add(2, 1, 2), Blocks.OAK_PRESSURE_PLATE.getDefaultState());

        setBlock(world, bounds, origin.add(0, 2, 0), Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.SOUTH));
    }

    private static void placeGarageBlocks(ServerWorldAccess world, BlockBox bounds, BlockPos origin) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 3; dz++) {
                fillColumnDown(world, bounds, origin.add(dx, -1, dz), Blocks.COBBLESTONE.getDefaultState());
                clearAirAbove(world, bounds, origin.add(dx, 0, dz), 6);
                setBlock(world, bounds, origin.add(dx, -1, dz), Blocks.SMOOTH_STONE.getDefaultState());
            }
        }

        for (int y = 0; y <= 3; y++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -4; dz <= 0; dz++) {
                    boolean isWall = (dx == -3 || dx == 3 || dz == -4 || dz == 0);
                    if (isWall) {
                        if (dz == 0 && Math.abs(dx) <= 1 && y <= 2) {
                            continue;
                        }
                        BlockState mat = ((dx + y) % 2 == 0) ? Blocks.IRON_BLOCK.getDefaultState() : Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
                        setBlock(world, bounds, origin.add(dx, y, dz), mat);
                    }
                }
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 0; dz++) {
                setBlock(world, bounds, origin.add(dx, 4, dz), Blocks.STONE_BRICK_SLAB.getDefaultState());
            }
        }

        setBlock(world, bounds, origin.add(-2, 0, -3), Blocks.CRAFTING_TABLE.getDefaultState());
        setBlock(world, bounds, origin.add(-2, 0, -2), Blocks.ANVIL.getDefaultState());
        setBlock(world, bounds, origin.add(2, 0, -3), Blocks.BLAST_FURNACE.getDefaultState().with(BlastFurnaceBlock.FACING, Direction.WEST));
        setBlock(world, bounds, origin.add(0, 0, -3), Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));

        setBlock(world, bounds, origin.add(2, 0, 2), Blocks.CAMPFIRE.getDefaultState());
        setBlock(world, bounds, origin.add(-2, 1, 1), Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, Direction.SOUTH));
    }

    // =========================================================================
    // MAIN-THREAD FINALIZATION (Spawns Boss + Sets Sign Text + Fills Chest)
    // =========================================================================

    private static void finalizeSpotOnMainThread(ServerWorld world, BlockPos origin, String spotKind) {
        try {
            switch (spotKind) {
                case SPOT_PADIK -> {
                    writeSignText(world, origin.add(-1, 1, 0), "ПАДИК №1", "СЕМКИ ЕСТЬ?", "НЕ КУРИТЬ", "ШТРАФ 5000");
                    fillChestLoot(world, origin.add(2, 0, -3), "bazar_boss_padik",
                            List.of(new ItemStack(Items.EMERALD, 4), new ItemStack(Items.GOLDEN_APPLE, 1), new ItemStack(Items.PUMPKIN_SEEDS, 12)));

                    BlockPos bossPos = origin.add(0, 0, 1);
                    ZombieEntity boss = new ZombieEntity(EntityType.ZOMBIE, world);
                    boss.refreshPositionAndAngles(bossPos.getX() + 0.5, bossPos.getY(), bossPos.getZ() + 0.5, 0.0f, 0.0f);
                    boss.setBaby(false);
                    boss.setPersistent();
                    boss.addCommandTag("bazar_boss_padik");
                    boss.setCustomName(Text.literal("Старший по падику").formatted(Formatting.GOLD, Formatting.BOLD));
                    boss.setCustomNameVisible(true);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH) != null) {
                        boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(50.0);
                        boss.setHealth(50.0f);
                    }
                    ItemStack jacket = new ItemStack(Items.LEATHER_CHESTPLATE);
                    jacket.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0x1E1E24, false));
                    boss.equipStack(EquipmentSlot.CHEST, jacket);
                    boss.equipStack(EquipmentSlot.MAINHAND, PsychedelicraftIntegration.createCigarette(1));
                    world.spawnEntity(boss);
                }
                case SPOT_KIOSK -> {
                    writeSignText(world, origin.add(0, 2, 0), "ЛАРЁК 24/7", "ЖИВОЕ ПЕННОЕ", "ВОДКА И РЫБКА", "В ДОЛГ НЕ ДАЁМ");
                    fillChestLoot(world, origin.add(1, 0, -2), "bazar_boss_kiosk",
                            List.of(new ItemStack(Items.EMERALD, 5), new ItemStack(Items.COOKED_COD, 6)));

                    BlockPos bossPos = origin.add(0, 0, 1);
                    ZombieVillagerEntity boss = new ZombieVillagerEntity(EntityType.ZOMBIE_VILLAGER, world);
                    boss.refreshPositionAndAngles(bossPos.getX() + 0.5, bossPos.getY(), bossPos.getZ() + 0.5, 0.0f, 0.0f);
                    boss.setBaby(false);
                    boss.setPersistent();
                    boss.addCommandTag("bazar_boss_kiosk");
                    boss.setCustomName(Text.literal("Дядя Витя (Хозяин ларька)").formatted(Formatting.GOLD, Formatting.BOLD));
                    boss.setCustomNameVisible(true);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH) != null) {
                        boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(55.0);
                        boss.setHealth(55.0f);
                    }
                    boss.equipStack(EquipmentSlot.MAINHAND, PsychedelicraftIntegration.createBeerMug());
                    boss.equipStack(EquipmentSlot.OFFHAND, PsychedelicraftIntegration.createVodkaBottle());
                    world.spawnEntity(boss);
                }
                case SPOT_GARAGE -> {
                    writeSignText(world, origin.add(-2, 1, 1), "ГСК «МОТОР»", "ГАРАЖ БАТИ", "КАРБЮРАТОР", "НЕ ТРОГАТЬ!");
                    fillChestLoot(world, origin.add(0, 0, -3), "bazar_boss_garage",
                            List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.IRON_INGOT, 8)));

                    BlockPos bossPos = origin.add(0, 0, 1);
                    PillagerEntity boss = new PillagerEntity(EntityType.PILLAGER, world);
                    boss.refreshPositionAndAngles(bossPos.getX() + 0.5, bossPos.getY(), bossPos.getZ() + 0.5, 0.0f, 0.0f);
                    boss.setPersistent();
                    boss.addCommandTag("bazar_boss_garage");
                    boss.setCustomName(Text.literal("Батя с карбюратором").formatted(Formatting.GOLD, Formatting.BOLD));
                    boss.setCustomNameVisible(true);
                    if (boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH) != null) {
                        boss.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).setBaseValue(60.0);
                        boss.setHealth(60.0f);
                    }
                    boss.equipStack(EquipmentSlot.MAINHAND, PsychedelicraftIntegration.createVodkaBottle());
                    boss.equipStack(EquipmentSlot.OFFHAND, PsychedelicraftIntegration.createJoint(1));
                    world.spawnEntity(boss);
                }
            }
        } catch (Exception e) {
            BazarMod.LOGGER.error("Failed to finalize Bazar spot {} at {}", spotKind, origin, e);
        }
    }

    private static void writeSignText(ServerWorld world, BlockPos pos, String l1, String l2, String l3, String l4) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SignBlockEntity sign) {
            sign.changeText(text -> text
                    .withMessage(0, Text.literal(l1))
                    .withMessage(1, Text.literal(l2))
                    .withMessage(2, Text.literal(l3))
                    .withMessage(3, Text.literal(l4))
                    .withColor(DyeColor.BLACK), true);
        }
    }

    private static void fillChestLoot(ServerWorld world, BlockPos pos, String bossTag, List<ItemStack> baseLoot) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ChestBlockEntity chest) {
            int slot = 0;
            for (ItemStack base : baseLoot) {
                if (slot < chest.size()) {
                    chest.setStack(slot++, base);
                }
            }
            for (ItemStack modLoot : PsychedelicraftIntegration.generateSpotBossLoot(bossTag)) {
                if (slot < chest.size()) {
                    chest.setStack(slot++, modLoot);
                }
            }
            chest.markDirty();
        }
    }

    private static void setBlock(ServerWorldAccess world, BlockBox bounds, BlockPos pos, BlockState state) {
        if (bounds.contains(pos)) {
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS);
        }
    }

    private static void clearAirAbove(ServerWorldAccess world, BlockBox bounds, BlockPos base, int height) {
        for (int dy = 0; dy < height; dy++) {
            BlockPos p = base.up(dy);
            if (bounds.contains(p)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    private static void fillColumnDown(ServerWorldAccess world, BlockBox bounds, BlockPos start, BlockState filler) {
        for (int dy = 0; dy < 8; dy++) {
            BlockPos p = start.down(dy);
            if (!bounds.contains(p)) continue;
            BlockState existing = world.getBlockState(p);
            if (existing.isAir() || existing.isLiquid() || existing.isReplaceable()) {
                world.setBlockState(p, filler, Block.NOTIFY_LISTENERS);
            } else {
                break;
            }
        }
    }
}
