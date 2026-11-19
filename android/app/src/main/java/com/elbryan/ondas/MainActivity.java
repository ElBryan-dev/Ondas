package com.elbryan.ondas;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OndasNativePlugin.class);
        super.onCreate(savedInstanceState);
        YtEngine.init();
        // Sirve /_yt/<id> (audio de YouTube) y /_ia?u=... (Internet Archive) desde el propio teléfono
        bridge.setWebViewClient(new OndasWebViewClient(bridge));
        bridge.getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
    }
}
