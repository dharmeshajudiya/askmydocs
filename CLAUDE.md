# CLAUDE.md — RAG Document Q&A Agent

This file gives Claude Code the full context needed to work on this project autonomously.
Read this before touching any file.

---

## Project overview

A production-grade Retrieval-Augmented Generation (RAG) agent that lets users upload documents
(PDF, DOCX, TXT) and ask natural-language questions against them. The system returns grounded
answers with source citations pinpointing the exact chunk and page each fact came from.

Built as a freelancer portfolio project. Prioritise clean architecture, readable code, and
demo-ability over premature optimisation.

---

## Tech stack

| Layer | Technology | Notes |
|---|---|---|
| Frontend | React + TypeScript + Vite | Tailwind CSS for styling |
| Backend API | FastAPI (Python 3.11+) | Async, Pydantic v2 models |
| Orchestration | LangChain | Chains, text splitters, retrievers |
| Background jobs | Celery + Redis | Async ingestion pipeline |
| Embeddings | OpenAI `text-embedding-3-small` | Same model for docs and queries |
| Vector store | Pinecone (serverless) | Namespace per user |
| Reranker | Cohere `rerank-3` | Applied after top-20 retrieval |
| LLM | Claude `claude-sonnet-4-6` | Grounded answering |
| Relational DB | PostgreSQL 15 | Via SQLAlchemy async + Alembic |
| Auth | JWT (python-jose) | Access + refresh tokens |
| File storage | AWS S3 (or local MinIO for dev) | Raw document files |
| Containerisation | Docker + docker-compose | Dev parity with prod |

---

## Repository structure

```
rag-agent/
├── CLAUDE.md                  ← you are here
├── docker-compose.yml
├── .env.example
│
├── backend/
│   ├── app/
│   │   ├── main.py            ← FastAPI app factory
│   │   ├── config.py          ← Settings via pydantic-settings
│   │   ├── database.py        ← Async SQLAlchemy engine + session
│   │   │
│   │   ├── api/
│   │   │   ├── deps.py        ← Shared FastAPI dependencies (auth, db)
│   │   │   ├── documents.py   ← /documents routes
│   │   │   └── query.py       ← /query routes
│   │   │
│   │   ├── models/
│   │   │   ├── document.py    ← SQLAlchemy Document ORM model
│   │   │   └── query_history.py
│   │   │
│   │   ├── schemas/
│   │   │   ├── document.py    ← Pydantic request/response schemas
│   │   │   └── query.py
│   │   │
│   │   ├── services/
│   │   │   ├── ingestion.py   ← Text extraction, chunking, embedding, upsert
│   │   │   ├── retrieval.py   ← Embed query → vector search → rerank
│   │   │   └── generation.py  ← Prompt builder + Claude call
│   │   │
│   │   ├── tasks/
│   │   │   └── ingest.py      ← Celery task wrapping ingestion service
│   │   │
│   │   └── utils/
│   │       ├── s3.py
│   │       └── pinecone.py
│   │
│   ├── alembic/               ← DB migrations
│   ├── tests/
│   └── requirements.txt
│
└── frontend/
    ├── src/
    │   ├── components/
    │   │   ├── DocumentUpload.tsx
    │   │   ├── DocumentList.tsx
    │   │   └── ChatInterface.tsx
    │   ├── api/               ← Typed API client (axios)
    │   ├── hooks/
    │   └── App.tsx
    └── package.json
```

---

## Environment variables

Copy `.env.example` to `.env`. Never commit `.env`.

```
# API keys
OPENAI_API_KEY=
ANTHROPIC_API_KEY=
PINECONE_API_KEY=
PINECONE_ENVIRONMENT=
COHERE_API_KEY=

# Postgres
DATABASE_URL=postgresql+asyncpg://user:pass@localhost:5432/ragdb

# Redis
REDIS_URL=redis://localhost:6379/0

# S3 / MinIO
S3_BUCKET=rag-documents
S3_ENDPOINT_URL=http://localhost:9000   # omit for real AWS
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
AWS_REGION=us-east-1

# Auth
JWT_SECRET=change-me-in-production
JWT_ALGORITHM=HS256
ACCESS_TOKEN_EXPIRE_MINUTES=60

# App
ENVIRONMENT=development   # development | production
LOG_LEVEL=INFO
```

