package br.com.fiap.processing.domain.model;

import java.util.List;

/**
 * Recomendação gerada pela IA para mitigar riscos identificados.
 */
public record Recomendacao(
        String prioridade,
        String titulo,
        String descricao,
        List<String> referencias
) {}
