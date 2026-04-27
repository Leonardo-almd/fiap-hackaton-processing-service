package br.com.fiap.processing.config;

import br.com.fiap.processing.adapter.out.ai.stub.StubAIAdapter;
import br.com.fiap.processing.domain.port.out.AIAnalysisPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seleciona a implementação de IA com base na propriedade ai.adapter.
 * "stub"    → StubAIAdapter    (Fase 1 — SOAT)
 * "bedrock" → BedrockAIAdapter (Fase 2 — IADT, implementado na Semana 5)
 */
@Configuration
public class AIAdapterConfig {

    @Value("${ai.adapter:stub}")
    private String aiAdapter;

    @Bean
    public AIAnalysisPort aiAnalysisPort() {
        return switch (aiAdapter.toLowerCase()) {
            case "stub" -> {
                yield new StubAIAdapter();
            }
            default -> throw new IllegalStateException(
                    "AI adapter desconhecido: '" + aiAdapter + "'. Valores válidos: stub, bedrock");
        };
    }
}
