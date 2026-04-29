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
| `AI_ADAPTER` | `stub` | `stub` ou `bedrock` |
| `BEDROCK_MODEL_ID` | `anthropic.claude-3-sonnet-...` | ID do modelo no Bedrock |
