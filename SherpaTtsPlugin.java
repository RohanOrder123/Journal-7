package com.rohanchauhan.tradesjournal;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline Piper voices through sherpa-onnx 1.13.8 (VITS / Piper models).
 *
 * IMPORTANT (v3): this plugin is written for sherpa-onnx 1.x (target: 1.13.8).
 * In 1.x the AAR contains the built-in eSpeak-NG / piper-phonemize frontend, so a Piper model only needs
 * model + tokens.txt + dataDir (= the espeak-ng-data folder) and generate() accepts RAW TEXT.
 * In sherpa-onnx 2.x that frontend was removed (upstream PR #4055) - Piper voices then fail with
 * "phoneme_codepoints is empty, no lexicon, and no frontend available". Do NOT upgrade the AAR to 2.x
 * without also adding a lexicon.txt for each voice.
 *
 * Models live inside the APK:   assets/voices/<voiceId>/<voiceId>.onnx  +  tokens.txt
 * Shared phonemizer data:       assets/voices/espeak-ng-data/   (copied once to filesDir, because espeak needs real files)
 *
 * Because espeak needs a real dataDir on disk, sherpa is created with a NULL AssetManager and reads model +
 * tokens + espeak-ng-data ALL from the file system. So model and tokens are also copied once to
 * filesDir/voices/<voiceId>/.
 *
 * JS API (window.Capacitor.Plugins.SherpaTts):
 *   status()                      -> what is in the APK, native library state
 *   selfTest({voice,text})        -> load + synthesize (no playback); checks samples, rate, peak, phonemizer log
 *   selfTestAll()                 -> NEW: runs the Hindi AND the English self-test and reports both
 *   speak({voice,text,speed})     -> plays natively; resolves when finished OR interrupted (never hangs)
 *   stop()                        -> stops playback, resolves the pending speak() immediately
 *   preload({voice})              -> loads a voice into memory
 * Error codes: NO_ASSET, NATIVE_LIB, INIT_FAIL, GEN_FAIL, NO_AUDIO, BAD_ARGS
 */
@CapacitorPlugin(name = "SherpaTts")
public class SherpaTtsPlugin extends Plugin {

    private static final String ESPEAK_MARK = "espeak-ok-v1";
    private static final String TARGET_SHERPA = "1.13.8";
    private static final boolean SHERPA_DEBUG = true; /* sherpa prints its own errors to logcat; selfTest reads them back */
    private static final float[] END = new float[0];

    static final String HI_VOICE = "hi_IN-priyamvada-medium";
    static final String EN_VOICE = "en_US-amy-medium";
    /* Hindi test sentence is written with \\u escapes so the file is safe with any compiler encoding */
    static final String HI_TEST = "\u0928\u092e\u0938\u094d\u0924\u0947 \u0930\u094b\u0939\u0928 \u091c\u0940, \u092e\u0948\u0902 \u0935\u0947\u0926\u093e \u0939\u0942\u0901\u0964 \u092f\u0939 \u0939\u093f\u0902\u0926\u0940 \u0906\u0935\u093e\u091c\u093c \u0915\u093e \u092a\u0930\u0940\u0915\u094d\u0937\u0923 \u0939\u0948\u0964";
    static final String EN_TEST = "Hello Rohan ji, this is an offline English voice test.";

    /* the exact messages sherpa prints when the text -> phoneme -> token frontend is missing or fails */
    private static final String[] PHONEMIZER_MARKERS = {
            "phoneme_codepoints is empty",
            "no frontend available",
            "failed to convert text to token"
    };

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger gen = new AtomicInteger();
    private final Map<String, OfflineTts> engines = new ConcurrentHashMap<>();
    private final Object trackLock = new Object();
    private AudioTrack track;
    private volatile Run current;
    private String espeakDir;

    /* short phrases are cached so repeated replies start instantly */
    private final LinkedHashMap<String, float[]> cache = new LinkedHashMap<String, float[]>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> e) {
            return size() > 24;
        }
    };

    /* ---------------------------------------------------------------- helpers */

    private static class VoiceErr extends Exception {
        final String code;
        VoiceErr(String code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /** One pending speak() call. Resolved exactly once, from whichever thread gets there first. */
    private static class Run {
        final PluginCall call;
        final AtomicBoolean done = new AtomicBoolean(false);
        Run(PluginCall c) {
            call = c;
        }
        void ok(JSObject o) {
            if (done.compareAndSet(false, true)) call.resolve(o);
        }
        void fail(String msg, String code) {
            if (done.compareAndSet(false, true)) call.reject(msg, code);
        }
    }

    private static long since(long t0) {
        return SystemClock.elapsedRealtime() - t0;
    }

    private boolean assetExists(AssetManager am, String path) {
        try (InputStream in = am.open(path)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private long assetSize(AssetManager am, String path) {
        try (AssetFileDescriptor fd = am.openFd(path)) {
            return fd.getLength();
        } catch (Throwable e) {
            try (InputStream in = am.open(path)) {
                return in.available();
            } catch (Throwable e2) {
                return -1;
            }
        }
    }

    private int copyAssetDir(AssetManager am, String src, File dst) throws IOException {
        String[] list = am.list(src);
        if (list == null || list.length == 0) {
            try (InputStream in = am.open(src)) {
                File parent = dst.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream out = new FileOutputStream(dst)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                return 1;
            } catch (FileNotFoundException e) {
                dst.mkdirs(); /* an empty directory */
                return 0;
            }
        }
        dst.mkdirs();
        int count = 0;
        for (String n : list) count += copyAssetDir(am, src + "/" + n, new File(dst, n));
        return count;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** espeak-ng-data is copied once; a marker file proves the copy finished (survives app restarts). */
    private synchronized String ensureEspeak() throws VoiceErr {
        if (espeakDir != null) return espeakDir;
        File root = getContext().getFilesDir();
        File dst = new File(root, "espeak-ng-data");
        File mark = new File(root, ESPEAK_MARK);
        try {
            if (!(mark.exists() && dst.isDirectory())) {
                deleteTree(dst);
                int n = copyAssetDir(getContext().getAssets(), "voices/espeak-ng-data", dst);
                if (n < 20) throw new IOException("only " + n + " espeak files found in APK assets");
                if (!mark.exists() && !mark.createNewFile()) throw new IOException("cannot write marker");
            }
        } catch (IOException e) {
            throw new VoiceErr("NO_ASSET", "espeak-ng-data: " + e.getMessage());
        }
        espeakDir = dst.getAbsolutePath();
        return espeakDir;
    }

    /**
     * Model and tokens are copied to filesDir/voices/<id>/ once. With a real espeak dataDir on disk,
     * sherpa must read EVERYTHING from the file system (AssetManager = null), not from APK assets.
     * Each file is written to a .tmp first and renamed, so a half-finished copy is never used.
     * An existing .onnx copy whose size differs from the APK asset is replaced (APK update safety).
     */
    private synchronized File ensureVoiceFiles(String id) throws VoiceErr {
        File dir = new File(getContext().getFilesDir(), "voices/" + id);
        AssetManager am = getContext().getAssets();
        try {
            dir.mkdirs();
            for (String n : new String[]{id + ".onnx", "tokens.txt"}) {
                File f = new File(dir, n);
                if (f.isFile() && f.length() > 0) {
                    if (!n.endsWith(".onnx")) continue;
                    long want = assetSize(am, "voices/" + id + "/" + n);
                    if (want <= 0 || want == f.length()) continue;
                }
                File tmp = new File(dir, n + ".tmp");
                if (tmp.exists()) tmp.delete();
                copyAssetDir(am, "voices/" + id + "/" + n, tmp);
                if (!tmp.isFile() || tmp.length() == 0) throw new IOException("copy of " + n + " is empty");
                if (f.exists()) f.delete();
                if (!tmp.renameTo(f)) throw new IOException("cannot rename " + n);
            }
        } catch (IOException e) {
            throw new VoiceErr("NO_ASSET", "voice copy: " + e.getMessage());
        }
        return dir;
    }

    private synchronized OfflineTts load(String id) throws VoiceErr {
        OfflineTts t = (id == null) ? null : engines.get(id);
        if (t != null) return t;
        if (id == null || !id.matches("[A-Za-z0-9_\\-]+")) throw new VoiceErr("BAD_ARGS", "bad voice id");
        AssetManager am = getContext().getAssets();
        String dir = "voices/" + id;
        String model = dir + "/" + id + ".onnx";
        String tokens = dir + "/tokens.txt";
        for (String p : new String[]{model, tokens}) {
            if (!assetExists(am, p)) throw new VoiceErr("NO_ASSET", p);
        }
        String espeak = ensureEspeak();
        File vd = ensureVoiceFiles(id);
        try {
            /* sherpa-onnx 1.x Piper config: model + tokens + dataDir(espeak-ng-data). No lexicon needed. */
            OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
            v.setModel(new File(vd, id + ".onnx").getAbsolutePath());
            v.setTokens(new File(vd, "tokens.txt").getAbsolutePath());
            v.setDataDir(espeak);
            v.setNoiseScale(0.667f);
            v.setNoiseScaleW(0.8f);
            v.setLengthScale(1.0f);
            OfflineTtsModelConfig m = new OfflineTtsModelConfig();
            m.setVits(v);
            m.setNumThreads(2);
            m.setDebug(SHERPA_DEBUG);
            m.setProvider("cpu");
            OfflineTtsConfig c = new OfflineTtsConfig();
            c.setModel(m);
            c.setMaxNumSentences(1);
            t = new OfflineTts((AssetManager) null, c);   /* null = read everything from real files */
            int sr = t.sampleRate();
            if (sr <= 0) {
                try { t.release(); } catch (Throwable ignore) { /* ignore */ }
                throw new IllegalStateException("engine created but sampleRate=" + sr + " (model/tokens/espeak-ng-data not accepted)");
            }
        } catch (Throwable e) {
            String why = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new VoiceErr(e instanceof LinkageError ? "NATIVE_LIB" : "INIT_FAIL", why);
        }
        engines.put(id, t);
        return t;
    }

    private float[] synth(OfflineTts t, String voice, String chunk, float speed) {
        boolean cacheable = chunk.length() <= 80;
        String key = voice + "|" + speed + "|" + chunk;
        if (cacheable) {
            synchronized (cache) {
                float[] hit = cache.get(key);
                if (hit != null) return hit;
            }
        }
        GeneratedAudio a = t.generate(chunk, 0, speed);
        float[] s = (a == null) ? null : a.getSamples();
        if (cacheable && s != null && s.length > 0) {
            synchronized (cache) {
                cache.put(key, s);
            }
        }
        return s;
    }

    /** Sentence / clause splitting so the next chunk can be synthesized while the current one plays. */
    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(ch);
            boolean decimal = ch == '.' && i > 0 && i + 1 < s.length()
                    && Character.isDigit(s.charAt(i - 1)) && Character.isDigit(s.charAt(i + 1));
            boolean hard = !decimal && (ch == '\u0964' || ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == '\n');
            boolean soft = (ch == ',' || ch == ':') && b.length() > 140;
            if (hard || soft || b.length() >= 220) flush(out, b);
        }
        flush(out, b);
        return out;
    }

    private static void flush(List<String> out, StringBuilder b) {
        String t = b.toString().trim();
        b.setLength(0);
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetterOrDigit(t.charAt(i))) {
                out.add(t);
                return;
            }
        }
    }

    private AudioTrack openTrack(int sr) {
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, sr / 2), AudioTrack.MODE_STREAM);
        if (at.getState() != AudioTrack.STATE_INITIALIZED) {
            try { at.release(); } catch (Throwable ignore) { /* ignore */ }
            throw new IllegalStateException("AudioTrack not initialized (sampleRate=" + sr + ")");
        }
        at.play();
        synchronized (trackLock) {
            track = at;
        }
        return at;
    }

    /** Releasing the track also unblocks a write() that is waiting on the player. */
    private void killTrack() {
        AudioTrack t;
        synchronized (trackLock) {
            t = track;
            track = null;
        }
        if (t == null) return;
        try { t.pause(); } catch (Throwable e) { /* ignore */ }
        try { t.flush(); } catch (Throwable e) { /* ignore */ }
        try { t.release(); } catch (Throwable e) { /* ignore */ }
    }

    /** returns false when the write failed or the speech was interrupted */
    private boolean writeSamples(AudioTrack at, float[] s, int my) {
        short[] buf = new short[Math.min(s.length, 2048)];
        int pos = 0;
        while (pos < s.length) {
            if (gen.get() != my) return false;
            int n = Math.min(buf.length, s.length - pos);
            for (int i = 0; i < n; i++) {
                float v = s[pos + i] * 32767f;
                buf[i] = (short) (v > 32767f ? 32767 : (v < -32768f ? -32768 : v));
            }
            int w = at.write(buf, 0, n);
            if (w < 0) return false;
            pos += n;
        }
        return true;
    }

    private JSObject interrupted() {
        JSObject o = new JSObject();
        o.put("ok", true);
        o.put("interrupted", true);
        return o;
    }


    /* ------------------------------------------------------------ diagnostics */

    private int countAssets(AssetManager am, String path) {
        try {
            String[] l = am.list(path);
            if (l == null || l.length == 0) return assetExists(am, path) ? 1 : 0;
            int c = 0;
            for (String n : l) c += countAssets(am, path + "/" + n);
            return c;
        } catch (IOException e) {
            return -1;
        }
    }

    private static int countFiles(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return 1;
        File[] k = f.listFiles();
        int c = 0;
        if (k != null) for (File x : k) c += countFiles(x);
        return c;
    }

    private static boolean interesting(String line) {
        String low = line.toLowerCase(Locale.US);
        return low.contains("sherpa") || low.contains("espeak") || low.contains("piper") || low.contains("phonem")
                || low.contains("onnx") || low.contains("lexicon") || low.contains("token");
    }

    /**
     * The app may read its OWN logcat lines. sherpa-onnx / espeak print the real reason for a failure there.
     * sinceWallMs > 0 reads only lines newer than that time (so old messages cannot be mistaken for new ones).
     */
    private List<String> sherpaLines(long sinceWallMs) {
        List<String> keep = new ArrayList<>();
        try {
            String[] cmd;
            if (sinceWallMs > 0) {
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(sinceWallMs));
                cmd = new String[]{"logcat", "-d", "-T", ts};
            } else {
                cmd = new String[]{"logcat", "-d", "-t", "600"};
            }
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (interesting(line)) keep.add(line);
                }
            }
            int rc = pr.waitFor();
            if (rc != 0 && sinceWallMs > 0) return sherpaLines(0); /* this logcat does not know -T: use the plain tail */
        } catch (Throwable e) {
            keep.add("(logcat not readable: " + e + ")");
        }
        return keep;
    }

    private static String lastLines(List<String> keep, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, keep.size() - n); i < keep.size(); i++) b.append(keep.get(i)).append('\n');
        return b.length() == 0 ? "(no sherpa/espeak lines in logcat)" : b.toString();
    }

    /** returns the phonemizer failure lines (empty string = none) */
    private static String phonemizerErrors(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) {
            String low = l.toLowerCase(Locale.US);
            for (String m : PHONEMIZER_MARKERS) {
                if (low.contains(m)) {
                    if (b.length() < 600) b.append(l.trim()).append('\n');
                    break;
                }
            }
        }
        return b.toString().trim();
    }

    /** optional diagnostics only: looked up by reflection so an API difference can never break the build */
    private static Object opt(Object o, String method) {
        try {
            Method m = o.getClass().getMethod(method);
            return m.invoke(o);
        } catch (Throwable e) {
            return "n/a";
        }
    }

    private JSObject diag(OfflineTts t, GeneratedAudio a, List<String> lines) {
        JSObject d = new JSObject();
        AssetManager am = getContext().getAssets();
        d.put("targetSherpa", TARGET_SHERPA);
        if (t != null) {
            try { d.put("engineSampleRate", t.sampleRate()); } catch (Throwable e) { d.put("engineSampleRate", "err " + e); }
            d.put("numSpeakers", String.valueOf(opt(t, "numSpeakers")));
        }
        if (a != null) d.put("audioSampleRate", a.getSampleRate());
        d.put("espeakFilesInApk", countAssets(am, "voices/espeak-ng-data"));
        d.put("espeakFilesCopied", countFiles(new File(getContext().getFilesDir(), "espeak-ng-data")));
        d.put("espeakDir", String.valueOf(espeakDir));
        File[] top = new File(getContext().getFilesDir(), "espeak-ng-data").listFiles();
        StringBuilder b = new StringBuilder();
        if (top != null) for (File f : top) b.append(f.getName()).append(f.isDirectory() ? "/" : "").append(' ');
        d.put("espeakTop", b.toString().trim());
        d.put("log", lastLines(lines, 25));
        return d;
    }

    /**
     * One real synthesis test (no playback). ok = samples > 0 AND sampleRate > 0 AND duration > 0 AND peak > 0.01
     * AND sherpa printed no phonemizer error. Throws VoiceErr when the voice cannot even be loaded.
     */
    private JSObject runTest(String voice, String text) throws VoiceErr {
        long wall0 = System.currentTimeMillis() - 1000;
        long t0 = SystemClock.elapsedRealtime();
        OfflineTts t = load(voice);
        long initMs = since(t0);
        long t1 = SystemClock.elapsedRealtime();
        GeneratedAudio a;
        try {
            a = t.generate(text, 0, 1.0f);
        } catch (Throwable e) {
            throw new VoiceErr("GEN_FAIL", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long genMs = since(t1);
        float[] s = (a == null) ? null : a.getSamples();
        int sr = (a == null) ? 0 : a.getSampleRate();
        int n = (s == null) ? 0 : s.length;
        float peak = 0f;
        if (s != null) for (float v : s) peak = Math.max(peak, Math.abs(v));
        double secs = n / (double) Math.max(1, sr);
        List<String> lines = sherpaLines(wall0);
        String phon = phonemizerErrors(lines);
        boolean ok = n > 0 && sr > 0 && secs > 0 && peak > 0.01f && phon.isEmpty();
        JSObject o = new JSObject();
        o.put("ok", ok);
        o.put("voice", voice);
        o.put("initMs", initMs);
        o.put("synthMs", genMs);
        o.put("samples", n);
        o.put("sampleRate", sr);
        o.put("audioSec", Math.round(secs * 100) / 100.0);
        o.put("peak", Math.round(peak * 1000) / 1000.0);
        o.put("rtf", secs > 0 ? Math.round(genMs / 1000.0 / secs * 100) / 100.0 : -1);
        o.put("phonemizerOk", phon.isEmpty());
        if (!phon.isEmpty()) o.put("phonemizerErrors", phon);
        if (!ok) o.put("diag", diag(t, a, lines));
        return o;
    }

    private static String defaultTest(String voice) {
        return HI_VOICE.equals(voice) ? HI_TEST : (EN_VOICE.equals(voice) ? EN_TEST : "test");
    }

    /* ------------------------------------------------------------ plugin API */

    @PluginMethod
    public void status(PluginCall call) {
        JSObject o = new JSObject();
        AssetManager am = getContext().getAssets();
        StringBuilder abis = new StringBuilder();
        for (String a : Build.SUPPORTED_ABIS) abis.append(abis.length() > 0 ? "," : "").append(a);
        o.put("abis", abis.toString());
        o.put("targetSherpa", TARGET_SHERPA);
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineTts"); /* runs the static initializer = loads the native library */
            o.put("nativeOk", true);
        } catch (Throwable e) {
            o.put("nativeOk", false);
            o.put("nativeError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        JSObject files = new JSObject();
        try {
            String[] voices = am.list("voices");
            if (voices != null) {
                for (String v : voices) {
                    if (v.equals("espeak-ng-data")) continue;
                    JSObject f = new JSObject();
                    f.put("onnx", assetSize(am, "voices/" + v + "/" + v + ".onnx"));
                    f.put("tokens", assetSize(am, "voices/" + v + "/tokens.txt"));
                    f.put("loaded", engines.containsKey(v));
                    files.put(v, f);
                }
            }
        } catch (IOException e) {
            o.put("assetError", e.getMessage());
        }
        o.put("voices", files);
        o.put("espeakInApk", assetExists(am, "voices/espeak-ng-data/phondata"));
        File root = getContext().getFilesDir();
        o.put("espeakCopied", new File(root, ESPEAK_MARK).exists() && new File(root, "espeak-ng-data").isDirectory());
        o.put("storage", "APK assets (read-only) + filesDir/espeak-ng-data + filesDir/voices (app files dir)");
        call.resolve(o);
    }

    @PluginMethod
    public void preload(final PluginCall call) {
        final String voice = call.getString("voice");
        exec.execute(() -> {
            try {
                long t0 = SystemClock.elapsedRealtime();
                load(voice);
                JSObject o = new JSObject();
                o.put("ok", true);
                o.put("initMs", since(t0));
                call.resolve(o);
            } catch (VoiceErr e) {
                call.reject(e.getMessage(), e.code);
            } catch (Throwable e) {
                call.reject(String.valueOf(e), "INIT_FAIL");
            }
        });
    }

    @PluginMethod
    public void selfTest(final PluginCall call) {
        final String voice = call.getString("voice");
        final String given = call.getString("text");
        final String text = (given == null || given.trim().isEmpty()) ? defaultTest(voice) : given;
        exec.execute(() -> {
            try {
                call.resolve(runTest(voice, text));
            } catch (VoiceErr e) {
                call.reject(e.getMessage(), e.code);
            } catch (Throwable e) {
                call.reject(e.getClass().getSimpleName() + ": " + e.getMessage(), "GEN_FAIL");
            }
        });
    }

    /** Tests BOTH bundled voices with their fixed test sentences. ok is true only if both really produced audio. */
    @PluginMethod
    public void selfTestAll(final PluginCall call) {
        exec.execute(() -> {
            try {
                String[][] cases = {{HI_VOICE, HI_TEST}, {EN_VOICE, EN_TEST}};
                JSObject voices = new JSObject();
                boolean all = true;
                for (String[] c : cases) {
                    JSObject r;
                    try {
                        r = runTest(c[0], c[1]);
                    } catch (VoiceErr e) {
                        r = new JSObject();
                        r.put("ok", false);
                        r.put("voice", c[0]);
                        r.put("code", e.code);
                        r.put("error", e.getMessage());
                        r.put("diag", diag(null, null, sherpaLines(0)));
                    } catch (Throwable e) {
                        r = new JSObject();
                        r.put("ok", false);
                        r.put("voice", c[0]);
                        r.put("code", "GEN_FAIL");
                        r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    if (!Boolean.TRUE.equals(r.getBool("ok"))) all = false;
                    voices.put(c[0], r);
                }
                JSObject o = new JSObject();
                o.put("ok", all);
                o.put("targetSherpa", TARGET_SHERPA);
                o.put("voices", voices);
                call.resolve(o);
            } catch (Throwable e) {
                call.reject(e.getClass().getSimpleName() + ": " + e.getMessage(), "GEN_FAIL");
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        gen.incrementAndGet();
        killTrack();
        Run r = current;
        if (r != null) r.ok(interrupted());
        call.resolve();
    }

    @PluginMethod
    public void speak(final PluginCall call) {
        final String text = call.getString("text", "");
        final String voice = call.getString("voice");
        final float speed = Math.max(0.5f, Math.min(1.6f, call.getFloat("speed", 1.0f)));
        if (text == null || text.trim().isEmpty() || voice == null) {
            call.reject("text and voice are required", "BAD_ARGS");
            return;
        }
        final int my = gen.incrementAndGet();
        killTrack();
        Run old = current;
        if (old != null) old.ok(interrupted());
        final Run run = new Run(call);
        current = run;
        exec.execute(() -> {
            try {
                runSpeak(run, my, voice, text, speed);
            } catch (Throwable e) {
                run.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), "GEN_FAIL");
            } finally {
                if (current == run) current = null;
            }
        });
    }

    private void runSpeak(Run run, final int my, final String voice, String text, final float speed) {
        final long wall0 = System.currentTimeMillis() - 1000;
        long t0 = SystemClock.elapsedRealtime();
        final OfflineTts t;
        try {
            t = load(voice);
        } catch (VoiceErr e) {
            run.fail(e.getMessage(), e.code);
            return;
        }
        final long initMs = since(t0);
        final List<String> chunks = split(text);
        if (chunks.isEmpty() || gen.get() != my) {
            run.ok(interrupted());
            return;
        }
        final BlockingQueue<float[]> q = new ArrayBlockingQueue<>(2);
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicReference<Throwable> perr = new AtomicReference<>();

        /* producer: synthesizes chunk N+1 while chunk N is playing */
        Thread prod = new Thread(() -> {
            try {
                for (String c : chunks) {
                    if (abort.get() || gen.get() != my) break;
                    float[] s = synth(t, voice, c, speed);
                    if (s == null || s.length == 0) continue;
                    while (!q.offer(s, 200, TimeUnit.MILLISECONDS)) {
                        if (abort.get() || gen.get() != my) return;
                    }
                }
            } catch (Throwable e) {
                perr.set(e);
            } finally {
                try {
                    while (!q.offer(END, 200, TimeUnit.MILLISECONDS)) {
                        if (abort.get() || gen.get() != my) break;
                    }
                } catch (InterruptedException ie) { /* ignore */ }
            }
        }, "sherpa-synth");
        prod.start();

        AudioTrack at = null;
        long firstMs = -1;
        long frames = 0;
        boolean interrupted = false;
        try {
            while (true) {
                if (gen.get() != my) {
                    interrupted = true;
                    break;
                }
                float[] s = q.poll(200, TimeUnit.MILLISECONDS);
                if (s == null) continue;
                if (s == END) break;
                if (at == null) {
                    at = openTrack(t.sampleRate());
                    firstMs = since(t0);
                }
                if (!writeSamples(at, s, my)) {
                    interrupted = true;
                    break;
                }
                frames += s.length;
            }
            if (!interrupted && at != null) {
                at.stop(); /* MODE_STREAM: plays out everything already written */
                long limit = SystemClock.elapsedRealtime() + (frames * 1000L / Math.max(1, t.sampleRate())) + 1500;
                while (gen.get() == my && at.getPlaybackHeadPosition() < frames && SystemClock.elapsedRealtime() < limit) {
                    Thread.sleep(30);
                }
                if (gen.get() != my) interrupted = true;
            }
        } catch (Throwable e) {
            /* stop() releases the AudioTrack while we use it: that is an interruption, not an error */
            if (gen.get() != my) interrupted = true;
            else perr.compareAndSet(null, e);
        } finally {
            abort.set(true);
            try { prod.join(); } catch (InterruptedException ie) { /* ignore */ }
            synchronized (trackLock) {
                if (track == at) track = null;
            }
            if (at != null) {
                try { at.release(); } catch (Throwable e) { /* ignore */ }
            }
        }

        Throwable err = perr.get();
        if (err != null && !interrupted) {
            run.fail(err.getClass().getSimpleName() + ": " + err.getMessage(), "GEN_FAIL");
            return;
        }
        if (at == null && !interrupted) {
            String phon = phonemizerErrors(sherpaLines(wall0));
            run.fail("the engine produced no audio" + (phon.isEmpty() ? "" : " | phonemizer: " + phon), "NO_AUDIO");
            return;
        }
        JSObject o = new JSObject();
        o.put("ok", true);
        o.put("interrupted", interrupted);
        o.put("initMs", initMs);
        o.put("firstAudioMs", firstMs);
        o.put("totalMs", since(t0));
        o.put("chunks", chunks.size());
        o.put("sampleRate", t.sampleRate());
        run.ok(o);
    }
}
