package com.rohanchauhan.tradesjournal;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;

import com.getcapacitor.JSArray;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline Piper voices through sherpa-onnx 1.13.8 (VITS / Piper models).   (plugin version v4)
 *
 * sherpa-onnx 1.x contains the built-in eSpeak-NG / piper-phonemize frontend, so a Piper model only needs
 * model + tokens.txt + dataDir (= espeak-ng-data) and generate() accepts raw text. In sherpa-onnx 2.x that
 * frontend was removed - do NOT upgrade the AAR to 2.x without adding a lexicon.txt per voice.
 *
 * v4 = smooth, professional playback on slow phones:
 *   - ADAPTIVE PRE-BUFFER: playback starts only when enough audio is already synthesized that the rest can be
 *     produced in time (measured real-time factor of THIS phone). No more "few words, long pause, few words".
 *   - more CPU threads (auto by core count, and a one-time speed test: tune()).
 *   - clearer voice: calmer noise settings, loudness normalisation, 6 ms fades between chunks, natural pauses
 *     after sentences, text cleaner (emoji / markdown / symbols are not sent to the phonemizer).
 *   - shorter first chunk so speech starts sooner; underrun counter reported to JS.
 *
 * Models live inside the APK:   assets/voices/<voiceId>/<voiceId>.onnx  +  tokens.txt
 * Shared phonemizer data:       assets/voices/espeak-ng-data/   (copied once to filesDir)
 *
 * JS API (window.Capacitor.Plugins.SherpaTts):
 *   status()                      -> what is in the APK, native library state, current settings
 *   selfTest({voice,text})        -> load + synthesize (no playback)
 *   selfTestAll()                 -> Hindi AND English self-test
 *   speak({voice,text,speed})     -> plays natively; resolves when finished OR interrupted (never hangs)
 *   stop()                        -> stops playback, resolves the pending speak() immediately
 *   preload({voice})              -> loads a voice into memory
 *   tune()                        -> NEW: measures 2/3/4/6 threads on this phone and keeps the fastest
 *   config({threads,noiseScale,noiseScaleW,reset}) -> NEW: change / reset the saved settings
 * Error codes: NO_ASSET, NATIVE_LIB, INIT_FAIL, GEN_FAIL, NO_AUDIO, BAD_ARGS
 */
@CapacitorPlugin(name = "SherpaTts")
public class SherpaTtsPlugin extends Plugin {

    private static final String ESPEAK_MARK = "espeak-ok-v1";
    private static final String TARGET_SHERPA = "1.13.8";
    private static final boolean SHERPA_DEBUG = false;
    private static final String CFG = "sherpa_tts_cfg";

    private static final double MIN_LEAD_SEC = 0.5;      /* never start with less audio than this ... */
    private static final double MAX_LEAD_SEC = 40.0;     /* ... and never ask for more than this */
    private static final long MAX_PREBUFFER_MS = 22000;  /* start anyway after this long (user must hear something) */
    private static final int MAX_QUEUED_SEC = 90;        /* memory cap for synthesized-but-not-yet-played audio */

    static final String HI_VOICE = "hi_IN-priyamvada-medium";
    static final String EN_VOICE = "en_US-amy-medium";
    /* Hindi test sentence is written with \\u escapes so the file is safe with any compiler encoding */
    static final String HI_TEST = "\u0928\u092e\u0938\u094d\u0924\u0947 \u0930\u094b\u0939\u0928 \u091c\u0940, \u092e\u0948\u0902 \u0935\u0947\u0926\u093e \u0939\u0942\u0901\u0964 \u092f\u0939 \u0939\u093f\u0902\u0926\u0940 \u0906\u0935\u093e\u091c\u093c \u0915\u093e \u092a\u0930\u0940\u0915\u094d\u0937\u0923 \u0939\u0948\u0964";
    static final String EN_TEST = "Hello Rohan ji, this is an offline English voice test.";
    private static final String HI_PERCENT = " \u092a\u094d\u0930\u0924\u093f\u0936\u0924 ";
    private static final String HI_AND = " \u0914\u0930 ";

