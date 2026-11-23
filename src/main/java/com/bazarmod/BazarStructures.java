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
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
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
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

import java.util.Optional;

public class BazarStructures {
    public static final String SPOT_PADIK = "padik";
    public static final String SPOT_KIOSK = "beer_kiosk";
    public static final String SPOT_GARAGE = "garage";

    public static StructureType<BazarSpotStructure> PADIK_TYPE;
    public static StructureType<BazarSpotStructure> KIOSK_TYPE;
    public static StructureType<BazarSpotStructure> GARAGE_TYPE;
    public static StructurePieceType SPOT_PIECE_TYPE;

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
            buildSpotAt(world, chunkBox, origin, spotKind, random);
        }
    }

    /**
     * Builds the chosen structure (padik, beer_kiosk, or garage) either during worldgen or via /bazar spot.
     */
    public static void buildSpotAt(ServerWorldAccess world, BlockBox bounds, BlockPos origin, String spotKind, Random random) {
        switch (spotKind) {
            case SPOT_PADIK -> buildPadik(world, bounds, origin, random);
            case SPOT_KIOSK -> buildBeerKiosk(world, bounds, origin, random);
            case SPOT_GARAGE -> buildGarage(world, bounds, origin, random);
        }
    }

    // =========================================================================
    // 1. ПАДИК (Подъезд хрущёвки + лавочка + Старший по падику)
    // =========================================================================
    private static void buildPadik(ServerWorldAccess world, BlockBox bounds, BlockPos origin, Random random) {
        // Foundation & courtyard asphalt (7x8 area)
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 3; dz++) {
                fillColumnDown(world, bounds, origin.add(dx, -1, dz), Blocks.SMOOTH_STONE.getDefaultState());
                clearAirAbove(world, bounds, origin.add(dx, 0, dz), 6);
                BlockState floor = (dz >= 0) ? Blocks.ANDESITE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
                setBlock(world, bounds, origin.add(dx, -1, dz), floor);
            }
        }

        // Brick Khrushchyovka facade & stairwell (z from -4 to -1, x from -3 to 3, height 5)
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
        // Concrete Roof + Porch Awning (Козырёк над подъездом)
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 0; dz++) {
                setBlock(world, bounds, origin.add(dx, 5, dz), Blocks.GRAY_CONCRETE.getDefaultState());
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            setBlock(world, bounds, origin.add(dx, 3, 0), Blocks.GRAY_CONCRETE_SLAB.getDefaultState());
        }

        // Entrance doorway (open archway at x=0, z=-1, y=0..1) + Lantern
        setBlock(world, bounds, origin.add(0, 0, -1), Blocks.AIR.getDefaultState());
        setBlock(world, bounds, origin.add(0, 1, -1), Blocks.AIR.getDefaultState());
        setBlock(world, bounds, origin.add(0, 2, 0), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));

        // Interior stairs & loot chest ("Общак в подъезде")
        setBlock(world, bounds, origin.add(-2, 0, -3), Blocks.STONE_BRICK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
        setBlock(world, bounds, origin.add(-2, 1, -4), Blocks.STONE_BRICKS.getDefaultState());
        placeLootChest(world, bounds, origin.add(2, 0, -3), Direction.SOUTH, "chests/padik", random);

        // Wooden Bench (Лавочка у падика) & Trash Can (Урна)
        setBlock(world, bounds, origin.add(-2, 0, 1), Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
        setBlock(world, bounds, origin.add(-1, 0, 1), Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
        setBlock(world, bounds, origin.add(2, 0, 0), Blocks.CAULDRON.getDefaultState());

        // Wall Sign next to entrance
        placeWallSign(world, bounds, origin.add(-1, 1, 0), Direction.SOUTH,
                "ПАДИК №1", "СЕМКИ ЕСТЬ?", "НЕ КУРИТЬ", "ШТРАФ 5000");

        // Spawn Boss: Старший по падику
        BlockPos bossPos = origin.add(0, 0, 1);
        if (bounds.contains(bossPos)) {
            ZombieEntity boss = EntityType.ZOMBIE.create(world.toServerWorld(), null, bossPos, SpawnReason.STRUCTURE, true, false);
            if (boss != null) {
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
                world.spawnEntityAndPassengers(boss);
            }
        }
    }

    // =========================================================================
    // 2. ЛАРЁК С ПИВОМ 24/7 (Киоск + столики + Дядя Витя)
    // =========================================================================
    private static void buildBeerKiosk(ServerWorldAccess world, BlockBox bounds, BlockPos origin, Random random) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                fillColumnDown(world, bounds, origin.add(dx, -1, dz), Blocks.COBBLESTONE.getDefaultState());
                clearAirAbove(world, bounds, origin.add(dx, 0, dz), 6);
                setBlock(world, bounds, origin.add(dx, -1, dz), Blocks.STONE_BRICKS.getDefaultState());
            }
        }

        // Kiosk booth (x: -2..2, z: -3..-1, y: 0..2)
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

        // Counter window in front (x: -1..1, y: 1, z: -1) + Trapdoor counter shelf
        for (int dx = -1; dx <= 1; dx++) {
            setBlock(world, bounds, origin.add(dx, 1, -1), Blocks.GLASS_PANE.getDefaultState());
            setBlock(world, bounds, origin.add(dx, 1, 0), Blocks.SPRUCE_TRAPDOOR.getDefaultState().with(TrapdoorBlock.HALF, BlockHalf.TOP).with(TrapdoorBlock.OPEN, false));
        }

        // Striped awning roof
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 0; dz++) {
                BlockState roof = (Math.abs(dx) % 2 == 0) ? Blocks.LIME_CONCRETE.getDefaultState() : Blocks.YELLOW_CONCRETE.getDefaultState();
                setBlock(world, bounds, origin.add(dx, 3, dz), roof);
            }
        }
        setBlock(world, bounds, origin.add(0, 2, 0), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));

        // Inside kiosk: Barrel of Beer & Loot Chest
        setBlock(world, bounds, origin.add(-1, 0, -2), Blocks.BARREL.getDefaultState());
        placeLootChest(world, bounds, origin.add(1, 0, -2), Direction.SOUTH, "chests/beer_kiosk", random);

        // Outdoor standing table (Fence + Pressure plate) & stools
        setBlock(world, bounds, origin.add(-2, 0, 2), Blocks.OAK_FENCE.getDefaultState());
        setBlock(world, bounds, origin.add(-2, 1, 2), Blocks.OAK_PRESSURE_PLATE.getDefaultState());
        setBlock(world, bounds, origin.add(-1, 0, 2), Blocks.OAK_SLAB.getDefaultState());
        setBlock(world, bounds, origin.add(2, 0, 2), Blocks.OAK_FENCE.getDefaultState());
        setBlock(world, bounds, origin.add(2, 1, 2), Blocks.OAK_PRESSURE_PLATE.getDefaultState());

        // Kiosk Sign
        placeWallSign(world, bounds, origin.add(0, 2, 0), Direction.SOUTH,
                "ЛАРЁК 24/7", "ЖИВОЕ ПЕННОЕ", "ВОДКА И РЫБКА", "В ДОЛГ НЕ ДАЁМ");

        // Spawn Boss: Дядя Витя (Хозяин ларька)
        BlockPos bossPos = origin.add(0, 0, 1);
        if (bounds.contains(bossPos)) {
            ZombieVillagerEntity boss = EntityType.ZOMBIE_VILLAGER.create(world.toServerWorld(), null, bossPos, SpawnReason.STRUCTURE, true, false);
            if (boss != null) {
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
                world.spawnEntityAndPassengers(boss);
            }
        }
    }

    // =========================================================================
    // 3. ДЕДОВСКИЙ ГАРАЖ (Металлический бокс + мангал + Батя с карбюратором)
    // =========================================================================
    private static void buildGarage(ServerWorldAccess world, BlockBox bounds, BlockPos origin, Random random) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 3; dz++) {
                fillColumnDown(world, bounds, origin.add(dx, -1, dz), Blocks.COBBLESTONE.getDefaultState());
                clearAirAbove(world, bounds, origin.add(dx, 0, dz), 6);
                setBlock(world, bounds, origin.add(dx, -1, dz), Blocks.SMOOTH_STONE.getDefaultState());
            }
        }

        // Metal Garage Box (x: -3..3, z: -4..0, y: 0..3)
        for (int y = 0; y <= 3; y++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -4; dz <= 0; dz++) {
                    boolean isWall = (dx == -3 || dx == 3 || dz == -4 || dz == 0);
                    if (isWall) {
                        // Open wide garage gates on front (z=0, x=-1..1, y=0..2)
                        if (dz == 0 && Math.abs(dx) <= 1 && y <= 2) {
                            continue;
                        }
                        BlockState mat = ((dx + y) % 2 == 0) ? Blocks.IRON_BLOCK.getDefaultState() : Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
                        setBlock(world, bounds, origin.add(dx, y, dz), mat);
                    }
                }
            }
        }
        // Garage Roof
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -4; dz <= 0; dz++) {
                setBlock(world, bounds, origin.add(dx, 4, dz), Blocks.STONE_BRICK_SLAB.getDefaultState());
            }
        }

        // Inside Garage: Crafting Table, Anvil, Blast Furnace, Chest
        setBlock(world, bounds, origin.add(-2, 0, -3), Blocks.CRAFTING_TABLE.getDefaultState());
        setBlock(world, bounds, origin.add(-2, 0, -2), Blocks.ANVIL.getDefaultState());
        setBlock(world, bounds, origin.add(2, 0, -3), Blocks.BLAST_FURNACE.getDefaultState().with(BlastFurnaceBlock.FACING, Direction.WEST));
        placeLootChest(world, bounds, origin.add(0, 0, -3), Direction.SOUTH, "chests/garage", random);

        // Outdoor Mangal (Campfire) & Sign
        setBlock(world, bounds, origin.add(2, 0, 2), Blocks.CAMPFIRE.getDefaultState());
        placeWallSign(world, bounds, origin.add(-2, 1, 1), Direction.SOUTH,
                "ГСК «МОТОР»", "ГАРАЖ БАТИ", "КАРБЮРАТОР", "НЕ ТРОГАТЬ!");

        // Spawn Boss: Батя с карбюратором
        BlockPos bossPos = origin.add(0, 0, 1);
        if (bounds.contains(bossPos)) {
            PillagerEntity boss = EntityType.PILLAGER.create(world.toServerWorld(), null, bossPos, SpawnReason.STRUCTURE, true, false);
            if (boss != null) {
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
                world.spawnEntityAndPassengers(boss);
            }
        }
    }

    private static void placeLootChest(ServerWorldAccess world, BlockBox bounds, BlockPos pos, Direction facing, String lootTablePath, Random random) {
        if (!bounds.contains(pos)) return;
        world.setBlockState(pos, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing), Block.NOTIFY_LISTENERS);
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ChestBlockEntity chest) {
            chest.setLootTable(RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(BazarMod.MOD_ID, lootTablePath)), random.nextLong());
        }
    }

    private static void placeWallSign(ServerWorldAccess world, BlockBox bounds, BlockPos pos, Direction facing,
                                      String l1, String l2, String l3, String l4) {
        if (!bounds.contains(pos)) return;
        world.setBlockState(pos, Blocks.OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, facing), Block.NOTIFY_LISTENERS);
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
