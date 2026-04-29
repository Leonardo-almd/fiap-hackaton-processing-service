package br.com.fiap.processing.domain.port.out;

public interface FileDownloadPort {

    /**
     * Faz download do arquivo do S3 e retorna seu conteúdo como bytes.
     */
    byte[] download(String s3Key);
}
