package com.itwray.iw.starter.rocketmq.event;

import lombok.Getter;

/**
 * In-process message event.
 *
 * @author wray
 * @since 2026/6/27
 */
@Getter
public class LocalMessageEvent {

    private final String topic;

    private final String tag;

    private final Object body;

    private final String messageId;

    private LocalMessageEvent(String topic, String tag, Object body, String messageId) {
        this.topic = topic;
        this.tag = tag;
        this.body = body;
        this.messageId = messageId;
    }

    public static LocalMessageEvent of(String destination, Object body, String messageId) {
        String[] arr = destination.split(":", 2);
        return new LocalMessageEvent(arr[0], arr.length > 1 ? arr[1] : "", body, messageId);
    }
}
