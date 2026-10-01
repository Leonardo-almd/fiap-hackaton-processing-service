[🇧🇷 Português](#-fiap-hackaton-processing-service) | [🇦🇺 English](#-fiap-hackaton-processing-service-1)

---

# 🇧🇷 fiap-hackaton-processing-service

Microsserviço responsável por:
- Consumir mensagens da fila AWS SQS
- Orquestrar o pipeline de análise de IA
- Atualizar o status do job via HTTP no upload-service
- Persistir o relatório gerado via HTTP no report-service

**Responsável**: Pessoa 2

## Estratégia de IA

O serviço usa o padrão **Strategy** para a integração com IA via a interface `AIAnalysisPort`:

- **Fase 1 (SOAT)**: `StubAIAdapter` — retorna dados mockados com a estrutura real do relatório. Ativado com `AI_ADAPTER=stub`.
- **Fase 2 (IADT)**: `BedrockAIAdapter` — integração com Amazon Bedrock (Claude Sonnet 4.5). Ativado com `AI_ADAPTER=bedrock`.

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
2. **Modelo liberado**: no Console AWS → Amazon Bedrock → Model access → solicitar acesso ao modelo `Claude Sonnet 4.5` (`anthropic.claude-sonnet-4-5-20250929-v1:0`). Para chamadas cross-region, o `BEDROCK_MODEL_ID` usa prefixo (ex: `global.`).
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
      "Resource": [
        "arn:aws:bedrock:us-east-1:<account-id>:inference-profile/global.anthropic.claude-sonnet-4-5-20250929-v1:0",
        "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
        "arn:aws:bedrock:::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"
      ]
    }
  ]
}
```

> Em ECS Fargate, essa política é anexada à **Task Role** (não à Task Execution Role). O SDK usa `DefaultCredentialsProvider`, que funciona automaticamente com Task Role no ECS.

### Variáveis de ambiente necessárias

| Variável | Valor para Bedrock | Descrição |
|---|---|---|
| `AI_ADAPTER` | `bedrock` | Ativa o `BedrockAIAdapter` no lugar do stub |
| `BEDROCK_MODEL_ID` | `global.anthropic.claude-sonnet-4-5-20250929-v1:0` | ID completo do modelo no Bedrock |
| `BEDROCK_MODEL_ID_BASE` | `anthropic.claude-sonnet-4-5-20250929-v1:0` | ID base do modelo (sem prefixo) |
| `BEDROCK_MODEL_ID_PREFIX` | `global` | Prefixo cross-region do modelo (ex: global, us) |
| `BEDROCK_REGION` | `us-east-1` | Região onde o modelo está habilitado |

### Rodando localmente com Bedrock real

```bash
export AI_ADAPTER=bedrock
export BEDROCK_REGION=us-east-1
export BEDROCK_MODEL_ID=global.anthropic.claude-sonnet-4-5-20250929-v1:0
export BEDROCK_MODEL_ID_BASE=anthropic.claude-sonnet-4-5-20250929-v1:0
export BEDROCK_MODEL_ID_PREFIX=global

# Credenciais AWS (perfil local ou variáveis de ambiente)
export AWS_ACCESS_KEY_ID=<sua_access_key>
export AWS_SECRET_ACCESS_KEY=<sua_secret_key>
# Se usar perfil nomeado:
# export AWS_PROFILE=seu-perfil

mvn spring-boot:run
```

> O `AWS_ENDPOINT_OVERRIDE` **não deve** ser definido ao usar Bedrock real — o SDK usa os endpoints públicos da AWS automaticamente.

### Rodando com Docker Compose + Bedrock real

No `docker-compose.override.yml` (crie na raiz do `fiap-hackaton-infrastructure` se não existir):

```yaml
services:
  processing-service:
    environment:
      AI_ADAPTER: bedrock
      BEDROCK_REGION: us-east-1
      BEDROCK_MODEL_ID: global.anthropic.claude-sonnet-4-5-20250929-v1:0
      BEDROCK_MODEL_ID_BASE: anthropic.claude-sonnet-4-5-20250929-v1:0
      BEDROCK_MODEL_ID_PREFIX: global
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
- Modelo não habilitado na conta: acesse AWS Console → Amazon Bedrock → Model access e habilite `Claude Sonnet 4.5`.
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

