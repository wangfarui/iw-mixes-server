package com.itwray.iw.external.handler;

import com.itwray.iw.external.remoteshare.RemoteShareSessionService;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Opaque two-party WebRTC signaling and online-message relay; it never parses or logs payload content. */
public class RemoteShareWebSocketHandler extends TextWebSocketHandler {

    private final RemoteShareSessionService sessionService;
    private final Map<String, Map<RemoteShareSessionService.DeviceSlot, WebSocketSession>> peers = new ConcurrentHashMap<>();

    public RemoteShareWebSocketHandler(RemoteShareSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Map<String, String> query = query(session.getUri());
        try {
            RemoteShareSessionService.SessionState state = sessionService.state(query.get("room"), query.get("capability"));
            session.getAttributes().put("room", query.get("room"));
            session.getAttributes().put("slot", state.slot());
            WebSocketSession previous = peers.computeIfAbsent(query.get("room"), ignored -> new ConcurrentHashMap<>())
                    .put(state.slot(), session);
            if (previous != null && previous.isOpen()) {
                previous.close(CloseStatus.NORMAL.withReason("Reconnected from another browser tab"));
            }
        } catch (RuntimeException exception) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Unauthorized"));
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, TextMessage message) throws IOException {
        String room = (String) session.getAttributes().get("room");
        RemoteShareSessionService.DeviceSlot slot = (RemoteShareSessionService.DeviceSlot) session.getAttributes().get("slot");
        if (room == null || slot == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Unauthorized"));
            return;
        }
        RemoteShareSessionService.DeviceSlot receiver = slot == RemoteShareSessionService.DeviceSlot.A
                ? RemoteShareSessionService.DeviceSlot.B : RemoteShareSessionService.DeviceSlot.A;
        WebSocketSession peer = peers.getOrDefault(room, Map.of()).get(receiver);
        if (peer != null && peer.isOpen()) {
            peer.sendMessage(message);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String room = (String) session.getAttributes().get("room");
        RemoteShareSessionService.DeviceSlot slot = (RemoteShareSessionService.DeviceSlot) session.getAttributes().get("slot");
        if (room != null && slot != null) {
            peers.computeIfPresent(room, (ignored, roomPeers) -> {
                roomPeers.remove(slot, session);
                return roomPeers.isEmpty() ? null : roomPeers;
            });
        }
    }

    private Map<String, String> query(URI uri) {
        if (uri == null || uri.getRawQuery() == null) {
            return Map.of();
        }
        Map<String, String> result = new ConcurrentHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] values = pair.split("=", 2);
            if (values.length == 2) {
                result.put(URLDecoder.decode(values[0], StandardCharsets.UTF_8), URLDecoder.decode(values[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
