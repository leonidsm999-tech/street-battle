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
        VS_INTRO,      // Split-screen VS splash like screenshot 1
        MOB_ROUND,     // Mob's round: lines reveal progressively one by one + speech bubble
        PLAYER_ROUND,  // Player's round: bottom grid with mob's lines + peach prompter words + mic bar
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

        // Start showdown -> Launch Split-Screen VS_INTRO first!
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

                startBattleMusic(client);

                long now = System.currentTimeMillis();
                phase = BattlePhase.VS_INTRO;
                phaseStartMs = now;
                phaseEndMs = now + 2600L; // 2.6s dramatic VS splash screen
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
                stopBattleMusic(client);
                lastVerdict = payload;
                playerRank = payload.rankTitle();
                playerXp = payload.totalXp();
                mobCurWins = payload.mobWinsAfter();
                mobReqWins = payload.mobWinsRequired();

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
        // Give generous time for the mob's round (~2.7s per line, minimum 7.5s) so lines reveal progressively!
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
                    phaseEndMs = phaseStartMs + 18000L;
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
        // 1. VS INTRO SCREEN (Full-screen Navy Blue vs Crimson Split like screenshot 1!)
        // =========================================================================
        if (phase == BattlePhase.VS_INTRO) {
            // Left half: Deep Navy Blue; Right half: Deep Crimson Red
            ctx.fill(0, 0, centerX, screenH, 0xF00B1938);
            ctx.fill(centerX, 0, screenW, screenH, 0xF03B0B0B);

            // Center vertical neon divider (Cyan left edge, Orange-Red right edge)
            ctx.fill(centerX - 2, 0, centerX, screenH, 0xFF29B6F6);
            ctx.fill(centerX, 0, centerX + 2, screenH, 0xFFFF5722);

            // --- LEFT SIDE: PLAYER ---
            int leftCenterX = screenW / 4;
            int boxSize = 56;
            int pBoxX = leftCenterX - boxSize / 2;
            int pBoxY = centerY - 38;

            ctx.fill(pBoxX, pBoxY, pBoxX + boxSize, pBoxY + boxSize, 0xFF152A55);
            ctx.drawBorder(pBoxX - 2, pBoxY - 2, boxSize + 4, boxSize + 4, 0xFF29B6F6);
            renderEntityPortrait(ctx, pBoxX, pBoxY, pBoxX + boxSize, pBoxY + boxSize, client.player, true);

            String playerName = client.player.getName().getString();
            ctx.drawCenteredTextWithShadow(tr, Text.literal(playerName), leftCenterX, pBoxY + boxSize + 8, 0xFF4FC3F7);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("(" + playerRank + ")"), leftCenterX, pBoxY + boxSize + 20, 0xFFBBBBCC);

            // --- RIGHT SIDE: MOB ---
            int rightCenterX = (screenW * 3) / 4;
            int mBoxY = centerY - 46;
            if (client.world != null && activeMobEntityId >= 0) {
                Entity ent = client.world.getEntityById(activeMobEntityId);
                if (ent instanceof LivingEntity living) {
                    renderEntityPortrait(ctx, rightCenterX - 40, mBoxY, rightCenterX + 40, mBoxY + 66, living, false);
                }
            }

            int textStartY = centerY + 24;
            ctx.drawCenteredTextWithShadow(tr, Text.literal(currentMobNickname), rightCenterX, textStartY, 0xFFFF9800);
            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("(" + currentMobName + " " + Math.min(mobCurWins + 1, mobReqWins) + "/" + mobReqWins + ")"),
                    rightCenterX, textStartY + 12, 0xFFFFFFFF);

            // Orange Tier Badge Pill (like "МЕЛОЧЬ" in screenshot 1)
            int badgeW = tr.getWidth(currentTierBadge) + 14;
            int badgeX = rightCenterX - badgeW / 2;
            int badgeY = textStartY + 25;
            ctx.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 13, 0xFFE67E22);
            ctx.drawBorder(badgeX, badgeY, badgeW, 13, 0xFF000000);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(currentTierBadge), rightCenterX, badgeY + 3, 0xFFFFFFFF);

            // --- CENTER: BIG BOLD "VS" + 4 ANIMATED DOTS ---
            ctx.getMatrices().push();
            ctx.getMatrices().scale(3.0f, 3.0f, 1.0f);
            int scaledCenterX = (int) (centerX / 3.0f);
            int scaledCenterY = (int) ((centerY - 16) / 3.0f);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("VS"), scaledCenterX, scaledCenterY, 0xFFFFD54F);
            ctx.getMatrices().pop();

            // 4 progress squares under VS
            double introProgress = Math.min(1.0, (double) (now - phaseStartMs) / Math.max(1L, phaseEndMs - phaseStartMs));
            int activeDot = Math.min(3, (int) (introProgress * 4));
            int dotSize = 6;
            int dotGap = 5;
            int totalDotsW = 4 * dotSize + 3 * dotGap;
            int dotsStartX = centerX - totalDotsW / 2;
            int dotsY = centerY + 18;
            for (int d = 0; d < 4; d++) {
                int dx = dotsStartX + d * (dotSize + dotGap);
                int color = (d == activeDot) ? 0xFFFFD54F : 0xFF424242;
                ctx.fill(dx, dotsY, dx + dotSize, dotsY + dotSize, color);
                ctx.drawBorder(dx, dotsY, dotSize, dotSize, 0xFF111111);
            }
            return;
        }

        // =========================================================================
        // 2 & 3. IN-GAME BATTLE HUD (Like Screenshot 2: Top VS Bar + Countdown + Speech Bubble + Bottom Grid)
        // =========================================================================
        if (phase == BattlePhase.MOB_ROUND || phase == BattlePhase.PLAYER_ROUND) {
            boolean isMobTurn = (phase == BattlePhase.MOB_ROUND);

            // --- TOP COMPACT VS HEADER & BIG COUNTDOWN NUMBER ---
            String pName = client.player.getName().getString();
            String topTitle = "§b" + pName + "  §fVS  §6" + currentMobNickname + "  §7[" + currentTierBadge + "]";
            int topW = tr.getWidth(Text.literal(pName + "  VS  " + currentMobNickname + "  [" + currentTierBadge + "]")) + 24;
            ctx.fill(centerX - topW / 2, 4, centerX + topW / 2, 18, 0xCC101016);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(topTitle), centerX, 7, 0xFFFFFFFF);

            // Big countdown digit right below top bar (like "2" in screenshot 2)
            double remSec = Math.max(0.0, (phaseEndMs - now) / 1000.0);
            int secInt = (int) Math.ceil(remSec);
            ctx.getMatrices().push();
            ctx.getMatrices().scale(2.0f, 2.0f, 1.0f);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(String.valueOf(secInt)),
                    (int) (centerX / 2.0f), 11, isMobTurn ? 0xFFFF7043 : 0xFFFFD54F);
            ctx.getMatrices().pop();

            // How many mob lines have appeared so far (progressive reveal during MOB_ROUND, all visible in PLAYER_ROUND)
            int visibleLineCount;
            if (isMobTurn) {
                double mobProg = Math.min(0.999, (double) (now - phaseStartMs) / Math.max(1L, phaseEndMs - phaseStartMs));
                visibleLineCount = Math.min(currentMobDissLines.size(), 1 + (int) (mobProg * currentMobDissLines.size()));
            } else {
                visibleLineCount = currentMobDissLines.size();
            }

            // --- FLOATING SPEECH BUBBLE NEXT TO MOB (like "МС Бе-Бе" bubble in screenshot 2!) ---
            String latestMobLine = currentMobDissLines.get(Math.max(0, visibleLineCount - 1));
            renderMobSpeechBubble(ctx, tr, screenW, screenH, latestMobLine);

            // --- BOTTOM PROMPTER & DISS GRID PANEL (Styled after screenshot 2) ---
            int panelW = Math.min(screenW - 24, 440);
            int panelH = 104;
            int px1 = centerX - panelW / 2;
            int py1 = screenH - panelH - 12;

            // Top-left orange-bordered tab on the bottom box (like "перевод с овечьего" in screenshot 2)
            String tabLabel = isMobTurn
                    ? "раунд моба: " + currentMobNickname.toLowerCase(Locale.ROOT)
                    : "твой выход: вплети слова справа";
            int tabW = tr.getWidth(tabLabel) + 14;
            ctx.fill(px1, py1 - 14, px1 + tabW, py1, 0xEE181820);
            ctx.drawBorder(px1, py1 - 14, tabW, 15, 0xFFE67E22);
            ctx.drawTextWithShadow(tr, Text.literal(tabLabel), px1 + 7, py1 - 11, 0xFFFFCC80);

            // Main bottom container
            ctx.fill(px1, py1, px1 + panelW, py1 + panelH, 0xE612131A);
            ctx.drawBorder(px1, py1, panelW, panelH, isMobTurn ? 0xFFE67E22 : 0xFF29B6F6);

            // Split bottom container into:
            // Left 68% (3 columns x 3 rows): Mob's progressively revealed diss chunks
            // Right 32% (1 column of peach/orange cells): Required prompter words!
            int leftAreaW = (int) (panelW * 0.68);
            int rightAreaW = panelW - leftAreaW - 8;
            int leftStartX = px1 + 5;
            int rightStartX = px1 + leftAreaW + 4;
            int gridTopY = py1 + 5;
            int gridH = panelH - 24; // leave 19px at bottom for mic bar / status

            // Render 3x3 dark cells for Mob's diss lines (progressive reveal!)
            int cols = 3;
            int rows = 3;
            int cellW = (leftAreaW - 8) / cols;
            int cellH = (gridH - 4) / rows;

            for (int r = 0; r < rows; r++) {
                boolean lineRevealed = r < visibleLineCount;
                String[] chunks = lineRevealed ? splitIntoThreeChunks(currentMobDissLines.get(r)) : new String[]{"•", "•", "•"};
                boolean isCurrentLine = isMobTurn && (r == visibleLineCount - 1);

                for (int c = 0; c < cols; c++) {
                    int cx = leftStartX + c * cellW;
                    int cy = gridTopY + r * cellH;
                    int cellBg = isCurrentLine ? 0xFF262230 : 0xFF1A1B24;
                    ctx.fill(cx + 1, cy + 1, cx + cellW - 1, cy + cellH - 1, cellBg);
                    ctx.drawBorder(cx + 1, cy + 1, cellW - 2, cellH - 2, isCurrentLine ? 0x88FF9800 : 0xFF282A36);

                    String cellText = chunks[c];
                    int txtColor = !lineRevealed ? 0xFF555566 : (isCurrentLine ? 0xFFFFD54F : 0xFFDDDDDD);
                    // Fit text inside cell
                    while (tr.getWidth(cellText) > cellW - 6 && cellText.length() > 3) {
                        cellText = cellText.substring(0, cellText.length() - 2) + "…";
                    }
                    ctx.drawCenteredTextWithShadow(tr, Text.literal(cellText), cx + cellW / 2, cy + (cellH - 8) / 2, txtColor);
                }
            }

            // Render Right Column: Peach/Orange Prompter Word Cells (like "белая / я / бе-е-е / везде" in screenshot 2!)
            int wordSlots = Math.max(1, currentWords.size());
            int wCellH = Math.max(12, (gridH - 2) / wordSlots);
            for (int w = 0; w < wordSlots; w++) {
                int wy = gridTopY + w * wCellH;
                int actualH = (w == wordSlots - 1) ? (gridTopY + gridH - wy) : (wCellH - 1);
                // Peach-orange highlight when it's player's turn, muted when mob is still talking
                int pillColor = isMobTurn ? 0xFF5C4033 : (w == wordSlots - 1 ? 0xFFE57373 : 0xFFF4A261);
                int textColor = isMobTurn ? 0xFFCCCCCC : 0xFF111111;

                ctx.fill(rightStartX, wy, rightStartX + rightAreaW, wy + actualH, pillColor);
                ctx.drawBorder(rightStartX, wy, rightAreaW, actualH, 0xFF1A1A22);

                String wordUpper = currentWords.get(w).toUpperCase(Locale.ROOT);
                if (isMobTurn) {
                    ctx.drawCenteredTextWithShadow(tr, Text.literal(wordUpper), rightStartX + rightAreaW / 2, wy + (actualH - 8) / 2, textColor);
                } else {
                    // Dark crisp text on bright peach cell without muddy shadow
                    int tw = tr.getWidth(wordUpper);
                    ctx.drawText(tr, wordUpper, rightStartX + (rightAreaW - tw) / 2, wy + (actualH - 8) / 2, textColor, false);
                }
            }

            // Bottom status & live microphone bar inside the panel
            int barY = py1 + panelH - 15;
            int barW = panelW - 12;
            int barX = px1 + 6;
            ctx.fill(barX, barY, barX + barW, barY + 10, 0xFF1E1E28);

            if (isMobTurn) {
                double mobProg = Math.min(1.0, (double) (now - phaseStartMs) / Math.max(1L, phaseEndMs - phaseStartMs));
                ctx.fill(barX, barY, barX + (int) (barW * mobProg), barY + 10, 0xFFE67E22);
                ctx.drawCenteredTextWithShadow(tr,
                        Text.literal("👂 Слушай наезд моба (" + visibleLineCount + "/" + currentMobDissLines.size() + ") и готовь ответку..."),
                        centerX, barY + 1, 0xFFFFFFFF);
            } else {
                float level = MicrophoneRecorder.getCurrentLevel();
                int barColor = level > 0.65f ? 0xFFFF5555 : (level > 0.15f ? 0xFF55FF55 : 0xFF29B6F6);
                ctx.fill(barX, barY, barX + (int) (barW * level), barY + 10, barColor);
                String micLabel = level > 0.08f
                        ? "🎙 МИКРОФОН ПИШЕТ! ДАВИ НАПОРОМ И ВПЛЕТАЙ СЛОВА!"
                        : "🎙 ГОВОРИ В МИКРОФОН ГРОМЧЕ! (Ранг: " + playerRank + ")";
                ctx.drawCenteredTextWithShadow(tr, Text.literal(micLabel), centerX, barY + 1, 0xFFFFFFFF);
            }
            return;
        }

        // =========================================================================
        // 4. EVALUATING
        // =========================================================================
        if (phase == BattlePhase.EVALUATING) {
            int boxW = Math.min(screenW - 24, 340);
            int boxH = 54;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE6141218);
            ctx.drawBorder(x1, y1, boxW, boxH, 0xFFFFAA00);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("⏳ СМОТРЯЩИЙ ДЕЛАЕТ РАЗБОР БАТТЛА..."), centerX, y1 + 14, 0xFFFFD700);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("Нейросеть оценивает панчи, напор и слова суфлера"), centerX, y1 + 30, 0xFFBBBBBB);
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

    private static void renderMobSpeechBubble(DrawContext ctx, TextRenderer tr, int screenW, int screenH, String line) {
        int bubbleW = Math.min(185, screenW / 3);
        List<OrderedText> wrapped = tr.wrapLines(Text.literal(line), bubbleW - 14);
        int bubbleH = 18 + wrapped.size() * 10;

        // Place speech bubble to the right of center (next to the mob facing you!)
        int bx = Math.min(screenW - bubbleW - 10, (screenW / 2) + 46);
        int by = Math.max(36, (screenH / 2) - 42);

        // Cream/white speech bubble with dark border (matching screenshot 2!)
        ctx.fill(bx, by, bx + bubbleW, by + bubbleH, 0xF5F6F2E8);
        ctx.drawBorder(bx, by, bubbleW, bubbleH, 0xFF222222);

        // Little pointer triangle on the left side pointing toward the mob
        ctx.fill(bx - 4, by + 10, bx, by + 16, 0xF5F6F2E8);
        ctx.fill(bx - 7, by + 12, bx - 4, by + 15, 0xF5F6F2E8);

        // Mob nickname in orange at top of bubble
        ctx.drawCenteredTextWithShadow(tr, Text.literal(currentMobNickname), bx + bubbleW / 2, by + 4, 0xFFE65100);

        int ty = by + 15;
        for (OrderedText ot : wrapped) {
            int lw = tr.getWidth(ot);
            ctx.drawText(tr, ot, bx + (bubbleW - lw) / 2, ty, 0xFF1A1A1A, false);
            ty += 10;
        }
    }

    private static String[] splitIntoThreeChunks(String sentence) {
        String[] words = sentence.trim().split("\\s+");
        if (words.length <= 1) {
            return new String[]{sentence, "", ""};
        }
        if (words.length == 2) {
            return new String[]{words[0], words[1], ""};
        }
        int p1 = Math.max(1, words.length / 3);
        int p2 = Math.max(p1 + 1, (words.length * 2) / 3);
        String c1 = String.join(" ", Arrays.copyOfRange(words, 0, p1));
        String c2 = String.join(" ", Arrays.copyOfRange(words, p1, p2));
        String c3 = String.join(" ", Arrays.copyOfRange(words, p2, words.length));
        return new String[]{c1, c2, c3};
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
