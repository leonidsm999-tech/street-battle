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

    // TTS (Voice) settings
    public boolean ttsEnabled = true;
    public String ttsVoice = "auto"; // auto, ru-RU-DmitryNeural, ru-RU-SvetlanaNeural, windows-sapi
    public int ttsRate = 15; // -30..50

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

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("bazar_mod.properties");
    }

    public static BazarConfig load() {
        BazarConfig cfg = new BazarConfig();
        Path path = getConfigPath();
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
                cfg.ttsEnabled = Boolean.parseBoolean(props.getProperty("tts_enabled", "true").trim());
                cfg.ttsVoice = props.getProperty("tts_voice", "auto").trim();
                cfg.ttsRate = Math.max(-30, Math.min(50, parseInt(props.getProperty("tts_rate", "15"), 15)));
            } catch (Exception e) {
                BazarMod.LOGGER.error("Failed to read bazar_mod.properties", e);
            }
        } else {
            cfg.save();
        }
        return cfg;
    }

    public void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            String content = String.join("\n",
                "# Конфигурация мода Дворовая Стрелка (Bazar Battle)",
                "# Вставьте ваш бесплатный API ключ из Google AI Studio (https://aistudio.google.com/)",
                "api_key=" + (apiKey == null ? "" : apiKey.trim()),
                "",
                "# Модель Gemini (рекомендуется gemini-2.5-flash)",
                "model=" + (model == null || model.isBlank() ? "gemini-2.5-flash" : model.trim()),
                "",
                "# Опционально: HTTP прокси, если Gemini API недоступен напрямую (например 127.0.0.1 и 10809)",
                "proxy_host=" + (proxyHost == null ? "" : proxyHost.trim()),
                "proxy_port=" + Math.max(0, proxyPort),
                "",
                "# Длительность вашего ответа в баттле (сек)",
                "battle_duration_sec=" + Math.max(5, Math.min(30, battleDurationSec)),
                "",
                "# Минимальный балл (из 100) для победы в базаре",
                "min_win_score=" + Math.max(1, Math.min(100, minWinScore)),
                "",
                "# Озвучка голосом (TTS) для моба и Смотрящего",
                "tts_enabled=" + ttsEnabled,
                "tts_voice=" + (ttsVoice == null || ttsVoice.isBlank() ? "auto" : ttsVoice.trim()),
                "tts_rate=" + Math.max(-30, Math.min(50, ttsRate)),
                "",
                "# Тестовый режим без API ключа (если true — судья ставит случайный балл без нейросети)",
                "mock_mode=" + mockMode
            );
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            BazarMod.LOGGER.error("Failed to write bazar_mod.properties", e);
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
