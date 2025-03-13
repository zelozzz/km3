package org.jeecg.modules.KM.controller;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson2.JSON;
import org.jeecg.modules.KM.entity.TokenCounter;
import org.jeecg.modules.KM.service.IAIChatService;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/KM/chat")
public class AIChatController {

    private final IAIChatService chatService;


    private final RedisTemplate<String, Object> redisTemplate;
    private static final String tokenCounterKey = "tokenCounter";

    public AIChatController(IAIChatService chatService, RedisTemplate redisTemplate) {
        this.chatService = chatService;
        this.redisTemplate = redisTemplate;
    }

    public record ChatRequest(String message) {
    }

    /**
     * 流式对话
     *
     * @param request 用户指令
     * @return
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> stream(@RequestBody ChatRequest request) {
        AtomicReference<ChatResponse> last = new AtomicReference<>();
//        return chatService.chatByVectorStore(request.message)
//                .stream()
//                .content().map(content -> ServerSentEvent.builder(content).build())
//                .onErrorResume(e -> Flux.just(ServerSentEvent.builder("[ERROR]" + e.getMessage()).event("error").build()));
        AtomicLong start = new AtomicLong();
        return chatService.chatByVectorStore(request.message)
                .stream()
                .chatResponse()
                .doFirst(() -> {
                    start.set(DateUtil.currentSeconds());
                })
                .doOnNext(last::set)
                .doOnComplete(() -> {
                    Usage usage = last.get().getMetadata().getUsage();
                    long end = DateUtil.currentSeconds();
                    long used = end - start.get();
                    long speed = usage.getTotalTokens() / used;
                    String o = (String) redisTemplate.opsForValue().get(tokenCounterKey);
                    TokenCounter counter = null;
                    if (o == null) {
                        counter = TokenCounter
                                .builder()
                                .AverageTokensSpeed(speed)
                                .MaxGenerationTokensSpeed(speed)
                                .promptTokens(usage.getPromptTokens())
                                .completionTokens(usage.getCompletionTokens())
                                .totalTokens(usage.getTotalTokens())
                                .total(1)
                                .build();
                    } else {
                        counter = JSON.parseObject(o, TokenCounter.class);
                        counter.setAverageTokensSpeed(counter.getAverageTokensSpeed() + speed / 2);
                        if (speed > counter.getMaxGenerationTokensSpeed()) {
                            counter.setMaxGenerationTokensSpeed(speed);
                        }
                        counter.setPromptTokens(counter.getPromptTokens() + usage.getPromptTokens());
                        counter.setCompletionTokens(counter.getCompletionTokens() + usage.getCompletionTokens());
                        counter.setTotalTokens(counter.getTotalTokens() + usage.getTotalTokens());
                        counter.setTotal(counter.getTotal() + 1);
                    }
                    String value = JSON.toJSONString(counter);
                    redisTemplate.opsForValue().set(tokenCounterKey, value);
                });
    }


    /**
     * 完整对话
     *
     * @param request 用户指令
     * @return
     */
    @PostMapping(value = "/call")
    public ChatResponse content(@RequestBody ChatRequest request) {
        return chatService.chatByVectorStore(request.message)
                .call()
                .chatResponse();
    }

}
