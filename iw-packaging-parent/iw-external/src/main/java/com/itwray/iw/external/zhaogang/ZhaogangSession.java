package com.itwray.iw.external.zhaogang;

/** 浏览器 Cookie 中加密保存的 CODING 会话数据。 */
record ZhaogangSession(String token, Long userId, String userName, String avatar, String team, Long teamId) {

    ZhaogangSession(String token, Long userId, String userName, String avatar, String team) {
        this(token, userId, userName, avatar, team, null);
    }
}
