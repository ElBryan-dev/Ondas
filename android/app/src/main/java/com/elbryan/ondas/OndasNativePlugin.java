package com.elbryan.ondas;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.IOException;
import java.util.Iterator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** fetch nativo (sin CORS) para el catálogo de YouTube Music e Internet Archive. */
@CapacitorPlugin(name = "OndasNative")
public class OndasNativePlugin extends Plugin {
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    @PluginMethod
    public void warm(PluginCall call) {
        try {
            java.util.List<String> ids = call.getArray("ids").toList();
            YtEngine.warm(ids);
        } catch (Exception e) {
            // lista inválida: se ignora
        }
        call.resolve();
    }

    @PluginMethod
    public void prefetch(PluginCall call) {
        String id = call.getString("id");
        if (id != null) YtEngine.prefetch(id);
        call.resolve();
    }

    @PluginMethod
    public void fetch(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("url requerida");
            return;
        }
        String method = call.getString("method", "GET").toUpperCase();
        JSObject headers = call.getObject("headers", new JSObject());
        String b64 = call.getString("body");
        Request.Builder b = new Request.Builder().url(url);
        String contentType = null;
        boolean hasUa = false;
        boolean hasOrigin = false;
        Iterator<String> it = headers.keys();
        while (it.hasNext()) {
            String k = it.next();
            String lk = k.toLowerCase();
            String v = headers.optString(k, null);
            if (v == null) continue;
            if (lk.equals("accept-encoding") || lk.equals("content-length") || lk.equals("host") || lk.equals("connection")) continue;
            if (lk.equals("content-type")) contentType = v;
            if (lk.equals("user-agent")) hasUa = true;
            if (lk.equals("origin")) hasOrigin = true;
            try {
                b.header(k, v);
            } catch (IllegalArgumentException e) {
                // cabecera inválida: se ignora
            }
        }
        if (!hasUa) b.header("User-Agent", UA);
        if (!hasOrigin && url.contains("youtube.com/youtubei")) {
            String origin = url.contains("music.youtube.com") ? "https://music.youtube.com" : "https://www.youtube.com";
            b.header("Origin", origin);
            b.header("Referer", origin + "/");
        }
        RequestBody body = null;
        if (b64 != null && !b64.isEmpty()) {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            body = RequestBody.create(data, contentType != null ? MediaType.parse(contentType) : null);
        } else if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            body = RequestBody.create(new byte[0], (MediaType) null);
        }
        b.method(method, body);
        YtEngine.HTTP.newCall(b.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                call.reject("network: " + e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response r) {
                try (ResponseBody rb = r.body()) {
                    byte[] bytes = rb != null ? rb.bytes() : new byte[0];
                    JSObject h = new JSObject();
                    Headers hs = r.headers();
                    for (String name : hs.names()) {
                        String ln = name.toLowerCase();
                        if (ln.equals("content-encoding") || ln.equals("content-length") || ln.equals("transfer-encoding")) continue;
                        h.put(ln, android.text.TextUtils.join(", ", hs.values(name)));
                    }
                    JSObject ret = new JSObject();
                    ret.put("status", r.code());
                    ret.put("headers", h);
                    ret.put("url", r.request().url().toString());
                    ret.put("body", Base64.encodeToString(bytes, Base64.NO_WRAP));
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("read: " + e.getMessage());
                }
            }
        });
    }
}
