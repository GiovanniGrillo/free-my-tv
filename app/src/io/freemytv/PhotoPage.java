package io.freemytv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The page a phone opens from the QR code in My Photos (From your phone), to send photos from its gallery:
 * http://<the TV's address on the home network>:<a port the system picks>/<a random token>. The phone makes each photo
 * smaller first (at most 2400 px, JPEG: quick on the Wi-Fi, and no GPS or other tags come along) and sends them one at
 * a time; each lands in Photos' private inbox and is imported as tools/photos.py's are. Open while the code shows on
 * the TV (30 minutes at most). Plain HTTP on the LAN address only, as KeyPage: a phone can't check a certificate for a
 * LAN address, and the token keeps other devices on the network out.
 */
final class PhotoPage {
    /** Called on the page's own threads, never the UI's. */
    interface Listener {
        /** A photo came and waits in the inbox. */
        void received();
        /** The page stopped (closed, or 30 minutes). Called once. */
        void closed();
    }

    private static final long OPEN_FOR = 30 * 60_000L;
    private static final int MAX_HEAD = 8192, MAX_PHOTO = 20 * 1024 * 1024, MAX_ANSWERING = 3;
    /** A whole request: a 20 MB photo over a slow hotspot still fits. */
    private static final long READ_FOR_NS = 180_000_000_000L;

    final String url;
    private final Context app;
    private final ServerSocket server;
    private final String path;
    private final Listener listener;
    private final AtomicInteger answering = new AtomicInteger(), count = new AtomicInteger();
    private volatile boolean closed;

