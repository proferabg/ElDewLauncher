package com.hpgnd.eldewlauncher;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;

import java.net.URI;
import java.util.Collections;
import java.util.function.Consumer;

public class Rcon extends WebSocketClient {
    private final Consumer<ServerHandshake> _onOpen;
    private final Consumer<String> _onMessage;
    private final Consumer<String> _onClose;
    private final Consumer<Exception> _onError;

    public Rcon(URI serverUri, Consumer<ServerHandshake> onOpen, Consumer<String> onMessage, Consumer<String> onClose, Consumer<Exception> onError) {
        super(serverUri, new Draft_6455(Collections.emptyList(), Collections.singletonList(new Protocol("dew-rcon"))));
        this._onOpen = onOpen;
        this._onMessage = onMessage;
        this._onClose = onClose;
        this._onError = onError;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        _onOpen.accept(serverHandshake);
    }

    @Override
    public void onMessage(String msg) {
        _onMessage.accept(msg);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        _onClose.accept("(" + code + ") " + reason);
    }

    @Override
    public void onError(Exception e) {
        _onError.accept(e);
    }
}
