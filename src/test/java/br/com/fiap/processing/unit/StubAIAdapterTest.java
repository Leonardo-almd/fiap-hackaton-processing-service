package br.com.fiap.processing.unit;

import br.com.fiap.processing.adapter.out.ai.stub.StubAIAdapter;
import br.com.fiap.processing.domain.model.AnalysisResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StubAIAdapterTest {

    private final StubAIAdapter adapter = new StubAIAdapter();

    @Test
    void shouldReturnNonEmptyAnalysisForPdf() {
        AnalysisResult result = adapter.analyze("fake-pdf".getBytes(), "PDF");

        assertThat(result).isNotNull();
        assertThat(result.componentes()).isNotEmpty();
        assertThat(result.riscos()).isNotEmpty();
        assertThat(result.recomendacoes()).isNotEmpty();
    }

    @Test
    void shouldReturnNonEmptyAnalysisForImage() {
        AnalysisResult result = adapter.analyze("fake-image".getBytes(), "PNG");

        assertThat(result.componentes()).isNotEmpty();
        assertThat(result.riscos()).isNotEmpty();
        assertThat(result.recomendacoes()).isNotEmpty();
    }

    @Test
    void shouldReturnValidStructureInAllFields() {
        AnalysisResult result = adapter.analyze("content".getBytes(), "PDF");

        result.componentes().forEach(c -> {
            assertThat(c.nome()).isNotBlank();
            assertThat(c.tipo()).isNotBlank();
            assertThat(c.descricao()).isNotBlank();
        });

        result.riscos().forEach(r -> {
            assertThat(r.severidade()).isIn("ALTA", "MEDIA", "BAIXA");
            assertThat(r.categoria()).isNotBlank();
            assertThat(r.titulo()).isNotBlank();
        });

        result.recomendacoes().forEach(rec -> {
            assertThat(rec.prioridade()).isIn("ALTA", "MEDIA", "BAIXA");
            assertThat(rec.titulo()).isNotBlank();
        });
    }
}
