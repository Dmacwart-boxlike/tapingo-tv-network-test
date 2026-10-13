package com.tapingo.iremote;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends Activity {
    private TestServer server;
    private TextView status;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 40, 48, 40);
        root.setBackgroundColor(Color.rgb(18, 24, 38));

        TextView title = new TextView(this);
        title.setText("Tapingo iRemote\nNETWORK TEST");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.rgb(135, 235, 190));
        status.setTextSize(21);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 36, 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView note = new TextView(this);
        note.setText("Keep this screen open for the test.\nOn your iPhone, open Safari and enter the address shown below, followed by /ping.\n\nThis test does not control the TV yet.");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(18);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 28, 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        try {
            server = new TestServer();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            String ip = getLocalIpAddress();
            status.setText("Server running\n\nhttp://" + ip + ":8080/ping");
        } catch (Exception e) {
            status.setText("Server failed to start\n" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "<TV-IP>";
    }

    private static class TestServer extends NanoHTTPD {
        TestServer() { super("0.0.0.0", 8080); }
        @Override public Response serve(IHTTPSession session) {
            String path = session.getUri();
            String body;
            if ("/ping".equals(path)) {
                body = "{\"ok\":true,\"app\":\"Tapingo iRemote Network Test\",\"message\":\"iPhone reached the TV\"}";
                Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body);
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Access-Control-Allow-Private-Network", "true");
                r.addHeader("Cache-Control", "no-store");
                return r;
            }
            body = "<html><meta name='viewport' content='width=device-width, initial-scale=1'><body style='font-family:sans-serif;background:#121826;color:white;padding:2rem'><h1>Tapingo iRemote</h1><p>TV network test server is running.</p><p>Try <a style='color:#87ebbE' href='/ping'>/ping</a></p></body></html>";
            Response r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body);
            r.addHeader("Access-Control-Allow-Origin", "*");
            r.addHeader("Access-Control-Allow-Private-Network", "true");
            return r;
        }
    }
}
