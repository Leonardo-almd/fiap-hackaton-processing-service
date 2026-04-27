package br.com.fiap.processing.adapter.out.http;

import br.com.fiap.processing.domain.model.AnalysisResult;
import br.com.fiap.processing.domain.port.out.ReportPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Chama o report-service via HTTP para criar o relatório de análise.
 * Endpoint: POST /v1/reports
 */
@Component
public class ReportServiceAdapter implements ReportPort {

    private static final Logger log = LoggerFactory.getLogger(ReportServiceAdapter.class);

    private final RestClient restClient;

    public ReportServiceAdapter(RestClient.Builder restClientBuilder,
                                 @Value("${report-service.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public String createReport(String jobId, AnalysisResult result) {
        log.info("Criando relatório no report-service. jobId={}", jobId);

        var body = Map.of(
                "jobId", jobId,
                "componentes", result.componentes(),
                "riscos", result.riscos(),
                "recomendacoes", result.recomendacoes()
        );

        ReportCreatedResponse response = restClient.post()
                .uri("/v1/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ReportCreatedResponse.class);

        if (response == null || response.reportId() == null) {
            throw new RuntimeException("report-service retornou resposta inválida para jobId: " + jobId);
        }

        log.info("Relatório criado com sucesso. jobId={}, reportId={}", jobId, response.reportId());
        return response.reportId().toString();
    }

    record ReportCreatedResponse(@JsonProperty("reportId") UUID reportId) {}
}
