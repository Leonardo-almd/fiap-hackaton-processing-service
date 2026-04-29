package br.com.fiap.processing.domain.port.out;

/**
 * Atualiza o status do job no upload-service via HTTP (PATCH /v1/jobs/{id}/status).
 */
public interface JobStatusPort {

    void updateToProcessing(String jobId);

    void updateToAnalyzed(String jobId, String reportId);

    void updateToError(String jobId, String errorMessage);
}
