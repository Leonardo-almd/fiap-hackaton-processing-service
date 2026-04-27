package br.com.fiap.processing.domain.port.out;

import br.com.fiap.processing.domain.model.AnalysisResult;

/**
 * Interface de análise por IA.
 * Implementações: StubAIAdapter (Fase 1) e BedrockAIAdapter (Fase 2).
 */
public interface AIAnalysisPort {

    AnalysisResult analyze(byte[] fileContent, String fileType);
}