    /* the exact messages sherpa prints when the text -> phoneme -> token frontend is missing or fails */
    private static final String[] PHONEMIZER_MARKERS = {
            "phoneme_codepoints is empty",
            "no frontend available",
            "failed to convert text to token"
    };

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicInteger gen = new AtomicInteger();
    private final Map<String, OfflineTts> engines = new ConcurrentHashMap<>();
    private final Map<String, Float> peaks = new ConcurrentHashMap<>();
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

    /** a piece of text with the pause that follows it */
    static class Chunk {
        final String text;
        final int pauseMs;
        Chunk(String t, int p) {
            text = t;
            pauseMs = p;
        }
    }

    /** synthesized audio with the pause that follows it */
    private static class Item {
        final float[] s;
        final int pauseMs;
        Item(float[] s, int p) {
            this.s = s;
            this.pauseMs = p;
        }
    }

    private static final Item END_ITEM = new Item(new float[0], 0);

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

    /* ---------------------------------------------------------------- saved settings */

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(CFG, Context.MODE_PRIVATE);
    }

    private static int cores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static int defaultThreads() {
        int c = cores();
        return c >= 6 ? 4 : (c >= 4 ? 3 : 2);
    }

    private int threads() {
        int t = prefs().getInt("threads", defaultThreads());
        return Math.max(1, Math.min(8, t));
    }

    private float noiseScale() {
        return prefs().getFloat("noise", 0.55f);
    }

    private float noiseScaleW() {
        return prefs().getFloat("noisew", 0.7f);
    }

    private JSObject cfgJson() {
        JSObject o = new JSObject();
        o.put("threads", threads());
        o.put("cores", cores());
        o.put("noiseScale", noiseScale());
        o.put("noiseScaleW", noiseScaleW());
        return o;
    }

    /** releases every loaded voice so the next use rebuilds it with the current settings */
    private synchronized void dropEngines() {
        for (OfflineTts t : engines.values()) {
            try { t.release(); } catch (Throwable ignore) { /* ignore */ }
        }
        engines.clear();
        synchronized (cache) {
            cache.clear();
        }
    }

    /* ---------------------------------------------------------------- files */

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

    /** builds one engine with the given thread count (does not touch the engines map) */
    private OfflineTts buildEngine(String id, int threads) throws VoiceErr {
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
        OfflineTts t = null;
        try {
            /* sherpa-onnx 1.x Piper config: model + tokens + dataDir(espeak-ng-data). No lexicon needed. */
            OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
            v.setModel(new File(vd, id + ".onnx").getAbsolutePath());
            v.setTokens(new File(vd, "tokens.txt").getAbsolutePath());
            v.setDataDir(espeak);
            v.setNoiseScale(noiseScale());
            v.setNoiseScaleW(noiseScaleW());
            v.setLengthScale(1.0f);
            OfflineTtsModelConfig m = new OfflineTtsModelConfig();
            m.setVits(v);
            m.setNumThreads(threads);
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
        return t;
    }

    private synchronized OfflineTts load(String id) throws VoiceErr {
        OfflineTts t = (id == null) ? null : engines.get(id);
        if (t != null) return t;
        t = buildEngine(id, threads());
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

    /* ---------------------------------------------------------------- text preparation */

    /**
     * Removes everything the phonemizer cannot pronounce (emoji, markdown signs, links, symbols) so it never
     * produces garbage sounds or silent gaps, and spells out % and &amp;.
     */
    static String clean(String in, boolean hindi) {
        if (in == null) return "";
        String s = in.replaceAll("https?://\\S+", " ");
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') { b.append('\n'); continue; }
            if (cp == '\r' || cp == '\t') { b.append(' '); continue; }
            if (cp == '%') { b.append(hindi ? HI_PERCENT : " percent "); continue; }
            if (cp == '&') { b.append(hindi ? HI_AND : " and "); continue; }
            if ("*#_~`|<>[]{}\\^=@/()+\"\u201c\u201d\u2022\u00b7\u25ba\u25b6\u2192\u2190\u2191\u2193".indexOf(cp) >= 0) { b.append(' '); continue; }
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || type == Character.OTHER_SYMBOL || type == Character.MODIFIER_SYMBOL) {
                b.append(' ');
                continue;
            }
            if (cp >= 0xFE00 && cp <= 0xFE0F) continue;                      /* emoji variation selectors */
            if (type == Character.FORMAT && cp != 0x200C && cp != 0x200D) { continue; }
            b.appendCodePoint(cp);
        }
        return b.toString().replaceAll("[ ]{2,}", " ").replaceAll("[ ]+([.,!?;:\u0964])", "$1").replaceAll(" *\n *", "\n").trim();
    }

    /**
     * Sentence / clause splitting. The FIRST chunk is short so speech starts early; later chunks are longer
     * for natural flow. Every chunk carries the pause that is played after it.
     */
    static List<Chunk> split(String s) {
        List<Chunk> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            b.append(ch);
            boolean decimal = ch == '.' && i > 0 && i + 1 < s.length()
                    && Character.isDigit(s.charAt(i - 1)) && Character.isDigit(s.charAt(i + 1));
            boolean hard = !decimal && (ch == '\u0964' || ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == '\n');
            int softAt = out.isEmpty() ? 45 : 80;
            boolean soft = (ch == ',' || ch == ':') && b.length() > softAt;
            boolean longSpace = ch == ' ' && b.length() >= 120;
            boolean overflow = b.length() >= 200;
            if (hard) flush(out, b, (ch == '?' || ch == '!') ? 190 : (ch == ';' ? 120 : 170));
            else if (soft) flush(out, b, 70);
            else if (longSpace || overflow) flush(out, b, 40);
        }
        flush(out, b, 170);
        return out;
    }

    private static void flush(List<Chunk> out, StringBuilder b, int pauseMs) {
        String t = b.toString().trim();
        b.setLength(0);
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetterOrDigit(t.charAt(i))) {
                out.add(new Chunk(t, pauseMs));
                return;
            }
        }
    }

    /* ---------------------------------------------------------------- audio output */

    private AudioTrack openTrack(int sr) {
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        /* buffer is in BYTES and must be a multiple of the frame size (mono 16-bit = 2 bytes); an odd size
           throws "Invalid audio buffer size". About 1 second of audio gives smooth playback on slow phones. */
        int bytes = Math.max(min > 0 ? min * 2 : 0, sr * 2);
        bytes -= bytes % 2;
        AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STREAM);
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

    private void notePeak(String voice, float pk) {
        Float old = peaks.get(voice);
        if (old == null || pk > old) peaks.put(voice, pk);
    }

    /** one fixed gain per voice so loudness is even between chunks and replies (quiet voices are lifted) */
    private float gainFor(String voice) {
        Float pk = peaks.get(voice);
        if (pk == null) return 1f;
        float g = 0.92f / Math.max(pk, 0.05f);
        return Math.max(0.8f, Math.min(2.2f, g));
    }

    /**
     * Writes one chunk (with 6 ms fade in/out so chunks join without clicks) and then its pause as silence.
     * Returns the number of frames written, or -1 when interrupted / the write failed.
     */
    private long writeItem(AudioTrack at, Item it, float gain, int sr, int my) {
        float[] s = it.s;
        short[] buf = new short[2048];
        int fade = Math.max(1, sr * 6 / 1000);
        long frames = 0;
        int pos = 0;
        while (pos < s.length) {
            if (gen.get() != my) return -1;
            int n = Math.min(buf.length, s.length - pos);
            for (int i = 0; i < n; i++) {
                int idx = pos + i;
                float v = s[idx] * gain;
                if (idx < fade) v *= (float) idx / fade;
                int fromEnd = s.length - 1 - idx;
                if (fromEnd < fade) v *= (float) fromEnd / fade;
                if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                buf[i] = (short) (v * 32767f);
            }
            int w = at.write(buf, 0, n);
            if (w < 0) return -1;
            pos += n;
            frames += n;
        }
        long sil = (long) sr * it.pauseMs / 1000;
        if (sil > 0) {
            short[] zeros = new short[2048];
            while (sil > 0) {
                if (gen.get() != my) return -1;
                int n = (int) Math.min(zeros.length, sil);
                int w = at.write(zeros, 0, n);
                if (w < 0) return -1;
                sil -= n;
                frames += n;
            }
        }
        return frames;
    }

    private JSObject interrupted() {
        JSObject o = new JSObject();
        o.put("ok", true);
        o.put("interrupted", true);
        return o;
    }

    /**
     * How many seconds of audio must already be synthesized before playback may start, so that the REST of the
     * text can be produced in time. Uses the real-time factor measured on this phone during this very reply.
     */
    private static double needSec(int sr, long[] chars, long totalChars, int done, long charsDone,
                                  long statChars, long statSamples, long statMs) {
        if (statChars <= 0 || statSamples <= 0 || sr <= 0) return MIN_LEAD_SEC;
        double audioSec = statSamples / (double) sr;
        double rtf = (statMs / 1000.0) / audioSec;
        double secPerChar = audioSec / statChars;
        double remAudio = Math.max(0L, totalChars - charsDone) * secPerChar;
        double remSynth = remAudio * rtf;
        double nextChars = (done >= 0 && done < chars.length) ? chars[done] : 0;
        double nextSynth = nextChars * secPerChar * rtf;
        double need = Math.max(MIN_LEAD_SEC, Math.max(nextSynth * 1.25, remSynth - remAudio)) + 0.3;
        return Math.min(need, MAX_LEAD_SEC);
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
        o.put("threads", threads());
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
        o.put("settings", cfgJson());
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

    /** Changes / resets the saved settings. Loaded voices are released and rebuilt on next use. */
    @PluginMethod
    public void config(final PluginCall call) {
        exec.execute(() -> {
            try {
                SharedPreferences.Editor ed = prefs().edit();
                if (Boolean.TRUE.equals(call.getBoolean("reset"))) ed.clear();
                Integer th = call.getInt("threads");
                if (th != null) ed.putInt("threads", Math.max(1, Math.min(8, th)));
                Float ns = call.getFloat("noiseScale");
                if (ns != null) ed.putFloat("noise", Math.max(0.1f, Math.min(1.0f, ns)));
                Float nw = call.getFloat("noiseScaleW");
                if (nw != null) ed.putFloat("noisew", Math.max(0.1f, Math.min(1.0f, nw)));
                ed.apply();
                dropEngines();
                JSObject o = cfgJson();
                o.put("ok", true);
                call.resolve(o);
            } catch (Throwable e) {
                call.reject(e.getClass().getSimpleName() + ": " + e.getMessage(), "INIT_FAIL");
            }
        });
    }

    /**
     * One-time speed test on THIS phone: builds the English voice with 2 / 3 / 4 / 6 threads (as far as the phone
     * has cores), times the same sentence and keeps the fastest setting (a bigger thread count must be at least
     * 8% faster to win). Takes about 1-2 minutes.
     */
    @PluginMethod
    public void tune(final PluginCall call) {
        exec.execute(() -> {
            try {
                gen.incrementAndGet();
                killTrack();
                dropEngines();
                int cores = cores();
                int[] cand = {2, 3, 4, 6};
                JSArray table = new JSArray();
                int best = 0;
                double bestRtf = Double.MAX_VALUE;
                for (int th : cand) {
                    if (th > cores) continue;
                    OfflineTts e = buildEngine(EN_VOICE, th);
                    try {
                        e.generate("Hello.", 0, 1.0f);                       /* warm-up, not timed */
                        long t1 = SystemClock.elapsedRealtime();
                        GeneratedAudio a = e.generate(EN_TEST, 0, 1.0f);
                        long ms = since(t1);
                        double secs = (a == null || a.getSamples() == null) ? 0 : a.getSamples().length / (double) Math.max(1, a.getSampleRate());
                        double rtf = secs > 0 ? ms / 1000.0 / secs : 99;
                        JSObject row = new JSObject();
                        row.put("threads", th);
                        row.put("synthMs", ms);
                        row.put("audioSec", Math.round(secs * 100) / 100.0);
                        row.put("rtf", Math.round(rtf * 100) / 100.0);
                        table.put(row);
                        if (best == 0 || rtf < bestRtf * 0.92) {
                            best = th;
                            bestRtf = rtf;
                        }
                    } finally {
                        try { e.release(); } catch (Throwable ignore) { /* ignore */ }
                    }
                }
                if (best == 0) throw new VoiceErr("INIT_FAIL", "no thread setting could be tested");
                prefs().edit().putInt("threads", best).apply();
                JSObject o = new JSObject();
                o.put("ok", true);
                o.put("best", best);
                o.put("bestRtf", Math.round(bestRtf * 100) / 100.0);
                o.put("cores", cores);
                o.put("table", table);
                call.resolve(o);
            } catch (VoiceErr e) {
                call.reject(e.getMessage(), e.code);
            } catch (Throwable e) {
                call.reject(e.getClass().getSimpleName() + ": " + e.getMessage(), "GEN_FAIL");
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
        final long t0 = SystemClock.elapsedRealtime();
        final OfflineTts t;
        try {
            t = load(voice);
        } catch (VoiceErr e) {
            run.fail(e.getMessage(), e.code);
            return;
        }
        final long initMs = since(t0);
        final List<Chunk> chunks = split(clean(text, voice.startsWith("hi")));
        if (gen.get() != my) {
            run.ok(interrupted());
            return;
        }
        if (chunks.isEmpty()) {            /* only emoji / symbols: nothing to say, not an error */
            JSObject none = new JSObject();
            none.put("ok", true);
            none.put("interrupted", false);
            none.put("chunks", 0);
            run.ok(none);
            return;
        }
        final int sr = t.sampleRate();
        final int n = chunks.size();
        final long[] chars = new long[n];
        long tc = 0;
        for (int i = 0; i < n; i++) {
            chars[i] = chunks.get(i).text.length();
            tc += chars[i];
        }
        final long totalChars = tc;
        final BlockingQueue<Item> q = new LinkedBlockingQueue<>();
        final AtomicLong queued = new AtomicLong();
        final AtomicInteger doneIdx = new AtomicInteger();
        final AtomicLong charsDone = new AtomicLong();
        final AtomicLong statChars = new AtomicLong();
        final AtomicLong statSamples = new AtomicLong();
        final AtomicLong statMs = new AtomicLong();
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicReference<Throwable> perr = new AtomicReference<>();
        final long queueCap = (long) MAX_QUEUED_SEC * sr;

        /* producer: synthesizes chunk N+1 (N+2 ...) while chunk N is playing */
        Thread prod = new Thread(() -> {
            try {
                for (int i = 0; i < n; i++) {
                    if (abort.get() || gen.get() != my) break;
                    boolean stopNow = false;
                    while (queued.get() > queueCap) {
                        if (abort.get() || gen.get() != my) { stopNow = true; break; }
                        Thread.sleep(50);
                    }
                    if (stopNow) break;
                    Chunk c = chunks.get(i);
                    long ts = SystemClock.elapsedRealtime();
                    float[] s = synth(t, voice, c.text, speed);
                    long el = since(ts);
                    doneIdx.incrementAndGet();
                    charsDone.addAndGet(chars[i]);
                    if (s == null || s.length == 0) continue;
                    float pk = 0f;
                    for (float v : s) pk = Math.max(pk, Math.abs(v));
                    notePeak(voice, pk);
                    if (el >= 20) {                  /* a cache hit takes ~0 ms and says nothing about speed */
                        statChars.addAndGet(chars[i]);
                        statSamples.addAndGet(s.length);
                        statMs.addAndGet(el);
                    }
                    queued.addAndGet(s.length);
                    q.offer(new Item(s, c.pauseMs));
                }
            } catch (Throwable e) {
                perr.set(e);
            } finally {
                q.offer(END_ITEM);
            }
        }, "sherpa-synth");
        prod.start();

        AudioTrack at = null;
        long firstMs = -1;
        long leadMs = 0;
        long frames = 0;
        long underruns = -1;
        boolean interrupted = false;
        boolean ended = false;
        final ArrayDeque<Item> local = new ArrayDeque<>();
        try {
            /* phase 1: ADAPTIVE PRE-BUFFER - wait until the rest of the text can be produced before the audio runs out */
            long bufferedSamples = 0;
            final long waitStart = SystemClock.elapsedRealtime();
            while (true) {
                if (gen.get() != my) {
                    interrupted = true;
                    break;
                }
                Item it = q.poll(100, TimeUnit.MILLISECONDS);
                if (it == END_ITEM) {
                    ended = true;
                } else if (it != null) {
                    local.add(it);
                    bufferedSamples += it.s.length + (long) it.pauseMs * sr / 1000;
                }
                if (local.isEmpty()) {
                    if (ended) break;
                    continue;
                }
                double have = bufferedSamples / (double) sr;
                double need = needSec(sr, chars, totalChars, doneIdx.get(), charsDone.get(),
                        statChars.get(), statSamples.get(), statMs.get());
                if (ended || have >= need || since(waitStart) > MAX_PREBUFFER_MS) break;
            }
            leadMs = since(waitStart);

            /* phase 2: play */
            if (!interrupted && !local.isEmpty()) {
                at = openTrack(sr);
                firstMs = since(t0);
                while (true) {
                    if (gen.get() != my) {
                        interrupted = true;
                        break;
                    }
                    Item it = local.poll();
                    if (it == null) {
                        if (ended) break;
                        it = q.poll(100, TimeUnit.MILLISECONDS);
                        if (it == null) continue;
                        if (it == END_ITEM) {
                            ended = true;
                            break;
                        }
                    }
                    queued.addAndGet(-it.s.length);
                    long w = writeItem(at, it, gainFor(voice), sr, my);
                    if (w < 0) {
                        if (gen.get() != my) interrupted = true;
                        else perr.compareAndSet(null, new IllegalStateException("AudioTrack write failed"));
                        break;
                    }
                    frames += w;
                }
                if (!interrupted && perr.get() == null) {
                    at.stop(); /* MODE_STREAM: plays out everything already written */
                    long limit = SystemClock.elapsedRealtime() + (frames * 1000L / Math.max(1, sr)) + 1500;
                    while (gen.get() == my && at.getPlaybackHeadPosition() < frames && SystemClock.elapsedRealtime() < limit) {
                        Thread.sleep(30);
                    }
                    if (gen.get() != my) interrupted = true;
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    try { underruns = at.getUnderrunCount(); } catch (Throwable ignore) { /* ignore */ }
                }
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
        double audioSec = statSamples.get() / (double) Math.max(1, sr);
        JSObject o = new JSObject();
        o.put("ok", true);
        o.put("interrupted", interrupted);
        o.put("initMs", initMs);
        o.put("firstAudioMs", firstMs);
        o.put("leadMs", leadMs);
        o.put("totalMs", since(t0));
        o.put("chunks", n);
        o.put("sampleRate", sr);
        o.put("underruns", underruns);
        o.put("rtf", audioSec > 0 ? Math.round(statMs.get() / 1000.0 / audioSec * 100) / 100.0 : -1);
        o.put("threads", threads());
        run.ok(o);
    }
}
