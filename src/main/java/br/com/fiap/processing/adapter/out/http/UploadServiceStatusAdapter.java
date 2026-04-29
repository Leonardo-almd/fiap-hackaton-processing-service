package br.com.fiap.processing.adapter.out.http;

import br.com.fiap.processing.domain.port.out.JobStatusPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Chama o upload-service via HTTP para atualizar o status do job.
 * Endpoint: PATCH /v1/jobs/{jobId}/status
 */
@Component
public class UploadServiceStatusAdapter implements JobStatusPort {

    private static final Logger log = LoggerFactory.getLogger(UploadServiceStatusAdapter.class);

    private final RestClient restClient;

    public UploadServiceStatusAdapter(RestClient.Builder restClientBuilder,
                                       @Value("${upload-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public void updateToProcessing(String jobId) {
        patch(jobId, Map.of("status", "EM_PROCESSAMENTO"));
    }

    @Override
    public void updateToAnalyzed(String jobId, String reportId) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "ANALISADO");
        body.put("reportId", reportId);
        patch(jobId, body);
    }

    @Override
    public void updateToError(String jobId, String errorMessage) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "ERRO");
        body.put("errorMessage", errorMessage);
        patch(jobId, body);
    }

    private void patch(String jobId, Map<String, Object> body) {
        log.info("Atualizando status do job. jobId={}, novoStatus={}", jobId, body.get("status"));
        restClient.patch()
                .uri("/v1/jobs/{jobId}/status", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
