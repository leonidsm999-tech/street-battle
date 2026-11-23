package com.bazarmod;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class PsychedelicraftIntegration {
    private static final Random RANDOM = new Random();

    public static boolean isLoaded() {
        return Registries.ITEM.containsId(Identifier.of("psychedelicraft", "cigarette"));
    }

    public static ItemStack createCigarette(int count) {
        return getItemStack("psychedelicraft", "cigarette", count, Items.PAPER);
    }

    public static ItemStack createJoint(int count) {
        return getItemStack("psychedelicraft", "joint", count, Items.KELP);
    }

    public static ItemStack createCigar(int count) {
        return getItemStack("psychedelicraft", "cigar", count, Items.STICK);
    }

    public static ItemStack createBong() {
        return getItemStack("psychedelicraft", "bong", 1, Items.GLASS_BOTTLE);
    }

    /**
     * Creates a filled Psychedelicraft Bottle of Vodka (3x distilled potato/wheat spirit).
     */
    public static ItemStack createVodkaBottle() {
        ItemStack bottle = getItemStack("psychedelicraft", "bottle", 1, Items.POTION);
        if (!isLoaded()) {
            bottle.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Беленькая (Водка)").formatted(Formatting.AQUA));
            return bottle;
        }
        JsonObject attrs = new JsonObject();
        attrs.addProperty("fermentation", 2);
        attrs.addProperty("distillation", 3);
        applyFluidComponent(bottle, "psychedelicraft:potato", 40500, attrs);
        bottle.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("«Дворовая Столичная» — 40 оборотов").formatted(Formatting.GRAY)
        )));
        return bottle;
    }

    /**
     * Creates a filled Psychedelicraft Wooden Mug / Bottle of Beer (fermented & matured wheat_hop).
     */
    public static ItemStack createBeerMug() {
        ItemStack mug = getItemStack("psychedelicraft", "wooden_mug", 1, Items.HONEY_BOTTLE);
        if (!isLoaded()) {
            mug.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Живое Пенное").formatted(Formatting.GOLD));
            return mug;
        }
        JsonObject attrs = new JsonObject();
        attrs.addProperty("fermentation", 2);
        attrs.addProperty("maturation", 2);
        applyFluidComponent(mug, "psychedelicraft:wheat_hop", 40500, attrs);
        mug.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("«Разливное из ларька у дома»").formatted(Formatting.YELLOW)
        )));
        return mug;
    }

    /**
     * Generates thematic loot drops when defeating a spot boss or winning a tough street battle.
     */
    public static List<ItemStack> generateSpotBossLoot(String bossTag) {
        List<ItemStack> loot = new ArrayList<>();
        if ("bazar_boss_kiosk".equals(bossTag)) {
            loot.add(createBeerMug());
            loot.add(createBeerMug());
            loot.add(createVodkaBottle());
            loot.add(createCigarette(4 + RANDOM.nextInt(5)));
        } else if ("bazar_boss_padik".equals(bossTag)) {
            loot.add(createCigarette(6 + RANDOM.nextInt(6)));
            loot.add(createJoint(2 + RANDOM.nextInt(3)));
            loot.add(createBeerMug());
        } else if ("bazar_boss_garage".equals(bossTag)) {
            loot.add(createVodkaBottle());
            loot.add(createVodkaBottle());
            loot.add(createCigar(2 + RANDOM.nextInt(3)));
            loot.add(createJoint(1 + RANDOM.nextInt(2)));
        } else {
            // Random street drop for normal battles
            int roll = RANDOM.nextInt(3);
            if (roll == 0) loot.add(createCigarette(2 + RANDOM.nextInt(3)));
            else if (roll == 1) loot.add(createBeerMug());
            else loot.add(createJoint(1));
        }
        return loot;
    }

    private static ItemStack getItemStack(String namespace, String path, int count, Item fallback) {
        Identifier id = Identifier.of(namespace, path);
        if (Registries.ITEM.containsId(id)) {
            Item item = Registries.ITEM.get(id);
            return new ItemStack(item, count);
        }
        return new ItemStack(fallback, count);
    }

    @SuppressWarnings("unchecked")
    private static void applyFluidComponent(ItemStack stack, String fluidId, int amount, JsonObject attributes) {
        try {
            Identifier compId = Identifier.of("psychedelicraft", "fluids");
            ComponentType<?> rawType = Registries.DATA_COMPONENT_TYPE.get(compId);
            if (rawType == null || rawType.getCodec() == null) return;

            JsonObject root = new JsonObject();
            root.addProperty("fluid", fluidId);
            root.addProperty("amount", amount);
            if (attributes != null) {
                root.add("attributes", attributes);
            }

            Codec<Object> codec = (Codec<Object>) rawType.getCodec();
            Optional<Object> parsed = codec.parse(JsonOps.INSTANCE, root).result();
            parsed.ifPresent(val -> stack.set((ComponentType<Object>) rawType, val));
        } catch (Exception e) {
            BazarMod.LOGGER.debug("Could not apply psychedelicraft fluid component: {}", e.getMessage());
        }
    }
}
