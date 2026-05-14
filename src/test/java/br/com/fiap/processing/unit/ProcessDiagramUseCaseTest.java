package br.com.fiap.processing.unit;

import br.com.fiap.processing.application.usecase.ProcessDiagramUseCaseImpl;
import br.com.fiap.processing.domain.exception.InvalidAnalysisResultException;
import br.com.fiap.processing.domain.exception.InvalidDiagramException;
import br.com.fiap.processing.domain.model.AnalysisResult;
import br.com.fiap.processing.domain.model.Componente;
import br.com.fiap.processing.domain.model.Recomendacao;
import br.com.fiap.processing.domain.model.Risco;
import br.com.fiap.processing.domain.model.SqsMessage;
import br.com.fiap.processing.domain.port.out.AIAnalysisPort;
import br.com.fiap.processing.domain.port.out.FileDownloadPort;
import br.com.fiap.processing.domain.port.out.JobStatusPort;
import br.com.fiap.processing.domain.port.out.ReportPort;
import br.com.fiap.processing.domain.service.AnalysisResultGuardrail;
import br.com.fiap.processing.domain.service.DiagramInputValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessDiagramUseCaseTest {

    @Mock private FileDownloadPort fileDownloadPort;
    @Mock private AIAnalysisPort aiAnalysisPort;
    @Mock private JobStatusPort jobStatusPort;
    @Mock private ReportPort reportPort;

    // Instâncias reais dos validadores (sem dependências externas)
    private final DiagramInputValidator inputValidator = new DiagramInputValidator();
    private final AnalysisResultGuardrail resultGuardrail = new AnalysisResultGuardrail();

    private ProcessDiagramUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessDiagramUseCaseImpl(
                fileDownloadPort, aiAnalysisPort, jobStatusPort, reportPort,
                inputValidator, resultGuardrail);
    }

    private SqsMessage buildMessage() {
        return new SqsMessage("job-123", "uploads/job-123.pdf", "PDF",
                1024L, "diagram.pdf", "Teste", "2024-01-01T00:00:00Z");
    }

    /** Retorna magic bytes de um PDF válido (apenas cabeçalho %PDF). */
    private byte[] validPdfContent() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34};
    }

    private AnalysisResult buildResult() {
        return new AnalysisResult(
                List.of(new Componente("API Gateway", "GATEWAY", "Entry point")),
                List.of(new Risco("ALTA", "ACOPLAMENTO", "DB compartilhado", "Risco", List.of())),
                List.of(new Recomendacao("ALTA", "Separar DBs", "Detalhes", List.of()))
        );
    }

    // ── Pipeline completo bem-sucedido ────────────────────────────────────────

    @Test
    void shouldExecuteFullPipelineSuccessfully() {
        byte[] content = validPdfContent();
        AnalysisResult result = buildResult();

        when(fileDownloadPort.download("uploads/job-123.pdf")).thenReturn(content);
        when(aiAnalysisPort.analyze(content, "PDF")).thenReturn(result);
        when(reportPort.createReport("job-123", result)).thenReturn("report-456");

        useCase.execute(buildMessage());

        verify(jobStatusPort).updateToProcessing("job-123");
        verify(fileDownloadPort).download("uploads/job-123.pdf");
        verify(aiAnalysisPort).analyze(content, "PDF");
        verify(reportPort).createReport("job-123", result);
        verify(jobStatusPort).updateToAnalyzed("job-123", "report-456");
        verify(jobStatusPort, never()).updateToError(any(), any());
    }

    // ── Falha no download do S3 ───────────────────────────────────────────────

    @Test
    void shouldUpdateToErrorWhenFileDownloadFails() {
        when(fileDownloadPort.download(anyString())).thenThrow(new RuntimeException("S3 inacessível"));

        assertThatThrownBy(() -> useCase.execute(buildMessage()))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusPort).updateToProcessing("job-123");
        verify(jobStatusPort).updateToError(eq("job-123"), contains("S3 inacessível"));
    }

    // ── Validação de entrada ──────────────────────────────────────────────────

    @Test
    void shouldUpdateToErrorWhenInputValidationFailsDueToInvalidType() {
        SqsMessage badMessage = new SqsMessage("job-123", "uploads/file", "DOCX",
                100L, "file.docx", "desc", "2024-01-01T00:00:00Z");
        // Arquivo retornado com magic bytes de PNG, mas fileType é DOCX
        when(fileDownloadPort.download(anyString())).thenReturn(validPdfContent());

        assertThatThrownBy(() -> useCase.execute(badMessage))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusPort).updateToProcessing("job-123");
        verify(jobStatusPort).updateToError(eq("job-123"), contains("ENTRADA_INVALIDA"));
        verify(jobStatusPort).updateToError(eq("job-123"), contains("INVALID_FILE_TYPE"));
        verify(aiAnalysisPort, never()).analyze(any(), any());
    }

    @Test
    void shouldUpdateToErrorWhenInputValidationFailsDueToEmptyFile() {
        when(fileDownloadPort.download(anyString())).thenReturn(new byte[0]);

        assertThatThrownBy(() -> useCase.execute(buildMessage()))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusPort).updateToError(eq("job-123"), contains("EMPTY_FILE"));
        verify(aiAnalysisPort, never()).analyze(any(), any());
    }

    // ── Falha na IA com retry ─────────────────────────────────────────────────

    @Test
    void shouldRetryOnceWhenAIFailsOnFirstAttempt() {
        when(fileDownloadPort.download(anyString())).thenReturn(validPdfContent());
        when(aiAnalysisPort.analyze(any(), any()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(buildResult());
        when(reportPort.createReport(any(), any())).thenReturn("report-456");

        useCase.execute(buildMessage());

        // Deve ter chamado a IA duas vezes (1ª falhou, retry funcionou)
        verify(aiAnalysisPort, times(2)).analyze(any(), any());
        verify(jobStatusPort).updateToAnalyzed("job-123", "report-456");
        verify(jobStatusPort, never()).updateToError(any(), any());
    }

    @Test
    void shouldUpdateToErrorWithFalhaIAPrefixWhenBothAttemptsFailAreFailed() {
        when(fileDownloadPort.download(anyString())).thenReturn(validPdfContent());
        when(aiAnalysisPort.analyze(any(), any()))
                .thenThrow(new RuntimeException("AI indisponível"));

        assertThatThrownBy(() -> useCase.execute(buildMessage()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("job-123");

        // Deve ter tentado 2x (1 original + 1 retry)
        verify(aiAnalysisPort, times(2)).analyze(any(), any());
        verify(jobStatusPort).updateToError(eq("job-123"), contains("FALHA_IA"));
        verify(reportPort, never()).createReport(any(), any());
    }

    // ── Guardrail de saída ────────────────────────────────────────────────────

    @Test
    void shouldUpdateToErrorWithRespostaInvalidaPrefixWhenGuardrailRejects() {
        // Resultado inválido: componentes vazios
        AnalysisResult badResult = new AnalysisResult(List.of(), List.of(), List.of());

        when(fileDownloadPort.download(anyString())).thenReturn(validPdfContent());
        when(aiAnalysisPort.analyze(any(), any())).thenReturn(badResult);

        assertThatThrownBy(() -> useCase.execute(buildMessage()))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusPort).updateToError(eq("job-123"), contains("RESPOSTA_INVALIDA"));
        verify(reportPort, never()).createReport(any(), any());
        verify(jobStatusPort, never()).updateToAnalyzed(any(), any());
    }

    @Test
    void shouldNotRetryWhenGuardrailRejectsOutput() {
        // O guardrail lança InvalidAnalysisResultException — não deve ser retentado
        AnalysisResult badResult = new AnalysisResult(List.of(), List.of(), List.of());

        when(fileDownloadPort.download(anyString())).thenReturn(validPdfContent());
        when(aiAnalysisPort.analyze(any(), any())).thenReturn(badResult);

        assertThatThrownBy(() -> useCase.execute(buildMessage()))
                .isInstanceOf(RuntimeException.class);

        // Não deve ter retentado, pois é erro de validação de saída (não transiente)
        verify(aiAnalysisPort, times(1)).analyze(any(), any());
    }

    // ── Falha ao criar relatório ──────────────────────────────────────────────

    @Test
    void shouldUpdateToErrorWhenReportCreationFails() {
        when(fileDownloadPort.download(anyString())).thenReturn(validPdfContent());
        when(aiAnalysisPort.analyze(any(), any())).thenReturn(buildResult());
        when(reportPort.createReport(any(), any()))
                .thenThrow(new RuntimeException("report-service indisponível"));

        assertThatThrownBy(() -> useCase.execute(buildMessage()))
                .isInstanceOf(RuntimeException.class);

        verify(jobStatusPort).updateToError(eq("job-123"), any());
        verify(jobStatusPort, never()).updateToAnalyzed(any(), any());
    }
}
