package com.elbryan.ondas;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

/** Implementación de red para NewPipeExtractor basada en OkHttp. */
public class NpDownloader extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";
    private final OkHttpClient client;

    public NpDownloader(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        final String url = request.url();
        final byte[] data = request.dataToSend();
        RequestBody body = null;
        if (data != null) {
            body = RequestBody.create(data);
        }
        final okhttp3.Request.Builder b = new okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(url)
                .addHeader("User-Agent", USER_AGENT);
        final Map<String, List<String>> headers = request.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                b.removeHeader(e.getKey());
                for (String v : e.getValue()) {
                    b.addHeader(e.getKey(), v);
                }
            }
        }
        try (okhttp3.Response r = client.newCall(b.build()).execute()) {
            if (r.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", url);
            }
            String text = null;
            try (ResponseBody rb = r.body()) {
                if (rb != null) {
                    text = rb.string();
                }
            }
            return new Response(r.code(), r.message(), r.headers().toMultimap(), text,
                    r.request().url().toString());
        }
    }
}
