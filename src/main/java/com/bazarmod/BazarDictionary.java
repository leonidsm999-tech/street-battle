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
import java.util.concurrent.ConcurrentHashMap;

public class BazarDictionary {
    public record PromptBundle(
            int tier,
            String tierBadge,
            String mobTypeKey,
            String mobStreetNickname,
            List<String> mobDissLines,
            List<String> words,
            String bossTag
    ) {}

    private record BossProfile(String nickname, String badge, List<List<String>> disses, List<String> words) {}

    private static final Map<String, BossProfile> BOSS_PROFILES = new HashMap<>();
    private static final Map<String, String> MOB_NICKNAMES = new HashMap<>();
    private static final Map<String, List<List<String>>> MOB_DISSES = new HashMap<>();
    private static final Map<Integer, String> TIER_BADGES = new HashMap<>();
    private static final Map<Integer, List<List<String>>> TIER_DISSES = new HashMap<>();
    private static final Map<Integer, List<String>> TIER_WORDS = new HashMap<>();

    private static final Map<String, Deque<Integer>> RECENT_DISS_HISTORY = new ConcurrentHashMap<>();
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

            if (root.has("boss_specific")) {
                JsonObject bossSpec = root.getAsJsonObject("boss_specific");
                for (Map.Entry<String, JsonElement> entry : bossSpec.entrySet()) {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    String nick = obj.has("nickname") ? obj.get("nickname").getAsString() : "Босс Точки";
                    String badge = obj.has("badge") ? obj.get("badge").getAsString() : "БОСС ТОЧКИ";
                    List<List<String>> disses = obj.has("disses") ? parseDissList(obj.getAsJsonArray("disses")) : List.of();
                    List<String> words = new ArrayList<>();
                    if (obj.has("words")) {
                        obj.getAsJsonArray("words").forEach(w -> words.add(w.getAsString()));
                    }
                    BOSS_PROFILES.put(entry.getKey(), new BossProfile(nick, badge, disses, words));
                }
            }

            if (root.has("mob_specific")) {
                JsonObject mobSpec = root.getAsJsonObject("mob_specific");
                for (Map.Entry<String, JsonElement> entry : mobSpec.entrySet()) {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    if (obj.has("nickname")) {
                        MOB_NICKNAMES.put(entry.getKey(), obj.get("nickname").getAsString());
                    }
                    if (obj.has("disses")) {
                        MOB_DISSES.put(entry.getKey(), parseDissList(obj.getAsJsonArray("disses")));
                    }
                }
            }

            JsonObject tiers = root.getAsJsonObject("tiers");
            for (int t = 1; t <= 4; t++) {
                JsonObject tierObj = tiers.getAsJsonObject(String.valueOf(t));
                if (tierObj.has("badge")) {
                    TIER_BADGES.put(t, tierObj.get("badge").getAsString());
                }
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

    public static String getSpotBossTag(LivingEntity entity) {
        for (String tag : entity.getCommandTags()) {
            if (tag.startsWith("bazar_boss_")) {
                return tag;
            }
        }
        return null;
    }

    public static int resolveTier(LivingEntity entity) {
        if (getSpotBossTag(entity) != null) {
            return 3;
        }
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

    public static PromptBundle generateFor(LivingEntity entity, int mobWinsCount) {
        ensureLoaded();
        int tier = resolveTier(entity);
        Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
        String pathKey = typeId.getPath();

        // Check if this is a special structure boss (Padik, Beer Kiosk, Garage)
        String bossTag = getSpotBossTag(entity);
        if (bossTag != null && BOSS_PROFILES.containsKey(bossTag)) {
            BossProfile bp = BOSS_PROFILES.get(bossTag);
            List<List<String>> pool = bp.disses().isEmpty()
                    ? List.of(List.of("Поясни за свой визит на мою точку, фраер!"))
                    : bp.disses();
            int idx = pickNonRepeatingIndex(bossTag, pool.size());
            List<String> chosenDiss = new ArrayList<>(pool.get(idx));

            List<String> wordPool = new ArrayList<>(bp.words());
            Collections.shuffle(wordPool, RANDOM);
            List<String> chosenWords = new ArrayList<>(wordPool.subList(0, Math.min(5, wordPool.size())));
            return new PromptBundle(tier, bp.badge(), bossTag, bp.nickname(), chosenDiss, chosenWords, bossTag);
        }

        String nickname = entity.hasCustomName() && entity.getCustomName() != null
                ? entity.getCustomName().getString()
                : MOB_NICKNAMES.getOrDefault(pathKey, entity.getName().getString());

        String tierBadge = TIER_BADGES.getOrDefault(tier, "ДВОРОВЫЙ");

        List<List<String>> dissPool = new ArrayList<>();
        if (MOB_DISSES.containsKey(pathKey)) {
            dissPool.addAll(MOB_DISSES.get(pathKey));
        }
        if (TIER_DISSES.containsKey(tier)) {
            dissPool.addAll(TIER_DISSES.get(tier));
        }
        if (dissPool.isEmpty()) {
            dissPool.add(List.of("Слышь, фраер, ты с какого биома сюда забрёл?", "Поясни за свой базар, пока не поздно!"));
        }

        int chosenIdx = pickNonRepeatingIndex(pathKey, dissPool.size());
        List<String> chosenDiss = new ArrayList<>(dissPool.get(chosenIdx));

        List<String> wordPool = new ArrayList<>(TIER_WORDS.getOrDefault(tier, List.of("поясни", "уважение", "базар")));
        if (mobWinsCount > 0 && tier < 4 && TIER_WORDS.containsKey(tier + 1)) {
            wordPool.addAll(TIER_WORDS.get(tier + 1));
        }
        Collections.shuffle(wordPool, RANDOM);

        int wordCount = Math.min(wordPool.size(), 2 + tier + Math.min(2, mobWinsCount));
        List<String> chosenWords = new ArrayList<>(wordPool.subList(0, wordCount));
        return new PromptBundle(tier, tierBadge, pathKey, nickname, chosenDiss, chosenWords, null);
    }

    private static int pickNonRepeatingIndex(String key, int poolSize) {
        if (poolSize <= 1) return 0;
        Deque<Integer> recent = RECENT_DISS_HISTORY.computeIfAbsent(key, k -> new ArrayDeque<>());
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            if (!recent.contains(i)) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            recent.clear();
            for (int i = 0; i < poolSize; i++) candidates.add(i);
        }
        int picked = candidates.get(RANDOM.nextInt(candidates.size()));
        recent.addLast(picked);
        while (recent.size() >= Math.max(1, poolSize - 1)) {
            recent.removeFirst();
        }
        return picked;
    }
}
