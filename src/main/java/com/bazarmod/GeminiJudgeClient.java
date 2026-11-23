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
            String playerTranscript,
            String bestPunch,
            String judgeComment,
            String mobReaction
    ) {}

    public record ConnectionTestResult(boolean ok, String message) {}

    private static final Gson GSON = new Gson();
    private static final String STABLE_FALLBACK_MODEL = "gemini-3.8-flash";

    private static volatile HttpClient cachedClient = null;
    private static volatile String cachedProxySignature = "";

    private static synchronized HttpClient getSharedClient(String proxyHost, int proxyPort) {
        String sig = (proxyHost == null ? "" : proxyHost.trim()) + ":" + proxyPort;
        if (cachedClient == null || !sig.equals(cachedProxySignature)) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(25));
            if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost.trim(), proxyPort)));
            }
            cachedClient = builder.build();
            cachedProxySignature = sig;
        }
        return cachedClient;
    }

    private static boolean isRetryableStatus(int code) {
        return code == 503 || code == 429 || code == 500 || code == 502 || code == 504;
    }

    private static String extractApiErrorMessage(int statusCode, String body) {
        String msg = "HTTP " + statusCode;
        try {
            JsonObject errJson = JsonParser.parseString(body).getAsJsonObject();
            if (errJson.has("error") && errJson.getAsJsonObject("error").has("message")) {
                msg = statusCode + ": " + errJson.getAsJsonObject("error").get("message").getAsString();
            }
        } catch (Exception ignored) {}
        if (statusCode == 503) {
            return "503 (Серверы Google перегружены — модель временно занята)";
        }
        return msg;
    }

    private static HttpResponse<String> sendWithRetries(
            HttpClient client,
            String apiKey,
            String primaryModel,
            String jsonBody,
            int maxAttempts,
            Duration requestTimeout
    ) throws Exception {
        String currentModel = primaryModel;
        HttpResponse<String> lastResponse = null;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                        + currentModel + ":generateContent?key=" + apiKey;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                lastResponse = response;

                if (response.statusCode() == 200) {
                    return response;
                }

                if (isRetryableStatus(response.statusCode()) && attempt < maxAttempts) {
                    BazarMod.LOGGER.warn("Gemini API returned {} on model {} (attempt {}/{}), retrying...",
                            response.statusCode(), currentModel, attempt, maxAttempts);

                    // If a preview model is overloaded (503) on the last retry, fall back to stable gemini-3.8-flash
                    if (response.statusCode() == 503 && attempt == maxAttempts - 1 && !STABLE_FALLBACK_MODEL.equals(currentModel)) {
                        BazarMod.LOGGER.info("Switching fallback attempt to stable model: {}", STABLE_FALLBACK_MODEL);
                        currentModel = STABLE_FALLBACK_MODEL;
                    }

                    Thread.sleep(1500L * attempt);
                    continue;
                }

                return response;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    BazarMod.LOGGER.warn("Gemini API network error ({}) on attempt {}/{}, retrying...",
                            e.getClass().getSimpleName(), attempt, maxAttempts);
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }

        if (lastResponse != null) {
            return lastResponse;
        }
        throw lastException != null ? lastException : new RuntimeException("Unknown Gemini connection failure");
    }

    public static CompletableFuture<ConnectionTestResult> testConnectionAsync(
            String rawApiKey,
            String rawModel,
            String rawProxyHost,
            int proxyPort
    ) {
        String apiKey = rawApiKey == null ? "" : rawApiKey.trim();
        String model = (rawModel == null || rawModel.isBlank()) ? STABLE_FALLBACK_MODEL : rawModel.trim();
        String proxyHost = rawProxyHost == null ? "" : rawProxyHost.trim();

        if (apiKey.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new ConnectionTestResult(false, "❌ Вставь API ключ в поле выше!")
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            try {
                HttpClient client = getSharedClient(proxyHost, proxyPort);

                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", "Ответь одним словом: ОК");

                JsonArray parts = new JsonArray();
                parts.add(textPart);

                JsonObject contentObj = new JsonObject();
                contentObj.add("parts", parts);

                JsonArray contents = new JsonArray();
                contents.add(contentObj);

                JsonObject genConfig = new JsonObject();
                genConfig.addProperty("maxOutputTokens", 16);
                genConfig.addProperty("temperature", 0.1);

                JsonObject requestBody = new JsonObject();
                requestBody.add("contents", contents);
                requestBody.add("generationConfig", genConfig);

                HttpResponse<String> response = sendWithRetries(
                        client,
                        apiKey,
                        model,
                        GSON.toJson(requestBody),
                        3,
                        Duration.ofSeconds(30)
                );
                long elapsedMs = System.currentTimeMillis() - startMs;

                if (response.statusCode() == 200) {
                    String reply = "ОК";
                    try {
                        JsonObject respJson = JsonParser.parseString(response.body()).getAsJsonObject();
                        reply = respJson.getAsJsonArray("candidates")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("content")
                                .getAsJsonArray("parts")
                                .get(0).getAsJsonObject()
                                .get("text").getAsString().trim();
                        if (reply.length() > 24) reply = reply.substring(0, 24) + "...";
                    } catch (Exception ignored) {}
                    return new ConnectionTestResult(true, "✅ Связь есть! (" + elapsedMs + " мс) Ответ: " + reply);
                }

                String apiErrMsg = extractApiErrorMessage(response.statusCode(), response.body());
                if (apiErrMsg.length() > 64) {
                    apiErrMsg = apiErrMsg.substring(0, 61) + "...";
                }
                return new ConnectionTestResult(false, "❌ Ошибка " + apiErrMsg);

            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (reason.length() > 54) reason = reason.substring(0, 51) + "...";
                return new ConnectionTestResult(false, "❌ Сбой сети/прокси: " + reason);
            }
        });
    }

    public static CompletableFuture<JudgeVerdict> evaluateAsync(
            String mobName,
            String mobClaim,
            List<String> requiredWords,
            int effectiveWinThreshold,
            byte[] wavData
    ) {
        BazarConfig cfg = BazarConfig.get();

        if (cfg.mockMode || cfg.apiKey == null || cfg.apiKey.isBlank()) {
            return CompletableFuture.completedFuture(buildFallbackVerdict(cfg, effectiveWinThreshold, requiredWords, null));
        }

        String prompt = String.format(
                "Ты — жёсткий, едкий «Смотрящий» (пахан и судья дворовых и блатных словесных разборок) в мире Minecraft.\n" +
                "Игрок схлестнулся на стрелке с мобом (%s), который кинул ему предъяву: «%s».\n" +
                "У игрока было %d секунд, чтобы «перебазарить» моба по-пацански, используя обязательные слова из суфлера: [%s].\n\n" +
                "Прослушай аудиозапись ответа игрока и сделай разбор баттла по 3 критериям:\n" +
                "1. Напор, дерзость и уверенность в голосе (не зассал ли, не замямлил ли, давил ли как настоящий авторитет).\n" +
                "2. Использование слов из суфлера (какие слова из списка игрок реально произнес; учитывай склонения и падежи).\n" +
                "3. Жёсткость, угар и острота ответа на предъяву моба.\n\n" +
                "Если на записи тишина или невнятное блеяние — ставь низкий балл (5-25) и win=false.\n" +
                "Отвечай максимально живым дворовым, уличным и блатным слэнгом с сочным матом (бля, нахуй, сука, пиздец, ебать, фраер, терпила, зашквар, баклан) для угара, как в трэш-стримах!\n" +
                "Верни ответ СТРОГО в формате JSON со следующими полями:\n" +
                "{\n" +
                "  \"win\": boolean (true если score >= %d, иначе false),\n" +
                "  \"score\": integer (от 0 до 100),\n" +
                "  \"player_transcript\": \"Точная расшифровка того, что игрок сказал в микрофон (если тишина — напиши '[Тишина / неразборчиво]')\",\n" +
                "  \"used_words\": [\"список слов из суфлера, которые игрок реально сказал\"],\n" +
                "  \"best_punch\": \"Самая разъёбная фраза/панч игрока за раунд (или главная причина слива, если он затупил)\",\n" +
                "  \"judge_comment\": \"Разбор баттла (2-3 предложения) с матом и пацанским слэнгом: разбери подачу, ответ на предъяву и как вплёл слова\",\n" +
                "  \"mob_reaction\": \"Короткая (1 предложение) финальная фраза моба с матом/слэнгом перед тем как слиться или уебать игрока\"\n" +
                "}",
                mobName,
                mobClaim,
                cfg.battleDurationSec,
                String.join(", ", requiredWords),
                effectiveWinThreshold
        );

        String base64Audio = Base64.getEncoder().encodeToString(wavData);
        return sendGeminiRequest(cfg, prompt, base64Audio, null, effectiveWinThreshold);
    }

    public static CompletableFuture<JudgeVerdict> evaluateTextAsync(
            String mobName,
            String mobClaim,
            List<String> requiredWords,
            int effectiveWinThreshold,
            String playerText
    ) {
        BazarConfig cfg = BazarConfig.get();

        if (cfg.mockMode || cfg.apiKey == null || cfg.apiKey.isBlank()) {
            return CompletableFuture.completedFuture(buildFallbackVerdict(cfg, effectiveWinThreshold, requiredWords, playerText));
        }

        String prompt = String.format(
                "Ты — жёсткий, едкий «Смотрящий» (пахан и судья дворовых и блатных словесных разборок) в мире Minecraft.\n" +
                "Игрок схлестнулся на стрелке с мобом (%s), который кинул ему предъяву: «%s».\n" +
                "Игрок ответил текстом в чат (вместо микрофона), пытаясь «перебазарить» моба по-пацански, используя обязательные слова из суфлера: [%s].\n\n" +
                "Вот ответ игрока: «%s»\n\n" +
                "Оцени его ответ по 3 критериям:\n" +
                "1. Напор, дерзость и пацанский стиль (не зассал ли, не извинялся ли, давил ли как настоящий авторитет).\n" +
                "2. Использование слов из суфлера (какие слова из списка игрок реально написал; учитывай склонения и падежи).\n" +
                "3. Жёсткость, угар и острота ответа на предъяву моба.\n\n" +
                "Если игрок написал отписку в пару букв или бред — ставь низкий балл (5-25) и win=false.\n" +
                "Отвечай максимально живым дворовым, уличным и блатным слэнгом с сочным матом (бля, нахуй, сука, пиздец, ебать, фраер, терпила, зашквар, баклан) для угара!\n" +
                "Верни ответ СТРОГО в формате JSON со следующими полями:\n" +
                "{\n" +
                "  \"win\": boolean (true если score >= %d, иначе false),\n" +
                "  \"score\": integer (от 0 до 100),\n" +
                "  \"player_transcript\": \"[Чат] %s\",\n" +
                "  \"used_words\": [\"список слов из суфлера, которые игрок реально использовал\"],\n" +
                "  \"best_punch\": \"Самая разъёбная фраза/панч игрока за раунд (или главная причина слива, если он затупил)\",\n" +
                "  \"judge_comment\": \"Разбор баттла (2-3 предложения) с матом и пацанским слэнгом: разбери текст, ответ на предъяву и как вплёл слова\",\n" +
                "  \"mob_reaction\": \"Короткая (1 предложение) финальная фраза моба с матом/слэнгом перед тем как слиться или уебать игрока\"\n" +
                "}",
                mobName,
                mobClaim,
                String.join(", ", requiredWords),
                playerText,
                effectiveWinThreshold,
                playerText.replace("\"", "'")
        );

        return sendGeminiRequest(cfg, prompt, null, "[Чат] " + playerText, effectiveWinThreshold);
    }

    private static CompletableFuture<JudgeVerdict> sendGeminiRequest(
            BazarConfig cfg,
            String prompt,
            String base64AudioOrNull,
            String fallbackTranscriptOrNull,
            int effectiveWinThreshold
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient client = getSharedClient(cfg.proxyHost, cfg.proxyPort);

                JsonObject textPart = new JsonObject();
                textPart.addProperty("text", prompt);

                JsonArray parts = new JsonArray();
                parts.add(textPart);

                if (base64AudioOrNull != null) {
                    JsonObject inlineData = new JsonObject();
                    inlineData.addProperty("mime_type", "audio/wav");
                    inlineData.addProperty("data", base64AudioOrNull);

                    JsonObject audioPart = new JsonObject();
                    audioPart.add("inline_data", inlineData);
                    parts.add(audioPart);
                }

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

                String model = (cfg.model == null || cfg.model.isBlank()) ? STABLE_FALLBACK_MODEL : cfg.model.trim();
                HttpResponse<String> response = sendWithRetries(
                        client,
                        cfg.apiKey.trim(),
                        model,
                        GSON.toJson(requestBody),
                        3,
                        Duration.ofSeconds(60)
                );

                if (response.statusCode() != 200) {
                    BazarMod.LOGGER.error("Gemini API error ({}): {}", response.statusCode(), response.body());
                    String errDetail = extractApiErrorMessage(response.statusCode(), response.body());
                    return new JudgeVerdict(
                            false,
                            0,
                            List.of(),
                            fallbackTranscriptOrNull != null ? fallbackTranscriptOrNull : "[Ошибка сети]",
                            "Нет связи с судьёй",
                            "Смотрящий не дозвонился до центра (" + errDetail + "). Попробуй ещё раз через пару секунд!",
                            "Чё, связь отрубили? Расходимся нахуй!"
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
                boolean win = verdictJson.has("win") ? verdictJson.get("win").getAsBoolean() : (score >= effectiveWinThreshold);

                List<String> used = new ArrayList<>();
                if (verdictJson.has("used_words") && verdictJson.get("used_words").isJsonArray()) {
                    verdictJson.getAsJsonArray("used_words").forEach(el -> used.add(el.getAsString()));
                }

                String transcript = verdictJson.has("player_transcript")
                        ? verdictJson.get("player_transcript").getAsString()
                        : (fallbackTranscriptOrNull != null ? fallbackTranscriptOrNull : "[Расшифровка недоступна]");
                String bestPunch = verdictJson.has("best_punch")
                        ? verdictJson.get("best_punch").getAsString()
                        : "Без ярких панчей";
                String comment = verdictJson.has("judge_comment")
                        ? verdictJson.get("judge_comment").getAsString()
                        : "Базар принят по понятиям.";
                String reaction = verdictJson.has("mob_reaction")
                        ? verdictJson.get("mob_reaction").getAsString()
                        : (win ? "Всё-всё, братан, рамсы попутал, съёбываю..." : "Слабовато кукарекаешь, держи в ебало!");

                return new JudgeVerdict(win, score, used, transcript, bestPunch, comment, reaction);

            } catch (Exception e) {
                BazarMod.LOGGER.error("Failed to call Gemini API", e);
                return new JudgeVerdict(
                        false,
                        0,
                        List.of(),
                        fallbackTranscriptOrNull != null ? fallbackTranscriptOrNull : "[Ошибка запроса]",
                        "Сбой связи",
                        "Сбой связи со Смотрящим (" + e.getClass().getSimpleName() + "). Проверь интернет или прокси!",
                        "Технические шоколадки, братан!"
                );
            }
        });
    }

    private static JudgeVerdict buildFallbackVerdict(BazarConfig cfg, int threshold, List<String> words, String typedTextOrNull) {
        if (!cfg.mockMode) {
            return new JudgeVerdict(
                    false,
                    0,
                    List.of(),
                    typedTextOrNull != null ? "[Чат] " + typedTextOrNull : "[API ключ не задан]",
                    "Нет ключа",
                    "Не задан api_key! Нажми [K] в игре и впиши ключ Gemini (или включи тестовый режим).",
                    "Без ключа базар не принимается, фраер!"
            );
        }
        int score = 45 + new Random().nextInt(50);
        boolean win = score >= threshold;
        List<String> used = words.subList(0, Math.min(2, words.size()));
        String transcript = typedTextOrNull != null
                ? "[Чат] " + typedTextOrNull
                : "[Тестовый режим — запись микрофона не отправлялась в API]";
        return new JudgeVerdict(
                win,
                score,
                used,
                transcript,
                typedTextOrNull != null ? "«" + typedTextOrNull + "»" : "«Поясни за свой биом, бля, пока я тут порядок не навёл!»",
                "[ТЕСТ] Напор охуенный, слова из суфлера вплёл по красоте, предъяву отбил чётко.",
                win ? "Всё-всё, понял, ухожу без вопросов..." : "Не вывез ты базар, сука, держи леща!"
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
