package br.com.fiap.processing.adapter.out.ai.bedrock;

/**
 * Exceção lançada quando o pipeline de integração com o Amazon Bedrock falha.
 * O {@code ProcessDiagramUseCaseImpl} captura {@link RuntimeException} e atualiza
 * o status do job para ERRO, portanto esta classe estende RuntimeException.
 */
public class BedrockAnalysisException extends RuntimeException {

    public BedrockAnalysisException(String message) {
        super(message);
    }

    public BedrockAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
