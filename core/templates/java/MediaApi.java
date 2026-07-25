package __PKG__;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Camera, microphone, speech, playback, torch, volume — the noisy hardware. */
public class MediaApi extends ApiModule {

    private MediaRecorder recorder;
    private File recordingFile;
    private MediaPlayer player;
    private TextToSpeech tts;
    private ToneGenerator tones;

    public MediaApi(Bridge bridge) {
        super(bridge);
    }

    @Override
    public String name() {
        return "media";
    }

    @Override
    public String[] methods() {
        return new String[]{"capturePhoto", "captureVideo", "scanBarcode",
                "startRecording", "stopRecording", "play", "stop", "beep",
                "speak", "stopSpeak", "voices", "volume", "setVolume",
                "torch", "cameras", "snapshot"};
    }

    @Override
    public Object invoke(String method, JSONObject a) throws Exception {
        switch (method) {
            case "capturePhoto":
                return capturePhoto(a);
            case "captureVideo":
                return captureVideo(a);
            case "scanBarcode":
                return scanBarcode(a);
            case "startRecording":
                return startRecording(a);
            case "stopRecording":
                return stopRecording(a);
            case "play":
                return play(a);
            case "stop":
                return stopPlayback();
            case "beep":
                return beep(a);
            case "speak":
                return speak(a);
            case "stopSpeak":
                return stopSpeak();
            case "voices":
                return voices();
            case "volume":
                return volume();
            case "setVolume":
                return setVolume(a);
            case "torch":
                return torch(a);
            case "cameras":
                return cameras();
            case "snapshot":
                return snapshot(a);
            default:
                throw unknown(method);
        }
    }

    // --------------------------------------------------------------- capture

