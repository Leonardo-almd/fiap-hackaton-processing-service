# fiap-processing-service

Microsserviço responsável por:
- Consumir mensagens da fila AWS SQS
- Orquestrar o pipeline de análise de IA
- Atualizar o status do job via HTTP no upload-service
- Persistir o relatório gerado via HTTP no report-service

**Responsável**: Pessoa 2

## Estratégia de IA

O serviço usa o padrão **Strategy** para a integração com IA via a interface `AIAnalysisPort`:

- **Fase 1 (SOAT)**: `StubAIAdapter` — retorna dados mockados com a estrutura real do relatório. Ativado com `AI_ADAPTER=stub`.
- **Fase 2 (IADT)**: `BedrockAIAdapter` — integração com Amazon Bedrock (Claude 3 Sonnet). Ativado com `AI_ADAPTER=bedrock`.

## Estrutura (Arquitetura Hexagonal)

```
src/main/java/br/com/fiap/processing/
├── domain/
│   ├── model/          # AnalysisJob, SqsMessage, AnalysisResult
│   └── port/
│       ├── in/         # ProcessDiagramUseCase
│       └── out/        # FileStoragePort, AIAnalysisPort, JobStatusPort, ReportPort
├── application/
│   └── usecase/        # ProcessDiagramUseCaseImpl
├── adapter/
│   ├── in/
│   │   └── sqs/        # SqsMessageConsumer (polling scheduler)
│   └── out/
│       ├── aws/         # S3FileStorageAdapter, SqsAdapter
│       ├── http/        # UploadServiceClient, ReportServiceClient
│       └── ai/
│           ├── stub/    # StubAIAdapter
│           └── bedrock/ # BedrockAIAdapter (Fase 2)
└── config/             # AwsConfig, AIAdapterConfig, HttpClientConfig
```

## Ativando a integração real com IA (Amazon Bedrock)

### Pré-requisitos

1. **Conta AWS com acesso ao Amazon Bedrock habilitado** na região escolhida (`us-east-1` por padrão).
2. **Modelo liberado**: no Console AWS → Amazon Bedrock → Model access → solicitar acesso ao modelo `Claude 3 Sonnet` (`anthropic.claude-3-sonnet-20240229-v1:0`). O acesso é gratuito para solicitar, mas pode levar alguns minutos para ser aprovado.
3. **Credenciais AWS** com a permissão `bedrock:InvokeModel` configuradas no ambiente (ver seção IAM abaixo).

### Permissões IAM necessárias

A task/role que executa o serviço precisa da seguinte política mínima:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "InvokeBedrockModel",
      "Effect": "Allow",
      "Action": "bedrock:InvokeModel",
      "Resource": "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-3-sonnet-20240229-v1:0"
    }
  ]
}
```

> Em ECS Fargate, essa política é anexada à **Task Role** (não à Task Execution Role). O SDK usa `DefaultCredentialsProvider`, que funciona automaticamente com Task Role no ECS.

### Variáveis de ambiente necessárias

| Variável | Valor para Bedrock | Descrição |
|---|---|---|
| `AI_ADAPTER` | `bedrock` | Ativa o `BedrockAIAdapter` no lugar do stub |
| `BEDROCK_MODEL_ID` | `anthropic.claude-3-sonnet-20240229-v1:0` | ID do modelo no Bedrock |
| `BEDROCK_REGION` | `us-east-1` | Região onde o modelo está habilitado |

### Rodando localmente com Bedrock real

```bash
export AI_ADAPTER=bedrock
export BEDROCK_REGION=us-east-1
export BEDROCK_MODEL_ID=anthropic.claude-3-sonnet-20240229-v1:0

# Credenciais AWS (perfil local ou variáveis de ambiente)
export AWS_ACCESS_KEY_ID=<sua_access_key>
export AWS_SECRET_ACCESS_KEY=<sua_secret_key>
# Se usar perfil nomeado:
# export AWS_PROFILE=seu-perfil

mvn spring-boot:run
```

> O `AWS_ENDPOINT_OVERRIDE` **não deve** ser definido ao usar Bedrock real — o SDK usa os endpoints públicos da AWS automaticamente.

### Rodando com Docker Compose + Bedrock real

No `docker-compose.override.yml` (crie na raiz do `fiap-infrastructure` se não existir):

```yaml
services:
  processing-service:
    environment:
      AI_ADAPTER: bedrock
      BEDROCK_REGION: us-east-1
      BEDROCK_MODEL_ID: anthropic.claude-3-sonnet-20240229-v1:0
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
```

Suba apenas o `processing-service` com a nova configuração:

```bash
docker compose up -d --build processing-service
```

### Verificando que o Bedrock está sendo chamado

Nos logs do serviço, ao processar um job com `AI_ADAPTER=bedrock`, você verá:

```
{"level":"INFO","service":"processing-service","jobId":"<id>","message":"BedrockAIAdapter: iniciando análise. fileType=IMAGE, bytes=..."}
{"level":"INFO","service":"processing-service","jobId":"<id>","message":"Análise Bedrock concluída. componentes=5, riscos=3, recomendacoes=3"}
```

Se o adapter ainda for o stub, a mensagem será `StubAIAdapter: simulando análise`.

### Troubleshooting — Bedrock

#### `AccessDeniedException` ao invocar o modelo

Causas comuns:
- Modelo não habilitado na conta: acesse AWS Console → Amazon Bedrock → Model access e habilite `Claude 3 Sonnet`.
- IAM sem permissão `bedrock:InvokeModel`: revise a política da Task Role.
- Região errada: `BEDROCK_REGION` deve corresponder à região onde o acesso foi concedido.

#### `ResourceNotFoundException`

O `BEDROCK_MODEL_ID` está incorreto ou o modelo não existe na região configurada. Confirme o ID exato no Console AWS → Amazon Bedrock → Base models.

#### Resposta do modelo não é JSON válido

O `BedrockAIAdapter` tenta extrair o JSON da resposta mesmo quando o modelo retorna texto com markdown. Se ainda assim falhar, o job é marcado como `ERRO` e o detalhe aparece nos logs com o texto bruto recebido (nível DEBUG — ative com `LOG_LEVEL=DEBUG`).

---

## Desenvolvimento local

```bash
# Pré-requisito: infraestrutura + upload-service + report-service rodando
mvn spring-boot:run

