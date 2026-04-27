package br.com.fiap.processing.unit;

import br.com.fiap.processing.application.usecase.ProcessDiagramUseCaseImpl;
import br.com.fiap.processing.domain.model.*;
import br.com.fiap.processing.domain.port.out.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessDiagramUseCaseTest {

    @Mock private FileDownloadPort fileDownloadPort;
    @Mock private AIAnalysisPort aiAnalysisPort;
    @Mock private JobStatusPort jobStatusPort;
    @Mock private ReportPort reportPort;

    private ProcessDiagramUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessDiagramUseCaseImpl(fileDownloadPort, aiAnalysisPort, jobStatusPort, reportPort);
    }

    private SqsMessage buildMessage() {
        return new SqsMessage("job-123", "uploads/job-123.pdf", "PDF",
                1024L, "diagram.pdf", "Teste", "2024-01-01T00:00:00Z");
    }

    private AnalysisResult buildResult() {
        return new AnalysisResult(
                List.of(new Componente("API Gateway", "GATEWAY", "Entry point")),
                List.of(new Risco("ALTA", "ACOPLAMENTO", "DB compartilhado", "Risco", List.of())),
                List.of(new Recomendacao("ALTA", "Separar DBs", "Detalhes", List.of()))
        );
    }

    @Test
    void shouldExecuteFullPipelineSuccessfully() {
        SqsMessage message = buildMessage();
        byte[] content = "pdf-content".getBytes();
        AnalysisResult result = buildResult();

        when(fileDownloadPort.download("uploads/job-123.pdf")).thenReturn(content);
        when(aiAnalysisPort.analyze(content, "PDF")).thenReturn(result);
        when(reportPort.createReport("job-123", result)).thenReturn("report-456");

        useCase.execute(message);

        verify(jobStatusPort).updateToProcessing("job-123");
        verify(fileDownloadPort).download("uploads/job-123.pdf");
        verify(aiAnalysisPort).analyze(content, "PDF");
        verify(reportPort).createReport("job-123", result);
        verify(jobStatusPort).updateToAnalyzed("job-123", "report-456");
        verify(jobStatusPort, never()).updateToError(any(), any());
    }

    @Test
    void shouldUpdateToErrorAndRethrowWhenAIFails() {
        SqsMessage message = buildMessage();
        when(fileDownloadPort.download(anyString())).thenReturn("content".getBytes());
        when(aiAnalysisPort.analyze(any(), any())).thenThrow(new RuntimeException("AI indisponível"));

        assertThatThrownBy(() -> useCase.execute(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("job-123");

        verify(jobStatusPort).updateToProcessing("job-123");
        verify(jobStatusPort).updateToError(eq("job-123"), contains("AI indisponível"));
        verify(reportPort, never()).createReport(any(), any());
        verify(jobStatusPort, never()).updateToAnalyzed(any(), any());
    }

    @Test
    void shouldUpdateToErrorWhenFileDownloadFails() {
        SqsMessage message = buildMessage();
        when(fileDownloadPort.download(anyString())).thenThrow(new RuntimeException("S3 inacessível"));

        assertThatThrownBy(() -> useCase.execute(message))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusPort).updateToProcessing("job-123");
        verify(jobStatusPort).updateToError(eq("job-123"), contains("S3 inacessível"));
    }

    @Test
    void shouldUpdateToErrorWhenReportCreationFails() {
        SqsMessage message = buildMessage();
        when(fileDownloadPort.download(anyString())).thenReturn("content".getBytes());
        when(aiAnalysisPort.analyze(any(), any())).thenReturn(buildResult());
        when(reportPort.createReport(any(), any())).thenThrow(new RuntimeException("report-service indisponível"));

        assertThatThrownBy(() -> useCase.execute(message))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusPort).updateToError(eq("job-123"), any());
        verify(jobStatusPort, never()).updateToAnalyzed(any(), any());
    }
}
