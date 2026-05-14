package br.com.fiap.processing.unit;

import br.com.fiap.processing.domain.exception.InvalidAnalysisResultException;
import br.com.fiap.processing.domain.model.AnalysisResult;
import br.com.fiap.processing.domain.model.Componente;
import br.com.fiap.processing.domain.model.Recomendacao;
import br.com.fiap.processing.domain.model.Risco;
import br.com.fiap.processing.domain.service.AnalysisResultGuardrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultGuardrailTest {

    private AnalysisResultGuardrail guardrail;

    @BeforeEach
    void setUp() {
        guardrail = new AnalysisResultGuardrail();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Componente validComponent() {
        return new Componente("API Gateway", "GATEWAY", "Ponto de entrada");
    }

    private Risco validRisk() {
        return new Risco("ALTA", "SEGURANCA", "Sem autenticação", "Detalhes", List.of("API Gateway"));
    }

    private Recomendacao validRecommendation() {
        return new Recomendacao("ALTA", "Implementar mTLS", "Detalhes", List.of());
    }

    private AnalysisResult validResult() {
        return new AnalysisResult(
                List.of(validComponent()),
                List.of(validRisk()),
                List.of(validRecommendation())
        );
    }

    // ── Resultado válido ──────────────────────────────────────────────────────

    @Test
    void validate_withValidResult_passes() {
        assertDoesNotThrow(() -> guardrail.validate(validResult()));
    }

    @Test
    void validate_withEmptyRisksAndRecommendations_passes() {
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(), List.of());

        assertDoesNotThrow(() -> guardrail.validate(result));
    }

    @Test
    void validate_withMultipleComponents_passes() {
        AnalysisResult result = new AnalysisResult(
                List.of(
                        new Componente("Service A", "SERVICE", "desc"),
                        new Componente("Database", "DATABASE", "desc"),
                        new Componente("Queue", "QUEUE", "desc")
                ),
                List.of(), List.of());

        assertDoesNotThrow(() -> guardrail.validate(result));
    }

    // ── Resultado nulo ────────────────────────────────────────────────────────

    @Test
    void validate_withNullResult_throws() {
        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(null));
    }

    // ── Validação de componentes ──────────────────────────────────────────────

    @Test
    void validate_withNullComponentes_throws() {
        AnalysisResult result = new AnalysisResult(null, List.of(), List.of());
        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(result));
    }

    @Test
    void validate_withEmptyComponentes_throwsWithExplanation() {
        AnalysisResult result = new AnalysisResult(List.of(), List.of(), List.of());

        InvalidAnalysisResultException ex = assertThrows(InvalidAnalysisResultException.class,
                () -> guardrail.validate(result));

        assertTrue(ex.getMessage().contains("componente"));
    }

    @Test
    void validate_withComponentMissingNome_throws() {
        Componente bad = new Componente("", "SERVICE", "desc");
        AnalysisResult result = new AnalysisResult(List.of(bad), List.of(), List.of());

        InvalidAnalysisResultException ex = assertThrows(InvalidAnalysisResultException.class,
                () -> guardrail.validate(result));

        assertTrue(ex.getMessage().contains("componentes[0].nome"));
    }

    @Test
    void validate_withComponentMissingTipo_throws() {
        Componente bad = new Componente("Service", null, "desc");
        AnalysisResult result = new AnalysisResult(List.of(bad), List.of(), List.of());

        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(result));
    }

    @Test
    void validate_withComponentMissingDescricao_throws() {
        Componente bad = new Componente("Service", "SERVICE", "   ");
        AnalysisResult result = new AnalysisResult(List.of(bad), List.of(), List.of());

        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(result));
    }

    // ── Validação de riscos ───────────────────────────────────────────────────

    @Test
    void validate_withNullRiscos_throws() {
        AnalysisResult result = new AnalysisResult(List.of(validComponent()), null, List.of());
        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTA", "MEDIA", "BAIXA"})
    void validate_withValidSeveridade_passes(String severidade) {
        Risco risco = new Risco(severidade, "SEGURANCA", "título", "desc", List.of());
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(risco), List.of());

        assertDoesNotThrow(() -> guardrail.validate(result));
    }

    @Test
    void validate_withInvalidSeveridade_throwsWithDetails() {
        Risco bad = new Risco("CRITICO", "SEGURANCA", "título", "desc", List.of());
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(bad), List.of());

        InvalidAnalysisResultException ex = assertThrows(InvalidAnalysisResultException.class,
                () -> guardrail.validate(result));

        assertTrue(ex.getMessage().contains("riscos[0].severidade"));
        assertTrue(ex.getMessage().contains("CRITICO"));
    }

    @Test
    void validate_withRiscoMissingTitulo_throws() {
        Risco bad = new Risco("ALTA", "SEGURANCA", "", "desc", List.of());
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(bad), List.of());

        InvalidAnalysisResultException ex = assertThrows(InvalidAnalysisResultException.class,
                () -> guardrail.validate(result));

        assertTrue(ex.getMessage().contains("riscos[0].titulo"));
    }

    @Test
    void validate_withRiscoMissingDescricao_throws() {
        Risco bad = new Risco("ALTA", "SEGURANCA", "título", null, List.of());
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(bad), List.of());

        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(result));
    }

    // ── Validação de recomendações ────────────────────────────────────────────

    @Test
    void validate_withNullRecomendacoes_throws() {
        AnalysisResult result = new AnalysisResult(List.of(validComponent()), List.of(), null);
        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTA", "MEDIA", "BAIXA"})
    void validate_withValidPrioridade_passes(String prioridade) {
        Recomendacao rec = new Recomendacao(prioridade, "título", "desc", List.of());
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(), List.of(rec));

        assertDoesNotThrow(() -> guardrail.validate(result));
    }

    @Test
    void validate_withInvalidPrioridade_throwsWithDetails() {
        Recomendacao bad = new Recomendacao("URGENTE", "título", "desc", List.of());
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(), List.of(bad));

        InvalidAnalysisResultException ex = assertThrows(InvalidAnalysisResultException.class,
                () -> guardrail.validate(result));

        assertTrue(ex.getMessage().contains("recomendacoes[0].prioridade"));
        assertTrue(ex.getMessage().contains("URGENTE"));
    }

    @Test
    void validate_withRecomendacaoMissingTitulo_throws() {
        Recomendacao bad = new Recomendacao("ALTA", "  ", "desc", List.of());
        AnalysisResult result = new AnalysisResult(
                List.of(validComponent()), List.of(), List.of(bad));

        assertThrows(InvalidAnalysisResultException.class, () -> guardrail.validate(result));
    }

    // ── Validação de segundo elemento (índice diferente de 0) ─────────────────

    @Test
    void validate_withInvalidSecondRisco_reportsCorrectIndex() {
        List<Risco> riscos = List.of(
                new Risco("ALTA", "SEGURANCA", "válido", "desc", List.of()),
                new Risco("EXTREMO", "SEGURANCA", "inválido", "desc", List.of())
        );
        AnalysisResult result = new AnalysisResult(List.of(validComponent()), riscos, List.of());

        InvalidAnalysisResultException ex = assertThrows(InvalidAnalysisResultException.class,
                () -> guardrail.validate(result));

        assertTrue(ex.getMessage().contains("riscos[1].severidade"));
    }
}