mvn test
```

## Como rodar com Docker Compose

Este serviço eh executado via `docker compose` no repositório `fiap-infrastructure`.
Para o fluxo do `processing-service`, os seguintes serviços devem estar ativos:
- `localstack` (S3 + SQS)
- `upload-db` + `upload-service`
- `report-db` + `report-service`
- `processing-service`

### 1) Pre-requisitos

- Docker Desktop ativo
- Docker Compose v2 (`docker compose version`)
- Repositórios como pastas irmãs:

```text
Hackaton/
├── fiap-upload-service/
├── fiap-processing-service/
├── fiap-report-service/
└── fiap-infrastructure/
```

### 2) Subir stack completa para processamento

No diretório `fiap-infrastructure`:

```bash
cd ../fiap-infrastructure
docker compose up -d localstack upload-db report-db upload-service report-service processing-service
```

### 3) Verificar se tudo subiu corretamente

```bash
docker compose ps
```

Estado esperado:
- `fiap-localstack`: `healthy`
- `fiap-upload-db`: `healthy`
- `fiap-report-db`: `healthy`
- `fiap-upload-service`: `healthy`
- `fiap-report-service`: `healthy`
- `fiap-processing-service`: `running`

### 4) Teste rapido do fluxo de processamento (stub de IA)

1) Criar um job no upload-service:

```bash
echo "PNG_DATA_SIMULADO" > /tmp/diagrama.png

curl -X POST "http://localhost:8080/v1/uploads" \
  -F "file=@/tmp/diagrama.png;type=image/png" \
  -F "description=Teste de processamento"
```

2) Consultar status até sair de `RECEBIDO` para `ANALISADO`:

```bash
curl "http://localhost:8080/v1/jobs/<jobId>/status"
```

3) Após `ANALISADO`, buscar relatório:

```bash
curl "http://localhost:8082/v1/reports/<jobId>"
```

### 5) Rebuild do processing-service após alterar código

No `fiap-infrastructure`:

```bash
docker compose up -d --build processing-service
```

### 6) Logs do processamento

```bash
docker compose logs -f processing-service
```

### 7) Parar ambiente

```bash
docker compose stop processing-service report-service upload-service report-db upload-db localstack
```

Reset completo (remove volumes):

```bash
docker compose down -v --remove-orphans
```

### 8) Troubleshooting

#### Mensagens nao saem do status RECEBIDO

Verifique:
- `processing-service` esta rodando
- fila SQS foi criada no LocalStack
- variáveis `SQS_QUEUE_URL`, `UPLOAD_SERVICE_BASE_URL`, `REPORT_SERVICE_BASE_URL`

Comandos uteis:

```bash
docker compose logs -f processing-service
docker compose logs -f localstack
```

#### Erro ao atualizar status no upload-service

Sintoma comum:
- logs do processing com erro de PATCH `/v1/jobs/{id}/status`

Valide:
- `upload-service` ativo na porta interna `8080`
- variável `UPLOAD_SERVICE_BASE_URL=http://upload-service:8080`

#### Erro ao criar relatório no report-service

Sintoma comum:
- logs do processing com erro de POST `/v1/reports`

Valide:
- `report-service` ativo na porta interna `8082`
- variável `REPORT_SERVICE_BASE_URL=http://report-service:8082`

#### Confirmar que o Compose usa o Dockerfile correto

No `fiap-infrastructure/docker-compose.yml`:

```yaml
processing-service:
  build:
    context: ../fiap-processing-service
    dockerfile: Dockerfile
```

Ou seja, sim: o arquivo `fiap-processing-service/Dockerfile` eh o usado pelo Compose.

## Variáveis de ambiente

| Variável | Padrão (local) | Descrição |
|---|---|---|
| `AWS_REGION` | `us-east-1` | Região AWS |
| `AWS_ENDPOINT_OVERRIDE` | *(vazio)* | URL do LocalStack para dev local |
| `S3_BUCKET_NAME` | `fiap-diagrams` | Bucket com os arquivos |
| `SQS_QUEUE_URL` | `http://localhost:4566/...` | Fila a consumir |
| `UPLOAD_SERVICE_BASE_URL` | `http://localhost:8080` | URL do upload-service |
| `REPORT_SERVICE_BASE_URL` | `http://localhost:8082` | URL do report-service |
| `AI_ADAPTER` | `stub` | `stub` (fase 1) ou `bedrock` (fase 2) |
| `BEDROCK_MODEL_ID` | `anthropic.claude-3-sonnet-20240229-v1:0` | ID do modelo no Bedrock |
| `BEDROCK_REGION` | `us-east-1` | Região onde o acesso ao modelo foi habilitado |
