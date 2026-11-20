package com.elbryan.ondas;

import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OndasNativePlugin.class);
        super.onCreate(savedInstanceState);
        YtEngine.init();
        AudioCache.init(getCacheDir());
        // Sirve /_yt/<id> (audio de YouTube) y /_ia?u=... desde el propio teléfono
        bridge.setWebViewClient(new OndasWebViewClient(bridge));
        bridge.getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
        // Botón Atrás: la app decide (cerrar reproductor, volver de pantalla...).
        // Si no hay nada que cerrar, se va al fondo sin detener la música.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                bridge.getWebView().evaluateJavascript(
                        "(window.ondasBack&&window.ondasBack())?'1':'0'",
                        v -> {
                            if (v == null || !v.contains("1")) moveTaskToBack(true);
                        });
            }
        });
    }
}
