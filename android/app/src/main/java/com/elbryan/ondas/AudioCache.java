package com.elbryan.ondas;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Descarga cada canción completa en segundo plano a un archivo en caché y la sirve
 * desde ahí. Así la reproducción no depende de que YouTube responda a tiempo cada tramo:
 * el audio se acumula por delante y nunca se corta. También permite precargar la siguiente.
 */
final class AudioCache {
    private static final String TAG = "OndasCache";
    private static final long MAX_BYTES = 600L * 1024 * 1024;
    private static final long CHUNK = 3L * 1024 * 1024;
    private static File dir;
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private AudioCache() { }

    static synchronized void init(File cacheDir) {
        if (dir != null) return;
        dir = new File(cacheDir, "audio");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "no se pudo crear " + dir);
    }

    static final class Entry {
        final String id;
        final File file;
        final long size;
        final String mime;
        final Object lock = new Object();
        volatile long have = 0;
        volatile boolean done = false;
        volatile boolean failed = false;
        volatile boolean running = false;

        Entry(String id, File file, long size, String mime) {
            this.id = id;
            this.file = file;
            this.size = size;
            this.mime = mime;
        }

        File marker() {
            return new File(file.getPath() + ".ok");
        }

        synchronized void start() {
            if (done || running) return;
            failed = false;
            running = true;
            Thread t = new Thread(this::download, "ondas-dl-" + id);
            t.setPriority(Thread.MAX_PRIORITY - 1);
            t.start();
        }

        private void download() {
            long pos = have;
            int fails = 0;
            YtEngine.Resolved r = null;
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                if (pos == 0) raf.setLength(0);
                byte[] buf = new byte[64 * 1024];
                while (pos < size) {
                    long end = Math.min(pos + CHUNK, size) - 1;
                    try {
                        if (r == null) r = YtEngine.resolve(id, fails > 2);
                        Response up = YtEngine.fetchRange(r, pos, end);
                        if (up.code() == 403 || up.code() == 410) {
                            up.close();
                            r = YtEngine.resolve(id, true);
                            throw new IOException("enlace caducado");
                        }
                        ResponseBody body = up.body();
                        if (!up.isSuccessful() || body == null) {
                            int c = up.code();
                            up.close();
                            throw new IOException("HTTP " + c);
                        }
                        try (InputStream in = body.byteStream()) {
                            int n;
                            while (pos <= end && (n = in.read(buf, 0, (int) Math.min(buf.length, end - pos + 1))) > 0) {
                                raf.seek(pos);
                                raf.write(buf, 0, n);
                                pos += n;
                                synchronized (lock) {
                                    have = pos;
                                    lock.notifyAll();
                                }
                            }
                        } finally {
                            up.close();
                        }
                        if (pos <= end) throw new IOException("tramo incompleto");
                        fails = 0;
                    } catch (Exception e) {
                        fails++;
                        Log.w(TAG, id + " fallo " + fails + " en " + pos + ": " + e.getMessage());
                        if (fails > 10) throw e;
                        try {
                            Thread.sleep(Math.min(4000, 400L * fails));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                }
                done = true;
                try {
                    if (!marker().createNewFile()) Log.d(TAG, "marcador ya existía");
                } catch (IOException ignored) {
                    // sin marcador solo se pierde la caché entre sesiones
                }
            } catch (Exception e) {
                Log.e(TAG, "descarga fallida " + id, e);
                failed = true;
            } finally {
                running = false;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        }
    }

    private static String ext(String mime) {
        return "audio/mp4".equals(mime) ? "m4a" : "webm";
    }

    private static Entry fromDisk(String id) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(id + ".") && name.endsWith(".ok"));
        if (files == null) return null;
        for (File ok : files) {
            String binName = ok.getName().substring(0, ok.getName().length() - 3);
            String[] parts = binName.split("\\.");
            if (parts.length < 3) continue;
            try {
                long size = Long.parseLong(parts[parts.length - 2]);
                File bin = new File(dir, binName);
                if (bin.length() == size) {
                    Entry e = new Entry(id, bin, size, binName.endsWith(".m4a") ? "audio/mp4" : "audio/webm");
                    e.have = size;
                    e.done = true;
                    return e;
                }
            } catch (NumberFormatException ignored) {
                // nombre inesperado
            }
        }
        return null;
    }

    /** Devuelve la entrada de la canción y arranca (o reanuda) su descarga. */
    static Entry get(String id) throws Exception {
        Entry e = ENTRIES.get(id);
        if (e == null || e.failed) {
            Object lock = LOCKS.computeIfAbsent(id, k -> new Object());
            synchronized (lock) {
                e = ENTRIES.get(id);
                if (e == null) {
                    e = fromDisk(id);
                    if (e == null) {
                        YtEngine.Resolved r = YtEngine.resolve(id, false);
                        if (r.size <= 0) throw new IOException("Tamaño desconocido");
                        e = new Entry(id, new File(dir, id + "." + r.size + "." + ext(r.mime)), r.size, r.mime);
                    }
                    ENTRIES.put(id, e);
                    evict(id);
                }
            }
        }
        if (!e.done) e.start();
        if (!e.file.setLastModified(System.currentTimeMillis())) Log.d(TAG, "sin fecha");
        return e;
    }

    private static synchronized void evict(String keep) {
        File[] all = dir.listFiles((d, name) -> !name.endsWith(".ok"));
        if (all == null) return;
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= MAX_BYTES) return;
        List<File> list = new ArrayList<>(Arrays.asList(all));
        list.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : list) {
            if (total <= MAX_BYTES) break;
            String id = f.getName().split("\\.")[0];
            Entry e = ENTRIES.get(id);
            if (id.equals(keep) || (e != null && e.running)) continue;
            total -= f.length();
            if (!f.delete()) Log.w(TAG, "no se pudo borrar " + f);
            File ok = new File(f.getPath() + ".ok");
            if (ok.exists() && !ok.delete()) Log.w(TAG, "no se pudo borrar " + ok);
            ENTRIES.remove(id);
        }
    }

    /**
     * Lee [start, end]: del archivo si ya está descargado; si piden una zona muy adelantada
     * (al adelantar la canción o al leer el final del archivo) la trae directo de la red
     * para no esperar a que la descarga llegue hasta ahí.
     */
    static final class FileStream extends InputStream {
        private static final long DIRECT_GAP = 768L * 1024;
        private final Entry e;
        private long pos;
        private final long end;
        private RandomAccessFile raf;
        private Response net;
        private InputStream netIn;
        private long netEnd = -1;
        private int netFails = 0;
        private YtEngine.Resolved r;

        FileStream(Entry e, long start, long end) {
            this.e = e;
            this.pos = start;
            this.end = end;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n <= 0 ? -1 : (b[0] & 0xff);
        }

        private void closeNet() {
            if (net != null) net.close();
            net = null;
            netIn = null;
        }

        private int readNet(byte[] b, int off, int len) throws IOException {
            while (true) {
                try {
                    if (netIn == null || pos > netEnd) {
                        closeNet();
                        if (r == null) r = YtEngine.resolve(e.id, false);
                        netEnd = Math.min(end, pos + CHUNK - 1);
                        Response up = YtEngine.fetchRange(r, pos, netEnd);
                        if (up.code() == 403 || up.code() == 410) {
                            up.close();
                            r = YtEngine.resolve(e.id, true);
                            throw new IOException("caducado");
                        }
                        if (!up.isSuccessful() || up.body() == null) {
                            int c = up.code();
                            up.close();
                            throw new IOException("HTTP " + c);
                        }
                        net = up;
                        netIn = up.body().byteStream();
                    }
                    int n = netIn.read(b, off, (int) Math.min(len, netEnd - pos + 1));
                    if (n > 0) {
                        pos += n;
                        netFails = 0;
                        return n;
                    }
                    closeNet();
                    if (pos > end) return -1;
                } catch (Exception ex) {
                    closeNet();
                    if (++netFails > 5) throw new IOException(ex);
                    try {
                        Thread.sleep(300L * netFails);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrumpido");
                    }
                }
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos > end) return -1;
            long waitedSince = System.currentTimeMillis();
            long lastHave = e.have;
            while (e.have <= pos) {
                if (!e.done && pos - e.have > DIRECT_GAP) return readNet(b, off, len);
                if (e.failed) e.start();
                synchronized (e.lock) {
                    if (e.have <= pos) {
                        try {
                            e.lock.wait(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrumpido");
                        }
                    }
                }
                if (e.have != lastHave) {
                    lastHave = e.have;
                    waitedSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - waitedSince > 8000) {
                    return readNet(b, off, len); // la descarga se atascó: seguir por la red
                }
            }
            closeNet();
            if (raf == null) raf = new RandomAccessFile(e.file, "r");
            long avail = Math.min(e.have, end + 1) - pos;
            int n = (int) Math.min(len, avail);
            raf.seek(pos);
            int read = raf.read(b, off, n);
            if (read > 0) pos += read;
            return read;
        }

        @Override
        public void close() throws IOException {
            closeNet();
            if (raf != null) raf.close();
        }
    }
}
