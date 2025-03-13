package org.jeecg.modules.KM.entity;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenCounter {
    private long completionTokens;
    private long totalTokens;
    private long promptTokens;
    private long MaxGenerationTokensSpeed;
    private long AverageTokensSpeed;
    private long total;
}