Este serviço eh executado via `docker compose` no repositório `fiap-hackaton-infrastructure`.
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
├── fiap-hackaton-upload-service/
├── fiap-hackaton-processing-service/
├── fiap-hackaton-report-service/
└── fiap-hackaton-infrastructure/
```

### 2) Subir stack completa para processamento

No diretório `fiap-hackaton-infrastructure`:

```bash
cd ../fiap-hackaton-infrastructure
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

No `fiap-hackaton-infrastructure`:

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

No `fiap-hackaton-infrastructure/docker-compose.yml`:

```yaml
processing-service:
  build:
    context: ../fiap-hackaton-processing-service
    dockerfile: Dockerfile
```

Ou seja, sim: o arquivo `fiap-hackaton-processing-service/Dockerfile` eh o usado pelo Compose.

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
| `BEDROCK_MODEL_ID` | `global.anthropic.claude-sonnet-4-5-20250929-v1:0` | ID completo do modelo no Bedrock |
| `BEDROCK_MODEL_ID_BASE` | `anthropic.claude-sonnet-4-5-20250929-v1:0` | ID base do modelo (sem prefixo) |
| `BEDROCK_MODEL_ID_PREFIX` | `global` | Prefixo cross-region do modelo (ex: global, us) |
| `BEDROCK_REGION` | `us-east-1` | Região onde o acesso ao modelo foi habilitado |

---

