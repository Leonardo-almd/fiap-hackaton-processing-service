package br.com.fiap.processing.application.usecase;

import br.com.fiap.processing.domain.exception.InvalidAnalysisResultException;
import br.com.fiap.processing.domain.exception.InvalidDiagramException;
import br.com.fiap.processing.domain.model.AnalysisResult;
import br.com.fiap.processing.domain.model.SqsMessage;
import br.com.fiap.processing.domain.port.in.ProcessDiagramUseCase;
import br.com.fiap.processing.domain.port.out.AIAnalysisPort;
import br.com.fiap.processing.domain.port.out.FileDownloadPort;
import br.com.fiap.processing.domain.port.out.JobStatusPort;
import br.com.fiap.processing.domain.port.out.ReportPort;
import br.com.fiap.processing.domain.service.AnalysisResultGuardrail;
import br.com.fiap.processing.domain.service.DiagramInputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Orquestra o pipeline completo de análise de diagrama:
 * <ol>
 *   <li>Atualiza job para {@code EM_PROCESSAMENTO}
 *   <li>Baixa o arquivo do S3
 *   <li>Valida entrada ({@link DiagramInputValidator})
 *   <li>Chama o modelo de IA com retry (1 retentativa em caso de falha transiente)
 *   <li>Valida a resposta do modelo ({@link AnalysisResultGuardrail})
 *   <li>Persiste o relatório no report-service
 *   <li>Atualiza job para {@code ANALISADO}
 * </ol>
 *
 * <p>Em caso de falha, o job é marcado como {@code ERRO} com prefixo
 * categorizando a causa: {@code [ENTRADA_INVALIDA]}, {@code [RESPOSTA_INVALIDA]}
 * ou {@code [FALHA_IA]} — facilitando a triagem em produção.
 */
@Service
public class ProcessDiagramUseCaseImpl implements ProcessDiagramUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessDiagramUseCaseImpl.class);

    private final FileDownloadPort fileDownloadPort;
    private final AIAnalysisPort aiAnalysisPort;
    private final JobStatusPort jobStatusPort;
    private final ReportPort reportPort;
    private final DiagramInputValidator inputValidator;
    private final AnalysisResultGuardrail resultGuardrail;

    public ProcessDiagramUseCaseImpl(FileDownloadPort fileDownloadPort,
            AIAnalysisPort aiAnalysisPort,
            JobStatusPort jobStatusPort,
            ReportPort reportPort,
            DiagramInputValidator inputValidator,
            AnalysisResultGuardrail resultGuardrail) {
        this.fileDownloadPort = fileDownloadPort;
        this.aiAnalysisPort = aiAnalysisPort;
        this.jobStatusPort = jobStatusPort;
        this.reportPort = reportPort;
        this.inputValidator = inputValidator;
        this.resultGuardrail = resultGuardrail;
    }

    @Override
    public void execute(SqsMessage message) {
        MDC.put("jobId", message.jobId());
        log.info("Iniciando processamento. jobId={}, fileType={}, s3Key={}",
                message.jobId(), message.fileType(), message.s3Key());
        try {
            jobStatusPort.updateToProcessing(message.jobId());

            byte[] fileContent = fileDownloadPort.download(message.s3Key());
            log.info("Arquivo baixado do S3. jobId={}, bytes={}", message.jobId(), fileContent.length);

            inputValidator.validate(message, fileContent);

            AnalysisResult result = analyzeWithRetry(fileContent, message.fileType(), message.jobId());
            log.info("Análise concluída. jobId={}, componentes={}, riscos={}, recomendacoes={}",
                    message.jobId(), result.componentes().size(),
                    result.riscos().size(), result.recomendacoes().size());

            resultGuardrail.validate(result);

            String reportId = reportPort.createReport(message.jobId(), result);
            log.info("Relatório criado. jobId={}, reportId={}", message.jobId(), reportId);

            jobStatusPort.updateToAnalyzed(message.jobId(), reportId);
            log.info("Processamento concluído com sucesso. jobId={}", message.jobId());

        } catch (InvalidDiagramException e) {
            log.warn("Entrada inválida rejeitada pelo guardrail. jobId={}, code={}, mensagem={}",
                    message.jobId(), e.getErrorCode(), e.getMessage());
            String errorMsg = "[ENTRADA_INVALIDA:" + e.getErrorCode() + "] " + e.getMessage();
            tryUpdateToError(message.jobId(), errorMsg);
            throw new RuntimeException("Entrada inválida no job " + message.jobId(), e);

        } catch (InvalidAnalysisResultException e) {
            log.warn("Resposta da IA rejeitada pelo guardrail. jobId={}, mensagem={}",
                    message.jobId(), e.getMessage());
            tryUpdateToError(message.jobId(), "[RESPOSTA_INVALIDA] " + e.getMessage());
            throw new RuntimeException("Resposta inválida no job " + message.jobId(), e);

        } catch (Exception e) {
            log.error("Falha no processamento. jobId={}, erro={}", message.jobId(), e.getMessage(), e);
            tryUpdateToError(message.jobId(), e.getMessage() != null ? e.getMessage() : "Erro desconhecido");
            throw new RuntimeException("Falha ao processar job " + message.jobId(), e);

        } finally {
            MDC.remove("jobId");
        }
    }

    /**
     * Executa a análise de IA com uma retentativa automática.
     *
     * <p>Erros de validação ({@link InvalidDiagramException},
     * {@link InvalidAnalysisResultException}) não são retentados — são relançados
     * imediatamente para o bloco catch do chamador.
     *
     * @param fileContent conteúdo do arquivo já validado
     * @param fileType    tipo do arquivo (PDF ou IMAGE)
     * @param jobId       identificador do job para logging
     * @return resultado da análise
     */
    private AnalysisResult analyzeWithRetry(byte[] fileContent, String fileType, String jobId) {
        try {
            return aiAnalysisPort.analyze(fileContent, fileType);
        } catch (InvalidDiagramException | InvalidAnalysisResultException e) {
            throw e;
        } catch (Exception firstException) {
            log.warn("Primeira tentativa de análise falhou — retentando. jobId={}, erro={}",
                    jobId, firstException.getMessage());
            try {
                return aiAnalysisPort.analyze(fileContent, fileType);
            } catch (InvalidDiagramException | InvalidAnalysisResultException e) {
                throw e;
            } catch (Exception retryException) {
                log.error("Retentativa também falhou. jobId={}, erro={}", jobId, retryException.getMessage());
                throw new RuntimeException("[FALHA_IA] " + retryException.getMessage(), retryException);
            }
        }
    }

    private void tryUpdateToError(String jobId, String errorMessage) {
        try {
            jobStatusPort.updateToError(jobId, errorMessage);
        } catch (Exception ex) {
            log.error("Falha ao atualizar status para ERRO. jobId={}", jobId, ex);
        }
    }
}
