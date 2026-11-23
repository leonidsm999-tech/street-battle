package com.bazarmod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BazarDictionary {
    public record PromptBundle(int tier, List<String> mobDissLines, List<String> words) {}

    private static final Map<String, List<List<String>>> MOB_DISSES = new HashMap<>();
    private static final Map<Integer, List<List<String>>> TIER_DISSES = new HashMap<>();
    private static final Map<Integer, List<String>> TIER_WORDS = new HashMap<>();
    private static final Random RANDOM = new Random();
    private static boolean loaded = false;

    public static void ensureLoaded() {
        if (loaded) return;
        try (InputStream is = BazarDictionary.class.getResourceAsStream("/data/bazarmod/bazar_data.json")) {
            if (is == null) {
                BazarMod.LOGGER.error("bazar_data.json not found in resources!");
                return;
            }
            JsonObject root = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonObject.class);

            if (root.has("mob_specific")) {
                JsonObject mobSpec = root.getAsJsonObject("mob_specific");
                for (Map.Entry<String, JsonElement> entry : mobSpec.entrySet()) {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    if (obj.has("disses")) {
                        MOB_DISSES.put(entry.getKey(), parseDissList(obj.getAsJsonArray("disses")));
                    }
                }
            }

            JsonObject tiers = root.getAsJsonObject("tiers");
            for (int t = 1; t <= 4; t++) {
                JsonObject tierObj = tiers.getAsJsonObject(String.valueOf(t));
                if (tierObj.has("disses")) {
                    TIER_DISSES.put(t, parseDissList(tierObj.getAsJsonArray("disses")));
                }
                List<String> wordList = new ArrayList<>();
                JsonArray wArr = tierObj.getAsJsonArray("words");
                wArr.forEach(el -> wordList.add(el.getAsString()));
                TIER_WORDS.put(t, wordList);
            }
            loaded = true;
        } catch (Exception e) {
            BazarMod.LOGGER.error("Error loading bazar_data.json", e);
        }
    }

    private static List<List<String>> parseDissList(JsonArray outer) {
        List<List<String>> result = new ArrayList<>();
        for (JsonElement el : outer) {
            if (el.isJsonArray()) {
                List<String> lines = new ArrayList<>();
                el.getAsJsonArray().forEach(line -> lines.add(line.getAsString()));
                if (!lines.isEmpty()) result.add(lines);
            }
        }
        return result;
    }

    public static int resolveTier(LivingEntity entity) {
        if (entity instanceof EnderDragonEntity || entity instanceof WitherEntity
                || entity instanceof WardenEntity || entity instanceof ElderGuardianEntity) {
            return 4;
        }
        if (entity instanceof CreeperEntity || entity instanceof EndermanEntity
                || entity instanceof BlazeEntity || entity instanceof WitchEntity
                || entity instanceof GhastEntity || entity instanceof RavagerEntity
                || entity instanceof PiglinBruteEntity || entity instanceof BreezeEntity) {
            return 3;
        }
        if (entity instanceof HostileEntity) {
            return 2;
        }
        if (entity instanceof PassiveEntity) {
            return 1;
        }
        return 2;
    }

    public static PromptBundle generateFor(LivingEntity entity) {
        ensureLoaded();
        int tier = resolveTier(entity);

        Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
        String pathKey = typeId.getPath();

        List<List<String>> dissPool = MOB_DISSES.get(pathKey);
        if (dissPool == null || dissPool.isEmpty()) {
            dissPool = TIER_DISSES.getOrDefault(tier, List.of(List.of(
                    "Слышь, ты с какого биома сюда забрёл?",
                    "Поясни за свой базар, пока не поздно!"
            )));
        }

        List<String> chosenDiss = new ArrayList<>(dissPool.get(RANDOM.nextInt(dissPool.size())));

        List<String> wordPool = new ArrayList<>(TIER_WORDS.getOrDefault(tier, List.of("поясни", "уважение", "базар")));
        Collections.shuffle(wordPool, RANDOM);

        int wordCount = Math.min(wordPool.size(), 2 + tier);
        List<String> chosenWords = new ArrayList<>(wordPool.subList(0, wordCount));
        return new PromptBundle(tier, chosenDiss, chosenWords);
    }
}
