package br.com.fiap.processing.domain.model;

/**
 * Componente arquitetural identificado pela IA no diagrama.
 * Formato definido em fiap-infrastructure/docs/schemas/report.json
 */
public record Componente(String nome, String tipo, String descricao) {}
