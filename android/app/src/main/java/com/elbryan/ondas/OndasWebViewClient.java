package com.elbryan.ondas;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

public class OndasWebViewClient extends BridgeWebViewClient {
    public OndasWebViewClient(Bridge bridge) {
        super(bridge);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        String path = u.getPath();
        if (path != null && "localhost".equals(u.getHost())) {
            if (path.startsWith("/_yt/")) {
                return YtEngine.serveYoutube(request, path.substring(5));
            }
            if (path.equals("/_ia")) {
                String target = u.getQueryParameter("u");
                return YtEngine.serveArchive(request, target == null ? "" : target);
            }
        }
        return super.shouldInterceptRequest(view, request);
    }
}