[⬆️ Back to top / Voltar ao topo](#-fiap-hackaton-processing-service)

---

# 🇦🇺 fiap-hackaton-processing-service

Microservice responsible for:
- Consuming messages from the AWS SQS queue
- Orchestrating the AI analysis pipeline
- Updating the job status via HTTP on upload-service
- Persisting the generated report via HTTP on report-service

**Owner**: Person 2

## AI Strategy

The service uses the **Strategy** pattern for AI integration via the `AIAnalysisPort` interface:

- **Phase 1 (SOAT)**: `StubAIAdapter` — returns mocked data with the real report structure. Enabled with `AI_ADAPTER=stub`.
- **Phase 2 (IADT)**: `BedrockAIAdapter` — integration with Amazon Bedrock (Claude Sonnet 4.5). Enabled with `AI_ADAPTER=bedrock`.

## Structure (Hexagonal Architecture)

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
│           └── bedrock/ # BedrockAIAdapter (Phase 2)
└── config/             # AwsConfig, AIAdapterConfig, HttpClientConfig
```

## Enabling Real AI Integration (Amazon Bedrock)

### Prerequisites

1. **AWS account with Amazon Bedrock access enabled** in the chosen region (`us-east-1` by default).
2. **Model access granted**: in AWS Console → Amazon Bedrock → Model access → request access to the `Claude Sonnet 4.5` model (`anthropic.claude-sonnet-4-5-20250929-v1:0`). For cross-region calls, `BEDROCK_MODEL_ID` uses a prefix (e.g. `global.`).
3. **AWS credentials** with `bedrock:InvokeModel` permission configured in the environment (see the IAM section below).

### Required IAM Permissions

The task/role running the service needs at least the following policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "InvokeBedrockModel",
      "Effect": "Allow",
      "Action": "bedrock:InvokeModel",
      "Resource": [
        "arn:aws:bedrock:us-east-1:<account-id>:inference-profile/global.anthropic.claude-sonnet-4-5-20250929-v1:0",
        "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0",
        "arn:aws:bedrock:::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"
      ]
    }
  ]
}
```

> On ECS Fargate, this policy is attached to the **Task Role** (not the Task Execution Role). The SDK uses `DefaultCredentialsProvider`, which works automatically with the Task Role on ECS.

### Required Environment Variables

| Variable | Value for Bedrock | Description |
|---|---|---|
| `AI_ADAPTER` | `bedrock` | Enables `BedrockAIAdapter` instead of the stub |
| `BEDROCK_MODEL_ID` | `global.anthropic.claude-sonnet-4-5-20250929-v1:0` | Full Bedrock model ID |
| `BEDROCK_MODEL_ID_BASE` | `anthropic.claude-sonnet-4-5-20250929-v1:0` | Base model ID (without prefix) |
| `BEDROCK_MODEL_ID_PREFIX` | `global` | Model's cross-region prefix (e.g. global, us) |
| `BEDROCK_REGION` | `us-east-1` | Region where the model is enabled |

### Running Locally with Real Bedrock

```bash
export AI_ADAPTER=bedrock
export BEDROCK_REGION=us-east-1
export BEDROCK_MODEL_ID=global.anthropic.claude-sonnet-4-5-20250929-v1:0
export BEDROCK_MODEL_ID_BASE=anthropic.claude-sonnet-4-5-20250929-v1:0
export BEDROCK_MODEL_ID_PREFIX=global

# AWS credentials (local profile or environment variables)
export AWS_ACCESS_KEY_ID=<your_access_key>
export AWS_SECRET_ACCESS_KEY=<your_secret_key>
# If using a named profile:
# export AWS_PROFILE=your-profile

mvn spring-boot:run
```

> `AWS_ENDPOINT_OVERRIDE` **must not** be set when using real Bedrock — the SDK automatically uses AWS's public endpoints.

### Running with Docker Compose + Real Bedrock

In `docker-compose.override.yml` (create it at the root of `fiap-hackaton-infrastructure` if it doesn't exist):

```yaml
services:
  processing-service:
    environment:
      AI_ADAPTER: bedrock
      BEDROCK_REGION: us-east-1
      BEDROCK_MODEL_ID: global.anthropic.claude-sonnet-4-5-20250929-v1:0
      BEDROCK_MODEL_ID_BASE: anthropic.claude-sonnet-4-5-20250929-v1:0
      BEDROCK_MODEL_ID_PREFIX: global
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
```

Start only `processing-service` with the new configuration:

```bash
docker compose up -d --build processing-service
```

### Confirming Bedrock Is Being Called

In the service logs, when processing a job with `AI_ADAPTER=bedrock`, you'll see:

```
{"level":"INFO","service":"processing-service","jobId":"<id>","message":"BedrockAIAdapter: iniciando análise. fileType=IMAGE, bytes=..."}
{"level":"INFO","service":"processing-service","jobId":"<id>","message":"Análise Bedrock concluída. componentes=5, riscos=3, recomendacoes=3"}
```

If the adapter is still the stub, the message will be `StubAIAdapter: simulando análise`.

### Troubleshooting — Bedrock

#### `AccessDeniedException` when invoking the model

Common causes:
- Model not enabled on the account: go to AWS Console → Amazon Bedrock → Model access and enable `Claude Sonnet 4.5`.
- IAM missing `bedrock:InvokeModel` permission: review the Task Role policy.
- Wrong region: `BEDROCK_REGION` must match the region where access was granted.

#### `ResourceNotFoundException`

`BEDROCK_MODEL_ID` is incorrect or the model doesn't exist in the configured region. Confirm the exact ID in AWS Console → Amazon Bedrock → Base models.

#### Model response is not valid JSON

`BedrockAIAdapter` attempts to extract JSON from the response even when the model returns text with markdown. If it still fails, the job is marked as `ERRO` and the details appear in the logs with the raw text received (DEBUG level — enable it with `LOG_LEVEL=DEBUG`).

---

## Local Development

```bash
# Prerequisite: infrastructure + upload-service + report-service running
mvn spring-boot:run

mvn test
```

## Running with Docker Compose

This service runs via `docker compose` in the `fiap-hackaton-infrastructure` repository.
For the `processing-service` flow, the following services must be running:
- `localstack` (S3 + SQS)
- `upload-db` + `upload-service`
- `report-db` + `report-service`
- `processing-service`

### 1) Prerequisites

- Docker Desktop running
- Docker Compose v2 (`docker compose version`)
- Repositories as sibling folders:

```text
Hackaton/
├── fiap-hackaton-upload-service/
├── fiap-hackaton-processing-service/
├── fiap-hackaton-report-service/
└── fiap-hackaton-infrastructure/
```

### 2) Starting the full processing stack

From the `fiap-hackaton-infrastructure` directory:

```bash
cd ../fiap-hackaton-infrastructure
docker compose up -d localstack upload-db report-db upload-service report-service processing-service
```

### 3) Verify everything started correctly

```bash
docker compose ps
```

Expected state:
- `fiap-localstack`: `healthy`
- `fiap-upload-db`: `healthy`
- `fiap-report-db`: `healthy`
- `fiap-upload-service`: `healthy`
- `fiap-report-service`: `healthy`
- `fiap-processing-service`: `running`

### 4) Quick test of the processing flow (AI stub)

1) Create a job on upload-service:

```bash
echo "SIMULATED_PNG_DATA" > /tmp/diagrama.png

curl -X POST "http://localhost:8080/v1/uploads" \
  -F "file=@/tmp/diagrama.png;type=image/png" \
  -F "description=Processing test"
```

2) Check status until it moves from `RECEBIDO` to `ANALISADO`:

