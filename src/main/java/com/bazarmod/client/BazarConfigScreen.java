package com.bazarmod.client;

import com.bazarmod.BazarConfig;
import com.bazarmod.BazarNetworking;
import com.bazarmod.GeminiJudgeClient;
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
    private ButtonWidget testApiButton;

    private boolean maskApiKey = true;
    private boolean mockMode;
    private boolean testingApi = false;
    private String apiTestStatus = "";
    private int apiTestStatusColor = 0xFFCCCCCC;

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
        int y = 32;
        int rowStep = 31;

        // 1. API Key + Eye toggle button
        this.apiKeyField = new TextFieldWidget(this.textRenderer, leftX, y, fieldW - 26, 17, Text.literal("API Ключ Gemini"));
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
                .dimensions(leftX + fieldW - 22, y - 1, 22, 19)
                .build());
        y += rowStep;

        // 2. Gemini Model
        this.modelField = new TextFieldWidget(this.textRenderer, leftX, y, fieldW, 17, Text.literal("Модель Gemini"));
        this.modelField.setMaxLength(100);
        this.modelField.setText(cfg.model);
        this.addDrawableChild(this.modelField);
        y += rowStep;

        // 3. Proxy Host & Port
        this.proxyHostField = new TextFieldWidget(this.textRenderer, leftX, y, 165, 17, Text.literal("Прокси Хост"));
        this.proxyHostField.setMaxLength(120);
        this.proxyHostField.setText(cfg.proxyHost);
        this.proxyHostField.setPlaceholder(Text.literal("127.0.0.1 (пусто = без прокси)"));
        this.addDrawableChild(this.proxyHostField);

        this.proxyPortField = new TextFieldWidget(this.textRenderer, leftX + 172, y, fieldW - 172, 17, Text.literal("Порт"));
        this.proxyPortField.setMaxLength(6);
        this.proxyPortField.setText(cfg.proxyPort > 0 ? String.valueOf(cfg.proxyPort) : "");
        this.proxyPortField.setPlaceholder(Text.literal("Порт"));
        this.addDrawableChild(this.proxyPortField);
        y += rowStep;

        // 4. Duration (sec) & Min Score & Mock Mode
        this.durationField = new TextFieldWidget(this.textRenderer, leftX, y, 68, 17, Text.literal("Секунды"));
        this.durationField.setMaxLength(3);
        this.durationField.setText(String.valueOf(cfg.battleDurationSec));
        this.addDrawableChild(this.durationField);

        this.minScoreField = new TextFieldWidget(this.textRenderer, leftX + 74, y, 68, 17, Text.literal("Порог"));
        this.minScoreField.setMaxLength(3);
        this.minScoreField.setText(String.valueOf(cfg.minWinScore));
        this.addDrawableChild(this.minScoreField);

        this.addDrawableChild(ButtonWidget.builder(getMockLabel(), btn -> {
            mockMode = !mockMode;
            btn.setMessage(getMockLabel());
        }).dimensions(leftX + 148, y - 1, fieldW - 148, 19).build());
        y += rowStep + 2;

        // 5. Test API Connection + Save & Cancel
        this.testApiButton = ButtonWidget.builder(Text.literal("🔌 Тест API"), btn -> runApiTest())
                .dimensions(leftX, y, 76, 20)
                .build();
        this.addDrawableChild(this.testApiButton);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("✅ Сохранить"), btn -> saveAndClose())
                .dimensions(leftX + 80, y, 102, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), btn -> close())
                .dimensions(leftX + 186, y, fieldW - 186, 20)
                .build());
        y += 24;

        // 6. Open Battle History & Breakdowns button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("📜 История баттлов и разборы [H]"), btn -> {
            if (this.client != null) {
                this.client.setScreen(new BazarHistoryScreen(this));
            }
        }).dimensions(leftX, y, fieldW, 19).build());
    }

    private void runApiTest() {
        if (testingApi) return;
        testingApi = true;
        apiTestStatus = "⏳ Проверяем связь с Gemini...";
        apiTestStatusColor = 0xFFFFD54F;
        if (this.testApiButton != null) {
            this.testApiButton.active = false;
        }

        String key = this.apiKeyField.getText().trim();
        String mdl = this.modelField.getText().trim();
        String host = this.proxyHostField.getText().trim();
        int port = parseSafeInt(this.proxyPortField.getText(), 0, 0, 65535);

        GeminiJudgeClient.testConnectionAsync(key, mdl, host, port).thenAccept(res -> {
            if (this.client != null) {
                this.client.execute(() -> {
                    testingApi = false;
                    apiTestStatus = res.message();
                    apiTestStatusColor = res.ok() ? 0xFF55FF55 : 0xFFFF5555;
                    if (this.testApiButton != null) {
                        this.testApiButton.active = true;
                    }
                });
            }
        });
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
        int y = 22;
        int rowStep = 31;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 8, 0xFFFFAA00);

        context.drawTextWithShadow(this.textRenderer, Text.literal("API Ключ Gemini (Google AI Studio):"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("Модель нейросети:"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("HTTP Прокси (Хост и Порт):"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("Сек. баттла  |  Порог  |  Режим без ключа:"), leftX, y, 0xFFCCCCCC);

        if (!apiTestStatus.isEmpty()) {
            int statusY = 32 + rowStep * 4 + 48;
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(apiTestStatus), centerX, statusY, apiTestStatusColor);
        }
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
