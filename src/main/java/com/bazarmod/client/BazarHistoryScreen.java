package com.bazarmod.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BazarHistoryScreen extends Screen {
    private final Screen parent;
    private int selectedIndex = 0;
    private int listScrollOffset = 0;
    private int detailScrollOffset = 0;

    public BazarHistoryScreen(Screen parent) {
        super(Text.literal("История стрелок и разборы Смотрящего"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        BattleHistoryManager.ensureLoaded();

        int bottomY = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("⚙ Настройки мода"), btn -> {
            if (this.client != null) {
                this.client.setScreen(new BazarConfigScreen(this));
            }
        }).dimensions(14, bottomY, 125, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("🗑 Очистить историю"), btn -> {
            BattleHistoryManager.clearHistory();
            selectedIndex = 0;
            listScrollOffset = 0;
            detailScrollOffset = 0;
        }).dimensions(145, bottomY, 130, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), btn -> close())
                .dimensions(this.width - 94, bottomY, 80, 20)
                .build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        List<BattleHistoryManager.BattleRecord> records = BattleHistoryManager.getRecords();
        int leftPanelX = 14;
        int leftPanelW = Math.min(185, this.width / 3);
        int topY = 30;
        int panelH = this.height - 66;

        if (mouseX >= leftPanelX && mouseX <= leftPanelX + leftPanelW && mouseY >= topY && mouseY <= topY + panelH) {
            int rowH = 30;
            int relY = (int) (mouseY - topY);
            int clickedIdx = listScrollOffset + (relY / rowH);
            if (clickedIdx >= 0 && clickedIdx < records.size()) {
                selectedIndex = clickedIdx;
                detailScrollOffset = 0;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<BattleHistoryManager.BattleRecord> records = BattleHistoryManager.getRecords();
        int leftPanelW = Math.min(185, this.width / 3);
        int delta = verticalAmount > 0 ? -1 : 1;

        if (mouseX <= 14 + leftPanelW) {
            int maxScroll = Math.max(0, records.size() - 6);
            listScrollOffset = Math.max(0, Math.min(maxScroll, listScrollOffset + delta));
        } else {
            detailScrollOffset = Math.max(0, detailScrollOffset + delta * 14);
        }
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("📜 ИСТОРИЯ ДВОРОВЫХ СТРЕЛОК И РАЗБОРЫ БАТТЛОВ"),
                centerX, 11, 0xFFFFAA00);

        List<BattleHistoryManager.BattleRecord> records = BattleHistoryManager.getRecords();

        int leftX = 14;
        int leftW = Math.min(185, this.width / 3);
        int topY = 30;
        int panelH = this.height - 66;

        // Left Panel: List of Battles
        ctx.fill(leftX, topY, leftX + leftW, topY + panelH, 0xCC101018);
        ctx.drawBorder(leftX, topY, leftW, panelH, 0xFF444455);

        // Right Panel: Full Breakdown
        int rightX = leftX + leftW + 10;
        int rightW = this.width - rightX - 14;
        ctx.fill(rightX, topY, rightX + rightW, topY + panelH, 0xCC12121A);
        ctx.drawBorder(rightX, topY, rightW, panelH, 0xFF555566);

        if (records.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Пока нет ни одной стрелки!"),
                    rightX + rightW / 2, topY + panelH / 2 - 10, 0xFFAAAAAA);
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Ударь любого моба или введи /bazar test"),
                    rightX + rightW / 2, topY + panelH / 2 + 6, 0xFF777788);
            return;
        }

        if (selectedIndex >= records.size()) selectedIndex = 0;

        // Render Left List
        int rowH = 30;
        int maxVisibleRows = panelH / rowH;
        for (int i = 0; i < maxVisibleRows; i++) {
            int idx = listScrollOffset + i;
            if (idx >= records.size()) break;

            BattleHistoryManager.BattleRecord rec = records.get(idx);
            int ry = topY + i * rowH;
            boolean isSelected = (idx == selectedIndex);

            int bg = isSelected ? 0xFF28283A : ((i % 2 == 0) ? 0x44181822 : 0x22101018);
            ctx.fill(leftX + 1, ry + 1, leftX + leftW - 1, ry + rowH - 1, bg);
            if (isSelected) {
                ctx.drawBorder(leftX + 1, ry + 1, leftW - 2, rowH - 2, rec.win ? 0xFF55FF55 : 0xFFFF5555);
            }

            String badge = rec.win ? "§a[ПОБЕДА] " : "§c[СЛИВ] ";
            String mobTitle = badge + "§f" + rec.mobName;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(mobTitle), leftX + 6, ry + 5, 0xFFFFFFFF);

            String sub = (rec.timestamp != null ? rec.timestamp : "") + "  |  Счёт: " + rec.score + "/100";
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(sub), leftX + 6, ry + 17, 0xFF9999AA);
        }

        // Render Right Breakdown Details
        BattleHistoryManager.BattleRecord sel = records.get(selectedIndex);
        int innerX = rightX + 10;
        int innerW = rightW - 20;
        int curY = topY + 10 - detailScrollOffset;
        int maxY = topY + panelH - 8;

        curY = drawHeaderLine(ctx, innerX, curY, topY, maxY,
                (sel.win ? "✅ ПОБЕДА" : "❌ ПОРАЖЕНИЕ") + " ПРОТИВ " + sel.mobName.toUpperCase(Locale.ROOT)
                        + "  |  СЧЁТ: " + sel.score + "/100  (+" + sel.xpGained + " XP)",
                sel.win ? 0xFF55FF55 : 0xFFFF5555);

        if (sel.rankTitle != null && !sel.rankTitle.isBlank()) {
            curY = drawHeaderLine(ctx, innerX, curY, topY, maxY,
                    "👑 Ранг после боя: " + sel.rankTitle, 0xFF55FFFF);
        }
        curY += 4;

        // 1. Mob Diss
        curY = drawHeaderLine(ctx, innerX, curY, topY, maxY, "🔥 ПРЕДЪЯВА МОБА:", 0xFFFF6666);
        if (sel.mobDissLines != null) {
            for (String line : sel.mobDissLines) {
                curY = drawWrappedBlock(ctx, innerX + 6, curY, innerW - 6, topY, maxY, "• " + line, 0xFFFFD700);
            }
        }
        curY += 5;

        // 2. Player Transcript
        curY = drawHeaderLine(ctx, innerX, curY, topY, maxY, "🎤 ЧТО ТЫ СКАЗАЛ В МИКРОФОН:", 0xFF55FFFF);
        String transcript = (sel.playerTranscript == null || sel.playerTranscript.isBlank())
                ? "[Нет расшифровки]"
                : "«" + sel.playerTranscript + "»";
        curY = drawWrappedBlock(ctx, innerX + 6, curY, innerW - 6, topY, maxY, transcript, 0xFFFFFFFF);
        curY += 5;

        // 3. Prompter Words Breakdown
        curY = drawHeaderLine(ctx, innerX, curY, topY, maxY, "📋 СЛОВА СУФЛЕРА:", 0xFFAAAAAA);
        Set<String> usedLower = new HashSet<>();
        if (sel.usedWords != null) {
            for (String u : sel.usedWords) usedLower.add(u.toLowerCase(Locale.ROOT).trim());
        }
        StringBuilder sb = new StringBuilder();
        if (sel.promptWords != null) {
            for (String pw : sel.promptWords) {
                boolean hit = usedLower.stream().anyMatch(u -> u.contains(pw.toLowerCase(Locale.ROOT)) || pw.toLowerCase(Locale.ROOT).contains(u));
                sb.append(hit ? "§a[✓ " + pw + "]  " : "§7[✗ " + pw + "]  ");
            }
        }
        curY = drawWrappedBlock(ctx, innerX + 6, curY, innerW - 6, topY, maxY, sb.toString().trim(), 0xFFFFFFFF);
        curY += 5;

        // 4. Best Punch & Judge Breakdown
        curY = drawHeaderLine(ctx, innerX, curY, topY, maxY, "⚖ РАЗБОР ОТ СМОТРЯЩЕГО:", 0xFFFFAA00);
        if (sel.bestPunch != null && !sel.bestPunch.isBlank()) {
            curY = drawWrappedBlock(ctx, innerX + 6, curY, innerW - 6, topY, maxY,
                    "Ключевой момент: " + sel.bestPunch, 0xFF55FF55);
        }
        if (sel.judgeComment != null && !sel.judgeComment.isBlank()) {
            curY = drawWrappedBlock(ctx, innerX + 6, curY, innerW - 6, topY, maxY,
                    sel.judgeComment, 0xFFEEEEEE);
        }
        curY += 5;

        // 5. Mob Reaction
        if (sel.mobReaction != null && !sel.mobReaction.isBlank()) {
            curY = drawHeaderLine(ctx, innerX, curY, topY, maxY, "💬 ФИНАЛЬНАЯ РЕАКЦИЯ МОБА:", 0xFFFF88CC);
            drawWrappedBlock(ctx, innerX + 6, curY, innerW - 6, topY, maxY,
                    "«" + sel.mobReaction + "»", 0xFFFFD700);
        }
    }

    private int drawHeaderLine(DrawContext ctx, int x, int y, int minY, int maxY, String text, int color) {
        if (y >= minY + 4 && y + 10 <= maxY) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(text), x, y, color);
        }
        return y + 12;
    }

    private int drawWrappedBlock(DrawContext ctx, int x, int y, int maxW, int minY, int maxY, String text, int color) {
        List<OrderedText> lines = this.textRenderer.wrapLines(Text.literal(text), Math.max(40, maxW));
        for (OrderedText line : lines) {
            if (y >= minY + 4 && y + 10 <= maxY) {
                ctx.drawTextWithShadow(this.textRenderer, line, x, y, color);
            }
            y += 11;
        }
        return y + 2;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
