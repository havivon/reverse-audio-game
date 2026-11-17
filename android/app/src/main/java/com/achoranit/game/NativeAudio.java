package com.achoranit.game;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Native recording (AudioRecord, raw 16-bit PCM) and playback (AudioTrack) exposed to the
 * page as window.NativeAudio. Audio crosses the bridge as base64 little-endian PCM16.
 * Async results are delivered via window.__nativeAudioEvent(type, value).
 */
public class NativeAudio {
    public static final int SAMPLE_RATE = 44100;
    private static final int MAX_SECONDS = 60;

    private final MainActivity activity;
    private final Object lock = new Object();

    // recording
    private AudioRecord recorder;
    private Thread recThread;
    private volatile boolean recording;
    private volatile float level;
    private ByteArrayOutputStream pcm;

    // playback
    private final Map<String, short[]> clips = new HashMap<String, short[]>();
    private final Map<String, Integer> clipRates = new HashMap<String, Integer>();
    private AudioTrack track;

    NativeAudio(MainActivity activity) {
        this.activity = activity;
    }

    /* ---------------- permission ---------------- */

    @JavascriptInterface
    public boolean hasMicPermission() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public void requestMicPermission() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.requestMicForNative();
            }
        });
    }

    /* ---------------- recording ---------------- */

    @JavascriptInterface
    public boolean startRecording() {
        synchronized (lock) {
            if (recording) return true;
            if (!hasMicPermission()) return false;
            releaseTrackLocked();

            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return false;
            int size = Math.max(min * 2, SAMPLE_RATE / 5 * 2);

            // VOICE_RECOGNITION is tuned for minimal processing (no AGC / noise suppression on most devices).
            AudioRecord r = createRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, size);
            if (r == null) r = createRecorder(MediaRecorder.AudioSource.MIC, size);
            if (r == null) return false;
            try {
                r.startRecording();
            } catch (Exception e) {
                r.release();
                return false;
            }
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                return false;
            }

            recorder = r;
            pcm = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 5);
            level = 0;
            recording = true;

            final AudioRecord rr = r;
            final ByteArrayOutputStream out = pcm;
            recThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    short[] buf = new short[SAMPLE_RATE / 50];
                    byte[] bytes = new byte[buf.length * 2];
                    int maxBytes = SAMPLE_RATE * MAX_SECONDS * 2;
                    while (recording) {
                        int n = rr.read(buf, 0, buf.length);
                        if (n < 0) break;
                        if (n == 0) continue;
                        double sum = 0;
                        for (int i = 0; i < n; i++) {
                            short s = buf[i];
                            bytes[2 * i] = (byte) (s & 0xff);
                            bytes[2 * i + 1] = (byte) ((s >> 8) & 0xff);
                            sum += (double) s * s;
                        }
                        level = (float) (Math.sqrt(sum / n) / 32768.0);
                        if (out.size() < maxBytes) out.write(bytes, 0, n * 2);
                    }
                }
            }, "achoranit-rec");
            recThread.start();
            return true;
        }
    }

    private static AudioRecord createRecorder(int source, int size) {
        try {
            AudioRecord r = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception ignored) {
        }
        return null;
    }

    @JavascriptInterface
    public float getLevel() {
        return recording ? level : 0f;
    }

    @JavascriptInterface
    public boolean isRecording() {
        return recording;
    }

    /** Stops recording and returns "sampleRate:base64pcm16". */
    @JavascriptInterface
    public String stopRecording() {
        byte[] data;
        synchronized (lock) {
            data = stopRecordingLocked();
        }
        return SAMPLE_RATE + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private byte[] stopRecordingLocked() {
        if (recorder == null) return new byte[0];
        recording = false;
        try {
            recThread.join(800);
        } catch (InterruptedException ignored) {
        }
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        recorder.release();
        recorder = null;
        recThread = null;
        byte[] d = pcm.toByteArray();
        pcm = null;
        return d;
    }

    /* ---------------- playback ---------------- */

    @JavascriptInterface
    public void load(String key, String base64, int sampleRate) {
        byte[] b = Base64.decode(base64, Base64.DEFAULT);
        short[] s = new short[b.length / 2];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
        synchronized (lock) {
            clips.put(key, s);
            clipRates.put(key, sampleRate);
        }
    }

    @JavascriptInterface
    public void play(String key, float rate, final int id) {
        synchronized (lock) {
            releaseTrackLocked();
            final short[] s = clips.get(key);
            Integer srObj = clipRates.get(key);
            if (s == null || s.length == 0 || srObj == null || recording) {
                emitEnded(id);
                return;
            }
            int sr = srObj;
            final AudioTrack t;
            try {
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sr)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(s.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                t.write(s, 0, s.length);
                float r = Math.max(0.5f, Math.min(2f, rate));
                if (Math.abs(r - 1f) > 0.001f) t.setPlaybackRate(Math.round(sr * r));
                t.play();
            } catch (Exception e) {
                emitEnded(id);
                return;
            }
            track = t;
            final int frames = s.length;
            final long timeoutMs = (long) (frames * 1000.0 / sr / Math.max(0.5f, rate)) + 2000;
            Thread watcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    long start = System.currentTimeMillis();
                    while (true) {
                        synchronized (lock) {
                            if (track != t) return; // stopped or replaced
                            int pos;
                            try {
                                pos = t.getPlaybackHeadPosition();
                            } catch (Exception e) {
                                pos = frames;
                            }
                            if (pos >= frames || System.currentTimeMillis() - start > timeoutMs) {
                                releaseTrackLocked();
                                break;
                            }
                        }
                        try {
                            Thread.sleep(15);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    emitEnded(id);
                }
            }, "achoranit-play");
            watcher.start();
        }
    }

    @JavascriptInterface
    public void stop() {
        synchronized (lock) {
            releaseTrackLocked();
        }
    }

    private void releaseTrackLocked() {
        if (track == null) return;
        AudioTrack t = track;
        track = null;
        try {
            t.pause();
            t.flush();
            t.stop();
        } catch (Exception ignored) {
        }
        t.release();
    }

    private void emitEnded(int id) {
        activity.emitAudioEvent("ended", String.valueOf(id));
    }

    /** Called by the activity when it goes to the background or is destroyed. */
    void shutdown() {
        synchronized (lock) {
            releaseTrackLocked();
            if (recorder != null) stopRecordingLocked();
        }
    }
}