    private Object capturePhoto(JSONObject a) throws Exception {
        if (!act.hasPermission("android.permission.CAMERA")
                && !act.requestPermissionsBlocking(new String[]{"android.permission.CAMERA"},
                180000).optBoolean("granted")) {
            throw BridgeError.denied("android.permission.CAMERA");
        }
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        boolean wantThumbnail = Jsonx.b(a, "thumbnail", false);
        Uri output = null;
        if (!wantThumbnail) {
            // Writing into MediaStore keeps the full-resolution image and needs
            // no storage permission for a row this app created.
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME,
                        Jsonx.str(a, "name", "capture_" + System.currentTimeMillis() + ".jpg"));
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                output = act.getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            } catch (Throwable ignored) {
                output = null;
            }
            if (output != null) {
                i.putExtra(MediaStore.EXTRA_OUTPUT, output);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        }
        MainActivity.ActResult r = act.startForResultBlocking(i, 600000);
        if (!r.ok()) {
            if (output != null) {
                try {
                    act.getContentResolver().delete(output, null, null);
                } catch (Throwable ignored) {
                }
            }
            return Jsonx.obj("ok", false, "reason", "cancelled");
        }
        if (output != null) {
            return Jsonx.obj("ok", true, "uri", output.toString(), "kind", "full");
        }
        // No output URI: the camera app hands back a thumbnail in the extras.
        Object data = r.data == null ? null : r.data.getExtras() == null ? null
                : r.data.getExtras().get("data");
        if (data instanceof Bitmap) {
            return Jsonx.obj("ok", true, "kind", "thumbnail",
                    "dataUrl", "data:image/png;base64," + encodePng((Bitmap) data, 100),
                    "width", ((Bitmap) data).getWidth(),
                    "height", ((Bitmap) data).getHeight());
        }
        return Jsonx.obj("ok", true, "kind", "unknown",
                "uri", r.data == null || r.data.getData() == null ? JSONObject.NULL
                        : r.data.getData().toString());
    }

    private Object captureVideo(JSONObject a) throws Exception {
        Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        int quality = Jsonx.i(a, "quality", -1);
        if (quality >= 0) {
            i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, quality);
        }
        int limit = Jsonx.i(a, "maxSeconds", 0);
        if (limit > 0) {
            // Camera apps read this as an int; a long extra is silently ignored.
            i.putExtra(MediaStore.EXTRA_DURATION_LIMIT, limit);
        }
        MainActivity.ActResult r = act.startForResultBlocking(i, 1800000);
        if (!r.ok() || r.data == null || r.data.getData() == null) {
            return Jsonx.obj("ok", false, "reason", "cancelled");
        }
        return Jsonx.obj("ok", true, "uri", r.data.getData().toString());
    }

    /** Delegates to any installed scanner app; no bundled ML dependency. */
    private Object scanBarcode(JSONObject a) throws Exception {
        Intent i = new Intent("com.google.zxing.client.android.SCAN");
        i.putExtra("SCAN_MODE", Jsonx.str(a, "mode", "QR_CODE_MODE"));
        if (act.getPackageManager().resolveActivity(i, 0) == null) {
            throw new BridgeError("no_activity",
                    "バーコードスキャナーアプリがインストールされていません");
        }
        MainActivity.ActResult r = act.startForResultBlocking(i, 300000);
        if (!r.ok() || r.data == null) {
            return Jsonx.obj("ok", false, "reason", "cancelled");
        }
        return Jsonx.obj("ok", true,
                "text", r.data.getStringExtra("SCAN_RESULT"),
                "format", r.data.getStringExtra("SCAN_RESULT_FORMAT"));
    }

    // ------------------------------------------------------------- recording

    private Object startRecording(JSONObject a) throws Exception {
        if (!act.hasPermission("android.permission.RECORD_AUDIO")
                && !act.requestPermissionsBlocking(
                new String[]{"android.permission.RECORD_AUDIO"}, 180000)
                .optBoolean("granted")) {
            throw BridgeError.denied("android.permission.RECORD_AUDIO");
        }
        synchronized (this) {
            if (recorder != null) {
                throw new BridgeError("busy", "すでに録音中です（media.stopRecording）");
            }
            File dir = act.getExternalFilesDir("recordings");
            if (dir == null) {
                dir = new File(act.getFilesDir(), "recordings");
            }
            if (!dir.exists() && !dir.mkdirs()) {
                throw new BridgeError("io", "録音フォルダーを作れません: " + dir);
            }
            recordingFile = new File(dir,
                    Jsonx.str(a, "name", "rec_" + System.currentTimeMillis() + ".m4a"));
            MediaRecorder mr = Build.VERSION.SDK_INT >= 31
                    ? new MediaRecorder(act) : new MediaRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioEncodingBitRate(Jsonx.i(a, "bitrate", 128000));
            mr.setAudioSamplingRate(Jsonx.i(a, "sampleRate", 44100));
            mr.setOutputFile(recordingFile.getAbsolutePath());
            mr.prepare();
            mr.start();
            recorder = mr;
        }
        return Jsonx.obj("recording", true, "path", recordingFile.getAbsolutePath());
    }

    private Object stopRecording(JSONObject a) throws Exception {
        MediaRecorder mr;
        File file;
        synchronized (this) {
            mr = recorder;
            file = recordingFile;
            recorder = null;
            recordingFile = null;
        }
        if (mr == null) {
            return Jsonx.obj("recording", false, "reason", "録音していません");
        }
        try {
            mr.stop();
        } catch (Throwable ignored) {
            // stop() throws when nothing was captured; the file is still there.
        }
        mr.reset();
        mr.release();
        JSONObject out = Jsonx.obj("path", file == null ? JSONObject.NULL
                        : file.getAbsolutePath(),
                "bytes", file == null ? 0 : file.length());
        if (file != null && Jsonx.b(a, "asBase64", false)) {
            if (file.length() > 8 * 1024 * 1024) {
                out.put("base64Error", "8MB を超えるため base64 は返しません");
            } else {
                byte[] bytes = new byte[(int) file.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int read = 0;
                    while (read < bytes.length) {
                        int n = in.read(bytes, read, bytes.length - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                }
                out.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
                out.put("mime", "audio/mp4");
            }
        }
        return out;
    }

    // -------------------------------------------------------------- playback

    private Object play(JSONObject a) throws Exception {
        final String source = Jsonx.str(a, "uri", Jsonx.str(a, "path", null));
        if (source == null) {
            throw new BridgeError("uri または path が必要です");
        }
        stopPlayback();
        final boolean loop = Jsonx.b(a, "loop", false);
        final float volume = (float) Jsonx.d(a, "volume", 1.0);
        return bridge.onUi(() -> {
            MediaPlayer mp = new MediaPlayer();
            Uri uri = source.startsWith("/") ? Uri.fromFile(new File(source))
                    : Uri.parse(source);
            mp.setDataSource(act, uri);
            mp.setLooping(loop);
            mp.setVolume(volume, volume);
            mp.setOnCompletionListener(p -> bridge.emit("media.completed",
                    Jsonx.obj("uri", source)));
            mp.prepare();
            mp.start();
            player = mp;
            return Jsonx.obj("playing", true, "durationMs", mp.getDuration());
        });
    }

    private Object stopPlayback() throws Exception {
        final MediaPlayer mp = player;
        player = null;
        if (mp == null) {
            return Jsonx.obj("playing", false);
        }
        return bridge.onUi(() -> {
            try {
                if (mp.isPlaying()) {
                    mp.stop();
                }
            } catch (Throwable ignored) {
            }
            mp.release();
            return Jsonx.obj("stopped", true);
        });
    }

    private Object beep(JSONObject a) throws Exception {
        synchronized (this) {
            if (tones == null) {
                tones = new ToneGenerator(AudioManager.STREAM_MUSIC,
                        Math.max(1, Math.min(100, Jsonx.i(a, "volume", 80))));
            }
        }
        int tone = Jsonx.i(a, "tone", ToneGenerator.TONE_PROP_BEEP);
        tones.startTone(tone, Math.max(10, Jsonx.i(a, "ms", 200)));
        return Jsonx.obj("played", true, "tone", tone);
    }

    // ----------------------------------------------------------------- speech

    private TextToSpeech ttsReady() throws Exception {
        synchronized (this) {
            if (tts != null) {
                return tts;
            }
        }
        final ArrayBlockingQueue<Integer> ready = new ArrayBlockingQueue<>(1);
        final TextToSpeech engine = bridge.onUi(() ->
                new TextToSpeech(act, status -> ready.offer(status)));
        Integer status = ready.poll(15, TimeUnit.SECONDS);
        if (status == null || status != TextToSpeech.SUCCESS) {
            engine.shutdown();
            throw BridgeError.unsupported("音声合成 (TTS) エンジン");
        }
        synchronized (this) {
            tts = engine;
        }
        return engine;
    }

    private Object speak(JSONObject a) throws Exception {
        TextToSpeech engine = ttsReady();
        String text = Jsonx.need(a, "text");
        String localeTag = Jsonx.str(a, "locale", null);
        if (localeTag != null) {
            engine.setLanguage(Locale.forLanguageTag(localeTag));
        }
        engine.setSpeechRate((float) Jsonx.d(a, "rate", 1.0));
        engine.setPitch((float) Jsonx.d(a, "pitch", 1.0));
        int mode = Jsonx.b(a, "queue", false) ? TextToSpeech.QUEUE_ADD
                : TextToSpeech.QUEUE_FLUSH;
        int result = engine.speak(text, mode, null, "sakiika");
        return Jsonx.obj("ok", result == TextToSpeech.SUCCESS, "text", text);
    }

    private Object stopSpeak() {
        synchronized (this) {
            if (tts != null) {
                tts.stop();
                return Jsonx.obj("stopped", true);
            }
        }
        return Jsonx.obj("stopped", false);
    }

    private Object voices() throws Exception {
        TextToSpeech engine = ttsReady();
        JSONArray out = new JSONArray();
        java.util.Set<android.speech.tts.Voice> all = engine.getVoices();
        if (all != null) {
            for (android.speech.tts.Voice v : all) {
                out.put(Jsonx.obj("name", v.getName(), "locale", v.getLocale().toLanguageTag(),
                        "quality", v.getQuality(), "networkRequired", v.isNetworkConnectionRequired()));
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- audio

    private AudioManager am() throws BridgeError {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            throw BridgeError.unsupported("AudioManager");
        }
        return am;
    }

    private static int streamOf(String name) {
        switch (name) {
            case "ring":
                return AudioManager.STREAM_RING;
            case "alarm":
                return AudioManager.STREAM_ALARM;
            case "notification":
                return AudioManager.STREAM_NOTIFICATION;
            case "voice":
                return AudioManager.STREAM_VOICE_CALL;
            case "system":
                return AudioManager.STREAM_SYSTEM;
            default:
                return AudioManager.STREAM_MUSIC;
        }
    }

    private Object volume() throws Exception {
        AudioManager am = am();
        JSONObject out = new JSONObject();
        for (String s : new String[]{"music", "ring", "alarm", "notification", "system", "voice"}) {
            int stream = streamOf(s);
            out.put(s, Jsonx.obj("current", am.getStreamVolume(stream),
                    "max", am.getStreamMaxVolume(stream)));
        }
        out.put("mode", am.getRingerMode());
        out.put("musicActive", am.isMusicActive());
        return out;
    }

    private Object setVolume(JSONObject a) throws Exception {
        AudioManager am = am();
        int stream = streamOf(Jsonx.str(a, "stream", "music"));
        int max = am.getStreamMaxVolume(stream);
        int value;
        if (a.has("percent")) {
            value = Math.round(max * (float) Math.max(0, Math.min(100, Jsonx.d(a, "percent", 50)))
                    / 100f);
        } else {
            value = Math.max(0, Math.min(max, Jsonx.i(a, "value", 0)));
        }
        am.setStreamVolume(stream, value, Jsonx.b(a, "showUi", false)
                ? AudioManager.FLAG_SHOW_UI : 0);
        return Jsonx.obj("stream", Jsonx.str(a, "stream", "music"), "value", value, "max", max);
    }

    // ----------------------------------------------------------------- camera

    private CameraManager camera() throws BridgeError {
        CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            throw BridgeError.unsupported("CameraManager");
        }
        return cm;
    }

    private Object torch(JSONObject a) throws Exception {
        CameraManager cm = camera();
        String id = Jsonx.str(a, "cameraId", null);
        if (id == null) {
            for (String candidate : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(candidate)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    id = candidate;
                    break;
                }
            }
        }
        if (id == null) {
            throw BridgeError.unsupported("フラッシュライト");
        }
        cm.setTorchMode(id, Jsonx.b(a, "on", true));
        return Jsonx.obj("cameraId", id, "on", Jsonx.b(a, "on", true));
    }

    private Object cameras() throws Exception {
        CameraManager cm = camera();
        JSONArray out = new JSONArray();
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            out.put(Jsonx.obj("id", id,
                    "facing", facing == null ? "unknown"
                            : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front"
                            : facing == CameraCharacteristics.LENS_FACING_BACK ? "back" : "external",
                    "flash", Boolean.TRUE.equals(flash),
                    "sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION)));
        }
        return out;
    }

    /** Renders the current WebView contents to a PNG data URL. */
    private Object snapshot(JSONObject a) throws Exception {
        final int quality = Math.max(10, Math.min(100, Jsonx.i(a, "quality", 90)));
        return bridge.onUi(() -> {
            android.webkit.WebView web = act.webView();
            int w = Math.max(1, web.getWidth());
            int h = Math.max(1, web.getHeight());
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            web.draw(canvas);
            String encoded = encodePng(bmp, quality);
            bmp.recycle();
            return Jsonx.obj("dataUrl", "data:image/png;base64," + encoded,
                    "width", w, "height", h);
        });
    }

    private static String encodePng(Bitmap bmp, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, quality, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    @Override
    public void dispose() {
        try {
            stopRecording(new JSONObject());
        } catch (Throwable ignored) {
        }
        try {
            stopPlayback();
        } catch (Throwable ignored) {
        }
        synchronized (this) {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
            if (tones != null) {
                tones.release();
                tones = null;
            }
        }
    }
}
