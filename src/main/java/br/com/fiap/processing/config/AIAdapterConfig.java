package br.com.fiap.processing.config;

import br.com.fiap.processing.adapter.out.ai.bedrock.BedrockAIAdapter;
import br.com.fiap.processing.adapter.out.ai.stub.StubAIAdapter;
import br.com.fiap.processing.domain.port.out.AIAnalysisPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;

/**
 * Seleciona a implementação de IA com base na propriedade {@code ai.adapter}.
 *
 * <ul>
 *   <li>{@code stub}    → {@link StubAIAdapter} (Fase 1 — SOAT)
 *   <li>{@code bedrock} → {@link BedrockAIAdapter} (Fase 2 — IADT)
 * </ul>
 *
 * O {@link BedrockRuntimeClient} é construído de forma lazy apenas quando
 * {@code ai.adapter=bedrock}, evitando conexões desnecessárias ao AWS em modo stub.
 */
@Configuration
public class AIAdapterConfig {

    @Value("${ai.adapter:stub}")
    private String aiAdapter;

    @Value("${ai.bedrock.model-id:anthropic.claude-3-sonnet-20240229-v1:0}")
    private String bedrockModelId;

    @Value("${ai.bedrock.region:us-east-1}")
    private String bedrockRegion;

    @Autowired
    private ObjectMapper objectMapper;

    @Bean
    public AIAnalysisPort aiAnalysisPort() {
        return switch (aiAdapter.toLowerCase()) {
            case "stub" -> new StubAIAdapter();
            case "bedrock" -> {
                BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                        .region(Region.of(bedrockRegion))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
                String modelId = bedrockModelId;
                BedrockAIAdapter.ModelInvoker invoker = body -> {
                    InvokeModelRequest req = InvokeModelRequest.builder()
                            .modelId(modelId)
                            .contentType("application/json")
                            .accept("application/json")
                            .body(SdkBytes.fromUtf8String(body))
                            .build();
                    return client.invokeModel(req).body().asUtf8String();
                };
                yield new BedrockAIAdapter(invoker, objectMapper);
            }
            default -> throw new IllegalStateException(
                    "AI adapter desconhecido: '" + aiAdapter + "'. Valores válidos: stub, bedrock");
        };
    }
}
