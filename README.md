# AskMyDocs — RAG Document Q&A Agent

A production-grade **Retrieval-Augmented Generation (RAG)** application that lets users upload documents (PDF, DOCX, TXT) and ask natural-language questions against them. Answers are grounded in the document with source citations showing the exact page and chunk each fact came from.

> **Two backend implementations are available:**
> - `main` branch — Python · FastAPI · LangChain · Celery
> - `java_version` branch — Java 21 · Spring Boot 3.3 · Spring AI 1.0

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Environment Variables](#environment-variables)
- [Running Locally](#running-locally)
  - [Python Backend (main)](#python-backend-main-branch)
  - [Java Backend (java_version)](#java-backend-java_version-branch)
  - [Frontend](#frontend)
- [API Reference](#api-reference)
- [Database Schema](#database-schema)
- [Ingestion Pipeline](#ingestion-pipeline)
- [Query Pipeline](#query-pipeline)
- [Testing](#testing)
- [Demo Script](#demo-script)
- [Common Issues](#common-issues)
- [Branch Comparison](#branch-comparison)

---

## Features

- Upload PDF, DOCX, and TXT documents
- Async background ingestion — upload returns immediately, status polled until ready
- Vector search with OpenAI `text-embedding-3-small` embeddings stored in Pinecone
- Cohere `rerank-3` re-ranking for high-precision retrieval
- Grounded answers from Claude `claude-sonnet-4-6` — streamed token-by-token via SSE
- Source citations rendered as collapsible cards (chunk text + page number + score)
- Hallucination guardrail — Claude answers only from provided context
- JWT authentication (access + refresh tokens) with per-user data isolation
- Full query history stored in PostgreSQL

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                        Browser                          │
│          React + TypeScript + Vite + Tailwind           │
└──────────────────────┬──────────────────────────────────┘
                       │ REST / SSE
┌──────────────────────▼──────────────────────────────────┐
│                    Backend API                          │
│        FastAPI (main) · Spring Boot (java_version)      │
│                                                         │
│  /auth   /documents   /query                            │
└───┬──────────┬────────────┬────────────────────────────┘
    │          │            │
    ▼          ▼            ▼
PostgreSQL   AWS S3 /    Ingestion Worker
(users,      MinIO       (Celery · @Async)
 documents,  (raw files)      │
 history)                     │ embed → upsert
                         ┌────▼────────┐
                         │  Pinecone   │
                         │ (vectors)   │
                         └────┬────────┘
                              │ query → rerank
                         ┌────▼────────┐
                         │   Cohere    │
                         │  rerank-3   │
                         └────┬────────┘
                              │ prompt → stream
                         ┌────▼────────┐
                         │   Claude    │
                         │sonnet-4-6   │
                         └─────────────┘
```

---

## Tech Stack

### Common (both branches)

| Layer | Technology |
|---|---|
| Frontend | React 18 + TypeScript + Vite + Tailwind CSS |
| Embeddings | OpenAI `text-embedding-3-small` |
| Vector Store | Pinecone (serverless, namespace per user) |
| Reranker | Cohere `rerank-3` |
| LLM | Anthropic `claude-sonnet-4-6` |
| Relational DB | PostgreSQL 15 |
| File Storage | AWS S3 (or local MinIO for dev) |
| Containers | Docker + docker-compose |

### `main` branch — Python

| Layer | Technology |
|---|---|
| Backend API | FastAPI + Pydantic v2 |
| Orchestration | LangChain (text splitters, retrievers) |
| Background Jobs | Celery + Redis |
| DB Migrations | Alembic (async) |
| Auth | python-jose (JWT) |
| Logging | structlog |
| Testing | pytest + pytest-asyncio + VCR |

### `java_version` branch — Java

| Layer | Technology |
|---|---|
| Backend API | Spring Boot 3.3 + Spring MVC |
| AI Framework | Spring AI 1.0 |
| Background Jobs | Spring `@Async` + ThreadPoolTaskExecutor |
| DB Migrations | Flyway |
| Auth | Spring Security + JJWT 0.12 |
| Logging | SLF4J + Logback |
| Testing | JUnit 5 + Mockito + MockMvc |

---

## Project Structure

```
askmydocs/
├── docker-compose.yml         # PostgreSQL, Redis, MinIO
├── .env.example               # Environment variable template
│
├── backend/                   # API + services
│   │
│   │── [main branch — Python]
│   ├── app/
│   │   ├── main.py            # FastAPI app factory
│   │   ├── config.py          # pydantic-settings
│   │   ├── database.py        # Async SQLAlchemy engine
│   │   ├── api/
│   │   │   ├── deps.py        # JWT dependency
│   │   │   ├── auth.py        # /auth routes
│   │   │   ├── documents.py   # /documents routes
│   │   │   └── query.py       # /query routes (SSE)
│   │   ├── models/            # SQLAlchemy ORM models
│   │   ├── schemas/           # Pydantic v2 request/response
│   │   ├── services/
│   │   │   ├── ingestion.py   # S3 → extract → chunk → embed → Pinecone
│   │   │   ├── retrieval.py   # embed → Pinecone → Cohere rerank
│   │   │   └── generation.py  # prompt builder → Claude stream
│   │   ├── tasks/ingest.py    # Celery task
│   │   └── utils/             # S3, Pinecone helpers
│   ├── alembic/               # DB migrations
│   ├── tests/                 # pytest test suite
│   └── requirements.txt
│
│   │── [java_version branch — Java]
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/askmydocs/
│       │   ├── AskMyDocsApplication.java
│       │   ├── config/        # Security, Async, AppConfig (beans)
│       │   ├── controller/    # AuthController, DocumentController, QueryController
│       │   ├── dto/           # Java records (request/response shapes)
│       │   ├── entity/        # JPA entities (User, Document, QueryHistory)
│       │   ├── repository/    # Spring Data JPA interfaces
│       │   ├── security/      # JwtUtil, JwtAuthFilter, UserDetailsServiceImpl
│       │   ├── service/       # AuthService, DocumentService, IngestionService,
│       │   │                  # RetrievalService, GenerationService
│       │   └── util/          # S3Util
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/  # Flyway SQL (V1–V3)
│       └── test/java/         # JUnit 5 service + controller tests
│
└── frontend/
    ├── src/
    │   ├── App.tsx            # Auth gate + sidebar + chat layout
    │   ├── api/               # Typed axios client + fetch SSE stream
    │   │   ├── client.ts      # Axios instance + JWT interceptors
    │   │   ├── auth.ts
    │   │   ├── documents.ts
    │   │   └── query.ts       # streamQuery via fetch ReadableStream
    │   ├── components/
    │   │   ├── DocumentUpload.tsx   # Drag-drop + status polling
    │   │   ├── DocumentList.tsx     # Color-coded status badges
    │   │   └── ChatInterface.tsx    # SSE streaming + source citations
    │   └── hooks/
    │       ├── useAuth.ts
    │       └── useDocuments.ts      # Exponential-backoff status polling
    ├── package.json
    └── vite.config.ts         # Dev proxy → localhost:8000
```

---

## Prerequisites

| Tool | Version |
|---|---|
| Docker + Docker Compose | v24+ |
| Node.js | v20+ |
| **Python** (main branch) | 3.11+ |
| **Java + Maven** (java_version) | Java 21 + Maven 3.9+ |

API keys required:

- [OpenAI](https://platform.openai.com) — embeddings (`text-embedding-3-small`)
- [Anthropic](https://console.anthropic.com) — LLM (`claude-sonnet-4-6`)
- [Pinecone](https://app.pinecone.io) — vector store (create a serverless index, dimension `1536`)
- [Cohere](https://dashboard.cohere.com) — reranker (`rerank-3`)
- AWS credentials — S3 bucket (or use local MinIO — no credentials needed for dev)

---

## Environment Variables

Copy `.env.example` to `.env` and fill in your values. **Never commit `.env`.**

```bash
cp .env.example .env
```

```env
# AI / ML
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
PINECONE_API_KEY=...
PINECONE_ENVIRONMENT=https://my-index-xyz.svc.aped-xxx.pinecone.io   # index host URL
COHERE_API_KEY=...

# Database
DATABASE_URL=postgresql+asyncpg://user:pass@localhost:5432/ragdb      # Python
# DB_USERNAME / DB_PASSWORD used by Spring (java_version)

# Redis (Python / Celery only)
REDIS_URL=redis://localhost:6379/0

# File storage
S3_BUCKET=rag-documents
S3_ENDPOINT_URL=http://localhost:9000    # MinIO dev; omit for real AWS
AWS_ACCESS_KEY_ID=minioadmin
AWS_SECRET_ACCESS_KEY=minioadmin
AWS_REGION=us-east-1

# Auth
JWT_SECRET=change-me-in-production-min-32-chars!!
JWT_ALGORITHM=HS256                      # Python only
ACCESS_TOKEN_EXPIRE_MINUTES=60           # Python only

# App
ENVIRONMENT=development
LOG_LEVEL=INFO
```

---

## Running Locally

### 1. Start infrastructure (both branches)

```bash
docker-compose up -d postgres redis minio
```

This starts:
- **PostgreSQL 15** on `localhost:5432`
- **Redis** on `localhost:6379`
- **MinIO** (S3-compatible) on `localhost:9000` · Console at `localhost:9001`

---

### Python Backend (`main` branch)

```bash
git checkout main
cd backend

# Install dependencies
pip install -r requirements.txt

# Run database migrations
alembic upgrade head

# Start the API server
uvicorn app.main:app --reload --port 8000

# In a separate terminal — start the Celery worker
celery -A app.tasks worker --loglevel=info
```

API available at `http://localhost:8000`
Interactive docs at `http://localhost:8000/docs`

---

### Java Backend (`java_version` branch)

```bash
git checkout java_version
cd backend

# Build and run (Flyway migrations run automatically on startup)
./mvnw spring-boot:run
```

Or build a JAR:

```bash
./mvnw clean package -DskipTests
java -jar target/askmydocs-backend-1.0.0.jar
```

API available at `http://localhost:8080`

> **Note:** The Java backend reads environment variables directly. Export them before running or use a `.env` loader:
> ```bash
> export OPENAI_API_KEY=sk-...
> export ANTHROPIC_API_KEY=sk-ant-...
> # ... etc
> ```

---

### Frontend

```bash
cd frontend
npm install
npm run dev     # http://localhost:5173
```

The Vite dev server proxies `/api` → `http://localhost:8000` (Python) or update `vite.config.ts` to port `8080` for Java.

---

## API Reference

All endpoints except `/auth/*` require `Authorization: Bearer <access_token>`.

### Auth

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/register` | Create account → returns tokens |
| `POST` | `/auth/login` | Sign in → returns tokens |
| `POST` | `/auth/refresh` | Rotate tokens (body: `{"refresh_token": "..."}`) |

### Documents

| Method | Path | Description |
|---|---|---|
| `POST` | `/documents` | Upload file (multipart) → queue ingestion → `202 Accepted` |
| `GET` | `/documents` | List all documents for authenticated user |
| `GET` | `/documents/{id}/status` | Poll status: `queued → processing → ready → error` |
| `DELETE` | `/documents/{id}` | Delete doc + Pinecone vectors + S3 file |

### Query

| Method | Path | Description |
|---|---|---|
| `POST` | `/query` | Ask a question → stream answer as SSE |
| `GET` | `/query/history` | Retrieve past Q&A for the user |

**POST /query request body:**
```json
{
  "document_id": "uuid",
  "question": "What are the termination conditions?"
}
```

**SSE stream format:**
```
data: The termination
data:  conditions are
data:  outlined in clause 12.3
data: [DONE]
```

---

## Database Schema

### `users`
```sql
id              UUID PRIMARY KEY DEFAULT gen_random_uuid()
email           VARCHAR(255) NOT NULL UNIQUE
hashed_password TEXT NOT NULL
created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
```

### `documents`
```sql
id           UUID PRIMARY KEY DEFAULT gen_random_uuid()
user_id      UUID NOT NULL REFERENCES users(id)
filename     VARCHAR(255) NOT NULL
s3_url       TEXT NOT NULL
status       VARCHAR(20) NOT NULL DEFAULT 'queued'
             -- queued | processing | ready | error
total_chunks INTEGER
created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
error_log    TEXT
```

### `query_history`
```sql
id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
user_id       UUID NOT NULL REFERENCES users(id)
document_id   UUID NOT NULL REFERENCES documents(id)
question      TEXT NOT NULL
answer        TEXT NOT NULL
source_chunks JSONB NOT NULL   -- [{chunkText, page, score}, ...]
latency_ms    INTEGER
created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
```

---

## Ingestion Pipeline

Triggered by `POST /documents`. The API uploads the file to S3, creates a DB record (`status=queued`), and returns immediately. A background worker runs the following:

```
1. Set status = "processing"  ← before any I/O (prevents duplicate jobs on crash)
2. Download raw file from S3
3. Extract text:
   - PDF  → PyMuPDF (Python) / Spring AI PagePdfDocumentReader (Java) — preserves page numbers
   - DOCX → python-docx (Python) / Spring AI TikaDocumentReader (Java)
   - TXT  → plain read
4. Chunk with RecursiveCharacterTextSplitter
   chunk_size=500, chunk_overlap=50
   Metadata per chunk: {doc_id, chunk_index, page_number, char_start, char_end, user_id}
5. Embed in batches of 100 → OpenAI text-embedding-3-small (1536 dimensions)
6. Upsert to Pinecone
   namespace = "user_{user_id}"
   vector_id = "{doc_id}_{chunk_index}"
7. Set status = "ready", total_chunks = N
```

On any exception: `status = "error"`, full traceback written to `error_log`.

---

## Query Pipeline

Runs on `POST /query`. Target end-to-end latency < 3 s.

```
1. Query rewrite (if question ≤ 20 words)
   → Claude: "Rewrite this question to be specific and self-contained..."
   max_tokens=150

2. Embed rewritten query → text-embedding-3-small

3. Pinecone vector search
   top_k=20, namespace="user_{user_id}", filter={"doc_id": id}

4. Cohere rerank-3 on 20 candidates → keep top 5
   Fallback: top-5 by raw cosine score if Cohere unavailable

5. Build prompt:
   System: Answer ONLY from context. Cite page numbers. Hallucination guardrail.
   Context: [CHUNK 1 — page 3] ... [CHUNK 2 — page 7] ...
   Question: {rewritten_question}
   (Trim lowest-ranked chunks if context > 6000 tokens)

6. Stream Claude response (claude-sonnet-4-6, temp=0, max_tokens=1024)
   → sent to client as Server-Sent Events

7. Persist question, answer, source_chunks, latency_ms to query_history
```

---

## Testing

### Python (`main` branch)

```bash
cd backend
pytest tests/ -v
```

| File | What it tests |
|---|---|
| `test_ingestion.py` | Chunking metadata, embedding batch logic, status transitions |
| `test_retrieval.py` | Reranker fallback, 6000-token prompt guard, query rewrite skip |
| `test_api_documents.py` | Upload, status polling, delete — S3/Pinecone/Celery mocked |
| `test_api_query.py` | Full Q&A round trip via SSE, history endpoint |

### Java (`java_version` branch)

```bash
cd backend
./mvnw test
```

| File | What it tests |
|---|---|
| `IngestionServiceTest` | Processing status ordering, error handling, success path |
| `RetrievalServiceTest` | Cohere fallback, rewrite skip for long questions, empty results |
| `DocumentControllerTest` | Upload/list/status/delete via MockMvc |
| `QueryControllerTest` | SSE streaming, 404 for non-ready docs, history |

---

## Demo Script

1. Register an account and sign in
2. Upload `sample_contract.pdf` from the `demo/` folder
3. Wait for status badge to turn **green** (ready) — ~8 s for a 10-page doc
4. Ask: **"What are the termination conditions?"**
   → Answer cites page 4, clause 12.3
5. Ask: **"What is the liability cap?"**
   → Answer cites page 6, clause 15
6. Ask: **"Who is the CEO of the company?"**
   → `"I couldn't find that in the document."` — hallucination guardrail working

---

## Common Issues

| Symptom | Likely cause | Fix |
|---|---|---|
| `status` stuck at `processing` | Worker not running | Python: `celery -A app.tasks worker` · Java: `@Async` runs in-process |
| Empty retrieval results | Wrong Pinecone namespace | Verify `user_id` in filter matches upsert namespace |
| Answer ignores context | Prompt truncation | Check logs for `context_truncated`; lowest-ranked chunk trimmed |
| Slow first query | Cold Cohere rerank | Pre-warm call fires on startup automatically |
| Duplicate chunks after re-upload | Same doc re-uploaded | Vector IDs include `doc_id` — same doc re-upload overwrites cleanly |
| `401 Unauthorized` | Expired access token | Use `/auth/refresh` with refresh token to rotate |
| MinIO upload fails | Bucket not created | Create `rag-documents` bucket via MinIO console at `localhost:9001` |

---

## Branch Comparison

| | `main` (Python) | `java_version` (Java) |
|---|---|---|
| Language | Python 3.11 | Java 21 |
| Framework | FastAPI | Spring Boot 3.3 |
| AI SDK | LangChain + openai + anthropic | Spring AI 1.0 |
| Async jobs | Celery + Redis | Spring `@Async` thread pool |
| DB migrations | Alembic | Flyway |
| Auth | python-jose | Spring Security + JJWT |
| Startup | `uvicorn` + `celery worker` | Single JAR (`spring-boot:run`) |
| Tests | pytest + pytest-asyncio | JUnit 5 + Mockito + MockMvc |

Both branches share the same **frontend**, **database schema**, **API contract**, and **infrastructure** (docker-compose).

---

## License

MIT
