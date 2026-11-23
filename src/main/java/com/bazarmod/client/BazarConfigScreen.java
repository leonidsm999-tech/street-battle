package com.bazarmod.client;

import com.bazarmod.BazarConfig;
import com.bazarmod.BazarNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

public class BazarConfigScreen extends Screen {
    private final Screen parent;

    private TextFieldWidget apiKeyField;
    private TextFieldWidget modelField;
    private TextFieldWidget proxyHostField;
    private TextFieldWidget proxyPortField;
    private TextFieldWidget durationField;
    private TextFieldWidget minScoreField;

    private boolean maskApiKey = true;
    private boolean mockMode;

    public BazarConfigScreen(Screen parent) {
        super(Text.literal("Настройки Дворовой Стрелки (Bazar Battle)"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        BazarConfig cfg = BazarConfig.get();
        this.mockMode = cfg.mockMode;

        int centerX = this.width / 2;
        int fieldW = 240;
        int leftX = centerX - fieldW / 2;
        int y = 38;
        int rowStep = 35;

        // 1. API Key + Eye toggle button
        this.apiKeyField = new TextFieldWidget(this.textRenderer, leftX, y, fieldW - 26, 18, Text.literal("API Ключ Gemini"));
        this.apiKeyField.setMaxLength(256);
        this.apiKeyField.setText(cfg.apiKey);
        this.apiKeyField.setPlaceholder(Text.literal("AIzaSy... (ключ Google AI Studio)"));
        this.apiKeyField.setRenderTextProvider((text, firstIdx) -> {
            if (maskApiKey && !text.isEmpty()) {
                return OrderedText.styledForwardsVisitedString("•".repeat(text.length()), Style.EMPTY);
            }
            return OrderedText.styledForwardsVisitedString(text, Style.EMPTY);
        });
        this.addDrawableChild(this.apiKeyField);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("👁"), btn -> maskApiKey = !maskApiKey)
                .dimensions(leftX + fieldW - 22, y - 1, 22, 20)
                .build());
        y += rowStep;

        // 2. Gemini Model
        this.modelField = new TextFieldWidget(this.textRenderer, leftX, y, fieldW, 18, Text.literal("Модель Gemini"));
        this.modelField.setMaxLength(100);
        this.modelField.setText(cfg.model);
        this.addDrawableChild(this.modelField);
        y += rowStep;

        // 3. Proxy Host & Port
        this.proxyHostField = new TextFieldWidget(this.textRenderer, leftX, y, 165, 18, Text.literal("Прокси Хост"));
        this.proxyHostField.setMaxLength(120);
        this.proxyHostField.setText(cfg.proxyHost);
        this.proxyHostField.setPlaceholder(Text.literal("127.0.0.1 (пусто = без прокси)"));
        this.addDrawableChild(this.proxyHostField);

        this.proxyPortField = new TextFieldWidget(this.textRenderer, leftX + 172, y, fieldW - 172, 18, Text.literal("Порт"));
        this.proxyPortField.setMaxLength(6);
        this.proxyPortField.setText(cfg.proxyPort > 0 ? String.valueOf(cfg.proxyPort) : "");
        this.proxyPortField.setPlaceholder(Text.literal("Порт"));
        this.addDrawableChild(this.proxyPortField);
        y += rowStep;

        // 4. Duration (sec) & Min Score & Mock Mode
        this.durationField = new TextFieldWidget(this.textRenderer, leftX, y, 68, 18, Text.literal("Секунды"));
        this.durationField.setMaxLength(3);
        this.durationField.setText(String.valueOf(cfg.battleDurationSec));
        this.addDrawableChild(this.durationField);

        this.minScoreField = new TextFieldWidget(this.textRenderer, leftX + 74, y, 68, 18, Text.literal("Порог"));
        this.minScoreField.setMaxLength(3);
        this.minScoreField.setText(String.valueOf(cfg.minWinScore));
        this.addDrawableChild(this.minScoreField);

        this.addDrawableChild(ButtonWidget.builder(getMockLabel(), btn -> {
            mockMode = !mockMode;
            btn.setMessage(getMockLabel());
        }).dimensions(leftX + 148, y - 1, fieldW - 148, 20).build());
        y += rowStep + 4;

        // Save & Cancel
        this.addDrawableChild(ButtonWidget.builder(Text.literal("✅ Сохранить и применить"), btn -> saveAndClose())
                .dimensions(leftX, y, 152, 22)
                .build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), btn -> close())
                .dimensions(leftX + 158, y, fieldW - 158, 22)
                .build());
        y += 28;

        // Open Battle History & Breakdowns button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("📜 История баттлов и разборы [H]"), btn -> {
            if (this.client != null) {
                this.client.setScreen(new BazarHistoryScreen(this));
            }
        }).dimensions(leftX, y, fieldW, 20).build());
    }

    private Text getMockLabel() {
        return Text.literal("Тест без API: " + (mockMode ? "§eДА" : "§7НЕТ"));
    }

    private void saveAndClose() {
        BazarConfig cfg = BazarConfig.get();
        cfg.apiKey = this.apiKeyField.getText().trim();
        cfg.model = this.modelField.getText().trim().isEmpty() ? "gemini-2.5-flash" : this.modelField.getText().trim();
        cfg.proxyHost = this.proxyHostField.getText().trim();
        cfg.proxyPort = parseSafeInt(this.proxyPortField.getText(), 0, 0, 65535);
        cfg.battleDurationSec = parseSafeInt(this.durationField.getText(), 15, 5, 30);
        cfg.minWinScore = parseSafeInt(this.minScoreField.getText(), 50, 1, 100);
        cfg.mockMode = this.mockMode;
        cfg.save();

        if (this.client != null && this.client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new BazarNetworking.SyncConfigPayload(
                    cfg.apiKey,
                    cfg.model,
                    cfg.proxyHost,
                    cfg.proxyPort,
                    cfg.battleDurationSec,
                    cfg.minWinScore,
                    cfg.mockMode
            ));
        }
        close();
    }

    private int parseSafeInt(String raw, int def, int min, int max) {
        try {
            int val = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, val));
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int leftX = centerX - 120;
        int y = 27;
        int rowStep = 35;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 10, 0xFFFFAA00);

        context.drawTextWithShadow(this.textRenderer, Text.literal("API Ключ Gemini (Google AI Studio):"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("Модель нейросети:"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("HTTP Прокси (Хост и Порт):"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("Сек. баттла  |  Порог  |  Режим без ключа:"), leftX, y, 0xFFCCCCCC);
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