    PhotoPage(Context c, InetAddress lan, Listener listener) throws IOException {
        app = c.getApplicationContext();
        this.listener = listener;
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        path = "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = new ServerSocket(0, 4, lan);
        url = "http://" + lan.getHostAddress() + ":" + server.getLocalPort() + path;
        Thread t = new Thread(this::serve, "PhotoPage");
        t.setDaemon(true);
        t.start();
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        long until = System.nanoTime() + OPEN_FOR * 1_000_000L;
        try {
            while (!closed) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0) break;
                server.setSoTimeout((int) Math.min(left, 30_000));
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (answering.incrementAndGet() > MAX_ANSWERING) {
                    answering.decrementAndGet();
                    socket.close();
                    continue;
                }
                Thread t = new Thread(() -> answer(socket), "PhotoPage answer");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (!closed) AppLog.w("photo page: " + e.getMessage());
        } finally {
            close();
            listener.closed();
        }
    }

    private void answer(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20_000);
            handle(s.getInputStream(), s.getOutputStream(), System.nanoTime() + READ_FOR_NS);
        } catch (IOException | RuntimeException e) {
            // A phone that went away midway: the page stays open. Nothing may escape this thread (:home's watchdog).
        } finally {
            answering.decrementAndGet();
        }
    }

    private void handle(InputStream in, OutputStream out, long deadline) throws IOException {
        String head = readHead(in, deadline);
        if (head == null) return;
        String[] line = head.substring(0, Math.max(0, head.indexOf("\r\n"))).split(" ");
        String method = line.length > 1 ? line[0] : "", target = line.length > 1 ? line[1] : "";
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        if (target.equals(path) && (method.equals("GET") || method.equals("HEAD"))) {
            respond(out, "200 OK", "text/html; charset=utf-8", method.equals("HEAD") ? new byte[0] : page());
            return;
        }
        if (!target.equals(path + "/photo") || !method.equals("POST")) {
            respond(out, "404 Not Found", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        int length = contentLength(head);
        if (length <= 0 || length > MAX_PHOTO) {
            respond(out, "413 Payload Too Large", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        if (closed) {
            respond(out, "410 Gone", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        if (Photos.count(app) >= Photos.MAX) {
            respond(out, "409 Conflict", "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        File inbox = Photos.privateInbox(app);
        if (!inbox.isDirectory() && !inbox.mkdirs()) throw new IOException("no inbox");
        String name = String.format(Locale.US, "phone-%013d-%03d.jpg", System.currentTimeMillis(), count.incrementAndGet() % 1000);
        // Hidden while it's written (an import meanwhile leaves it alone), named once all of it came.
        File part = new File(inbox, "." + name + ".part");
        boolean whole = false;
        try (OutputStream os = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int left = length, first = -1;
            while (left > 0) {
                int got = in.read(buf, 0, Math.min(buf.length, left));
                if (got < 0 || System.nanoTime() > deadline) throw new IOException("cut short");
                if (first < 0) first = (buf[0] & 0xFF) << 8 | (got > 1 ? buf[1] & 0xFF : 0);
                if (first != 0xFFD8) throw new IOException("not a JPEG");   // the page sends JPEG only
                os.write(buf, 0, got);
                left -= got;
            }
            whole = true;
        } finally {
            if (!whole) part.delete();
        }
        if (!part.renameTo(new File(inbox, name))) {
            part.delete();
            throw new IOException("can't name " + name);
        }
        respond(out, "200 OK", "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.US_ASCII));
        listener.received();
    }

    private static String readHead(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0, b;
        while (matched < 4 && (b = in.read()) >= 0) {
            if (head.size() >= MAX_HEAD || System.nanoTime() > deadline) return null;
            head.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1
                    : b == '\r' ? 1 : 0;
        }
        return matched == 4 ? head.toString(StandardCharsets.ISO_8859_1.name()) : null;
    }

    private static int contentLength(String head) {
        for (String h : head.split("\r\n")) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(h.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void respond(OutputStream out, String status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff"
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // MARK: - The page (in the TV's language)

    private byte[] page() {
        StringBuilder b = new StringBuilder(6144).append("<!doctype html><html lang=\"").append(esc(Ui.language))
                .append("\"><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">")
                .append("<title>Free My TV</title><style>")
                .append("body{margin:0 auto;max-width:34em;padding:24px;font:17px/1.5 system-ui,-apple-system,sans-serif;")
                .append("background:#1c1c1e;color:#f5f5f7}h1{font-size:24px}")
                .append("label,button{display:block;box-sizing:border-box;width:100%;margin:14px 0;padding:16px;border:0;")
                .append("border-radius:12px;font:600 17px system-ui,sans-serif;text-align:center}")
                .append("label{background:#3a3a3c;color:#f5f5f7}button{background:#f5f5f7;color:#1c1c1e}")
                .append("button:disabled{opacity:.4}input{display:none}.s{color:#98989d;font-size:15px}")
                .append("#st{font-size:19px;min-height:1.5em}.ok{color:#7ee08a}.no{color:#ffc266}</style>")
                .append("<h1>").append(esc(Ui.tr("My Photos"))).append(" · Free My TV</h1>")
                .append("<label>").append(esc(Ui.tr("Choose photos")))
                .append("<input type=file id=f accept=\"image/*\" multiple></label>")
                .append("<button id=go disabled>").append(esc(Ui.tr("Send to the TV"))).append("</button>")
                .append("<p id=st></p><p class=s>")
                .append(esc(Ui.tr("They go straight to the TV over this Wi-Fi, made smaller, without their location.")))
                .append("</p><script>")
                .append("var T={chosen:").append(js(Ui.tr("Chosen: {n}")))
                .append(",sending:").append(js(Ui.tr("Sending {n} of {total}…")))
                .append(",sent:").append(js(Ui.tr("Sent: {n}. They show on the TV in a moment.")))
                .append(",full:").append(js(Ui.trf("The TV holds {n} photos at most: remove some there first.", "n", Photos.MAX)))
                .append(",failed:").append(js(Ui.tr("Not arrived: {n}. Send them again.")))
                .append(",gone:").append(js(Ui.tr("The code closed on the TV: open it again there.")))
                .append("};").append(SCRIPT).append("</script></html>");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The phone's side: makes each photo at most 2400 px on its long side (turned upright: browsers apply EXIF when they
     * decode), as JPEG, and sends them one by one.
     */
    private static final String SCRIPT =
            "var f=document.getElementById('f'),go=document.getElementById('go'),st=document.getElementById('st');"
            + "function pl(p,n){return p.replace('{n}',n)}"
            + "function say(t,c){st.textContent=t;st.className=c||''}"
            + "f.onchange=function(){go.disabled=!f.files.length;say(f.files.length?pl(T.chosen,f.files.length):'')};"
            + "function load(file){if(window.createImageBitmap)return createImageBitmap(file,{imageOrientation:'from-image'})"
            + ".catch(function(){return img(file)});return img(file)}"
            + "function img(file){return new Promise(function(ok,no){var i=new Image();i.onload=function(){ok(i)};i.onerror=no;"
            + "i.src=URL.createObjectURL(file)})}"
            + "function shrink(file){return load(file).then(function(b){var w=b.width,h=b.height,s=Math.min(1,2400/Math.max(w,h));"
            + "var c=document.createElement('canvas');c.width=Math.round(w*s);c.height=Math.round(h*s);"
            + "c.getContext('2d').drawImage(b,0,0,c.width,c.height);"
            + "return new Promise(function(ok,no){c.toBlob(function(x){x?ok(x):no()},'image/jpeg',0.88)})})}"
            + "go.onclick=function(){var list=[].slice.call(f.files),i=0,done=0,bad=0;go.disabled=true;"
            + "function next(){if(i>=list.length){say(bad?pl(T.failed,bad):pl(T.sent,done),bad?'no':'ok');go.disabled=false;"
            + "f.value='';return}"
            + "say(T.sending.replace('{n}',i+1).replace('{total}',list.length));var file=list[i++];"
            + "shrink(file).then(function(b){return fetch(location.pathname+'/photo',{method:'POST',"
            + "headers:{'Content-Type':'image/jpeg'},body:b})}).then(function(r){"
            + "if(r.status==409){say(T.full,'no');go.disabled=false;throw 'stop'}"
            + "if(r.status==410||r.status==404){say(T.gone,'no');throw 'stop'}"
            + "if(r.ok)done++;else bad++;next()}).catch(function(e){if(e==='stop')return;bad++;next()})}next()};";

    private static String js(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '<') b.append("\\u003c");
            else if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "PhotoPage(port %d)", server.getLocalPort());   // never the token
    }
}
