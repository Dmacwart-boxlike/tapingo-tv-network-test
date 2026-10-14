package com.tapingo.iremote;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

public class MainActivity extends Activity {

    private TestServer httpServer;
    private WebSocketTestServer wsServer;
    private TextView status;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(Color.rgb(18, 24, 38));

        TextView title = new TextView(this);
        title.setText("Tapingo iRemote\nNETWORK TEST");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.rgb(135, 235, 190));
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 28, 0, 0);
        root.addView(
                status,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView note = new TextView(this);
        note.setText(
                "\nKeep this screen open during testing.\n\n" +
                "1. Open the address below on your iPhone.\n" +
                "2. Tap Connect WebSocket.\n" +
                "3. Send a test message.\n\n" +
                "This tests communication only; it does not control the TV yet."
        );
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(17);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 24, 0, 0);
        root.addView(note);

        setContentView(root);

        startServers();
    }

    private void startServers() {
        try {
            String ip = getLocalIpAddress();

            httpServer = new TestServer();
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

            wsServer = new WebSocketTestServer();
            wsServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

            status.setText(
                    "HTTP server: RUNNING\n" +
                    "WebSocket server: RUNNING\n\n" +
                    "Open on your iPhone:\n" +
                    "http://" + ip + ":8080/\n\n" +
                    "WebSocket port: 8081"
            );

        } catch (Exception e) {
            stopServers();

            status.setText(
                    "Server failed to start\n\n" +
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
    }

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface ni :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {

                for (java.net.InetAddress address :
                        Collections.list(ni.getInetAddresses())) {

                    if (!address.isLoopbackAddress()
                            && address instanceof Inet4Address) {

                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "<TV-IP>";
    }

    private void stopServers() {
        if (wsServer != null) {
            wsServer.stop();
            wsServer = null;
        }

        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopServers();
        super.onDestroy();
    }

    private static class TestServer extends NanoHTTPD {

        TestServer() {
            super("0.0.0.0", 8080);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String path = session.getUri();

            if ("/ping".equals(path)) {
                String body =
                        "{\"ok\":true," +
                        "\"app\":\"Tapingo iRemote Network Test\"," +
                        "\"message\":\"iPhone reached the TV\"}";

                Response response = newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json; charset=utf-8",
                        body
                );

                response.addHeader("Cache-Control", "no-store");
                return response;
            }

            String html =
                    "<!doctype html>" +
                    "<html><head>" +
                    "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                    "<title>Tapingo WebSocket Test</title>" +
                    "<style>" +
                    "body{font-family:Arial,sans-serif;background:#121826;color:white;" +
                    "padding:22px;max-width:600px;margin:auto}" +
                    "button,input{font-size:17px;padding:12px;margin:6px 0;" +
                    "width:100%;box-sizing:border-box;border-radius:8px}" +
                    "button{background:#87ebbe;border:0;color:#121826;font-weight:bold}" +
                    "input{background:#fff;color:#111;border:0}" +
                    "#log{white-space:pre-wrap;overflow-wrap:anywhere;" +
                    "background:#202b3e;padding:12px;border-radius:8px;min-height:90px}" +
                    "</style></head><body>" +

                    "<h1>Tapingo iRemote</h1>" +
                    "<h2>WebSocket Connection Test</h2>" +
                    "<p>This page tests two-way communication with your TV.</p>" +

                    "<button onclick='connectWS()'>Connect WebSocket</button>" +
                    "<p id='state'>Status: Not connected</p>" +

                    "<input id='message' value='Hello from iPhone' " +
                    "placeholder='Enter a test message'>" +

                    "<button onclick='sendWS()'>Send Test Message</button>" +
                    "<button onclick='disconnectWS()'>Disconnect</button>" +

                    "<h3>Connection log</h3>" +
                    "<div id='log'>Waiting for test...</div>" +

                    "<script>" +
                    "let socket=null;" +
                    "let heartbeatTimer=null;" +
                    "let reconnectTimer=null;" +
                    "let manuallyDisconnected=false;" +
                    "let reconnectDelay=1500;" +
                    "let lastMessageTime=0;" +

                    "function log(s){" +
                    "document.getElementById('log').textContent+=" +
                    "'\\n'+new Date().toLocaleTimeString()+' — '+s;" +
                    "}" +

                    "function setState(s){" +
                    "document.getElementById('state').textContent='Status: '+s;" +
                    "}" +

                    "function stopHeartbeat(){" +
                    "if(heartbeatTimer)clearInterval(heartbeatTimer);" +
                    "heartbeatTimer=null;" +
                    "}" +

                    "function stopReconnectTimer(){" +
                    "if(reconnectTimer)clearTimeout(reconnectTimer);" +
                    "reconnectTimer=null;" +
                    "}" +

                    "function scheduleReconnect(){" +
                    "if(manuallyDisconnected||document.hidden||reconnectTimer)return;" +
                    "setState('RECONNECTING');" +
                    "log('Reconnecting in '+(reconnectDelay/1000)+' seconds');" +
                    "reconnectTimer=setTimeout(function(){" +
                    "reconnectTimer=null;" +
                    "connectWS();" +
                    "},reconnectDelay);" +
                    "reconnectDelay=Math.min(reconnectDelay*2,10000);" +
                    "}" +

                    "function connectWS(){" +
                    "if(manuallyDisconnected)return;" +
                    "if(document.hidden)return;" +
                    "if(socket&&(socket.readyState===WebSocket.OPEN||" +
                    "socket.readyState===WebSocket.CONNECTING))return;" +

                    "stopReconnectTimer();" +
                    "stopHeartbeat();" +

                    "const host=window.location.hostname;" +
                    "setState('CONNECTING');" +
                    "log('Connecting to '+host+':8081/ws');" +

                    "try{" +
                    "socket=new WebSocket('ws://'+host+':8081/ws');" +
                    "}catch(e){" +
                    "log('Could not create WebSocket: '+e.message);" +
                    "scheduleReconnect();" +
                    "return;" +
                    "}" +

                    "socket.onopen=function(){" +
                    "reconnectDelay=1500;" +
                    "lastMessageTime=Date.now();" +
                    "setState('CONNECTED');" +
                    "log('WebSocket connected');" +

                    "stopHeartbeat();" +
                    "heartbeatTimer=setInterval(function(){" +
                    "if(socket&&socket.readyState===WebSocket.OPEN){" +
                    "socket.send('HEARTBEAT');" +
                    "log('Heartbeat sent');" +
                    "}" +
                    "},2000);" +
                    "};" +

                    "socket.onmessage=function(e){" +
                    "lastMessageTime=Date.now();" +
                    "log('TV replied: '+e.data);" +
                    "};" +

                    "socket.onerror=function(){" +
                    "log('WebSocket error; waiting for connection to close');" +
                    "};" +

                    "socket.onclose=function(e){" +
                    "stopHeartbeat();" +
                    "socket=null;" +
                    "setState(manuallyDisconnected?'DISCONNECTED':'DISCONNECTED — RETRYING');" +
                    "log('Connection closed (code '+e.code+')');" +
                    "scheduleReconnect();" +
                    "};" +
                    "}" +

                    "function sendWS(){" +
                    "if(!socket||socket.readyState!==WebSocket.OPEN){" +
                    "log('Not connected. Please wait for reconnection.');" +
                    "return;" +
                    "}" +
                    "const message=document.getElementById('message').value;" +
                    "socket.send(message);" +
                    "log('iPhone sent: '+message);" +
                    "}" +

                    "function disconnectWS(){" +
                    "manuallyDisconnected=true;" +
                    "stopHeartbeat();" +
                    "stopReconnectTimer();" +
                    "if(socket){" +
                    "const oldSocket=socket;" +
                    "socket=null;" +
                    "oldSocket.close();" +
                    "}" +
                    "setState('DISCONNECTED');" +
                    "log('Disconnected manually');" +
                    "}" +

                    "document.addEventListener('visibilitychange',function(){" +
                    "if(!document.hidden){" +
                    "log('Page active; checking connection');" +
                    "if(!manuallyDisconnected&&(!socket||" +
                    "socket.readyState!==WebSocket.OPEN)){" +
                    "stopReconnectTimer();" +
                    "connectWS();" +
                    "}" +
                    "}" +
                    "});" +

                    "window.addEventListener('pageshow',function(){" +
                    "if(!document.hidden&&!manuallyDisconnected&&" +
                    "(!socket||socket.readyState!==WebSocket.OPEN)){" +
                    "stopReconnectTimer();" +
                    "connectWS();" +
                    "}" +
                    "});" +
                    "</script></body></html>";

            Response response = newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html; charset=utf-8",
                    html
            );

            response.addHeader("Cache-Control", "no-store");
            return response;
        }
    }

    private static class WebSocketTestServer extends NanoWSD {

        WebSocketTestServer() {
            super("0.0.0.0", 8081);
        }

        @Override
        protected WebSocket openWebSocket(IHTTPSession handshake) {
            return new TestWebSocket(handshake);
        }

        @Override
        public Response serve(IHTTPSession session) {
            if (!"/ws".equals(session.getUri())) {
                return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain; charset=utf-8",
                        "WebSocket test server. Connect to /ws."
                );
            }

            return super.serve(session);
        }

        private static class TestWebSocket extends WebSocket {

            TestWebSocket(IHTTPSession handshake) {
                super(handshake);
            }

            @Override
            protected void onOpen() {
                try {
                    send("CONNECTED: iPhone reached the TV WebSocket server.");
                } catch (IOException e) {
                    onException(e);
                }
            }

            @Override
            protected void onMessage(WebSocketFrame message) {
                try {
                    String text = message.getTextPayload();

                    if (text == null) {
                        text = "";
                    }

                    send("TV ECHO: " + text);

                } catch (IOException e) {
                    onException(e);
                }
            }

            @Override
            protected void onClose(
                    WebSocketFrame.CloseCode code,
                    String reason,
                    boolean initiatedByRemote
            ) {
                // The test connection has closed.
            }

            @Override
            protected void onPong(WebSocketFrame pong) {
                // No additional action is needed for this test.
            }

            @Override
            protected void onException(IOException exception) {
                // The browser will show a failed connection if one occurs.
            }
        }
    }
}
