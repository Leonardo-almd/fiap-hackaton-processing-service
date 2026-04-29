package br.com.fiap.processing.domain.model;

import java.util.List;

/**
 * Resultado produzido pelo pipeline de IA após analisar um diagrama.
 * É o contrato de saída da AIAnalysisPort e de entrada da ReportPort.
 */
public record AnalysisResult(
        List<Componente> componentes,
        List<Risco> riscos,
        List<Recomendacao> recomendacoes
) {}
