package br.com.fiap.processing.domain.model;

/**
 * Representa a mensagem recebida da fila SQS.
 * Formato definido em fiap-infrastructure/docs/schemas/sqs-message.json
 */
public record SqsMessage(
        String jobId,
        String s3Key,
        String fileType,
        Long fileSize,
        String originalFilename,
        String description,
        String publishedAt
) {}
