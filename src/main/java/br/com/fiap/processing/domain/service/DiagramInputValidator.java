package br.com.fiap.processing.domain.service;

import br.com.fiap.processing.domain.exception.InvalidDiagramException;
import br.com.fiap.processing.domain.model.SqsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Valida os requisitos de entrada antes de enviar o arquivo ao modelo de IA.
 *
 * <p>Regras aplicadas:
 * <ol>
 *   <li>Tipo de arquivo deve ser {@code PDF} ou {@code IMAGE} (case-insensitive)
 *   <li>Conteúdo não pode ser vazio ou nulo
 *   <li>Tamanho máximo de {@value MAX_FILE_SIZE_MB} MB
 *   <li>Arquivos PDF devem começar com a assinatura {@code %PDF}
 *   <li>Arquivos IMAGE devem ter assinatura reconhecida (PNG ou JPEG)
 * </ol>
 */
@Component
public class DiagramInputValidator {

    private static final Logger log = LoggerFactory.getLogger(DiagramInputValidator.class);

    public static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_FILE_SIZE_MB = 20;
    private static final Set<String> VALID_FILE_TYPES = Set.of("PDF", "IMAGE");

    // Assinaturas de formato (magic bytes)
    private static final byte[] MAGIC_PDF = {0x25, 0x50, 0x44, 0x46}; // %PDF
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47}; // \x89PNG
    private static final byte MAGIC_JPEG_0 = (byte) 0xFF;
    private static final byte MAGIC_JPEG_1 = (byte) 0xD8;

    /**
     * Valida a mensagem SQS e o conteúdo do arquivo baixado do S3.
     *
     * @param message     mensagem SQS com metadados do job
     * @param fileContent conteúdo binário do arquivo
     * @throws InvalidDiagramException se qualquer regra for violada
     */
    public void validate(SqsMessage message, byte[] fileContent) {
        validateFileType(message.fileType());
        validateContent(fileContent);
        validateFileSize(fileContent);
        validateMagicBytes(message.fileType(), fileContent);

        log.info("Arquivo validado com sucesso. jobId={}, fileType={}, bytes={}",
                message.jobId(), message.fileType(), fileContent.length);
    }

    private void validateFileType(String fileType) {
        if (fileType == null || !VALID_FILE_TYPES.contains(fileType.toUpperCase())) {
            throw new InvalidDiagramException("INVALID_FILE_TYPE",
                    "Tipo de arquivo não suportado: '" + fileType
                            + "'. Tipos aceitos: " + VALID_FILE_TYPES);
        }
    }

    private void validateContent(byte[] fileContent) {
        if (fileContent == null || fileContent.length == 0) {
            throw new InvalidDiagramException("EMPTY_FILE",
                    "O arquivo baixado do S3 está vazio ou nulo");
        }
    }

    private void validateFileSize(byte[] fileContent) {
        if (fileContent.length > MAX_FILE_SIZE_BYTES) {
            double sizeMb = fileContent.length / (1024.0 * 1024.0);
            throw new InvalidDiagramException("FILE_TOO_LARGE",
                    String.format("Arquivo excede o limite de %d MB. Tamanho: %.1f MB",
                            MAX_FILE_SIZE_MB, sizeMb));
        }
    }

    private void validateMagicBytes(String fileType, byte[] fileContent) {
        if ("PDF".equalsIgnoreCase(fileType)) {
            if (!startsWith(fileContent, MAGIC_PDF)) {
                throw new InvalidDiagramException("INVALID_PDF_SIGNATURE",
                        "O arquivo declarado como PDF não possui a assinatura '%PDF'");
            }
            return;
        }

        // Para IMAGE: aceita PNG ou JPEG
        boolean isPng = startsWith(fileContent, MAGIC_PNG);
        boolean isJpeg = fileContent.length >= 2
                && fileContent[0] == MAGIC_JPEG_0
                && fileContent[1] == MAGIC_JPEG_1;

        if (!isPng && !isJpeg) {
            throw new InvalidDiagramException("INVALID_IMAGE_FORMAT",
                    "O arquivo IMAGE não é um PNG ou JPEG válido (assinatura de formato inválida)");
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
