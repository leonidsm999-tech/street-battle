package com.bazarmod.client;

import com.bazarmod.BazarNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class BazarModClient implements ClientModInitializer {
    private static KeyBinding joinBattleKey;
    private static KeyBinding openConfigKey;
    private static KeyBinding openHistoryKey;
    private static BattleMusicSoundInstance activeMusic;

    private enum BattlePhase {
        NONE,
        UNIFIED_BATTLE, // Combines Mob's full diss AND Player's prompter/mic in one unified window!
        EVALUATING,
        VERDICT
    }

    private static BattlePhase phase = BattlePhase.NONE;
    private static UUID activeShowdownId = null;
    private static int activeMobEntityId = -1;
    private static String currentMobName = "";
    private static List<String> currentMobDissLines = List.of();
    private static List<String> currentWords = List.of();
    private static int playerTurnDurationSec = 15;

    // Progression info shown in battle HUD
    private static int mobCurWins = 0;
    private static int mobReqWins = 3;
    private static String playerRank = "Новичок на районе";
    private static int playerXp = 0;

    private static long battleStartMs = 0L;
    private static long recordingStartMs = 0L;
    private static long battleEndMs = 0L;
    private static boolean micRecordingStarted = false;

    // Friend invite state
    private static UUID pendingInviteId = null;
    private static String pendingInviteText = "";
    private static long inviteExpireMs = 0L;

    // Verdict banner state
    private static BazarNetworking.ShowdownResultPayload lastVerdict = null;

    public static boolean isBattleHudActive() {
        return phase == BattlePhase.UNIFIED_BATTLE || phase == BattlePhase.EVALUATING;
    }

    @Override
    public void onInitializeClient() {
        joinBattleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazarmod.join_battle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.bazarmod"
        ));

        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazarmod.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.bazarmod"
        ));

        openHistoryKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazarmod.open_history",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.bazarmod"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BazarConfigScreen(null));
                }
            }

            while (openHistoryKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BazarHistoryScreen(null));
                }
            }

            while (joinBattleKey.wasPressed()) {
                if (pendingInviteId != null && System.currentTimeMillis() < inviteExpireMs) {
                    ClientPlayNetworking.send(new BazarNetworking.JoinShowdownPayload(pendingInviteId));
                    pendingInviteId = null;
                }
            }

            // After 3.0s read-in, trigger microphone recording while keeping the exact same unified HUD!
            if (phase == BattlePhase.UNIFIED_BATTLE && !micRecordingStarted && System.currentTimeMillis() >= recordingStartMs) {
                beginMicRecording(client);
            }
        });

        // Start showdown -> Launch Unified Battle HUD immediately
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.StartShowdownPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                activeShowdownId = payload.showdownId();
                activeMobEntityId = payload.mobEntityId();
                currentMobName = payload.mobName();
                currentMobDissLines = payload.mobDissLines().isEmpty()
                        ? List.of("Поясни за свой базар, фраер!")
                        : payload.mobDissLines();
                currentWords = payload.promptWords();
                playerTurnDurationSec = payload.durationSec();
                mobCurWins = payload.mobCurrentWins();
                mobReqWins = payload.mobRequiredWins();
                playerRank = payload.playerRank();
                playerXp = payload.playerXp();

                lastVerdict = null;
                micRecordingStarted = false;

                startBattleMusic(client);

                long now = System.currentTimeMillis();
                battleStartMs = now;
                recordingStartMs = now + 3000L; // 3 seconds to read the mob's diss before mic starts recording
                battleEndMs = recordingStartMs + (playerTurnDurationSec * 1000L);
                phase = BattlePhase.UNIFIED_BATTLE;
            });
        });

        // Friend invite
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.InviteFriendPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                pendingInviteId = payload.showdownId();
                pendingInviteText = "Кент " + payload.friendName() + " забился с [" + payload.mobName() + "]! Нажми [G], чтобы вписаться!";
                inviteExpireMs = System.currentTimeMillis() + 10000L;
            });
        });

        // Verdict received -> Save to History & show detailed post-battle breakdown card
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.ShowdownResultPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                stopBattleMusic(client);
                lastVerdict = payload;
                playerRank = payload.rankTitle();
                playerXp = payload.totalXp();
                mobCurWins = payload.mobWinsAfter();
                mobReqWins = payload.mobWinsRequired();

                // Save record to persistent battle history
                BattleHistoryManager.BattleRecord rec = new BattleHistoryManager.BattleRecord();
                rec.mobName = currentMobName;
                rec.mobDissLines = new ArrayList<>(currentMobDissLines);
                rec.promptWords = new ArrayList<>(payload.promptWords());
                rec.usedWords = new ArrayList<>(payload.usedWords());
                rec.playerTranscript = payload.playerTranscript();
                rec.bestPunch = payload.bestPunch();
                rec.judgeComment = payload.judgeComment();
                rec.mobReaction = payload.mobReaction();
                rec.win = payload.win();
                rec.score = payload.score();
                rec.xpGained = payload.xpGained();
                rec.rankTitle = payload.rankTitle();
                BattleHistoryManager.addRecord(rec);

                phase = BattlePhase.VERDICT;
                battleStartMs = System.currentTimeMillis();
                battleEndMs = battleStartMs + 11000L; // 11 seconds to read the breakdown
            });
        });

        // Stop music
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.StopMusicPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> stopBattleMusic(client));
        });

        // Open screen via /bazar config or /bazar history
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.OpenScreenPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                if ("history".equalsIgnoreCase(payload.screenName())) {
                    client.setScreen(new BazarHistoryScreen(client.currentScreen));
                } else {
                    client.setScreen(new BazarConfigScreen(client.currentScreen));
                }
            });
        });

        // Render Unified Battle HUD
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> renderUnifiedBattleHud(drawContext));
    }

    private static void beginMicRecording(MinecraftClient client) {
        micRecordingStarted = true;
        UUID sessionId = activeShowdownId;
        MicrophoneRecorder.recordAsync(playerTurnDurationSec, wavBytes -> {
            client.execute(() -> {
                if (phase == BattlePhase.UNIFIED_BATTLE) {
                    phase = BattlePhase.EVALUATING;
                    battleStartMs = System.currentTimeMillis();
                    battleEndMs = battleStartMs + 18000L;
                }
                stopBattleMusic(client);
                if (sessionId != null) {
                    ClientPlayNetworking.send(new BazarNetworking.SubmitAudioPayload(sessionId, wavBytes));
                }
            });
        });
    }

    private static void startBattleMusic(MinecraftClient client) {
        stopBattleMusic(client);
        activeMusic = new BattleMusicSoundInstance();
        client.getSoundManager().play(activeMusic);
    }

    private static void stopBattleMusic(MinecraftClient client) {
        if (activeMusic != null) {
            activeMusic.stopMusic();
            client.getSoundManager().stop(activeMusic);
            activeMusic = null;
        }
    }

    private static void renderUnifiedBattleHud(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        TextRenderer tr = client.textRenderer;
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        long now = System.currentTimeMillis();

        if (phase != BattlePhase.NONE && now > battleEndMs && !(phase == BattlePhase.UNIFIED_BATTLE && !micRecordingStarted)) {
            phase = BattlePhase.NONE;
        }

        // --- 1. UNIFIED BATTLE WINDOW (Mob's Diss + Player's Words & Mic together!) ---
        if (phase == BattlePhase.UNIFIED_BATTLE) {
            int boxW = Math.min(screenW - 20, 440);
            int boxH = 186;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;

            boolean isRecording = micRecordingStarted;
            int borderColor = isRecording ? 0xFF00E5FF : 0xFFFFAA00;
            int headerBg = isRecording ? 0xBB0C2E3E : 0xBB3A220C;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE2101118);
            ctx.drawBorder(x1, y1, boxW, boxH, borderColor);
            ctx.fill(x1 + 1, y1 + 1, x1 + boxW - 1, y1 + 22, headerBg);

            // Header with Timer & Rank/Mastery
            String statusText;
            if (!isRecording) {
                double prepLeft = Math.max(0.0, (recordingStartMs - now) / 1000.0);
                statusText = String.format(Locale.US, "🔥 СТРЕЛКА С %s  |  ЧИТАЙ ПРЕДЪЯВУ (%.1fс)",
                        currentMobName.toUpperCase(Locale.ROOT), prepLeft);
            } else {
                double talkLeft = Math.max(0.0, (battleEndMs - now) / 1000.0);
                statusText = String.format(Locale.US, "🎤 ТВОЙ ОТВЕТ! ПЕРЕБАЗАРЬ %s  |  ⏱ %.1fс",
                        currentMobName.toUpperCase(Locale.ROOT), talkLeft);
            }
            ctx.drawCenteredTextWithShadow(tr, Text.literal(statusText), centerX, y1 + 7, isRecording ? 0xFF55FFFF : 0xFFFFD700);

            // Left Column: 3D Mob Avatar + Mastery Progress
            int avatarW = 72;
            int avatarH = 92;
            int avX1 = x1 + 8;
            int avY1 = y1 + 27;
            ctx.fill(avX1, avY1, avX1 + avatarW, avY1 + avatarH, 0x66000000);
            ctx.drawBorder(avX1, avY1, avatarW, avatarH, 0x77FF6666);
            renderMobAvatar(client, ctx, avX1, avY1, avX1 + avatarW, avY1 + avatarH - 14);

            String masteryStr = "Побед: " + mobCurWins + "/" + mobReqWins;
            ctx.drawCenteredTextWithShadow(tr, Text.literal(masteryStr), avX1 + avatarW / 2, avY1 + avatarH - 11, 0xFFFFAA00);

            // Top-Right Box: FULL MOB DISS (always visible so player can respond to every line!)
            int dissX = avX1 + avatarW + 8;
            int dissW = boxW - avatarW - 24;
            ctx.fill(dissX, avY1, dissX + dissW, avY1 + avatarH, 0x55221218);
            ctx.drawBorder(dissX, avY1, dissW, avatarH, 0x55FF5555);

            ctx.drawTextWithShadow(tr, Text.literal("💬 " + currentMobName + " говорит:"), dissX + 6, avY1 + 5, 0xFFFF7777);

            int curY = avY1 + 17;
            for (String line : currentMobDissLines) {
                List<OrderedText> wrapped = tr.wrapLines(Text.literal("• " + line), dissW - 12);
                for (OrderedText ot : wrapped) {
                    if (curY + 9 < avY1 + avatarH - 3) {
                        ctx.drawTextWithShadow(tr, ot, dissX + 6, curY, 0xFFFFE082);
                        curY += 10;
                    }
                }
                curY += 2;
            }

            // Divider line between Mob's Round and Player's Round
            int divY = avY1 + avatarH + 5;
            ctx.fill(x1 + 8, divY, x1 + boxW - 8, divY + 1, 0x558888AA);

            // Bottom Section: Player's Prompter Words + Live Mic Bar
            int playerY = divY + 5;
            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("ВПЛЕТИ В СВОЙ ОТВЕТ СЛОВА (Ранг: " + playerRank + "):"),
                    centerX, playerY, 0xFFBBBBCC);

            String wordsLine = "[ " + String.join(" ]  [ ", currentWords).toUpperCase(Locale.ROOT) + " ]";
            List<OrderedText> wordsWrapped = tr.wrapLines(Text.literal(wordsLine), boxW - 20);
            int wY = playerY + 12;
            for (int i = 0; i < Math.min(2, wordsWrapped.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, wordsWrapped.get(i), centerX, wY, 0xFF55FF55);
                wY += 11;
            }

            // Live Microphone Level Bar
            float level = isRecording ? MicrophoneRecorder.getCurrentLevel() : 0.0f;
            int barW = boxW - 28;
            int barH = 9;
            int barX = centerX - barW / 2;
            int barY = y1 + boxH - 23;
            ctx.fill(barX, barY, barX + barW, barY + barH, 0xFF20202A);
            int filledW = (int) (barW * level);
            int barColor = level > 0.65f ? 0xFFFF5555 : (level > 0.15f ? 0xFF55FF55 : 0xFF5599FF);
            ctx.fill(barX, barY, barX + filledW, barY + barH, barColor);
            ctx.drawBorder(barX, barY, barW, barH, 0xFF666677);

            String micHint;
            if (!isRecording) {
                micHint = "⏳ Приготовься! Запись начнётся через секунду...";
            } else if (level > 0.08f) {
                micHint = "🎙 Микрофон пишет! Дави напором и отвечай на предъяву!";
            } else {
                micHint = "🎙 Говори в микрофон громче и увереннее!";
            }
            ctx.drawCenteredTextWithShadow(tr, Text.literal(micHint), centerX, y1 + boxH - 12, 0xFFDDDDDD);
            return;
        }

        // --- 2. EVALUATING ---
        if (phase == BattlePhase.EVALUATING) {
            int boxW = Math.min(screenW - 24, 340);
            int boxH = 54;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE2141218);
            ctx.drawBorder(x1, y1, boxW, boxH, 0xFFFFAA00);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("⏳ СМОТРЯЩИЙ ДЕЛАЕТ РАЗБОР БАТТЛА..."), centerX, y1 + 14, 0xFFFFD700);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("Нейросеть оценивает панчи, подачу и слова суфлера"), centerX, y1 + 30, 0xFFBBBBBB);
            return;
        }

        // --- 3. POST-BATTLE BREAKDOWN CARD ---
        if (phase == BattlePhase.VERDICT && lastVerdict != null) {
            int boxW = Math.min(screenW - 20, 440);
            int boxH = 166;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;
            int borderColor = lastVerdict.win() ? 0xFF55FF55 : 0xFFFF5555;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE4101016);
            ctx.drawBorder(x1, y1, boxW, boxH, borderColor);

            String header = (lastVerdict.win() ? "✅ ПОБЕДА В БАЗАРЕ" : "❌ ТЕБЯ ПЕРЕБАЗАРИЛИ")
                    + "  |  СЧЁТ: " + lastVerdict.score() + "/100  (+" + lastVerdict.xpGained() + " XP)";
            ctx.drawCenteredTextWithShadow(tr, Text.literal(header), centerX, y1 + 7, borderColor);

            String progLine = "👑 Ранг: " + lastVerdict.rankTitle()
                    + "  |  Прогресс против " + currentMobName + ": " + lastVerdict.mobWinsAfter() + "/" + lastVerdict.mobWinsRequired()
                    + (lastVerdict.newlyDominated() ? " §d(БОИТСЯ ТЕБЯ!)" : "");
            ctx.drawCenteredTextWithShadow(tr, Text.literal(progLine), centerX, y1 + 20, 0xFF55FFFF);

            // Used vs Missed words
            Set<String> usedLower = new HashSet<>();
            for (String w : lastVerdict.usedWords()) {
                usedLower.add(w.toLowerCase(Locale.ROOT).trim());
            }
            StringBuilder wordStatus = new StringBuilder("Слова: ");
            for (String pw : lastVerdict.promptWords()) {
                boolean hit = usedLower.stream().anyMatch(u -> u.contains(pw.toLowerCase(Locale.ROOT)) || pw.toLowerCase(Locale.ROOT).contains(u));
                wordStatus.append(hit ? "§a[✓ " + pw + "] " : "§7[✗ " + pw + "] ");
            }
            ctx.drawCenteredTextWithShadow(tr, Text.literal(wordStatus.toString().trim()), centerX, y1 + 34, 0xFFFFFFFF);

            int curY = y1 + 49;
            // Best punch / key moment
            List<OrderedText> punchLines = tr.wrapLines(Text.literal("🎯 Ключевой момент: " + lastVerdict.bestPunch()), boxW - 22);
            for (int i = 0; i < Math.min(2, punchLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, punchLines.get(i), centerX, curY, 0xFF88FF88);
                curY += 11;
            }
            curY += 2;

            // Judge breakdown
            List<OrderedText> judgeLines = tr.wrapLines(Text.literal("⚖ Разбор Смотрящего: " + lastVerdict.judgeComment()), boxW - 22);
            for (int i = 0; i < Math.min(3, judgeLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, judgeLines.get(i), centerX, curY, 0xFFFFFFFF);
                curY += 11;
            }
            curY += 2;

            // Mob final reaction
            List<OrderedText> mobLines = tr.wrapLines(Text.literal(currentMobName + ": «" + lastVerdict.mobReaction() + "»"), boxW - 22);
            for (int i = 0; i < Math.min(2, mobLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, mobLines.get(i), centerX, curY, 0xFFFFD700);
                curY += 11;
            }

            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("Нажми [H] для полного разбора с расшифровкой и истории баттлов"),
                    centerX, y1 + boxH - 12, 0xFF9999AA);
            return;
        }

        // --- 4. FRIEND INVITE BANNER ---
        if (pendingInviteId != null && now < inviteExpireMs) {
            int boxW = Math.min(screenW - 20, 380);
            int x1 = centerX - boxW / 2;
            ctx.fill(x1, 12, x1 + boxW, 34, 0xCC181828);
            ctx.drawBorder(x1, 12, boxW, 22, 0xFF55FFFF);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(pendingInviteText), centerX, 19, 0xFF55FFFF);
        }
    }

    private static void renderMobAvatar(MinecraftClient client, DrawContext ctx, int x1, int y1, int x2, int y2) {
        if (client.world == null || activeMobEntityId < 0) return;
        Entity entity = client.world.getEntityById(activeMobEntityId);
        if (entity instanceof LivingEntity living) {
            try {
                float height = Math.max(0.6f, living.getHeight());
                int scale = (int) Math.max(14, Math.min(34, 48.0f / height));
                InventoryScreen.drawEntity(ctx, x1, y1, x2, y2, scale, 0.0625f, (x1 + x2) / 2.0f - 15.0f, (y1 + y2) / 2.0f, living);
            } catch (Throwable ignored) {
            }
        }
    }
}
