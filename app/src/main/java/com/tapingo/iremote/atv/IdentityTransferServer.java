package com.tapingo.iremote.atv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Temporary, one-use LAN upload server for transferring the existing
 * Tapingo Android TV Remote v2 PKCS12 identity from a laptop to the TV.
 * The PKCS12 password is never sent to this server or to the laptop browser.
 */
public final class IdentityTransferServer extends NanoHTTPD {

    public interface Listener {
        void onIdentityUploaded(File file);
        void onTransferError(String message);
    }

    private static final long MAX_UPLOAD_BYTES = 2L * 1024L * 1024L;

    private final Context appContext;
    private final String oneTimeToken;
    private final Listener listener;
    private volatile boolean uploadConsumed = false;

    public IdentityTransferServer(
            Context context,
            int port,
            String token,
            Listener listener
    ) {
        super("0.0.0.0", port);
        this.appContext = context.getApplicationContext();
        this.oneTimeToken = token;
        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();

        if (Method.GET.equals(session.getMethod()) && "/".equals(path)) {
            return htmlResponse();
        }

        if (!Method.POST.equals(session.getMethod()) || !"/upload".equals(path)) {
            return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain; charset=utf-8",
                    "Not found"
            );
        }

        if (uploadConsumed) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "text/plain; charset=utf-8",
                    "This transfer link has already been used."
            );
        }

        Map<String, String> parameters = session.getParms();
        String suppliedToken = parameters.get("token");
        if (suppliedToken == null || !constantTimeEquals(oneTimeToken, suppliedToken)) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    "text/plain; charset=utf-8",
                    "Invalid or expired transfer token."
            );
        }

        long contentLength = contentLength(session.getHeaders());
        if (contentLength <= 0 || contentLength > MAX_UPLOAD_BYTES) {
            return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "text/plain; charset=utf-8",
                    "Upload must be between 1 byte and 2 MB."
            );
        }

        File stagedFile = new File(
                appContext.getFilesDir(),
                "tapingo-identity-upload.pending.p12"
        );
        File partialFile = new File(
                appContext.getFilesDir(),
                "tapingo-identity-upload.partial"
        );

        try {
            if (partialFile.exists() && !partialFile.delete()) {
                throw new java.io.IOException("Could not clear previous partial upload.");
            }

            long copied = 0;
            byte[] buffer = new byte[8192];
            try (InputStream input = session.getInputStream();
                 FileOutputStream output = new FileOutputStream(partialFile)) {

                while (copied < contentLength) {
                    int wanted = (int) Math.min(buffer.length, contentLength - copied);
                    int count = input.read(buffer, 0, wanted);
                    if (count < 0) {
                        break;
                    }
                    output.write(buffer, 0, count);
                    copied += count;
                }
                output.flush();
                output.getFD().sync();
            }

            if (copied != contentLength || copied == 0) {
                throw new java.io.IOException("The upload was incomplete.");
            }

            // Basic PKCS12 file signature check: ASN.1 DER files start with 0x30.
            try (InputStream check = new java.io.FileInputStream(partialFile)) {
                if (check.read() != 0x30) {
                    throw new java.io.IOException("The uploaded file is not a valid PKCS12 file.");
                }
            }

            if (stagedFile.exists() && !stagedFile.delete()) {
                throw new java.io.IOException("Could not replace the previous staged upload.");
            }
            if (!partialFile.renameTo(stagedFile)) {
                throw new java.io.IOException("Could not save the uploaded identity.");
            }

            uploadConsumed = true;
            if (listener != null) {
                listener.onIdentityUploaded(stagedFile);
            }

            return newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain; charset=utf-8",
                    "Identity file received by the TV. You can return to the TV screen."
            );

        } catch (Exception e) {
            if (partialFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partialFile.delete();
            }
            if (listener != null) {
                listener.onTransferError(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
                );
            }
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8",
                    "Upload failed: " + safeMessage(e)
            );
        }
    }

    private Response htmlResponse() {
        String html = "<!doctype html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Tapingo identity transfer</title>"
                + "<style>body{font-family:Arial,sans-serif;background:#121826;color:#fff;"
                + "max-width:560px;margin:40px auto;padding:22px}"
                + "section{background:#202b3e;padding:22px;border-radius:14px}"
                + "input,button{box-sizing:border-box;width:100%;padding:14px;margin-top:14px;"
                + "font-size:16px;border-radius:8px}button{border:0;background:#87ebbe;"
                + "color:#121826;font-weight:bold}small{color:#c5cede}#status{white-space:pre-wrap}</style>"
                + "</head><body><section><h2>Tapingo iRemote</h2>"
                + "<h3>Transfer TV identity</h3>"
                + "<p>Select the <b>.p12</b> identity file created on this Windows laptop."
                + " The password is entered on the TV and is not requested here.</p>"
                + "<input id='file' type='file' accept='.p12,application/x-pkcs12'>"
                + "<button id='send'>Send identity to TV</button>"
                + "<p id='status'><small>Keep the TV and laptop on the same Wi-Fi network.</small></p>"
                + "</section><script>"
                + "const status=document.getElementById('status');"
                + "document.getElementById('send').addEventListener('click',async()=>{"
                + "const f=document.getElementById('file').files[0];"
                + "if(!f){status.textContent='Choose your .p12 file first.';return;}"
                + "if(f.size>2097152){status.textContent='File is larger than 2 MB.';return;}"
                + "status.textContent='Sending identity file to TV...';"
                + "try{const q=new URLSearchParams(location.search);"
                + "const r=await fetch('/upload?token='+encodeURIComponent(q.get('token')||''),"
                + "{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f});"
                + "const t=await r.text();status.textContent=t;"
                + "if(!r.ok)throw new Error(t);"
                + "}catch(e){status.textContent='Transfer failed: '+e.message;}"
                + "});</script></body></html>";

        Response response = newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                html
        );
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        return response;
    }

    private static long contentLength(Map<String, String> headers) {
        try {
            String value = headers.get("content-length");
            if (value == null) {
                value = headers.get("Content-Length");
            }
            return value == null ? -1 : Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        if (expected.length() != actual.length()) return false;
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) {
            difference |= expected.charAt(i) ^ actual.charAt(i);
        }
        return difference == 0;
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
