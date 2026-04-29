package br.com.fiap.processing.adapter.in.sqs;

import br.com.fiap.processing.domain.model.SqsMessage;
import br.com.fiap.processing.domain.port.in.ProcessDiagramUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;

@Component
public class SqsMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsMessageConsumer.class);

    private final SqsClient sqsClient;
    private final ProcessDiagramUseCase processUseCase;
    private final ObjectMapper objectMapper;
    private final String queueUrl;
    private final int maxMessages;
    private final int visibilityTimeout;

    public SqsMessageConsumer(SqsClient sqsClient,
                               ProcessDiagramUseCase processUseCase,
                               ObjectMapper objectMapper,
                               @Value("${aws.sqs.queue-url}") String queueUrl,
                               @Value("${aws.sqs.max-messages:5}") int maxMessages,
                               @Value("${aws.sqs.visibility-timeout:60}") int visibilityTimeout) {
        this.sqsClient = sqsClient;
        this.processUseCase = processUseCase;
        this.objectMapper = objectMapper;
        this.queueUrl = queueUrl;
        this.maxMessages = maxMessages;
        this.visibilityTimeout = visibilityTimeout;
    }

    @Scheduled(fixedDelayString = "${aws.sqs.polling-interval-ms:5000}")
    public void poll() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .visibilityTimeout(visibilityTimeout)
                .waitTimeSeconds(0)
                .build();

        List<Message> messages = sqsClient.receiveMessage(request).messages();

        if (!messages.isEmpty()) {
            log.info("Mensagens recebidas do SQS. quantidade={}", messages.size());
        }

        for (Message message : messages) {
            processMessage(message);
        }
    }

    private void processMessage(Message message) {
        String receiptHandle = message.receiptHandle();
        try {
            SqsMessage sqsMessage = objectMapper.readValue(message.body(), SqsMessage.class);
            log.info("Processando mensagem SQS. jobId={}", sqsMessage.jobId());

            processUseCase.execute(sqsMessage);

            deleteMessage(receiptHandle);
            log.info("Mensagem SQS deletada após processamento bem-sucedido. jobId={}", sqsMessage.jobId());

        } catch (Exception e) {
            log.error("Falha ao processar mensagem SQS. receiptHandle={}, erro={}",
                    receiptHandle, e.getMessage(), e);
            // Não deleta a mensagem: ela retornará à fila após o visibilityTimeout
            // e irá para a DLQ após maxReceiveCount tentativas
        }
    }

    private void deleteMessage(String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }
}