---

## API surface

### Documents

| Method | Path | Description |
|---|---|---|
| `POST` | `/documents` | Upload file → queue ingestion → return `doc_id` |
| `GET` | `/documents` | List all docs for authenticated user |
| `GET` | `/documents/{id}/status` | Poll status: `queued` → `processing` → `ready` → `error` |
| `DELETE` | `/documents/{id}` | Delete doc + Pinecone vectors + S3 file |

### Query

| Method | Path | Description |
|---|---|---|
| `POST` | `/query` | Ask question → return answer + source citations |
| `GET` | `/query/history` | Retrieve past Q&A for the user |

### Auth

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/register` | Create account |
| `POST` | `/auth/login` | Return access + refresh tokens |
| `POST` | `/auth/refresh` | Rotate tokens |

All protected routes require `Authorization: Bearer <token>`.

---

## Database schema

### `documents` table

```sql
id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
user_id       UUID NOT NULL REFERENCES users(id)
filename      VARCHAR(255) NOT NULL
s3_url        TEXT NOT NULL
status        VARCHAR(20) NOT NULL DEFAULT 'queued'
              -- queued | processing | ready | error
total_chunks  INTEGER
created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
error_log     TEXT
```

### `query_history` table

```sql
id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
user_id       UUID NOT NULL REFERENCES users(id)
document_id   UUID NOT NULL REFERENCES documents(id)
question      TEXT NOT NULL
answer        TEXT NOT NULL
source_chunks JSONB NOT NULL    -- array of {chunk_text, page, score}
latency_ms    INTEGER
created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
```

---

## Ingestion pipeline

Triggered by `POST /documents`. The API saves the file to S3, creates a DB record with
`status=queued`, enqueues a Celery task, and returns immediately.

The Celery worker (`tasks/ingest.py`) runs:

1. **Download** raw file from S3
2. **Extract text** — use `PyMuPDF` for PDF, `python-docx` for DOCX, plain `open()` for TXT.
   Preserve page numbers in metadata.
3. **Chunk** — `RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)`.
   Each chunk carries metadata: `{doc_id, chunk_index, page_number, char_start, char_end, user_id}`
4. **Embed** — batch call to `text-embedding-3-small` (max 2048 tokens/call, batch 100 chunks)
5. **Upsert** to Pinecone — namespace = `user_{user_id}`, vector id = `{doc_id}_{chunk_index}`
6. Update DB record: `status=ready`, `total_chunks=N`

On any exception: set `status=error`, write traceback to `error_log`.

---

## Query pipeline

Runs synchronously on `POST /query`. Target latency < 3 s end-to-end.

1. **Query rewrite** — single Claude call with prompt:
   `"Rewrite this question to be specific and self-contained for document search: {question}"`
   Skip if question is already > 20 words.

2. **Embed** rewritten query with same `text-embedding-3-small` model.

3. **Vector search** — Pinecone `query(vector, top_k=20, namespace=user_{user_id}, filter={doc_id})`
   The `doc_id` filter scopes results to the selected document.

4. **Rerank** — Cohere `rerank-3` on the 20 candidates, keep top 5.

5. **Build prompt**:
   ```
   System: You are a document assistant. Answer ONLY from the provided context.
           If the answer is not in the context, say "I couldn't find that in the document."
           Always cite the page number for each fact.

   Context:
   [CHUNK 1 — page 3]
   <chunk text>

   [CHUNK 2 — page 7]
   <chunk text>
   ...

   Question: {rewritten_question}
   ```

6. **Call Claude** (`claude-sonnet-4-6`, `max_tokens=1024`, `temperature=0`).
   Stream the response back to the client via Server-Sent Events.

7. **Persist** question, answer, source chunks, and latency to `query_history`.

---

## Key implementation rules

### General
- All backend code is async (`async def`, `await`). Never use blocking I/O in route handlers.
- Use Pydantic v2 models for all request/response shapes. No raw dicts crossing API boundaries.
- All DB access goes through the session dependency in `api/deps.py`. Never import the engine directly.
- Use `structlog` for logging. Include `doc_id` and `user_id` in every log line that touches a document.

### Ingestion
- Always process embeddings in batches of 100. Single-chunk calls will hit rate limits on large docs.
- Store `char_start` and `char_end` offsets in chunk metadata — needed for future highlight features.
- Set `status=processing` before starting extraction, not after. This prevents duplicate jobs if the
  worker crashes and restarts.

### Retrieval
- The reranker is not optional. Raw cosine similarity gives poor precision on short factual questions.
  Budget ~200 ms for the rerank call; it's worth it.
- Filter by `doc_id` in the Pinecone query. Never let results bleed across documents.
- If Cohere is unavailable, fall back to top-5 by raw score — do not error out.

### LLM calls
- Always set `temperature=0` for the answering call. Determinism matters for document Q&A.
- Never send more than 6000 tokens of context to Claude. If top-5 chunks exceed this, trim the
  lowest-ranked chunk first.
- The query-rewrite call uses `max_tokens=150`. It should never need more.

### Auth
- Every route except `/auth/*` requires a valid JWT. Enforce in `api/deps.py` via a `get_current_user`
  dependency.
- Scope all DB queries and Pinecone operations to `current_user.id`. Never let a user touch
  another user's documents.

### Frontend
- The upload component polls `GET /documents/{id}/status` every 2 s until `ready` or `error`.
  Use exponential backoff after 10 polls.
- Stream the Claude answer using the EventSource API. Show a typing indicator while streaming.
- Render source citations as collapsible cards below each answer — show chunk text and page number.

---

## Running locally

```bash
# 1. Start infrastructure
docker-compose up -d postgres redis minio

# 2. Backend
cd backend
pip install -r requirements.txt
alembic upgrade head
uvicorn app.main:app --reload --port 8000

# 3. Celery worker (separate terminal)
cd backend
celery -A app.tasks worker --loglevel=info

# 4. Frontend
cd frontend
npm install
npm run dev   # starts on http://localhost:5173
```

---

## Testing

```bash
# Backend unit + integration tests
cd backend
pytest tests/ -v

# Key test files
tests/test_ingestion.py     # chunking, embedding batch logic
tests/test_retrieval.py     # reranker fallback, prompt length guard
tests/test_api_documents.py # upload, status polling, delete
tests/test_api_query.py     # full Q&A round trip (uses VCR cassettes)
```

Use `pytest-asyncio` for async tests. Mock OpenAI, Cohere, and Pinecone calls — never hit live
APIs in tests.

---

## Demo script (for portfolio / client presentations)

1. Upload `sample_contract.pdf` (included in `demo/` folder)
2. Wait for status to reach `ready` (~8 s for a 10-page doc)
3. Ask: *"What are the termination conditions?"*
   → Answer cites page 4, clause 12.3
4. Ask: *"What is the liability cap?"*
   → Answer cites page 6, clause 15
5. Ask: *"Who is the CEO of the company?"*
   → Answer: "I couldn't find that in the document." — demonstrates hallucination guardrail

---

## Common failure modes and fixes

| Symptom | Likely cause | Fix |
|---|---|---|
| `status` stuck at `processing` | Celery worker not running | `celery -A app.tasks worker` |
| Empty retrieval results | Wrong Pinecone namespace | Check `user_id` in filter matches upsert namespace |
| Answer ignores context | Prompt truncation | Log token count; trim lowest-ranked chunk |
| Slow first query | Cold Cohere rerank | Pre-warm with a dummy call on startup |
| Duplicate chunks after re-upload | Pinecone upsert not idempotent by default | Vector IDs include `doc_id` — same doc re-upload overwrites cleanly |
