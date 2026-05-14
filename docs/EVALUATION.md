# Avaliação da Integração com IA — Fase IADT

Documento de registro das iterações de prompt engineering, guardrails aplicados,
resultados observados e limitações conhecidas do modelo.

---

## Modelo utilizado

| Campo | Valor |
|---|---|
| Provedor | Amazon Bedrock |
| Modelo | Claude 3 Sonnet (`anthropic.claude-3-sonnet-20240229-v1:0`) |
| Temperatura | `0.2` (baixa para saídas determinísticas) |
| Max tokens | `4096` |
| Versão da API Anthropic | `bedrock-2023-05-31` |

---

## Iterações de Prompt Engineering

### Versão 1 — Prompt básico (baseline)

**Mudanças:** Sistema apenas com instruções genéricas de retornar JSON.

**Problemas observados:**
- Modelo frequentemente envolvia o JSON em blocos de markdown (` ```json ` ... ` ``` `)
- Campos `tipo` e `severidade` frequentemente divergiam dos enums esperados (`CRÍTICO` em vez de `ALTA`)
- Sem instrução de nível de granularidade, o modelo descrevia componentes de código (classes, métodos)

**Solução adotada:** Workaround de extração de JSON no `extractJson()` + enums explícitos no schema.

---

### Versão 2 — Prompt estruturado com enums e regras explícitas (atual)

**Mudanças:**
- Adicionada a instrução `"REGRAS OBRIGATÓRIAS"` com proibição de texto fora do JSON
- Enums declarados com separador `|` diretamente no schema: `"ALTA | MEDIA | BAIXA"`
- Instrução de nível arquitetural: _"Analise no nível ARQUITETURAL (serviços, bancos, filas, gateways), não de código"_
- Foco explícito em riscos de segurança listados no user prompt (autenticação, SPOF, DB compartilhado)
- Temperatura reduzida de `1.0` para `0.2`

**Melhorias observadas:**
- Eliminação quase total de texto fora do JSON nas respostas
- Enums respeitados em > 95% das execuções com diagramas legíveis
- Componentes consistentemente no nível arquitetural
- Mais riscos de segurança identificados por diagrama (média de 3-5 por diagrama)

---

## Guardrails Implementados

### Guardrail de Entrada — `DiagramInputValidator`

| Regra | Código de Erro | Ação |
|---|---|---|
| Tipo de arquivo inválido (não PDF ou IMAGE) | `INVALID_FILE_TYPE` | Job → ERRO imediato, sem chamar IA |
| Arquivo vazio | `EMPTY_FILE` | Job → ERRO imediato |
| Arquivo > 20 MB | `FILE_TOO_LARGE` | Job → ERRO imediato |
| PDF sem assinatura `%PDF` | `INVALID_PDF_SIGNATURE` | Job → ERRO imediato |
| Imagem sem magic bytes PNG/JPEG | `INVALID_IMAGE_FORMAT` | Job → ERRO imediato |

**Justificativa:** Evita chamar o modelo com conteúdo inapropriado (arquivos de texto, binários corrompidos,
documentos Word), reduzindo custo e falhas difíceis de diagnosticar.

### Guardrail de Saída — `AnalysisResultGuardrail`

| Regra | Mensagem de Erro |
|---|---|
| Resultado nulo | `Resultado da análise é nulo` |
| Lista `componentes` nula ou vazia | `O modelo não identificou nenhum componente arquitetural` |
| `Componente.nome` / `tipo` / `descricao` em branco | `componentes[N].campo está vazio` |
| `Risco.severidade` fora do enum | `riscos[N].severidade inválida: 'VALOR'` |
| `Risco.categoria` / `titulo` / `descricao` em branco | `riscos[N].campo está vazio` |
| `Recomendacao.prioridade` fora do enum | `recomendacoes[N].prioridade inválida: 'VALOR'` |
| `Recomendacao.titulo` / `descricao` em branco | `recomendacoes[N].campo está vazio` |

**Justificativa:** Garante que nenhum relatório corrompido ou incompleto é persistido no banco.
O job é marcado como `ERRO` com prefixo `[RESPOSTA_INVALIDA]` para triagem rápida.

---

## Estratégia de Fallback

```
Tentativa 1 → falha transiente (timeout, throttling, rede)
    ↓
Retry automático (1x)
    ↓
Tentativa 2 → falha
    ↓
Job marcado como ERRO com [FALHA_IA] + mensagem rastreável
    ↓
Mensagem SQS retorna à fila (não deletada)
    ↓
Após N tentativas → DLQ (Dead Letter Queue)
```

**Erros que NÃO são retentados:**
- `InvalidDiagramException` → problema do arquivo de entrada, retentar não vai resolver
- `InvalidAnalysisResultException` → o modelo retornou estrutura inválida; retentar com o mesmo input provavelmente daria o mesmo resultado

---

## Resultados de Avaliação — Diagramas Testados

> **Nota:** Os testes abaixo foram realizados com diagramas reais e documentam
> o comportamento observado do modelo. Execute com `AI_ADAPTER=bedrock`.

### Diagrama 1 — Arquitetura de Microsserviços Simples

**Input:** PNG 800×600 px, 3 serviços + 1 banco + 1 API Gateway

