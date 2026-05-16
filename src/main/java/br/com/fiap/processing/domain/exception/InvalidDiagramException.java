package br.com.fiap.processing.domain.exception;

/**
 * Lançada quando o arquivo recebido não atende aos requisitos de entrada
 * antes de ser enviado ao modelo de IA.
 *
 * <p>O {@code errorCode} identifica a causa raiz para fins de rastreabilidade
 * no status do job: {@code INVALID_FILE_TYPE}, {@code EMPTY_FILE}, {@code FILE_TOO_LARGE}.
 */
public class InvalidDiagramException extends RuntimeException {

    private final String errorCode;

    public InvalidDiagramException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
