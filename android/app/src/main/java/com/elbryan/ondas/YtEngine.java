package com.elbryan.ondas;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;

/**
 * Obtiene el audio de YouTube con NewPipeExtractor y lo entrega al WebView
 * respetando las peticiones por rangos (como hace NewPipe).
 */
public final class YtEngine {
    private static final String TAG = "OndasYt";
    private static final long CHUNK = 2L * 1024 * 1024;
    private static final Pattern VID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final byte[] POST_BODY = new byte[] {0x78, 0};
    private static final AtomicLong RN = new AtomicLong();
    private static final Map<String, Resolved> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean ready = false;

    public static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

    private YtEngine() { }

    static final class Resolved {
        String url;
        String mime;
        long size;
        long expires;
    }

    public static synchronized void init() {
        if (ready) return;
        String lang = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();
        if (lang == null || lang.isEmpty()) lang = "es";
        if (country == null || country.isEmpty()) country = "PE";
        NewPipe.init(new NpDownloader(HTTP), new Localization(lang, country), new ContentCountry(country));
        ready = true;
    }

    private static String param(String url, String name) {
        try {
            return Uri.parse(url).getQueryParameter(name);
        } catch (Exception e) {
            return null;
        }
    }

    static Resolved resolve(String id, boolean fresh) throws Exception {
        init();
        Resolved c = CACHE.get(id);
        if (!fresh && c != null && c.expires > System.currentTimeMillis()) return c;
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + id);
        List<AudioStream> streams = info.getAudioStreams();
        AudioStream best = null;
        int bestScore = -1;
        for (AudioStream s : streams) {
            if (!s.isUrl() || s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            AudioTrackType tt = s.getAudioTrackType();
            if (tt != null && tt != AudioTrackType.ORIGINAL) continue;
            MediaFormat f = s.getFormat();
            int br = Math.max(s.getAverageBitrate(), s.getBitrate() / 1000);
            int score = br + ((f == MediaFormat.WEBMA_OPUS || f == MediaFormat.WEBMA) ? 40 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best == null) {
            for (AudioStream s : streams) {
                if (s.isUrl() && s.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP) {
                    best = s;
                    break;
                }
            }
        }
        if (best == null) throw new ContentNotAvailableException("Sin audio disponible");
        Resolved r = new Resolved();
        r.url = best.getContent();
        MediaFormat f = best.getFormat();
        r.mime = f != null && f.getMimeType() != null ? f.getMimeType() : "audio/webm";
        if ("audio/mp4".equals(r.mime) || f == MediaFormat.M4A) r.mime = "audio/mp4";
        String clen = param(r.url, "clen");
        r.size = clen != null ? Long.parseLong(clen) : headSize(r.url);
        String exp = param(r.url, "expire");
        long now = System.currentTimeMillis();
        r.expires = exp != null ? Math.min(Long.parseLong(exp) * 1000L - 120_000L, now + 5 * 3600_000L) : now + 3600_000L;
        CACHE.put(id, r);
        return r;
    }

    private static Request.Builder ytRequest(String url) {
        Request.Builder b = new Request.Builder().url(url);
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            b.header("Origin", "https://www.youtube.com");
            b.header("Referer", "https://www.youtube.com/");
            b.header("Sec-Fetch-Dest", "empty");
            b.header("Sec-Fetch-Mode", "cors");
            b.header("Sec-Fetch-Site", "cross-site");
        }
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
            b.header("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null));
        } else {
            b.header("User-Agent", NpDownloader.USER_AGENT);
        }
        b.header("Accept-Encoding", "identity");
        return b;
    }

