package com.itwray.iw.external.zhaogang.notification;

import com.itwray.iw.external.zhaogang.profile.WorkbenchMobile;
import java.util.List;

/** Durable payload: mentions belong to one message, never to a whole batch. */
record NotificationMessage(String type, String content, List<String> mentionedMobiles) {
    NotificationMessage {
        if (!"text".equals(type) && !"markdown".equals(type)) throw new IllegalArgumentException("消息类型不支持");
        mentionedMobiles = mentionedMobiles == null ? List.of() : mentionedMobiles.stream()
                .map(WorkbenchMobile::normalize).filter(java.util.Objects::nonNull).distinct().toList();
        if (!"text".equals(type) && !mentionedMobiles.isEmpty()) throw new IllegalArgumentException("手机号提醒仅支持文本消息");
    }
    static NotificationMessage markdown(String content) { return new NotificationMessage("markdown", content, List.of()); }
    static NotificationMessage text(String content) { return new NotificationMessage("text", content, List.of()); }
    int byteLimit() { return "text".equals(type) ? 2048 : 4096; }
    @Override public String toString() { return "NotificationMessage[type=" + type + ", content=[redacted], mentionedMobiles=[redacted]]"; }
}
