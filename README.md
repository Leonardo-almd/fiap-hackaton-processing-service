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