```bash
curl "http://localhost:8080/v1/jobs/<jobId>/status"
```

3) Once `ANALISADO`, fetch the report:

```bash
curl "http://localhost:8082/v1/reports/<jobId>"
```

### 5) Rebuilding processing-service after a code change

In `fiap-hackaton-infrastructure`:

```bash
docker compose up -d --build processing-service
```

### 6) Processing logs

```bash
docker compose logs -f processing-service
```

### 7) Stopping the environment

```bash
docker compose stop processing-service report-service upload-service report-db upload-db localstack
```

Full reset (removes volumes):

```bash
docker compose down -v --remove-orphans
```

### 8) Troubleshooting

#### Messages stuck in RECEBIDO status

Check:
- `processing-service` is running
- the SQS queue was created in LocalStack
- the `SQS_QUEUE_URL`, `UPLOAD_SERVICE_BASE_URL`, `REPORT_SERVICE_BASE_URL` variables

Useful commands:

```bash
docker compose logs -f processing-service
docker compose logs -f localstack
```

#### Error updating status on upload-service

Common symptom:
- processing logs show a PATCH `/v1/jobs/{id}/status` error

Check:
- `upload-service` is active on internal port `8080`
- the `UPLOAD_SERVICE_BASE_URL=http://upload-service:8080` variable

#### Error creating a report on report-service

Common symptom:
- processing logs show a POST `/v1/reports` error

Check:
- `report-service` is active on internal port `8082`
- the `REPORT_SERVICE_BASE_URL=http://report-service:8082` variable

#### Confirming Compose uses the correct Dockerfile

In `fiap-hackaton-infrastructure/docker-compose.yml`:

```yaml
processing-service:
  build:
    context: ../fiap-hackaton-processing-service
    dockerfile: Dockerfile
```

So yes: the `fiap-hackaton-processing-service/Dockerfile` file is the one used by Compose.

## Environment Variables

| Variable | Default (local) | Description |
|---|---|---|
| `AWS_REGION` | `us-east-1` | AWS region |
| `AWS_ENDPOINT_OVERRIDE` | *(empty)* | LocalStack URL for local dev |
| `S3_BUCKET_NAME` | `fiap-diagrams` | Bucket holding the files |
| `SQS_QUEUE_URL` | `http://localhost:4566/...` | Queue to consume |
| `UPLOAD_SERVICE_BASE_URL` | `http://localhost:8080` | upload-service URL |
| `REPORT_SERVICE_BASE_URL` | `http://localhost:8082` | report-service URL |
| `AI_ADAPTER` | `stub` | `stub` (phase 1) or `bedrock` (phase 2) |
| `BEDROCK_MODEL_ID` | `global.anthropic.claude-sonnet-4-5-20250929-v1:0` | Full Bedrock model ID |
| `BEDROCK_MODEL_ID_BASE` | `anthropic.claude-sonnet-4-5-20250929-v1:0` | Base model ID (without prefix) |
| `BEDROCK_MODEL_ID_PREFIX` | `global` | Model's cross-region prefix (e.g. global, us) |
| `BEDROCK_REGION` | `us-east-1` | Region where model access was granted |

---

[⬆️ Back to top / Voltar ao topo](#-fiap-hackaton-processing-service)
