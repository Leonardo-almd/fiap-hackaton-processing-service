package br.com.fiap.processing.domain.model;

import java.util.List;

/**
 * Risco arquitetural detectado pela IA no diagrama.
 */
public record Risco(
        String severidade,
        String categoria,
        String titulo,
        String descricao,
        List<String> componentesAfetados
) {}
