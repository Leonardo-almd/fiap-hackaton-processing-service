package br.com.fiap.processing.domain.exception;

/**
 * Lançada quando a resposta do modelo de IA não passa pelo guardrail de saída —
 * ou seja, a estrutura do JSON retornado é inválida, incompleta ou contém
 * valores fora dos enums esperados.
 *
 * <p>Ao ser capturada pelo use case, o job é marcado como {@code ERRO} com
 * o prefixo {@code [RESPOSTA_INVALIDA]} na mensagem de erro para facilitar
 * a triagem em produção.
 */
public class InvalidAnalysisResultException extends RuntimeException {

    public InvalidAnalysisResultException(String message) {
        super(message);
    }
}
