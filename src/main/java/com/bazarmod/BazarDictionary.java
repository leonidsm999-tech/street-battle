package com.bazarmod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.PassiveEntity;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BazarDictionary {
    public record PromptBundle(int tier, String claim, List<String> words) {}

    private static final Map<Integer, List<String>> CLAIMS = new HashMap<>();
    private static final Map<Integer, List<String>> WORDS = new HashMap<>();
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
            JsonObject tiers = root.getAsJsonObject("tiers");
            for (int t = 1; t <= 4; t++) {
                JsonObject tierObj = tiers.getAsJsonObject(String.valueOf(t));
                List<String> claimList = new ArrayList<>();
                JsonArray cArr = tierObj.getAsJsonArray("claims");
                cArr.forEach(el -> claimList.add(el.getAsString()));
                CLAIMS.put(t, claimList);

                List<String> wordList = new ArrayList<>();
                JsonArray wArr = tierObj.getAsJsonArray("words");
                wArr.forEach(el -> wordList.add(el.getAsString()));
                WORDS.put(t, wordList);
            }
            loaded = true;
        } catch (Exception e) {
            BazarMod.LOGGER.error("Error loading bazar_data.json", e);
        }
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
        List<String> claimPool = CLAIMS.getOrDefault(tier, List.of("Поясни за себя, браток!"));
        List<String> wordPool = new ArrayList<>(WORDS.getOrDefault(tier, List.of("поясни", "уважение", "базар")));

        String claim = claimPool.get(RANDOM.nextInt(claimPool.size()));
        Collections.shuffle(wordPool, RANDOM);

        int wordCount = Math.min(wordPool.size(), 2 + tier); // Tier 1 -> 3 words, Tier 4 -> 6 words
        List<String> chosenWords = new ArrayList<>(wordPool.subList(0, wordCount));
        return new PromptBundle(tier, claim, chosenWords);
    }
}
