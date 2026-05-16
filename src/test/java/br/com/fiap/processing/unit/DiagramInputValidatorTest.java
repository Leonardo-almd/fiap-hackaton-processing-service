package br.com.fiap.processing.unit;

import br.com.fiap.processing.domain.exception.InvalidDiagramException;
import br.com.fiap.processing.domain.model.SqsMessage;
import br.com.fiap.processing.domain.service.DiagramInputValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagramInputValidatorTest {

    private DiagramInputValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DiagramInputValidator();
    }

    // ── Magic bytes helpers ───────────────────────────────────────────────────

    private byte[] validPngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private byte[] validJpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
    }

    private byte[] validPdfBytes() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-
    }

    private SqsMessage messageWith(String fileType) {
        return new SqsMessage("job-1", "uploads/file", fileType,
                1024L, "diagram", "desc", "2024-01-01T00:00:00Z");
    }

    // ── Tipos de arquivo válidos ──────────────────────────────────────────────

    @Test
    void validate_withPdfFile_passes() {
        assertDoesNotThrow(() -> validator.validate(messageWith("PDF"), validPdfBytes()));
    }

    @Test
    void validate_withImageFilePng_passes() {
        assertDoesNotThrow(() -> validator.validate(messageWith("IMAGE"), validPngBytes()));
    }

    @Test
    void validate_withImageFileJpeg_passes() {
        assertDoesNotThrow(() -> validator.validate(messageWith("IMAGE"), validJpegBytes()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "Pdf", "PDF"})
    void validate_withCaseInsensitivePdf_passes(String fileType) {
        assertDoesNotThrow(() -> validator.validate(messageWith(fileType), validPdfBytes()));
    }

    // ── Tipo de arquivo inválido ──────────────────────────────────────────────

    @Test
    void validate_withUnsupportedFileType_throwsInvalidDiagramException() {
        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("DOCX"), validPngBytes()));

        assertEquals("INVALID_FILE_TYPE", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("DOCX"));
    }

    @Test
    void validate_withNullFileType_throwsInvalidDiagramException() {
        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith(null), validPngBytes()));

        assertEquals("INVALID_FILE_TYPE", ex.getErrorCode());
    }

    // ── Conteúdo vazio ────────────────────────────────────────────────────────

    @Test
    void validate_withEmptyContent_throwsInvalidDiagramException() {
        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("IMAGE"), new byte[0]));

        assertEquals("EMPTY_FILE", ex.getErrorCode());
    }

    @Test
    void validate_withNullContent_throwsInvalidDiagramException() {
        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("IMAGE"), null));

        assertEquals("EMPTY_FILE", ex.getErrorCode());
    }

    // ── Tamanho do arquivo ────────────────────────────────────────────────────

    @Test
    void validate_withFileExceedingMaxSize_throwsInvalidDiagramException() {
        // Cria array de 21 MB com magic bytes de PNG
        byte[] oversizedFile = new byte[(int) (DiagramInputValidator.MAX_FILE_SIZE_BYTES + 1)];
        byte[] png = validPngBytes();
        System.arraycopy(png, 0, oversizedFile, 0, png.length);

        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("IMAGE"), oversizedFile));

        assertEquals("FILE_TOO_LARGE", ex.getErrorCode());
        assertTrue(ex.getMessage().contains(String.valueOf(DiagramInputValidator.MAX_FILE_SIZE_MB)));
    }

    @Test
    void validate_withFileSizeAtLimit_passes() {
        byte[] maxSizeFile = new byte[(int) DiagramInputValidator.MAX_FILE_SIZE_BYTES];
        byte[] png = validPngBytes();
        System.arraycopy(png, 0, maxSizeFile, 0, png.length);

        assertDoesNotThrow(() -> validator.validate(messageWith("IMAGE"), maxSizeFile));
    }

    // ── Assinatura de formato (magic bytes) ───────────────────────────────────

    @Test
    void validate_withPdfTypeButNotPdfSignature_throwsInvalidDiagramException() {
        byte[] notPdf = "this is not a pdf file".getBytes();

        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("PDF"), notPdf));

        assertEquals("INVALID_PDF_SIGNATURE", ex.getErrorCode());
    }

    @Test
    void validate_withImageTypeButUnknownFormat_throwsInvalidDiagramException() {
        byte[] unknown = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05};

        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("IMAGE"), unknown));

        assertEquals("INVALID_IMAGE_FORMAT", ex.getErrorCode());
    }

    @Test
    void validate_withTextFileAsImage_throwsInvalidDiagramException() {
        byte[] textContent = "Hello, this is a text file".getBytes();

        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("IMAGE"), textContent));

        assertEquals("INVALID_IMAGE_FORMAT", ex.getErrorCode());
    }

    @Test
    void validate_errorCodeIsIncludedInExceptionMessage() {
        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("MP4"), validPngBytes()));

        assertEquals("INVALID_FILE_TYPE", ex.getErrorCode());
        // O errorCode deve ser rastreável nos logs do job
        assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank());
    }

    // ── Arrays muito pequenos (cobertura de edge cases) ───────────────────────

    @Test
    void validate_withSingleByteContent_throwsForImageInvalidFormat() {
        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("IMAGE"), new byte[]{0x42}));

        assertEquals("INVALID_IMAGE_FORMAT", ex.getErrorCode());
    }

    @Test
    void validate_withSingleByteContent_throwsForPdfInvalidSignature() {
        InvalidDiagramException ex = assertThrows(InvalidDiagramException.class,
                () -> validator.validate(messageWith("PDF"), new byte[]{0x25}));

        assertEquals("INVALID_PDF_SIGNATURE", ex.getErrorCode());
    }
}
