package com.autovoice.server.contracts;

/**
 * 回复，sealed 风格（非层级，单类 + kind 字段），与网关协议 reply 消息的三种形态对应。
 *
 * <p>访问器语义（供网关消费）：</p>
 * <ul>
 *   <li>text 回复：{@code text()} / {@code speakText()} 可取，其余访问器返回 null；</li>
 *   <li>audio 回复：{@code mime()} / {@code data()} 可取；</li>
 *   <li>action 回复：{@code intent()} / {@code speakText()} 可取。</li>
 * </ul>
 *
 * <p>所有"不属于本 kind"的访问器均返回 null 而非抛异常，便于网关按 kind 分支后安全读取。</p>
 */
public final class Reply {

    private final String kind;
    private final String text;
    private final String speakText;
    private final String mime;
    private final byte[] data;
    private final Intent intent;

    private Reply(String kind, String text, String speakText, String mime, byte[] data, Intent intent) {
        this.kind = kind;
        this.text = text;
        this.speakText = speakText;
        this.mime = mime;
        this.data = data;
        this.intent = intent;
    }

    public static Reply ofText(String text) {
        // speakText 与 text 一致，匹配 fixture 中 text 形态含 speakText 的惯例
        return new Reply("text", text, text, null, null, null);
    }

    public static Reply ofAudio(String mime, byte[] data) {
        return new Reply("audio", null, null, mime, data, null);
    }

    /** S2S 音频回复；可同时携带最终口语文本和需由端侧执行一次的 intent。 */
    public static Reply ofAudio(String mime, byte[] data, String speakText, Intent intent) {
        return new Reply("audio", null, speakText, mime, data, intent);
    }

    public static Reply ofAction(Intent intent, String speakText) {
        return new Reply("action", null, speakText, null, null, intent);
    }

    /** "text" / "audio" / "action" 三值之一。 */
    public String kind() {
        return kind;
    }

    /** 仅 text 回复有值，其余为 null。 */
    public String text() {
        return text;
    }

    /** text / action 回复有值；S2S audio 回复可携带最终口语文本。 */
    public String speakText() {
        return speakText;
    }

    /** 仅 audio 回复有值。 */
    public String mime() {
        return mime;
    }

    /** 仅 audio 回复有值。 */
    public byte[] data() {
        return data;
    }

    /** action 或带终局工具调用的 S2S audio 回复有值。 */
    public Intent intent() {
        return intent;
    }
}
