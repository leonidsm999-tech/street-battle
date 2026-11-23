package com.bazarmod.client;

import com.bazarmod.BazarConfig;
import com.bazarmod.BazarMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

public class TtsSpeaker {
    private static volatile Process activeProcess = null;

    public static void stopCurrent() {
        Process p = activeProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
        activeProcess = null;
    }

    public static void speakAsync(String text, boolean isMob) {
        BazarConfig cfg = BazarConfig.get();
        if (!cfg.ttsEnabled || text == null || text.isBlank()) {
            return;
        }

        stopCurrent();

        Thread thread = new Thread(() -> {
            try {
                String cleanText = text.replaceAll("[«»\"'\\r\\n]", " ").trim();
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                String voicePref = cfg.ttsVoice == null ? "auto" : cfg.ttsVoice;
                int rate = cfg.ttsRate;

                if (os.contains("win")) {
                    speakWindows(cleanText, voicePref, rate, isMob);
                } else if (os.contains("mac")) {
                    ProcessBuilder pb = new ProcessBuilder("say", "-v", "Milena", cleanText);
                    activeProcess = pb.start();
                    activeProcess.waitFor();
                } else {
                    ProcessBuilder pb = new ProcessBuilder("espeak", "-v", "ru", cleanText);
                    activeProcess = pb.start();
                    activeProcess.waitFor();
                }
            } catch (Exception e) {
                BazarMod.LOGGER.debug("TTS playback skipped: {}", e.getMessage());
            }
        }, "BazarMod-TTS");

        thread.setDaemon(true);
        thread.start();
    }

    private static void speakWindows(String text, String voicePref, int rate, boolean isMob) throws Exception {
        // 1. If user chose Edge-TTS neural voice (or auto when edge-tts CLI is installed), try edge-tts first
        if (!"windows-sapi".equalsIgnoreCase(voicePref)) {
            String edgeVoice = "ru-RU-SvetlanaNeural".equalsIgnoreCase(voicePref)
                    ? "ru-RU-SvetlanaNeural"
                    : "ru-RU-DmitryNeural";
            String rateArg = (rate >= 0 ? "+" : "") + rate + "%";
            Path tempMp3 = Files.createTempFile("bazar_tts_", ".mp3");

            try {
                ProcessBuilder edgePb = new ProcessBuilder(
                        "edge-tts",
                        "--voice", edgeVoice,
                        "--rate=" + rateArg,
                        "--text", text,
                        "--write-media", tempMp3.toAbsolutePath().toString()
                );
                Process edgeProc = edgePb.start();
                activeProcess = edgeProc;
                int code = edgeProc.waitFor();
                if (code == 0 && Files.exists(tempMp3) && Files.size(tempMp3) > 100) {
                    // Play MP3 silently via PowerShell WPF MediaPlayer
                    String psPlay = "Add-Type -AssemblyName presentationCore; " +
                            "$p = New-Object System.Windows.Media.MediaPlayer; " +
                            "$p.Open([Uri]'" + tempMp3.toAbsolutePath().toString().replace("'", "''") + "'); " +
                            "$p.Play(); Start-Sleep -Milliseconds 400; " +
                            "while ($p.Position -lt $p.NaturalDuration.TimeSpan) { Start-Sleep -Milliseconds 150 }; " +
                            "$p.Close()";
                    Process playProc = new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psPlay).start();
                    activeProcess = playProc;
                    playProc.waitFor();
                    Files.deleteIfExists(tempMp3);
                    return;
                }
            } catch (Exception ignored) {
                // edge-tts not installed -> fall back seamlessly to built-in Windows SAPI
            } finally {
                Files.deleteIfExists(tempMp3);
            }
        }

        // 2. Built-in Windows System.Speech Synthesizer (works on every Windows PC with zero setup!)
        int sapiRate = Math.max(-5, Math.min(6, rate / 10)); // map -30..50 -> -3..5
        String b64Text = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String genderPref = "ru-RU-SvetlanaNeural".equalsIgnoreCase(voicePref) ? "Female" : "Male";

        String psScript =
                "Add-Type -AssemblyName System.Speech; " +
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "try { $s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::" + genderPref + ", [System.Speech.Synthesis.VoiceAge]::Adult, 0, [System.Globalization.CultureInfo]::GetCultureInfo('ru-RU')) } catch {}; " +
                "$s.Rate = " + sapiRate + "; " +
                "$t = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('" + b64Text + "')); " +
                "$s.Speak($t);";

        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psScript);
        activeProcess = pb.start();
        activeProcess.waitFor();
    }
}
