# Ondas

Reproductor de música para Android, al estilo de Spotify y YouTube Music.

- **YouTube Music**: búsqueda, álbumes, artistas, radio automática y letras. Funciona directo desde el teléfono, sin servidor.
- **FLAC sin pérdidas**: tu propia música (hasta 24-bit/192 kHz) y el catálogo libre de Internet Archive.
- Descargas para escuchar sin conexión, playlists, "Me gusta", cola, aleatorio y repetir.
- Ecualizador de 10 bandas, temporizador de apagado y controles en la pantalla de bloqueo.

## Instalar
Descarga **Ondas.apk** de la [última versión](../../releases/latest) y ábrelo en tu teléfono.

## Compilación
Cada cambio en `main` compila el APK automáticamente con GitHub Actions y lo publica en *Releases*.

- `web/`: interfaz (HTML/CSS/JS)
- `src/native.js`: catálogo de YouTube Music (youtubei.js)
- `android/`: app nativa (Capacitor); el audio se obtiene con NewPipeExtractor