    private static long headSize(String url) {
        try (Response r = HTTP.newCall(ytRequest(url).head().build()).execute()) {
            String cl = r.header("Content-Length");
            return cl != null ? Long.parseLong(cl) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Pide un tramo del audio igual que NewPipe: POST con &range= y &rn=. */
    static Response fetchRange(Resolved r, long start, long end) throws IOException {
        String u = r.url + "&range=" + start + "-" + end + "&rn=" + RN.incrementAndGet();
        Request req = ytRequest(u).post(RequestBody.create(POST_BODY, (MediaType) null)).build();
        return HTTP.newCall(req).execute();
    }

    private static String header(WebResourceRequest req, String name) {
        Map<String, String> h = req.getRequestHeaders();
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    static WebResourceResponse error(int code, String msg) {
        String json;
        try {
            json = new JSONObject().put("detail", msg).toString();
        } catch (Exception e) {
            json = "{}";
        }
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/json", "utf-8", code, code == 404 ? "Not Found" : "Error", h,
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    public static WebResourceResponse serveYoutube(WebResourceRequest req, String id) {
        if (!VID.matcher(id).matches()) return error(400, "id inválido");
        Resolved r;
        try {
            r = resolve(id, false);
        } catch (ContentNotAvailableException e) {
            Log.w(TAG, "no disponible " + id, e);
            return error(404, "Canción no disponible en tu país o eliminada");
        } catch (Exception e) {
            Log.e(TAG, "resolve " + id, e);
            return error(503, "YouTube no respondió. Revisa tu conexión e inténtalo de nuevo.");
        }
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        if ("HEAD".equalsIgnoreCase(req.getMethod())) {
            if (r.size > 0) h.put("Content-Length", String.valueOf(r.size));
            return new WebResourceResponse(r.mime, null, 200, "OK", h, new ByteArrayInputStream(new byte[0]));
        }
        String range = header(req, "Range");
        try {
            if (range != null && r.size > 0) {
                Matcher m = Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);
                long start = 0;
                long end = -1;
                if (m.find()) {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Long.parseLong(m.group(2));
                }
                if (start >= r.size) {
                    h.put("Content-Range", "bytes */" + r.size);
                    return new WebResourceResponse(r.mime, null, 416, "Range Not Satisfiable", h, new ByteArrayInputStream(new byte[0]));
                }
                if (end < 0 || end - start + 1 > CHUNK) end = start + CHUNK - 1;
                end = Math.min(end, r.size - 1);
                Response up = fetchRange(r, start, end);
                if (up.code() == 403 || up.code() == 410) {
                    up.close();
                    r = resolve(id, true);
                    up = fetchRange(r, start, end);
                }
                if (!up.isSuccessful() || up.body() == null) {
                    int c = up.code();
                    up.close();
                    return error(503, "YouTube rechazó la reproducción (" + c + ")");
                }
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + r.size);
                h.put("Content-Length", String.valueOf(end - start + 1));
                return new WebResourceResponse(r.mime, null, 206, "Partial Content", h, up.body().byteStream());
            }
            // Sin Range (descargas): archivo completo, por tramos
            if (r.size > 0) h.put("Content-Length", String.valueOf(r.size));
            return new WebResourceResponse(r.mime, null, 200, "OK", h, new ChunkedStream(r));
        } catch (Exception e) {
            Log.e(TAG, "stream " + id, e);
            return error(503, "Error al cargar el audio");
        }
    }

    /** Lee el archivo completo pidiendo tramos consecutivos. */
    static final class ChunkedStream extends InputStream {
        private final Resolved r;
        private long pos = 0;
        private InputStream cur;
        private Response resp;

        ChunkedStream(Resolved r) {
            this.r = r;
        }

        private boolean next() throws IOException {
            closeCur();
            if (r.size > 0 && pos >= r.size) return false;
            long end = r.size > 0 ? Math.min(pos + CHUNK, r.size) - 1 : pos + CHUNK - 1;
            resp = fetchRange(r, pos, end);
            if (!resp.isSuccessful() || resp.body() == null) {
                closeCur();
                return false;
            }
            cur = resp.body().byteStream();
            return true;
        }

        private void closeCur() {
            if (resp != null) resp.close();
            resp = null;
            cur = null;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (true) {
                if (cur == null && !next()) return -1;
                int n = cur.read(b, off, len);
                if (n > 0) {
                    pos += n;
                    return n;
                }
                closeCur();
                if (r.size <= 0) return -1;
            }
        }

        @Override
        public void close() {
            closeCur();
        }
    }

    /** Proxy para archive.org (FLAC libre), con soporte de Range. */
    public static WebResourceResponse serveArchive(WebResourceRequest req, String target) {
        try {
            Uri u = Uri.parse(target);
            String host = u.getHost();
            if (!"https".equals(u.getScheme()) || host == null || !(host.equals("archive.org") || host.endsWith(".archive.org"))) {
                return error(400, "Solo archive.org");
            }
            Request.Builder b = new Request.Builder().url(target).header("User-Agent", "Ondas/1.0 (Android)");
            String range = header(req, "Range");
            if (range != null) b.header("Range", range);
            if ("HEAD".equalsIgnoreCase(req.getMethod())) b.head();
            Response up = HTTP.newCall(b.build()).execute();
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Accept-Ranges", "bytes");
            for (String k : new String[] {"Content-Length", "Content-Range"}) {
                String v = up.header(k);
                if (v != null) h.put(k, v);
            }
            String ct = up.header("Content-Type", "audio/flac");
            String mime = ct.split(";")[0].trim();
            int code = up.code();
            String reason = code == 206 ? "Partial Content" : (code < 300 ? "OK" : "Error");
            InputStream body = up.body() != null ? up.body().byteStream() : new ByteArrayInputStream(new byte[0]);
            return new WebResourceResponse(mime, null, code, reason, h, body);
        } catch (Exception e) {
            Log.e(TAG, "archive", e);
            return error(503, "No se pudo conectar con Internet Archive");
        }
    }
}
