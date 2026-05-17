package br.com.fiap.processing.adapter.out.ai.bedrock;

import br.com.fiap.processing.domain.model.AnalysisResult;
import br.com.fiap.processing.domain.port.out.AIAnalysisPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Implementação de {@link AIAnalysisPort} que usa Amazon Bedrock (Claude Sonnet 4.5)
 * para analisar diagramas de arquitetura e retornar riscos, componentes e recomendações.
 *
 * <p>Ativado via: {@code ai.adapter=bedrock}
 *
 * <p>Suporta arquivos do tipo:
 * <ul>
 *   <li>{@code IMAGE} — PNG, JPEG enviados diretamente como imagem base64
 *   <li>{@code PDF}   — páginas renderizadas via PDFBox antes do envio
 * </ul>
 */
public class BedrockAIAdapter implements AIAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(BedrockAIAdapter.class);

    private static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";
    private static final int MAX_TOKENS = 4096;
    // Temperatura baixa para saídas determinísticas e bem estruturadas
    private static final double TEMPERATURE = 0.2;
    private static final String MEDIA_TYPE_PNG = "image/png";
    private static final String MEDIA_TYPE_JPEG = "image/jpeg";

    // Magic bytes para detecção de formato de imagem
    private static final int JPEG_MAGIC_0 = 0xFF;
    private static final int JPEG_MAGIC_1 = 0xD8;

    /**
     * System prompt v2 — instrui o modelo a agir como analisador de arquitetura,
     * define o schema JSON de saída com todos os enums válidos e orienta o
     * comportamento quando o conteúdo não é um diagrama legível.
     *
     * <p>Melhorias em relação à v1:
     * <ul>
     *   <li>Schema com exemplos de valores para cada campo enum
     *   <li>Instrução explícita sobre nível de granularidade (arquitetural, não de código)
     *   <li>Tratamento de diagramas ilegíveis: retornar o melhor possível com os dados visíveis
    *   <li>Ênfase em riscos de segurança, ponto forte do Claude Sonnet 4.5
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
            Você é um especialista sênior em arquitetura de software e segurança de sistemas. \
            Sua única função é analisar diagramas de arquitetura e retornar uma análise \
            estruturada em JSON puro — sem nenhum texto antes ou depois, sem markdown.

            REGRAS OBRIGATÓRIAS:
            - Retorne EXCLUSIVAMENTE o objeto JSON. Nenhuma palavra fora do JSON.
            - Analise no nível ARQUITETURAL (serviços, bancos, filas, gateways), não de código.
            - Se o diagrama estiver parcialmente ilegível, analise o que for visível.
            - Priorize riscos de segurança e acoplamento, pois são os mais críticos.
            - Para componentes afetados em riscos, use exatamente o mesmo "nome" definido \
            em "componentes".

            SCHEMA DO JSON (siga exatamente):
            {
              "componentes": [
                {
                  "nome": "nome único e descritivo do componente",
                  "tipo": "GATEWAY | SERVICE | DATABASE | QUEUE | CACHE | \
            LOAD_BALANCER | STORAGE | CLIENT | OTHER",
                  "descricao": "função do componente no sistema (1-2 frases)"
                }
              ],
              "riscos": [
                {
                  "severidade": "ALTA | MEDIA | BAIXA",
                  "categoria": "SEGURANCA | ACOPLAMENTO | ESCALABILIDADE | \
            DISPONIBILIDADE | OBSERVABILIDADE | OTHER",
                  "titulo": "título objetivo do risco (máx 80 caracteres)",
                  "descricao": "explicação do risco e seu impacto potencial",
                  "componentesAfetados": ["nome exato do componente da lista acima"]
                }
              ],
              "recomendacoes": [
                {
                  "prioridade": "ALTA | MEDIA | BAIXA",
                  "titulo": "título da recomendação (máx 80 caracteres)",
                  "descricao": "como implementar a recomendação de forma prática",
                  "referencias": ["URL ou referência técnica relevante"]
                }
              ]
            }""";

    /**
     * User prompt v2 — solicita análise em três dimensões e reforça a restrição
     * de formato puro JSON sem texto adicional.
     */
    private static final String USER_PROMPT = """
            Analise este diagrama de arquitetura de software e produza:

            1. COMPONENTES: liste todos os elementos arquiteturais visíveis \
            (nome único, tipo e função no sistema)

            2. RISCOS: identifique vulnerabilidades e problemas de design, ordenados \
            do mais crítico ao menos crítico. Dê atenção especial a:
               - Ausência de autenticação ou autorização entre serviços
               - Pontos únicos de falha (SPOF)
               - Banco de dados ou recursos compartilhados entre microsserviços
               - Falta de circuit breakers ou rate limiting
               - Comunicação não criptografada

            3. RECOMENDAÇÕES: para cada risco identificado, forneça uma recomendação \
            concreta e priorizada com referências técnicas.

            Retorne APENAS o JSON, sem texto algum fora do objeto JSON.""";

    /**
     * Abstração da chamada HTTP ao Amazon Bedrock.
     * Recebe o corpo da requisição em JSON e retorna o corpo da resposta em JSON.
     * Facilita testes unitários sem necessidade de mock do AWS SDK.
     */
    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(String jsonRequestBody);
    }

    private final ModelInvoker modelInvoker;
    private final ObjectMapper objectMapper;
    private final PdfToImageConverter pdfConverter;

    public BedrockAIAdapter(ModelInvoker modelInvoker, ObjectMapper objectMapper) {
        this.modelInvoker = modelInvoker;
        this.objectMapper = objectMapper;
        this.pdfConverter = new PdfToImageConverter();
    }

    @Override
    public AnalysisResult analyze(byte[] fileContent, String fileType) {
        log.info("BedrockAIAdapter: iniciando análise. fileType={}, bytes={}", fileType, fileContent.length);
        try {
            List<byte[]> imagePages = toImageList(fileContent, fileType);
            String requestBody = buildRequestBody(imagePages, detectMediaType(imagePages, fileType));
            String responseJson = invokeModel(requestBody);
            AnalysisResult result = parseResponse(responseJson);
            log.info("Análise Bedrock concluída. componentes={}, riscos={}, recomendacoes={}",
                    result.componentes().size(), result.riscos().size(), result.recomendacoes().size());
            return result;
        } catch (IOException e) {
            throw new BedrockAnalysisException("Falha ao processar arquivo para o Bedrock: " + e.getMessage(), e);
        }
    }

    // ── Conversão de arquivo ──────────────────────────────────────────────────

    private List<byte[]> toImageList(byte[] fileContent, String fileType) throws IOException {
        if ("PDF".equalsIgnoreCase(fileType)) {
            List<byte[]> pages = pdfConverter.convertToImages(fileContent);
            log.info("PDF convertido para imagens. páginas={}", pages.size());
            return pages;
        }
        return List.of(fileContent);
    }

    /**
     * Detecta o media type a partir dos magic bytes da primeira página.
     * PDFs são convertidos para PNG pelo {@link PdfToImageConverter}, portanto
     * sempre resultam em {@code image/png}. Imagens diretas podem ser JPEG ou PNG.
     */
    private String detectMediaType(List<byte[]> imagePages, String fileType) {
        if ("PDF".equalsIgnoreCase(fileType)) {
            return MEDIA_TYPE_PNG;
        }
        byte[] firstPage = imagePages.get(0);
        if (firstPage.length >= 2
                && (firstPage[0] & 0xFF) == JPEG_MAGIC_0
                && (firstPage[1] & 0xFF) == JPEG_MAGIC_1) {
            return MEDIA_TYPE_JPEG;
        }
        return MEDIA_TYPE_PNG;
    }

    // ── Construção do payload Bedrock ─────────────────────────────────────────

    private String buildRequestBody(List<byte[]> imagePages, String mediaType) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("anthropic_version", ANTHROPIC_VERSION);
        root.put("max_tokens", MAX_TOKENS);
        root.put("temperature", TEMPERATURE);
        root.put("system", SYSTEM_PROMPT);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        ArrayNode content = userMessage.putArray("content");

        for (byte[] imageBytes : imagePages) {
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);
            ObjectNode imageBlock = content.addObject();
            imageBlock.put("type", "image");
            ObjectNode source = imageBlock.putObject("source");
            source.put("type", "base64");
            source.put("media_type", mediaType);
            source.put("data", base64Data);
        }

        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", USER_PROMPT);

        return objectMapper.writeValueAsString(root);
    }

    // ── Chamada ao Bedrock ────────────────────────────────────────────────────

    private String invokeModel(String requestBody) {
        log.debug("Invocando Bedrock via ModelInvoker");
        return modelInvoker.invoke(requestBody);
    }

    // ── Parsing da resposta ───────────────────────────────────────────────────

    private AnalysisResult parseResponse(String responseJson) throws IOException {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode contentArray = root.path("content");

        if (contentArray.isEmpty()) {
            throw new BedrockAnalysisException("Resposta do Bedrock não contém campo 'content'");
        }

        String rawText = contentArray.get(0).path("text").asText();
        log.debug("Texto bruto recebido do modelo. tamanho={}", rawText.length());

        String analysisJson = extractJson(rawText);
        return objectMapper.readValue(analysisJson, AnalysisResult.class);
    }

    /**
     * Extrai o JSON do texto retornado pelo modelo, removendo eventuais blocos
     * de markdown (```json ... ```) ou texto explicativo ao redor.
     */
    private String extractJson(String text) {
        String trimmed = text.trim();

        if (trimmed.contains("```json")) {
            int start = trimmed.indexOf("```json") + 7;
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("```") + 3;
            int end = trimmed.lastIndexOf("```");
            if (end > start) {
                return trimmed.substring(start, end).trim();
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }

        return trimmed;
    }
}
