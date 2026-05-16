package br.com.fiap.processing.adapter.out.ai.bedrock;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Converte páginas de um PDF em imagens PNG usando Apache PDFBox 3.x.
 * Renderiza no máximo MAX_PAGES páginas a 150 DPI para envio ao modelo de visão.
 */
class PdfToImageConverter {

    private static final Logger log = LoggerFactory.getLogger(PdfToImageConverter.class);
    private static final float RENDER_DPI = 150f;
    private static final int MAX_PAGES = 3;

    /**
     * Converte as primeiras páginas de um PDF em uma lista de bytes PNG.
     *
     * @param pdfBytes conteúdo binário do arquivo PDF
     * @return lista de imagens PNG (uma por página renderizada)
     * @throws IOException se o PDF não puder ser lido ou renderizado
     */
    List<byte[]> convertToImages(byte[] pdfBytes) throws IOException {
        List<byte[]> images = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pagesToRender = Math.min(document.getNumberOfPages(), MAX_PAGES);
            log.info("Convertendo PDF para imagem. totalPaginas={}, paginas={}", document.getNumberOfPages(),
                    pagesToRender);

            for (int i = 0; i < pagesToRender; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);
                images.add(baos.toByteArray());
                log.debug("Página {} convertida. bytes={}", i, baos.size());
            }
        }

        return images;
    }
}
