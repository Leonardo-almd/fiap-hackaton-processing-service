package br.com.fiap.processing.unit;

import br.com.fiap.processing.adapter.out.ai.bedrock.BedrockAIAdapter;
import br.com.fiap.processing.adapter.out.ai.bedrock.BedrockAnalysisException;
import br.com.fiap.processing.domain.model.AnalysisResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAIAdapterTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BedrockAIAdapter adapterWith(String responseText) {
        String bedrockResponse = bedrockEnvelope(responseText);
        return new BedrockAIAdapter(body -> bedrockResponse, objectMapper);
    }

    private String bedrockEnvelope(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"content\": [{\"type\": \"text\", \"text\": \"" + escaped + "\"}]}";
    }

    /**
     * Retorna um array de bytes mínimo de uma imagem PNG válida (1x1 pixel).
     */
    private byte[] minimalPngBytes() {
        return new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
            (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xFF, (byte) 0xFF,
            0x3F, 0x00, 0x05, (byte) 0xFE, 0x02, (byte) 0xFE, (byte) 0xDC, (byte) 0xCC,
            0x59, (byte) 0xE7, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
    }

    // ── Testes de análise com IMAGE ───────────────────────────────────────────

    @Test
    void analyze_withImageFile_deserializesResultCorrectly() {
        String analysisJson = """
                {
                  "componentes": [
                    {"nome": "API Gateway", "tipo": "GATEWAY", "descricao": "Ponto de entrada"}
                  ],
                  "riscos": [
                    {
                      "severidade": "ALTA", "categoria": "SEGURANCA",
                      "titulo": "Sem autenticação mútua",
                      "descricao": "Serviços internos sem mTLS",
                      "componentesAfetados": ["API Gateway"]
                    }
                  ],
                  "recomendacoes": [
                    {
                      "prioridade": "ALTA", "titulo": "Implementar mTLS",
                      "descricao": "Configure mTLS entre todos os microsserviços",
                      "referencias": ["https://grpc.io/docs/guides/auth/"]
                    }
                  ]
                }""";

        AnalysisResult result = adapterWith(analysisJson).analyze(minimalPngBytes(), "IMAGE");

        assertNotNull(result);
        assertEquals(1, result.componentes().size());
        assertEquals("API Gateway", result.componentes().get(0).nome());
        assertEquals("GATEWAY", result.componentes().get(0).tipo());
        assertEquals(1, result.riscos().size());
        assertEquals("ALTA", result.riscos().get(0).severidade());
        assertEquals("SEGURANCA", result.riscos().get(0).categoria());
        assertEquals(1, result.recomendacoes().size());
        assertEquals("ALTA", result.recomendacoes().get(0).prioridade());
    }

    @Test
    void analyze_withMultipleComponents_returnsAllComponents() {
        String analysisJson = """
                {
                  "componentes": [
                    {"nome": "Service A", "tipo": "SERVICE", "descricao": "desc A"},
                    {"nome": "Service B", "tipo": "SERVICE", "descricao": "desc B"},
                    {"nome": "Database", "tipo": "DATABASE", "descricao": "desc DB"}
                  ],
                  "riscos": [],
                  "recomendacoes": []
                }""";

        AnalysisResult result = adapterWith(analysisJson).analyze(minimalPngBytes(), "IMAGE");

        assertEquals(3, result.componentes().size());
        assertTrue(result.riscos().isEmpty());
        assertTrue(result.recomendacoes().isEmpty());
    }

    // ── Testes de extração de JSON (markdown wrapping) ────────────────────────

    @Test
    void analyze_withMarkdownJsonBlock_extractsJsonCorrectly() {
        String wrappedJson = "```json\n"
                + "{\"componentes\": [{\"nome\": \"Cache\", \"tipo\": \"CACHE\","
                + " \"descricao\": \"Redis\"}], \"riscos\": [], \"recomendacoes\": []}\n"
                + "```";

        AnalysisResult result = adapterWith(wrappedJson).analyze(minimalPngBytes(), "IMAGE");

        assertEquals(1, result.componentes().size());
        assertEquals("Cache", result.componentes().get(0).nome());
    }

    @Test
    void analyze_withPlainCodeBlock_extractsJsonCorrectly() {
        String wrappedJson = "```\n"
                + "{\"componentes\": [{\"nome\": \"DB\", \"tipo\": \"DATABASE\","
                + " \"descricao\": \"PostgreSQL\"}], \"riscos\": [], \"recomendacoes\": []}\n"
                + "```";

        AnalysisResult result = adapterWith(wrappedJson).analyze(minimalPngBytes(), "IMAGE");

        assertEquals(1, result.componentes().size());
        assertEquals("DB", result.componentes().get(0).nome());
    }

    @Test
    void analyze_withJsonSurroundedByText_extractsJsonCorrectly() {
        String responseWithText = "Aqui está a análise:\n"
                + "{\"componentes\": [{\"nome\": \"SQS\", \"tipo\": \"QUEUE\","
                + " \"descricao\": \"Fila\"}], \"riscos\": [], \"recomendacoes\": []}\n"
                + "Espero que ajude!";

        AnalysisResult result = adapterWith(responseWithText).analyze(minimalPngBytes(), "IMAGE");

        assertEquals(1, result.componentes().size());
        assertEquals("SQS", result.componentes().get(0).nome());
    }

    // ── Testes de erros e fallback ────────────────────────────────────────────

    @Test
    void analyze_whenModelInvokerThrows_propagatesException() {
        BedrockAIAdapter adapter = new BedrockAIAdapter(
                body -> { throw new RuntimeException("Bedrock unavailable"); },
                objectMapper);

        assertThrows(RuntimeException.class,
                () -> adapter.analyze(minimalPngBytes(), "IMAGE"));
    }

    @Test
    void analyze_whenContentArrayIsEmpty_throwsBedrockAnalysisException() {
        BedrockAIAdapter adapter = new BedrockAIAdapter(
                body -> "{\"content\": []}",
                objectMapper);

        assertThrows(BedrockAnalysisException.class,
                () -> adapter.analyze(minimalPngBytes(), "IMAGE"));
    }

    // ── Teste de passagem do conteúdo ao ModelInvoker ─────────────────────────

    @Test
    void analyze_passesBase64ImageInRequestBody() {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        String minimalResponse = "{\"content\": [{\"type\": \"text\", \"text\":"
                + " \"{\\\"componentes\\\":[],\\\"riscos\\\":[],\\\"recomendacoes\\\":[]}\"}]}";

        BedrockAIAdapter adapter = new BedrockAIAdapter(body -> {
            capturedBody.set(body);
            return minimalResponse;
        }, objectMapper);

        adapter.analyze(minimalPngBytes(), "IMAGE");

        assertNotNull(capturedBody.get());
        assertTrue(capturedBody.get().contains("base64"), "Corpo deve conter encoding base64");
        assertTrue(capturedBody.get().contains("image/png"), "Corpo deve declarar media_type image/png");
        assertTrue(capturedBody.get().contains("bedrock-2023-05-31"), "Corpo deve conter versão do Anthropic");
    }
}
