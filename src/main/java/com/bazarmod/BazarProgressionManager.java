package com.bazarmod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BazarProgressionManager {
    public static class PlayerProgress {
        public int respectXp = 0;
        public int totalWins = 0;
        public int totalLosses = 0;
        public Map<String, Integer> mobWins = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, PlayerProgress> DATA = new ConcurrentHashMap<>();
    private static boolean loaded = false;

    private static Path getSavePath() {
        return FabricLoader.getInstance().getConfigDir().resolve("bazar_progression.json");
    }

    public static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = getSavePath();
        if (!Files.exists(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, PlayerProgress>>() {}.getType();
            Map<String, PlayerProgress> raw = GSON.fromJson(reader, type);
            if (raw != null) {
                for (Map.Entry<String, PlayerProgress> e : raw.entrySet()) {
                    try {
                        DATA.put(UUID.fromString(e.getKey()), e.getValue());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            BazarMod.LOGGER.error("Failed to load bazar_progression.json", e);
        }
    }

    public static void save() {
        Path path = getSavePath();
        try {
            Files.createDirectories(path.getParent());
            Map<String, PlayerProgress> raw = new HashMap<>();
            for (Map.Entry<UUID, PlayerProgress> e : DATA.entrySet()) {
                raw.put(e.getKey().toString(), e.getValue());
            }
            Files.writeString(path, GSON.toJson(raw), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BazarMod.LOGGER.error("Failed to save bazar_progression.json", e);
        }
    }

    public static PlayerProgress get(UUID playerId) {
        ensureLoaded();
        return DATA.computeIfAbsent(playerId, id -> new PlayerProgress());
    }

    public static int getMobWins(UUID playerId, String mobTypeKey) {
        return get(playerId).mobWins.getOrDefault(mobTypeKey, 0);
    }

    public static int getRequiredWinsForTier(int tier) {
        return switch (tier) {
            case 1 -> 3; // Мирные: 3 победы -> боятся тебя
            case 2 -> 3; // Обычные враги: 3 победы
            case 3 -> 2; // Опасные (крипер, эндермен): 2 победы
            case 4 -> 1; // Боссы (Дракон, Визер, Варден): 1 победа
            default -> 3;
        };
    }

    public static boolean isMobTypeDominated(UUID playerId, String mobTypeKey, int tier) {
        return getMobWins(playerId, mobTypeKey) >= getRequiredWinsForTier(tier);
    }

    public static String getRankTitle(int xp) {
        if (xp >= 1000) return "Легенда Двора";
        if (xp >= 600) return "Смотрящий";
        if (xp >= 300) return "Авторитет";
        if (xp >= 100) return "Ровный пацан";
        return "Новичок на районе";
    }

    public static int getRankLevel(int xp) {
        if (xp >= 1000) return 5;
        if (xp >= 600) return 4;
        if (xp >= 300) return 3;
        if (xp >= 100) return 2;
        return 1;
    }

    public record BattleOutcomeUpdate(
            int xpGained,
            int totalXp,
            String oldRank,
            String newRank,
            boolean rankUp,
            int mobWinsAfter,
            int mobWinsRequired,
            boolean newlyDominated
    ) {}

    public static BattleOutcomeUpdate recordBattle(UUID playerId, String mobTypeKey, int tier, boolean win, int score) {
        PlayerProgress p = get(playerId);
        String oldRank = getRankTitle(p.respectXp);
        int reqWins = getRequiredWinsForTier(tier);
        int prevMobWins = p.mobWins.getOrDefault(mobTypeKey, 0);

        int xpGained;
        int newMobWins = prevMobWins;
        if (win) {
            p.totalWins++;
            newMobWins = prevMobWins + 1;
            p.mobWins.put(mobTypeKey, newMobWins);
            xpGained = 25 + (tier * 15) + (score / 4);
        } else {
            p.totalLosses++;
            xpGained = Math.max(5, score / 10); // consolation street experience
        }

        p.respectXp += xpGained;
        String newRank = getRankTitle(p.respectXp);
        boolean rankUp = !oldRank.equals(newRank);
        boolean newlyDominated = win && prevMobWins < reqWins && newMobWins >= reqWins;

        save();
        return new BattleOutcomeUpdate(
                xpGained,
                p.respectXp,
                oldRank,
                newRank,
                rankUp,
                newMobWins,
                reqWins,
                newlyDominated
        );
    }
}
