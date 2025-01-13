import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;

@Configuration
public class VectorStoreConfig {

@Bean
public JedisPooled jedisPooled() {
    return new JedisPooled("kykms-redis", 6379);
}
@Bean
public VectorStore vectorStore(JedisPooled jedisPooled, EmbeddingModel embeddingModel) {
    return RedisVectorStore.builder(jedisPooled, embeddingModel)
        .indexName("custom-index")                // Optional: defaults to "spring-ai-index"
        .prefix("custom-prefix")                  // Optional: defaults to "embedding:"
 //       .metadataFields(                         // Optional: define metadata fields for filtering
 //           MetadataField.tag("country"),
 //           MetadataField.numeric("year"))
        .initializeSchema(true)                   // Optional: defaults to false
//        .batchingStrategy(new TokenCountBatchingStrategy()) // Optional: defaults to TokenCountBatchingStrategy
        .build();
}

// This can be any EmbeddingModel implementation
@Bean
public EmbeddingModel embeddingModel() {
    OllamaApi ollamaApi = new OllamaApi("http://jeecg-boot-ollama-1:11434");	
    OllamaOptions option =  OllamaOptions.builder().model("mofanke/dmeta-embedding-zh").build();
    io.micrometer.observation.ObservationRegistry observationRegistry = null;
    ModelManagementOptions modelManagementOption = null; 
    return new OllamaEmbeddingModel(ollamaApi, option, observationRegistry, modelManagementOption);
}
}
