package br.com.fiap.processing.adapter.out.aws;

import br.com.fiap.processing.domain.port.out.FileDownloadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@Component
public class S3FileDownloadAdapter implements FileDownloadPort {

    private static final Logger log = LoggerFactory.getLogger(S3FileDownloadAdapter.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3FileDownloadAdapter(S3Client s3Client,
                                  @Value("${aws.s3.bucket-name}") String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Override
    public byte[] download(String s3Key) {
        log.info("Baixando arquivo do S3. bucket={}, key={}", bucketName, s3Key);

        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(s3Key)
                        .build()
        );

        byte[] content = response.asByteArray();
        log.info("Arquivo baixado com sucesso. key={}, bytes={}", s3Key, content.length);
        return content;
    }
}
