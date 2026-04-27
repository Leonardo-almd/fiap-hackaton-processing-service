package br.com.fiap.processing.adapter.out.ai.stub;

import br.com.fiap.processing.domain.model.*;
import br.com.fiap.processing.domain.port.out.AIAnalysisPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Implementação stub do AIAnalysisPort para a Fase 1 (SOAT).
 * Retorna uma análise mockada com a estrutura real do relatório,
 * permitindo validar o fluxo completo sem depender do modelo de IA.
 *
 * Ativado via: ai.adapter=stub (padrão)
 * Substituído por BedrockAIAdapter na Fase 2 (IADT).
 */
public class StubAIAdapter implements AIAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(StubAIAdapter.class);

    @Override
    public AnalysisResult analyze(byte[] fileContent, String fileType) {
        log.info("StubAIAdapter: simulando análise. fileType={}, bytes={}", fileType, fileContent.length);

        List<Componente> componentes = List.of(
                new Componente("API Gateway", "GATEWAY",
                        "Ponto de entrada centralizado que roteia requisições para os microsserviços downstream."),
                new Componente("Serviço de Autenticação", "SERVICE",
                        "Responsável por validar tokens JWT e gerenciar sessões de usuário."),
                new Componente("Banco de Dados Principal", "DATABASE",
                        "PostgreSQL acessado por múltiplos serviços — possível ponto de acoplamento."),
                new Componente("Fila de Mensagens", "QUEUE",
                        "Mensageria assíncrona para desacoplar produtores e consumidores."),
                new Componente("Cache Redis", "CACHE",
                        "Cache em memória para reduzir latência em consultas frequentes.")
        );

        List<Risco> riscos = List.of(
                new Risco("ALTA", "ACOPLAMENTO",
                        "Banco de dados compartilhado entre microsserviços",
                        "O uso de um único banco de dados por múltiplos serviços cria acoplamento forte, " +
                        "dificulta o deploy independente e torna o banco um ponto único de falha.",
                        List.of("Banco de Dados Principal")),
                new Risco("MEDIA", "SEGURANCA",
                        "Ausência de autenticação entre serviços internos",
                        "A comunicação interna entre microsserviços não apresenta mecanismos " +
                        "de autenticação mútua (mTLS ou tokens de serviço).",
                        List.of("API Gateway", "Serviço de Autenticação")),
                new Risco("BAIXA", "ESCALABILIDADE",
                        "Cache sem política de invalidação visível",
                        "Não há indicação de estratégia de invalidação do cache Redis, " +
                        "o que pode causar dados obsoletos sob alta concorrência.",
                        List.of("Cache Redis"))
        );

        List<Recomendacao> recomendacoes = List.of(
                new Recomendacao("ALTA",
                        "Adotar o padrão Database per Service",
                        "Cada microsserviço deve possuir seu próprio banco de dados isolado. " +
                        "Use event sourcing ou sagas para consistência eventual entre serviços.",
                        List.of("https://microservices.io/patterns/data/database-per-service.html")),
                new Recomendacao("MEDIA",
                        "Implementar autenticação entre serviços internos",
                        "Configure mutual TLS (mTLS) ou tokens de serviço (JWT com escopo restrito) " +
                        "para comunicação interna entre microsserviços.",
                        List.of("https://oauth.net/2/", "https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/security/ssl")),
                new Recomendacao("BAIXA",
                        "Definir TTL e política de invalidação no cache",
                        "Estabeleça TTLs adequados para cada tipo de dado no Redis e implemente " +
                        "invalidação explícita ao atualizar entidades cacheadas.",
                        List.of())
        );

        return new AnalysisResult(componentes, riscos, recomendacoes);
    }
}
