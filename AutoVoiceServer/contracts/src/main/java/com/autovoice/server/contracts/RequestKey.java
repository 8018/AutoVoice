package com.autovoice.server.contracts;

/**
 * D06a 复合请求标识:主体(subject)/会话(session)/轮次(turn)/执行请求(request)。
 * 用于在途任务登记与取消,消除仅 utteranceId 索引造成的跨设备冲突。
 * 字段归一化:null → 空串;不得携带秘密(令牌等)进入键。
 */
public record RequestKey(String subject, String session, String turn, String request) {

    public RequestKey {
        subject = subject == null ? "" : subject;
        session = session == null ? "" : session;
        turn = turn == null ? "" : turn;
        request = request == null ? "" : request;
    }

    public static RequestKey of(String subject, String session, String turn, String request) {
        return new RequestKey(subject, session, turn, request);
    }
}
