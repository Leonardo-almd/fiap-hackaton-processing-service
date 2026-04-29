package br.com.fiap.processing.domain.port.out;

import br.com.fiap.processing.domain.model.AnalysisResult;

/**
 * Persiste o relatório no report-service via HTTP (POST /v1/reports).
 * Retorna o reportId criado.
 */
public interface ReportPort {

    String createReport(String jobId, AnalysisResult result);
}
