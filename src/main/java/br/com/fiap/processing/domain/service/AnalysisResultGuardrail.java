package br.com.fiap.processing.domain.service;

import br.com.fiap.processing.domain.exception.InvalidAnalysisResultException;
import br.com.fiap.processing.domain.model.AnalysisResult;
import br.com.fiap.processing.domain.model.Componente;
import br.com.fiap.processing.domain.model.Recomendacao;
import br.com.fiap.processing.domain.model.Risco;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Guardrail de saída: valida a estrutura e os valores do {@link AnalysisResult}
 * retornado pelo modelo de IA antes de persistir o relatório.
 *
 * <p>Regras aplicadas:
 * <ol>
 *   <li>Resultado não pode ser nulo
 *   <li>Listas {@code componentes}, {@code riscos} e {@code recomendacoes} não podem ser nulas
 *   <li>Deve haver ao menos um componente identificado
 *   <li>Cada componente deve ter {@code nome}, {@code tipo} e {@code descricao} preenchidos
 *   <li>Cada risco deve ter {@code severidade} no enum {@code ALTA|MEDIA|BAIXA}
 *       e {@code categoria}, {@code titulo}, {@code descricao} preenchidos
 *   <li>Cada recomendação deve ter {@code prioridade} no enum {@code ALTA|MEDIA|BAIXA}
 *       e {@code titulo}, {@code descricao} preenchidos
 * </ol>
 */
@Component
public class AnalysisResultGuardrail {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultGuardrail.class);

    private static final Set<String> VALID_SEVERIDADES = Set.of("ALTA", "MEDIA", "BAIXA");
    private static final Set<String> VALID_PRIORIDADES = Set.of("ALTA", "MEDIA", "BAIXA");

    /**
     * Valida o resultado produzido pelo modelo de IA.
     *
     * @param result resultado a validar
     * @throws InvalidAnalysisResultException se qualquer regra for violada
     */
    public void validate(AnalysisResult result) {
        if (result == null) {
            throw new InvalidAnalysisResultException("Resultado da análise é nulo");
        }

        validateComponentes(result.componentes());
        validateRiscos(result.riscos());
        validateRecomendacoes(result.recomendacoes());

        log.info("Guardrail de saída aprovado. componentes={}, riscos={}, recomendacoes={}",
                result.componentes().size(), result.riscos().size(), result.recomendacoes().size());
    }

    private void validateComponentes(List<Componente> componentes) {
        if (componentes == null) {
            throw new InvalidAnalysisResultException("Lista de componentes é nula");
        }
        if (componentes.isEmpty()) {
            throw new InvalidAnalysisResultException(
                    "O modelo não identificou nenhum componente arquitetural. "
                            + "Verifique se a imagem contém um diagrama de arquitetura legível");
        }
        for (int i = 0; i < componentes.size(); i++) {
            Componente c = componentes.get(i);
            if (isBlank(c.nome())) {
                throw new InvalidAnalysisResultException(
                        "componentes[" + i + "].nome está vazio");
            }
            if (isBlank(c.tipo())) {
                throw new InvalidAnalysisResultException(
                        "componentes[" + i + "].tipo está vazio");
            }
            if (isBlank(c.descricao())) {
                throw new InvalidAnalysisResultException(
                        "componentes[" + i + "].descricao está vazia");
            }
        }
    }

    private void validateRiscos(List<Risco> riscos) {
        if (riscos == null) {
            throw new InvalidAnalysisResultException("Lista de riscos é nula");
        }
        for (int i = 0; i < riscos.size(); i++) {
            Risco r = riscos.get(i);
            if (!VALID_SEVERIDADES.contains(r.severidade())) {
                throw new InvalidAnalysisResultException(
                        "riscos[" + i + "].severidade inválida: '" + r.severidade()
                                + "'. Valores aceitos: " + VALID_SEVERIDADES);
            }
            if (isBlank(r.categoria())) {
                throw new InvalidAnalysisResultException("riscos[" + i + "].categoria está vazia");
            }
            if (isBlank(r.titulo())) {
                throw new InvalidAnalysisResultException("riscos[" + i + "].titulo está vazio");
            }
            if (isBlank(r.descricao())) {
                throw new InvalidAnalysisResultException("riscos[" + i + "].descricao está vazia");
            }
        }
    }

    private void validateRecomendacoes(List<Recomendacao> recomendacoes) {
        if (recomendacoes == null) {
            throw new InvalidAnalysisResultException("Lista de recomendações é nula");
        }
        for (int i = 0; i < recomendacoes.size(); i++) {
            Recomendacao rec = recomendacoes.get(i);
            if (!VALID_PRIORIDADES.contains(rec.prioridade())) {
                throw new InvalidAnalysisResultException(
                        "recomendacoes[" + i + "].prioridade inválida: '" + rec.prioridade()
                                + "'. Valores aceitos: " + VALID_PRIORIDADES);
            }
            if (isBlank(rec.titulo())) {
                throw new InvalidAnalysisResultException(
                        "recomendacoes[" + i + "].titulo está vazio");
            }
            if (isBlank(rec.descricao())) {
                throw new InvalidAnalysisResultException(
                        "recomendacoes[" + i + "].descricao está vazia");
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
