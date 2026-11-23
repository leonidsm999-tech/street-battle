package com.bazarmod.client;

import com.bazarmod.BazarMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BattleHistoryManager {
    public static class BattleRecord {
        public String timestamp;
        public String mobName;
        public List<String> mobDissLines;
        public List<String> promptWords;
        public List<String> usedWords;
        public String playerTranscript;
        public String bestPunch;
        public String judgeComment;
        public String mobReaction;
        public boolean win;
        public int score;
        public int xpGained;
        public String rankTitle;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<BattleRecord> RECORDS = new ArrayList<>();
    private static boolean loaded = false;

    private static Path getHistoryPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("bazar_history.json");
    }

    public static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = getHistoryPath();
        if (!Files.exists(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<BattleRecord>>() {}.getType();
            List<BattleRecord> list = GSON.fromJson(reader, listType);
            if (list != null) {
                RECORDS.clear();
                RECORDS.addAll(list);
            }
        } catch (Exception e) {
            BazarMod.LOGGER.error("Failed to read bazar_history.json", e);
        }
    }

    public static void addRecord(BattleRecord rec) {
        ensureLoaded();
        rec.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm"));
        RECORDS.add(0, rec);
        while (RECORDS.size() > 50) {
            RECORDS.remove(RECORDS.size() - 1);
        }
        save();
    }

    public static void clearHistory() {
        ensureLoaded();
        RECORDS.clear();
        save();
    }

    public static List<BattleRecord> getRecords() {
        ensureLoaded();
        return Collections.unmodifiableList(RECORDS);
    }

    private static void save() {
        Path path = getHistoryPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(RECORDS), StandardCharsets.UTF_8);
        } catch (Exception e) {
            BazarMod.LOGGER.error("Failed to write bazar_history.json", e);
        }
    }
}
