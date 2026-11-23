package com.bazarmod.client;

import com.bazarmod.BazarMod;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

public class MicrophoneRecorder {
    private static final float SAMPLE_RATE = 16000.0f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;

    public static void recordAsync(int durationSec, Consumer<byte[]> onComplete) {
        Thread thread = new Thread(() -> {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                BazarMod.LOGGER.warn("Microphone line not supported, sending silent WAV.");
                onComplete.accept(wrapPcmAsWav(new byte[3200]));
                return;
            }

            try (TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info)) {
                line.open(format);
                line.start();

                ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[2048];
                long endTime = System.currentTimeMillis() + (durationSec * 1000L);

                while (System.currentTimeMillis() < endTime) {
                    int read = line.read(chunk, 0, chunk.length);
                    if (read > 0) {
                        pcmBuffer.write(chunk, 0, read);
                    }
                }

                line.stop();
                byte[] wavBytes = wrapPcmAsWav(pcmBuffer.toByteArray());
                onComplete.accept(wavBytes);
            } catch (Exception e) {
                BazarMod.LOGGER.error("Microphone recording failed", e);
                onComplete.accept(wrapPcmAsWav(new byte[3200]));
            }
        }, "BazarMod-MicRecorder");

        thread.setDaemon(true);
        thread.start();
    }

    private static byte[] wrapPcmAsWav(byte[] pcmData) {
        int byteRate = (int) (SAMPLE_RATE * CHANNELS * (SAMPLE_SIZE_BITS / 8));
        int blockAlign = CHANNELS * (SAMPLE_SIZE_BITS / 8);
        int dataSize = pcmData.length;
        int chunkSize = 36 + dataSize;

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(chunkSize);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16); // Subchunk1Size for PCM
        header.putShort((short) 1); // AudioFormat 1 = PCM
        header.putShort((short) CHANNELS);
        header.putInt((int) SAMPLE_RATE);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) SAMPLE_SIZE_BITS);
        header.put("data".getBytes());
        header.putInt(dataSize);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
            out.write(header.array());
            out.write(pcmData);
            return out.toByteArray();
        } catch (IOException e) {
            return pcmData;
        }
    }
}
