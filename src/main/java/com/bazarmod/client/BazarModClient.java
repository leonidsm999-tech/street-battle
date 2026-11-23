package com.bazarmod.client;

import com.bazarmod.BazarNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
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
        VS_INTRO,      // Animated split-screen VS splash
        MOB_ROUND,     // Mob's round: big cinematic subtitles at the bottom
        PLAYER_ROUND,  // Player's round: clean row of neon word pills + pulsing mic line
        EVALUATING,
        VERDICT
    }

    private static BattlePhase phase = BattlePhase.NONE;
    private static UUID activeShowdownId = null;
    private static int activeMobEntityId = -1;
    private static String currentMobName = "";
    private static String currentMobNickname = "";
    private static String currentTierBadge = "ДВОРОВЫЙ";
    private static List<String> currentMobDissLines = List.of();
    private static List<String> currentWords = List.of();
    private static int playerTurnDurationSec = 15;

    private static int mobCurWins = 0;
    private static int mobReqWins = 3;
    private static String playerRank = "Новичок на районе";
    private static int playerXp = 0;

    private static long phaseStartMs = 0L;
    private static long phaseEndMs = 0L;
    private static boolean micRecordingStarted = false;

    // Spectator Support Meter state (0.0 = 100% Mob, 0.5 = 50/50, 1.0 = 100% Player)
    private static float spectatorSupportBase = 0.50f;
    private static long lastSupportTickMs = 0L;

    // Friend invite state
    private static UUID pendingInviteId = null;
    private static String pendingInviteText = "";
    private static long inviteExpireMs = 0L;

    // Verdict banner state
    private static BazarNetworking.ShowdownResultPayload lastVerdict = null;

    public static boolean isBattleHudActive() {
        return phase == BattlePhase.VS_INTRO
                || phase == BattlePhase.MOB_ROUND
                || phase == BattlePhase.PLAYER_ROUND
                || phase == BattlePhase.EVALUATING;
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

            long now = System.currentTimeMillis();

            // Transition 1: VS_INTRO -> MOB_ROUND
            if (phase == BattlePhase.VS_INTRO && now >= phaseEndMs) {
                startMobRoundPhase();
            }

            // Transition 2: MOB_ROUND -> PLAYER_ROUND
            if (phase == BattlePhase.MOB_ROUND && now >= phaseEndMs && !micRecordingStarted) {
                startPlayerRoundPhase(client);
            }
        });

                // Intercept chat messages during battle so players without a mic can type their diss in chat!
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if ((phase == BattlePhase.MOB_ROUND || phase == BattlePhase.PLAYER_ROUND) && activeShowdownId != null) {
                String trimmed = message == null ? "" : message.trim();
                if (!trimmed.isEmpty()) {
                    MicrophoneRecorder.cancel();
                    micRecordingStarted = true;
                    phase = BattlePhase.EVALUATING;
                    phaseStartMs = System.currentTimeMillis();
                    phaseEndMs = phaseStartMs + 70000L;
                    MinecraftClient mc = MinecraftClient.getInstance();
                    stopBattleMusic(mc);
                    ClientPlayNetworking.send(new BazarNetworking.SubmitTextPayload(activeShowdownId, trimmed));
                    return false; // consume chat message so it doesn't spam public chat
                }
            }
            return true;
        });

        // Start showdown -> Launch Animated VS_INTRO
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.StartShowdownPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                activeShowdownId = payload.showdownId();
                activeMobEntityId = payload.mobEntityId();
                currentMobName = payload.mobName();
                currentMobNickname = payload.mobStreetNickname();
                currentTierBadge = payload.tierBadge();
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
                spectatorSupportBase = 0.48f;
                lastSupportTickMs = System.currentTimeMillis();

                startBattleMusic(client);

                long now = System.currentTimeMillis();
                phase = BattlePhase.VS_INTRO;
                phaseStartMs = now;
                phaseEndMs = now + 2800L;
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

        // Verdict received -> Save to History & show post-battle breakdown card
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.ShowdownResultPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                MicrophoneRecorder.cancel();
                stopBattleMusic(client);
                lastVerdict = payload;
                playerRank = payload.rankTitle();
                playerXp = payload.totalXp();
                mobCurWins = payload.mobWinsAfter();
                mobReqWins = payload.mobWinsRequired();
                spectatorSupportBase = payload.win() ? 0.92f : 0.08f;

                BattleHistoryManager.BattleRecord rec = new BattleHistoryManager.BattleRecord();
                rec.mobName = currentMobNickname + " (" + currentMobName + ")";
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
                phaseStartMs = System.currentTimeMillis();
                phaseEndMs = phaseStartMs + 11000L;
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

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> renderBattleHud(drawContext));
    }

    private static void startMobRoundPhase() {
        long now = System.currentTimeMillis();
        long mobRoundMs = Math.max(7500L, currentMobDissLines.size() * 2700L);
        phase = BattlePhase.MOB_ROUND;
        phaseStartMs = now;
        phaseEndMs = now + mobRoundMs;
    }

    private static void startPlayerRoundPhase(MinecraftClient client) {
        micRecordingStarted = true;
        long now = System.currentTimeMillis();
        phase = BattlePhase.PLAYER_ROUND;
        phaseStartMs = now;
        phaseEndMs = now + (playerTurnDurationSec * 1000L);

        UUID sessionId = activeShowdownId;
        MicrophoneRecorder.recordAsync(playerTurnDurationSec, wavBytes -> {
            client.execute(() -> {
                if (phase == BattlePhase.PLAYER_ROUND) {
                    phase = BattlePhase.EVALUATING;
                    phaseStartMs = System.currentTimeMillis();
                    phaseEndMs = phaseStartMs + 70000L;
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

    private static void renderBattleHud(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        TextRenderer tr = client.textRenderer;
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        long now = System.currentTimeMillis();

        if (phase != BattlePhase.NONE && now > phaseEndMs && phase != BattlePhase.VS_INTRO && phase != BattlePhase.MOB_ROUND) {
            phase = BattlePhase.NONE;
        }

        // =========================================================================
        // 1. ANIMATED VS INTRO SPLASH
        // =========================================================================
        if (phase == BattlePhase.VS_INTRO) {
            double totalMs = Math.max(1.0, (double) (phaseEndMs - phaseStartMs));
            double t = Math.max(0.0, Math.min(1.0, (now - phaseStartMs) / totalMs));

            // Slide-in easing (0.0 -> 0.25) and smooth fade-out at end (0.85 -> 1.0)
            double slideProg = Math.min(1.0, t / 0.24);
            double easeOut = 1.0 - Math.pow(1.0 - slideProg, 3.0);
            double fadeOut = t > 0.85 ? Math.max(0.0, 1.0 - (t - 0.85) / 0.15) : 1.0;

            int alphaBg = (int) (0xF2 * fadeOut);
            int alphaSolid = (int) (0xFF * fadeOut);
            if (alphaSolid <= 4) return;

            int leftEdge = (int) (-centerX * (1.0 - easeOut));
            int rightEdge = (int) (centerX + centerX * (1.0 - easeOut));

            // Left navy half & Right crimson half sliding in from sides
            ctx.fill(leftEdge, 0, leftEdge + centerX, screenH, (alphaBg << 24) | 0x0B1938);
            ctx.fill(rightEdge, 0, rightEdge + centerX + 2, screenH, (alphaBg << 24) | 0x3B0B0B);

            // Neon center divider appears once halves meet
            if (easeOut > 0.92) {
                ctx.fill(centerX - 2, 0, centerX, screenH, (alphaSolid << 24) | 0x29B6F6);
                ctx.fill(centerX, 0, centerX + 2, screenH, (alphaSolid << 24) | 0xFF5722);
            }

            // White impact flash right when the panels slam together (t in 0.22..0.34)
            if (t >= 0.22 && t <= 0.34) {
                double flashAlpha = (1.0 - (t - 0.22) / 0.12) * 0.45;
                int fa = (int) (255 * flashAlpha);
                ctx.fill(0, 0, screenW, screenH, (fa << 24) | 0xFFFFFF);
            }

            // --- LEFT SIDE: PLAYER ---
            int leftCenterX = leftEdge + (screenW / 4);
            int boxSize = 56;
            int pBoxX = leftCenterX - boxSize / 2;
            int pBoxY = centerY - 38;

            ctx.fill(pBoxX, pBoxY, pBoxX + boxSize, pBoxY + boxSize, (alphaSolid << 24) | 0x152A55);
            ctx.drawBorder(pBoxX - 2, pBoxY - 2, boxSize + 4, boxSize + 4, (alphaSolid << 24) | 0x29B6F6);
            if (fadeOut > 0.5) {
                renderEntityPortrait(ctx, pBoxX, pBoxY, pBoxX + boxSize, pBoxY + boxSize, client.player, true);
            }

            String playerName = client.player.getName().getString();
            ctx.drawCenteredTextWithShadow(tr, Text.literal(playerName), leftCenterX, pBoxY + boxSize + 8, (alphaSolid << 24) | 0x4FC3F7);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("(" + playerRank + ")"), leftCenterX, pBoxY + boxSize + 20, (alphaSolid << 24) | 0xBBBBCC);

            // --- RIGHT SIDE: MOB ---
            int rightCenterX = rightEdge + (screenW / 4);
            int mBoxY = centerY - 46;
            if (fadeOut > 0.5 && client.world != null && activeMobEntityId >= 0) {
                Entity ent = client.world.getEntityById(activeMobEntityId);
                if (ent instanceof LivingEntity living) {
                    renderEntityPortrait(ctx, rightCenterX - 40, mBoxY, rightCenterX + 40, mBoxY + 66, living, false);
                }
            }

            int textStartY = centerY + 24;
            ctx.drawCenteredTextWithShadow(tr, Text.literal(currentMobNickname), rightCenterX, textStartY, (alphaSolid << 24) | 0xFF9800);
            String vsCountLabel = currentMobNickname.equalsIgnoreCase(currentMobName)
                    ? "(" + Math.min(mobCurWins + 1, mobReqWins) + "/" + mobReqWins + ")"
                    : "(" + currentMobName + " " + Math.min(mobCurWins + 1, mobReqWins) + "/" + mobReqWins + ")";
            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal(vsCountLabel),
                    rightCenterX, textStartY + 12, (alphaSolid << 24) | 0xFFFFFF);

            int badgeW = tr.getWidth(currentTierBadge) + 14;
            int badgeX = rightCenterX - badgeW / 2;
            int badgeY = textStartY + 25;
            ctx.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 13, (alphaSolid << 24) | 0xE67E22);
            ctx.drawBorder(badgeX, badgeY, badgeW, 13, (alphaSolid << 24) | 0x000000);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(currentTierBadge), rightCenterX, badgeY + 3, (alphaSolid << 24) | 0xFFFFFF);

            // --- CENTER: SLAMMING "VS" EMBLEM + 4 PROGRESS DOTS ---
            if (t >= 0.18) {
                double vsProg = Math.min(1.0, (t - 0.18) / 0.16);
                // Elastic slam from 5.2x down to 3.0x
                float vsScale = (float) (3.0 + 2.2 * Math.pow(1.0 - vsProg, 2.0));
                ctx.getMatrices().push();
                ctx.getMatrices().scale(vsScale, vsScale, 1.0f);
                int scaledCenterX = (int) (centerX / vsScale);
                int scaledCenterY = (int) ((centerY - 16) / vsScale);
                ctx.drawCenteredTextWithShadow(tr, Text.literal("VS"), scaledCenterX, scaledCenterY, (alphaSolid << 24) | 0xFFD54F);
                ctx.getMatrices().pop();

                int activeDot = Math.min(3, (int) (t * 4.5));
                int dotSize = 6;
                int dotGap = 5;
                int totalDotsW = 4 * dotSize + 3 * dotGap;
                int dotsStartX = centerX - totalDotsW / 2;
                int dotsY = centerY + 18;
                for (int d = 0; d < 4; d++) {
                    int dx = dotsStartX + d * (dotSize + dotGap);
                    int color = (d <= activeDot) ? ((alphaSolid << 24) | 0xFFD54F) : ((alphaSolid << 24) | 0x424242);
                    ctx.fill(dx, dotsY, dx + dotSize, dotsY + dotSize, color);
                    ctx.drawBorder(dx, dotsY, dotSize, dotSize, (alphaSolid << 24) | 0x111111);
                }
            }
            return;
        }

        // =========================================================================
        // 2 & 3. CINEMATIC SUBTITLES HUD + RIGHT SPECTATOR SUPPORT GAUGE
        // =========================================================================
        if (phase == BattlePhase.MOB_ROUND || phase == BattlePhase.PLAYER_ROUND) {
            boolean isMobTurn = (phase == BattlePhase.MOB_ROUND);
            long totalPhaseMs = Math.max(1L, phaseEndMs - phaseStartMs);
            double remainingSec = Math.max(0.0, (phaseEndMs - now) / 1000.0);
            double progressRatio = Math.max(0.0, Math.min(1.0, (double) (phaseEndMs - now) / totalPhaseMs));

            // --- TOP: SLEEK SHRINKING TIMER BAR & COMPACT VS TITLE ---
            int topBarW = Math.min(screenW - 80, 320);
            int topBarX = centerX - topBarW / 2;
            ctx.fill(topBarX, 8, topBarX + topBarW, 12, 0x88111118);
            int filledTimerW = (int) (topBarW * progressRatio);
            int timerColor = isMobTurn ? 0xFFFF5252 : 0xFF00E5FF;
            ctx.fill(centerX - filledTimerW / 2, 8, centerX + filledTimerW / 2, 12, timerColor);

            String pName = client.player.getName().getString();
            String headerText = String.format(Locale.US, "§b%s  §7VS  §6%s  §8|  §f%.1fс",
                    pName, currentMobNickname, remainingSec);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(headerText), centerX, 16, 0xFFFFFFFF);

            // --- RIGHT SIDE: SWAYING SPECTATOR SUPPORT METER ("Шкала поддержки смотрящих") ---
            updateAndRenderSpectatorSupportGauge(ctx, tr, screenW, screenH, now, isMobTurn);

            // --- BOTTOM: CINEMATIC SUBTITLES (CENTER OF SCREEN STAYS 100% OPEN!) ---
            if (isMobTurn) {
                double mobProg = Math.min(0.999, (double) (now - phaseStartMs) / totalPhaseMs);
                int lineIdx = Math.min(currentMobDissLines.size() - 1, (int) (mobProg * currentMobDissLines.size()));
                String currentLine = currentMobDissLines.get(lineIdx);

                // Speaker tag above subtitle
                String speakerTag = "§6" + currentMobNickname + " §7(" + (lineIdx + 1) + "/" + currentMobDissLines.size() + "):";
                ctx.drawCenteredTextWithShadow(tr, Text.literal(speakerTag), centerX, screenH - 54, 0xFFFFAA00);

                // Big, bold cinematic subtitle with soft dark backing pill
                List<OrderedText> subLines = tr.wrapLines(Text.literal("«" + currentLine + "»"), Math.min(screenW - 90, 420));
                int subY = screenH - 40;
                for (OrderedText ot : subLines) {
                    int lineW = tr.getWidth(ot);
                    ctx.fill(centerX - lineW / 2 - 8, subY - 2, centerX + lineW / 2 + 8, subY + 11, 0x990A0A10);
                    ctx.drawCenteredTextWithShadow(tr, ot, centerX, subY, 0xFFFFF176);
                    subY += 13;
                }
            } else {
                // Player's Round:
                // 1) Last mob punchline shown subtly above so player knows what to clap back at
                String lastMobLine = currentMobDissLines.get(currentMobDissLines.size() - 1);
                String shortMobLine = lastMobLine.length() > 64 ? lastMobLine.substring(0, 61) + "..." : lastMobLine;
                ctx.drawCenteredTextWithShadow(tr,
                        Text.literal("§7Наезд: §f«" + shortMobLine + "»"),
                        centerX, screenH - 58, 0xFFCCCCCC);

                // 2) Single clean horizontal row of big neon word pills!
                renderNeonWordPillsRow(ctx, tr, centerX, screenH - 42, screenW - 95, currentWords);

                // 3) Thin pulsing microphone line at the very bottom
                float level = MicrophoneRecorder.getCurrentLevel();
                int micLineW = Math.min(screenW - 110, 300);
                int micX = centerX - micLineW / 2;
                int micY = screenH - 18;
                ctx.fill(micX, micY, micX + micLineW, micY + 4, 0x77181822);
                int activeW = (int) (micLineW * level);
                int pulseColor = level > 0.65f ? 0xFFFF5252 : (level > 0.12f ? 0xFF69F0AE : 0xFF40C4FF);
                ctx.fill(centerX - activeW / 2, micY, centerX + activeW / 2, micY + 4, pulseColor);

                String micPrompt = level > 0.08f
                        ? "🎙 МИКРОФОН ПИШЕТ — ДАВИ НАПОРОМ!"
                        : "🎙 ГОВОРИ В МИКРОФОН ИЛИ ПИШИ В ЧАТ [T]";
                ctx.drawCenteredTextWithShadow(tr, Text.literal(micPrompt), centerX, screenH - 12, 0xFFDDDDDD);
            }
            return;
        }

        // =========================================================================
        // 4. EVALUATING
        // =========================================================================
        if (phase == BattlePhase.EVALUATING) {
            ctx.fill(centerX - 150, screenH - 44, centerX + 150, screenH - 14, 0xBB101018);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("⏳ СМОТРЯЩИЕ СОВЕЩАЮТСЯ И ДЕЛАЮТ РАЗБОР..."), centerX, screenH - 36, 0xFFFFD54F);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("Оцениваем напор, панчи и слова из суфлера"), centerX, screenH - 24, 0xFFBBBBBB);
            return;
        }

        // =========================================================================
        // 5. POST-BATTLE BREAKDOWN CARD
        // =========================================================================
        if (phase == BattlePhase.VERDICT && lastVerdict != null) {
            int boxW = Math.min(screenW - 20, 440);
            int boxH = 166;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;
            int borderColor = lastVerdict.win() ? 0xFF55FF55 : 0xFFFF5555;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE8101016);
            ctx.drawBorder(x1, y1, boxW, boxH, borderColor);

            String header = (lastVerdict.win() ? "✅ ПОБЕДА В БАЗАРЕ" : "❌ ТЕБЯ ПЕРЕБАЗАРИЛИ")
                    + "  |  СЧЁТ: " + lastVerdict.score() + "/100  (+" + lastVerdict.xpGained() + " XP)";
            ctx.drawCenteredTextWithShadow(tr, Text.literal(header), centerX, y1 + 7, borderColor);

            String progLine = "👑 Ранг: " + lastVerdict.rankTitle()
                    + "  |  Прогресс против " + currentMobName + ": " + lastVerdict.mobWinsAfter() + "/" + lastVerdict.mobWinsRequired()
                    + (lastVerdict.newlyDominated() ? " §d(БОИТСЯ ТЕБЯ!)" : "");
            ctx.drawCenteredTextWithShadow(tr, Text.literal(progLine), centerX, y1 + 20, 0xFF55FFFF);

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
            List<OrderedText> punchLines = tr.wrapLines(Text.literal("🎯 Ключевой момент: " + lastVerdict.bestPunch()), boxW - 22);
            for (int i = 0; i < Math.min(2, punchLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, punchLines.get(i), centerX, curY, 0xFF88FF88);
                curY += 11;
            }
            curY += 2;

            List<OrderedText> judgeLines = tr.wrapLines(Text.literal("⚖ Разбор Смотрящего: " + lastVerdict.judgeComment()), boxW - 22);
            for (int i = 0; i < Math.min(3, judgeLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, judgeLines.get(i), centerX, curY, 0xFFFFFFFF);
                curY += 11;
            }
            curY += 2;

            List<OrderedText> mobLines = tr.wrapLines(Text.literal(currentMobNickname + ": «" + lastVerdict.mobReaction() + "»"), boxW - 22);
            for (int i = 0; i < Math.min(2, mobLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, mobLines.get(i), centerX, curY, 0xFFFFD700);
                curY += 11;
            }

            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("Нажми [H] для полного разбора с расшифровкой и истории баттлов"),
                    centerX, y1 + boxH - 12, 0xFF9999AA);
            return;
        }

        // =========================================================================
        // 6. FRIEND INVITE BANNER
        // =========================================================================
        if (pendingInviteId != null && now < inviteExpireMs) {
            int boxW = Math.min(screenW - 20, 380);
            int x1 = centerX - boxW / 2;
            ctx.fill(x1, 12, x1 + boxW, 34, 0xCC181828);
            ctx.drawBorder(x1, 12, boxW, 22, 0xFF55FFFF);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(pendingInviteText), centerX, 19, 0xFF55FFFF);
        }
    }

    /**
     * Renders the vertical "Шкала поддержки смотрящих" on the right side of the screen.
     * Dynamically sways/wobbles in real time:
     * - Top (Red/Orange) = Spectators leaning toward Mob
     * - Bottom (Cyan/Green) = Spectators leaning toward Player
     */
    private static void updateAndRenderSpectatorSupportGauge(DrawContext ctx, TextRenderer tr, int screenW, int screenH, long now, boolean isMobTurn) {
        long dtMs = Math.min(100L, Math.max(1L, now - lastSupportTickMs));
        lastSupportTickMs = now;
        float dt = dtMs / 1000.0f;

        if (isMobTurn) {
            // During mob's round, support slowly drifts toward the mob (0.30)
            float target = 0.30f;
            spectatorSupportBase += (target - spectatorSupportBase) * (1.8f * dt);
        } else {
            // During player's round, loud/confident voice pushes support toward player (up to 0.88),
            // while silence lets it drift back toward the mob (0.34).
            float mic = MicrophoneRecorder.getCurrentLevel();
            if (mic > 0.12f) {
                spectatorSupportBase = Math.min(0.88f, spectatorSupportBase + (0.28f * mic * dt));
            } else {
                spectatorSupportBase = Math.max(0.28f, spectatorSupportBase - (0.09f * dt));
            }
        }

        // Organic multi-frequency wobble ("пусть шкала виляет")
        double sec = now / 1000.0;
        float wobble = (float) (Math.sin(sec * 5.2) * 0.055 + Math.sin(sec * 11.7) * 0.025);
        float displayedSupport = Math.max(0.05f, Math.min(0.95f, spectatorSupportBase + wobble));

        int gaugeW = 14;
        int gaugeH = Math.min(130, screenH - 110);
        int gx = screenW - 28;
        int gy = (screenH / 2) - (gaugeH / 2);

        // Header above gauge
        ctx.drawCenteredTextWithShadow(tr, Text.literal("ГОЛОСА"), gx + gaugeW / 2, gy - 21, 0xFFFFD54F);
        ctx.drawCenteredTextWithShadow(tr, Text.literal("МОБ"), gx + gaugeW / 2, gy - 10, 0xFFFF5252);

        // Outer frame
        ctx.fill(gx - 2, gy - 2, gx + gaugeW + 2, gy + gaugeH + 2, 0xCC0D0E14);
        ctx.drawBorder(gx - 2, gy - 2, gaugeW + 4, gaugeH + 4, 0xFF666677);

        // Split fill: Top part = Mob (crimson/orange), Bottom part = Player (cyan/emerald)
        int playerHeight = (int) (gaugeH * displayedSupport);
        int mobHeight = gaugeH - playerHeight;

        if (mobHeight > 0) {
            ctx.fill(gx, gy, gx + gaugeW, gy + mobHeight, 0xFFE53935);
        }
        if (playerHeight > 0) {
            ctx.fill(gx, gy + mobHeight, gx + gaugeW, gy + gaugeH, 0xFF00E676);
        }

        // 50/50 center tick mark
        int midY = gy + gaugeH / 2;
        ctx.fill(gx - 3, midY, gx + gaugeW + 3, midY + 1, 0xAAFFFFFF);

        // Animated swaying pointer needle at the split boundary
        int needleY = gy + mobHeight;
        ctx.fill(gx - 5, needleY - 1, gx + gaugeW + 5, needleY + 2, 0xFFFFEB3B);

        // Percentage readout & label below gauge
        int playerPct = Math.round(displayedSupport * 100.0f);
        ctx.drawCenteredTextWithShadow(tr, Text.literal("ТЫ"), gx + gaugeW / 2, gy + gaugeH + 5, 0xFF69F0AE);
        ctx.drawCenteredTextWithShadow(tr, Text.literal(playerPct + "%"), gx + gaugeW / 2, gy + gaugeH + 15, 0xFFFFFFFF);
    }

    /**
     * Renders the required prompt words in a single clean horizontal row of bright neon pills.
     */
    private static void renderNeonWordPillsRow(DrawContext ctx, TextRenderer tr, int centerX, int y, int maxRowWidth, List<String> words) {
        if (words.isEmpty()) return;

        int padX = 7;
        int gap = 6;
        int pillH = 16;

        // Calculate total width to see if we need compact padding
        int totalW = 0;
        for (String w : words) {
            totalW += tr.getWidth(w.toUpperCase(Locale.ROOT)) + padX * 2 + gap;
        }
        totalW -= gap;

        if (totalW > maxRowWidth) {
            padX = 4;
            gap = 4;
            totalW = 0;
            for (String w : words) {
                totalW += tr.getWidth(w.toUpperCase(Locale.ROOT)) + padX * 2 + gap;
            }
            totalW -= gap;
        }

        int curX = centerX - totalW / 2;
        for (String word : words) {
            String upper = word.toUpperCase(Locale.ROOT);
            int wText = tr.getWidth(upper);
            int pillW = wText + padX * 2;

            // Dark translucent pill with bright neon cyan/green border
            ctx.fill(curX, y, curX + pillW, y + pillH, 0xCC0D1B2A);
            ctx.drawBorder(curX, y, pillW, pillH, 0xFF00E5FF);
            ctx.drawTextWithShadow(tr, Text.literal(upper), curX + padX, y + 4, 0xFF69F0AE);

            curX += pillW + gap;
        }
    }

    private static void renderEntityPortrait(DrawContext ctx, int x1, int y1, int x2, int y2, LivingEntity entity, boolean isPlayer) {
        try {
            float height = Math.max(0.6f, entity.getHeight());
            int scale = isPlayer ? 26 : (int) Math.max(18, Math.min(36, 52.0f / height));
            float lookX = isPlayer ? (x1 + x2) / 2.0f + 18.0f : (x1 + x2) / 2.0f - 18.0f;
            float lookY = (y1 + y2) / 2.0f - 6.0f;
            InventoryScreen.drawEntity(ctx, x1, y1, x2, y2, scale, 0.0625f, lookX, lookY, entity);
        } catch (Throwable ignored) {
        }
    }
}
