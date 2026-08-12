package com.autovoice.server.telemetry;

import com.autovoice.server.contracts.telemetry.TelemetryEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 链路数据平台 HTTP 端点（{@code /api/telemetry}，同进程同端口）：
 * <ul>
 *   <li>{@code POST /round}：端侧轮次事件包（整包入库 + SSE 推摘要）</li>
 *   <li>{@code POST /events}：tts-server 事件转发（body {@code {utteranceId, events[]}}，
 *       逐条 record——Task 5 TtsTelemetryForwarder 调用）</li>
 *   <li>{@code POST /audio}：PCM multipart 上传（加 WAV 头落盘）</li>
 *   <li>{@code GET /rounds} / {@code GET /rounds/{utteranceId}}：列表 / 单轮明细</li>
 *   <li>{@code GET /stream}：SSE，新轮次入库即推 round 摘要</li>
 *   <li>{@code GET /audio/{file}}：回放下载（audio/wav）</li>
 * </ul>
 * 禁用（{@code autovoice.telemetry.enabled=false}）时整个模块不装配。
 */
@RestController
@RequestMapping("/api/telemetry")
@ConditionalOnProperty(prefix = "autovoice.telemetry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TelemetryController {

    /** /events 转发 body（Task 5）：{utteranceId, events[]}。 */
    public record EventBatch(String utteranceId, List<TelemetryEvent> events) {
    }

    /**
     * SSE 发送线程池（daemon）：emitter.send 移到独立线程（review finding 2）——service 的
     * listener 回调在 telemetry 写线程上执行，直接 send 在客户端不读时会阻塞写线程连带
     * 查询卡死；这里只做入队（微秒级），实际 send 由 daemon 线程执行，阻塞只影响发送侧。
     */
    private final ExecutorService sseSender = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "telemetry-sse-sender");
        t.setDaemon(true);
        return t;
    });

    private final TelemetryService service;

    public TelemetryController(TelemetryService service) {
        this.service = service;
    }

    @PostMapping("/round")
    public void recordRound(@RequestBody TelemetryService.DeviceRoundPayload payload) {
        service.recordDeviceRound(payload);
    }

    /** tts-server 事件转发（独立进程不跨进程写库）：逐条 record，与本地插桩同通道。 */
    @PostMapping("/events")
    public void recordEvents(@RequestBody EventBatch batch) {
        if (batch == null || batch.utteranceId() == null || batch.utteranceId().isBlank()
                || batch.events() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "utteranceId and events are required");
        }
        for (TelemetryEvent e : batch.events()) {
            service.record(batch.utteranceId(), e);
        }
    }

    @PostMapping(value = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void uploadAudio(@RequestParam String utteranceId,
                            @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is required");
        }
        service.saveAudio(utteranceId, file.getBytes());
    }

    @GetMapping("/rounds")
    public List<RoundSummary> rounds(@RequestParam(required = false) String device,
                                     @RequestParam(required = false) Long from,
                                     @RequestParam(required = false) Long to) {
        return service.queryRounds(device, from == null ? 0 : from,
                to == null ? Long.MAX_VALUE : to);
    }

    @GetMapping("/rounds/{utteranceId}")
    public RoundDetail round(@PathVariable String utteranceId) {
        RoundDetail detail = service.queryRound(utteranceId);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "round not found: " + utteranceId);
        }
        return detail;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // 不超时
        Consumer<RoundSummary> listener = summary -> sseSender.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("round").data(summary));
            } catch (IOException ignored) {
                // 面板断开/已发送后失效：忽略，下一轮再推
            }
        });
        service.addListener(listener);
        // 面板断开后自移除（review finding 2）：不留滞留 listener
        Runnable detach = () -> service.removeListener(listener);
        emitter.onCompletion(detach);
        emitter.onTimeout(detach);
        emitter.onError(e -> detach.run());
        return emitter;
    }

    @GetMapping("/audio/{file}")
    public ResponseEntity<byte[]> audio(@PathVariable String file) {
        return service.readAudio(file)
                .map(data -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("audio/wav"))
                        .body(data))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "audio not found: " + file));
    }

    /**
     * 显式 400：JSON 解析失败（HttpMessageNotReadable）、参数缺失/类型错、service 路径
     * 防穿越拒绝（IllegalArgumentException）——仿 TtsController 的显式异常风格，不让
     * 框架默认 500 兜住客户端错误。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<String> badRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ex.getMessage() == null ? "bad request" : ex.getMessage());
    }
}
