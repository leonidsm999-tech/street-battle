package com.bazarmod;

import com.google.gson.*;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class GeminiJudgeClient {
    public record JudgeVerdict(
            boolean win,
            int score,
            List<String> usedWords,
            String judgeComment,
            String mobReaction
    ) {}

    private static final Gson GSON = new Gson();

    public static CompletableFuture<JudgeVerdict> evaluateAsync(
            String mobName,
            String mobClaim,
            List<String> requiredWords,
            byte[] wavData
    ) {
        BazarConfig cfg = BazarConfig.get();

        if (cfg.mockMode || cfg.apiKey == null || cfg.apiKey.isBlank()) {
            return CompletableFuture.completedFuture(buildFallbackVerdict(cfg, requiredWords));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10));

                if (!cfg.proxyHost.isBlank() && cfg.proxyPort > 0) {
                    clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(cfg.proxyHost, cfg.proxyPort)));
                }

                HttpClient client = clientBuilder.build();

                String prompt = String.format(
                        "Ты — жёсткий, беспристрастный «Смотрящий» (пахан и судья уличных и тюремных разборок) в мире Minecraft.\n" +
                        "Игрок схлестнулся на стрелке с мобом (%s), который кинул ему предъяву: «%s».\n" +
                        "У игрока было %d секунд, чтобы «перебазарить» моба по воровским и пацанским понятиям, используя обязательные слова из суфлера: [%s].\n\n" +
                        "Прослушай аудиозапись ответа игрока и оцени его по 3 критериям:\n" +
                        "1. Напор, дерзость и уверенность в голосе (не зассал ли, не мямлил ли, не извинялся ли, давил ли как настоящий пахан).\n" +
                        "2. Использование слов из суфлера (сколько слов из списка игрок реально произнес и насколько угарно вплел в речь; учитывай склонения).\n" +
                        "3. Жёсткость, юмор и креатив наезда (насколько смешно и унизительно он опустил моба).\n\n" +
                        "Если на записи тишина, блеяние или неуверенное мямленье — ставь низкий балл (5-25) и win=false.\n" +
                        "Отвечай живым дворовым, тюремным и воровским слэнгом (допускается лёгкий мат для колорита, как в угарных стримах).\n" +
                        "Верни ответ СТРОГО в формате JSON со следующими полями:\n" +
                        "{\n" +
                        "  \"win\": boolean (true если авторитет >= %d, иначе false),\n" +
                        "  \"score\": integer (от 0 до 100),\n" +
                        "  \"used_words\": [\"список слов из суфлера, которые игрок реально сказал\"],\n" +
                        "  \"judge_comment\": \"Короткий (1-2 предложения) едкий комментарий Смотрящего в блатном/дворовом стиле с оценкой его базара\",\n" +
                        "  \"mob_reaction\": \"Короткая (1 предложение) финальная фраза моба перед тем как слиться или пробить игроку в щи\"\n" +
                        "}",
                        mobName,
                        mobClaim,
                        cfg.battleDurationSec,
                        String.join(", ", requiredWords),
                        cfg.minWinScore
                );

                String base64Audio = Base64.getEncoder().encodeToString(wavData);

                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", prompt);

                JsonObject inlineData = new JsonObject();
                inlineData.addProperty("mime_type", "audio/wav");
                inlineData.addProperty("data", base64Audio);

                JsonObject audioPart = new JsonObject();
                audioPart.add("inline_data", inlineData);

                JsonArray parts = new JsonArray();
                parts.add(textPart);
                parts.add(audioPart);

                JsonObject contentObj = new JsonObject();
                contentObj.add("parts", parts);

                JsonArray contents = new JsonArray();
                contents.add(contentObj);

                JsonObject genConfig = new JsonObject();
                genConfig.addProperty("responseMimeType", "application/json");
                genConfig.addProperty("temperature", 0.95);

                JsonObject requestBody = new JsonObject();
                requestBody.add("contents", contents);
                requestBody.add("generationConfig", genConfig);

                String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                        + cfg.model + ":generateContent?key=" + cfg.apiKey;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(25))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody), StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    BazarMod.LOGGER.error("Gemini API error ({}): {}", response.statusCode(), response.body());
                    return new JudgeVerdict(
                            false,
                            0,
                            List.of(),
                            "Смотрящий не дозвонился до центра (Ошибка API " + response.statusCode() + "). Проверь ключ или прокси!",
                            "Чё, связь отрубили? Расходимся!"
                    );
                }

                JsonObject respJson = JsonParser.parseString(response.body()).getAsJsonObject();
                String rawText = respJson.getAsJsonArray("candidates")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("content")
                        .getAsJsonArray("parts")
                        .get(0).getAsJsonObject()
                        .get("text").getAsString();

                rawText = stripMarkdownFences(rawText);
                JsonObject verdictJson = JsonParser.parseString(rawText).getAsJsonObject();

                int score = verdictJson.has("score") ? verdictJson.get("score").getAsInt() : 0;
                boolean win = verdictJson.has("win") ? verdictJson.get("win").getAsBoolean() : (score >= cfg.minWinScore);

                List<String> used = new ArrayList<>();
                if (verdictJson.has("used_words") && verdictJson.get("used_words").isJsonArray()) {
                    verdictJson.getAsJsonArray("used_words").forEach(el -> used.add(el.getAsString()));
                }

                String comment = verdictJson.has("judge_comment")
                        ? verdictJson.get("judge_comment").getAsString()
                        : "Базар принят по понятиям.";
                String reaction = verdictJson.has("mob_reaction")
                        ? verdictJson.get("mob_reaction").getAsString()
                        : (win ? "Всё-всё, братан, рамсы попутал, ухожу..." : "Слабовато кукарекаешь, держи в щи!");

                return new JudgeVerdict(win, score, used, comment, reaction);

            } catch (Exception e) {
                BazarMod.LOGGER.error("Failed to call Gemini API", e);
                return new JudgeVerdict(
                        false,
                        0,
                        List.of(),
                        "Сбой связи со Смотрящим: " + e.getClass().getSimpleName(),
                        "Технические шоколадки, братан!"
                );
            }
        });
    }

    private static JudgeVerdict buildFallbackVerdict(BazarConfig cfg, List<String> words) {
        if (!cfg.mockMode) {
            return new JudgeVerdict(
                    false,
                    0,
                    List.of(),
                    "Не задан api_key! Нажми [K] в игре и впиши ключ Gemini (или включи тестовый режим).",
                    "Без ключа малява не принимается!"
            );
        }
        int score = 40 + new Random().nextInt(55);
        boolean win = score >= cfg.minWinScore;
        List<String> used = words.subList(0, Math.min(2, words.size()));
        return new JudgeVerdict(
                win,
                score,
                used,
                "[ТЕСТ] Напор чёткий, слова вплёл как по маслу, чисто ровный пацан!",
                win ? "Всё-всё, понял, ухожу под шконку..." : "Не вывез ты базар, держи леща!"
        );
    }

    private static String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
