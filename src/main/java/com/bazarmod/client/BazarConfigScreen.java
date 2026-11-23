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

import java.util.List;

public class BazarConfigScreen extends Screen {
    private static final List<String> VOICE_OPTIONS = List.of(
            "auto",
            "ru-RU-DmitryNeural",
            "ru-RU-SvetlanaNeural",
            "windows-sapi"
    );

    private final Screen parent;

    private TextFieldWidget apiKeyField;
    private TextFieldWidget modelField;
    private TextFieldWidget proxyHostField;
    private TextFieldWidget proxyPortField;
    private TextFieldWidget durationField;
    private TextFieldWidget minScoreField;

    private boolean maskApiKey = true;
    private boolean ttsEnabled;
    private int voiceIndex;
    private int ttsRate;
    private boolean mockMode;

    public BazarConfigScreen(Screen parent) {
        super(Text.literal("Настройки Дворовой Стрелки (Bazar Battle)"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        BazarConfig cfg = BazarConfig.get();
        this.ttsEnabled = cfg.ttsEnabled;
        this.ttsRate = cfg.ttsRate;
        this.mockMode = cfg.mockMode;
        this.voiceIndex = Math.max(0, VOICE_OPTIONS.indexOf(cfg.ttsVoice));

        int centerX = this.width / 2;
        int fieldW = 240;
        int leftX = centerX - fieldW / 2;
        int y = 38;
        int rowStep = 34;

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

        // 3. Proxy Host & Port (side-by-side)
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

        // 4. TTS Voice toggle + Voice selector + Test button
        this.addDrawableChild(ButtonWidget.builder(getTtsToggleLabel(), btn -> {
            ttsEnabled = !ttsEnabled;
            btn.setMessage(getTtsToggleLabel());
        }).dimensions(leftX, y - 1, 86, 20).build());

        this.addDrawableChild(ButtonWidget.builder(getVoiceLabel(), btn -> {
            voiceIndex = (voiceIndex + 1) % VOICE_OPTIONS.size();
            btn.setMessage(getVoiceLabel());
        }).dimensions(leftX + 90, y - 1, 114, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("🔊"), btn -> {
            BazarConfig temp = BazarConfig.get();
            temp.ttsEnabled = true;
            temp.ttsVoice = VOICE_OPTIONS.get(voiceIndex);
            temp.ttsRate = ttsRate;
            TtsSpeaker.speakAsync("Слышь, браток, проверка связи! Голос работает чётко.", true);
        }).dimensions(leftX + 208, y - 1, 32, 20).build());
        y += rowStep;

        // 5. Duration (sec) & Min Score & Mock Mode
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
        y += rowStep + 6;

        // Bottom Save & Cancel buttons
        this.addDrawableChild(ButtonWidget.builder(Text.literal("✅ Сохранить и применить"), btn -> saveAndClose())
                .dimensions(leftX, y, 152, 22)
                .build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), btn -> close())
                .dimensions(leftX + 158, y, fieldW - 158, 22)
                .build());
    }

    private Text getTtsToggleLabel() {
        return Text.literal("TTS: " + (ttsEnabled ? "§aВКЛ" : "§cВЫКЛ"));
    }

    private Text getVoiceLabel() {
        String v = VOICE_OPTIONS.get(voiceIndex);
        return switch (v) {
            case "ru-RU-DmitryNeural" -> Text.literal("Голос: Дмитрий");
            case "ru-RU-SvetlanaNeural" -> Text.literal("Голос: Светлана");
            case "windows-sapi" -> Text.literal("Голос: Windows");
            default -> Text.literal("Голос: Авто");
        };
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
        cfg.ttsEnabled = this.ttsEnabled;
        cfg.ttsVoice = VOICE_OPTIONS.get(this.voiceIndex);
        cfg.ttsRate = this.ttsRate;
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
        int rowStep = 34;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 10, 0xFFFFAA00);

        context.drawTextWithShadow(this.textRenderer, Text.literal("API Ключ Gemini (Google AI Studio):"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("Модель нейросети:"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("HTTP Прокси (Хост и Порт):"), leftX, y, 0xFFCCCCCC);
        y += rowStep;

        context.drawTextWithShadow(this.textRenderer, Text.literal("Озвучка голосом (TTS) и тест звука:"), leftX, y, 0xFFCCCCCC);
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
