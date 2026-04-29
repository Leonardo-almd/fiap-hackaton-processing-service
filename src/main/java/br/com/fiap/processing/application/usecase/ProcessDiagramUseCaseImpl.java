package br.com.fiap.processing.application.usecase;

import br.com.fiap.processing.domain.model.AnalysisResult;
import br.com.fiap.processing.domain.model.SqsMessage;
import br.com.fiap.processing.domain.port.in.ProcessDiagramUseCase;
import br.com.fiap.processing.domain.port.out.AIAnalysisPort;
import br.com.fiap.processing.domain.port.out.FileDownloadPort;
import br.com.fiap.processing.domain.port.out.JobStatusPort;
import br.com.fiap.processing.domain.port.out.ReportPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class ProcessDiagramUseCaseImpl implements ProcessDiagramUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessDiagramUseCaseImpl.class);

    private final FileDownloadPort fileDownloadPort;
    private final AIAnalysisPort aiAnalysisPort;
    private final JobStatusPort jobStatusPort;
    private final ReportPort reportPort;

    public ProcessDiagramUseCaseImpl(FileDownloadPort fileDownloadPort,
                                     AIAnalysisPort aiAnalysisPort,
                                     JobStatusPort jobStatusPort,
                                     ReportPort reportPort) {
        this.fileDownloadPort = fileDownloadPort;
        this.aiAnalysisPort = aiAnalysisPort;
        this.jobStatusPort = jobStatusPort;
        this.reportPort = reportPort;
    }

    @Override
    public void execute(SqsMessage message) {
        MDC.put("jobId", message.jobId());
        log.info("Iniciando processamento. jobId={}, fileType={}, s3Key={}",
                message.jobId(), message.fileType(), message.s3Key());

        try {
            jobStatusPort.updateToProcessing(message.jobId());
            log.info("Status atualizado para EM_PROCESSAMENTO. jobId={}", message.jobId());

            byte[] fileContent = fileDownloadPort.download(message.s3Key());
            log.info("Arquivo baixado do S3. jobId={}, bytes={}", message.jobId(), fileContent.length);

            AnalysisResult result = aiAnalysisPort.analyze(fileContent, message.fileType());
            log.info("Análise concluída. jobId={}, componentes={}, riscos={}, recomendacoes={}",
                    message.jobId(),
                    result.componentes().size(),
                    result.riscos().size(),
                    result.recomendacoes().size());

            String reportId = reportPort.createReport(message.jobId(), result);
            log.info("Relatório criado. jobId={}, reportId={}", message.jobId(), reportId);

            jobStatusPort.updateToAnalyzed(message.jobId(), reportId);
            log.info("Processamento concluído com sucesso. jobId={}", message.jobId());

        } catch (Exception e) {
            log.error("Falha no processamento. jobId={}, erro={}", message.jobId(), e.getMessage(), e);
            tryUpdateToError(message.jobId(), e.getMessage());
            throw new RuntimeException("Falha ao processar job " + message.jobId(), e);
        } finally {
            MDC.remove("jobId");
        }
    }

    private void tryUpdateToError(String jobId, String errorMessage) {
        try {
            jobStatusPort.updateToError(jobId, errorMessage != null ? errorMessage : "Erro desconhecido");
        } catch (Exception ex) {
            log.error("Falha ao atualizar status para ERRO. jobId={}", jobId, ex);
        }
    }
}