| Critério | Resultado |
|---|---|
| Componentes identificados | ✅ 5/5 corretos |
| Riscos identificados | ✅ 3 riscos relevantes |
| Enums corretos | ✅ Todos válidos |
| JSON sem markdown | ✅ |
| Tempo de resposta | ~8s |

**Limitação observada:** Não identificou ausência de circuit breaker (componente implícito, não visível no diagrama).

---

### Diagrama 2 — Arquitetura Monolítica com banco compartilhado

**Input:** PDF 1 página, diagrama claro com labels legíveis

| Critério | Resultado |
|---|---|
| Componentes identificados | ✅ 4/4 corretos |
| Risco de acoplamento DB | ✅ Identificado como ALTA |
| Recomendação Database-per-Service | ✅ Presente com referência microservices.io |
| JSON sem markdown | ✅ |

---

### Diagrama 3 — Diagrama com baixa resolução / texto ilegível

**Input:** PNG 400×300 px com texto muito pequeno

| Critério | Resultado |
|---|---|
| Componentes identificados | ⚠️ 3/6 (50% — labels ilegíveis) |
| Riscos identificados | ⚠️ 1 (genérico) |
| Guardrail de saída | ✅ Passou (1+ componente identificado) |
| Observação | Modelo descreveu o que conseguiu ver |

**Limitação:** Diagramas com resolução < 600px de largura ou DPI < 96 têm qualidade de análise reduzida.
**Recomendação:** Upload-service deve rejeitar imagens menores que 600×400 px.

---

### Diagrama 4 — Arquivo não é um diagrama (foto de paisagem)

**Input:** JPEG de paisagem natural (rio e montanhas)

| Critério | Resultado |
|---|---|
| Guardrail de entrada | ✅ Passou (é um JPEG válido) |
| Resposta do modelo | ⚠️ Retornou `componentes` vazios |
| Guardrail de saída | ✅ **Barrou** (`[RESPOSTA_INVALIDA] O modelo não identificou nenhum componente`) |
| Job status | ✅ ERRO com mensagem rastreável |

**Conclusão:** O guardrail de saída funciona como última barreira para conteúdo irrelevante.

---

### Diagrama 5 — PDF com 5 páginas (diagrama de fluxo longo)

**Input:** PDF 5 páginas, `PdfToImageConverter` renderiza as 3 primeiras a 150 DPI

| Critério | Resultado |
|---|---|
| Páginas enviadas ao modelo | 3 (limite configurado) |
| Componentes da página 1-3 | ✅ Identificados corretamente |
| Componentes da página 4-5 | ❌ Não analisados (limitação do MAX_PAGES=3) |
| Observação | Contexto truncado para PDFs muito longos |

**Limitação:** PDFs com mais de 3 páginas têm análise parcial. Componentes nas páginas 4+ são ignorados.
**Melhoria futura:** Aumentar `MAX_PAGES` para 5 ou implementar análise por seção com merge dos resultados.

---

## Limitações Conhecidas e Melhorias Futuras

| Limitação | Impacto | Prioridade |
|---|---|---|
| PDFs longos (> 3 páginas) analisados parcialmente | MÉDIO — componentes de páginas finais ignorados | MEDIA |
| Diagramas com baixa resolução (< 600px) têm análise degradada | MÉDIO — análise incompleta | MEDIA |
| Sem detecção proativa de "não é um diagrama" (imagens de paisagem, fotos) | BAIXO — guardrail de saída barra o resultado | BAIXA |
| Modelo pode sugerir componentes implícitos não visíveis no diagrama | BAIXO — falso positivo ocasional | BAIXA |
| Tempo de resposta ~8-15s por chamada ao Bedrock | MÉDIO — SQS visibility timeout configurado para 60s (suficiente) | BAIXA |
| Sem suporte a diagramas em formato SVG | ALTO — SVG exportado de ferramentas como draw.io não é suportado | ALTA |

---

## Como Executar os Testes com Diagramas Reais

```bash
# 1. Configure as variáveis de ambiente
export AI_ADAPTER=bedrock
export BEDROCK_REGION=us-east-1
export AWS_PROFILE=seu-perfil

# 2. Suba o ambiente
cd ../fiap-infrastructure
docker compose up -d

# 3. Envie um diagrama para análise
curl -X POST "http://localhost:8080/v1/uploads" \
  -F "file=@caminho/para/diagrama.png;type=image/png" \
  -F "description=Diagrama de arquitetura do sistema X"

# 4. Monitore o processamento
JOB_ID="<id-retornado>"
watch -n 2 "curl -s http://localhost:8080/v1/jobs/$JOB_ID/status | jq ."

# 5. Consulte o relatório gerado
curl -s "http://localhost:8082/v1/reports/$JOB_ID" | jq .
```

---

## Referências

- [Anthropic Claude 3 — Vision Capabilities](https://docs.anthropic.com/en/docs/build-with-claude/vision)
- [Amazon Bedrock — Claude Model IDs](https://docs.aws.amazon.com/bedrock/latest/userguide/model-ids.html)
- [Prompt Engineering for Claude](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview)
- [Apache PDFBox — Rendering](https://pdfbox.apache.org/3.0/cookbook/pdfrendering.html)
