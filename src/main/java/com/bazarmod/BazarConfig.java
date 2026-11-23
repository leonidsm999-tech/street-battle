package com.bazarmod;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class BazarConfig {
    public String apiKey = "";
    public String model = "gemini-2.5-flash";
    public String proxyHost = "";
    public int proxyPort = 0;
    public int battleDurationSec = 15;
    public int minWinScore = 50;
    public boolean mockMode = false;

    private static BazarConfig INSTANCE;

    public static BazarConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static void reload() {
        INSTANCE = load();
    }

    public static BazarConfig load() {
        BazarConfig cfg = new BazarConfig();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("bazar_mod.properties");
        Properties props = new Properties();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
                cfg.apiKey = props.getProperty("api_key", "").trim();
                cfg.model = props.getProperty("model", "gemini-2.5-flash").trim();
                cfg.proxyHost = props.getProperty("proxy_host", "").trim();
                cfg.proxyPort = parseInt(props.getProperty("proxy_port", "0"), 0);
                cfg.battleDurationSec = Math.max(5, Math.min(30, parseInt(props.getProperty("battle_duration_sec", "15"), 15)));
                cfg.minWinScore = Math.max(1, Math.min(100, parseInt(props.getProperty("min_win_score", "50"), 50)));
                cfg.mockMode = Boolean.parseBoolean(props.getProperty("mock_mode", "false").trim());
            } catch (Exception e) {
                BazarMod.LOGGER.error("Failed to read bazar_mod.properties", e);
            }
        } else {
            saveDefault(path, cfg);
        }
        return cfg;
    }

    private static void saveDefault(Path path, BazarConfig cfg) {
        try {
            Files.createDirectories(path.getParent());
            String template = String.join("\n",
                "# Конфигурация мода Дворовая Стрелка (Bazar Battle)",
                "# Вставьте ваш бесплатный API ключ из Google AI Studio (https://aistudio.google.com/)",
                "api_key=",
                "",
                "# Модель Gemini (рекомендуется gemini-2.5-flash или gemini-2.0-flash)",
                "model=" + cfg.model,
                "",
                "# Опционально: HTTP прокси, если Gemini API недоступен напрямую (например 127.0.0.1 и 10809)",
                "proxy_host=",
                "proxy_port=0",
                "",
                "# Длительность стрелки в секундах (по умолчанию 15)",
                "battle_duration_sec=" + cfg.battleDurationSec,
                "",
                "# Минимальный балл (из 100) для победы в базаре",
                "min_win_score=" + cfg.minWinScore,
                "",
                "# Тестовый режим без API ключа (если true — судья ставит случайный балл без нейросети)",
                "mock_mode=false"
            );
            Files.writeString(path, template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            BazarMod.LOGGER.error("Failed to write default bazar_mod.properties", e);
        }
    }

    private static int parseInt(String val, int fallback) {
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
