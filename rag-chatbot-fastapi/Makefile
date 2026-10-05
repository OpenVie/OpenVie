.DEFAULT_GOAL := help

PYTHON_MODE ?= venv

# Automatically use the active Conda environment if one is activated
ifdef CONDA_DEFAULT_ENV
ifeq ($(PYTHON_MODE),venv)
PYTHON_MODE = system
endif
# Fix for Conda on Windows: prevent httpx from crashing due to a missing SSL_CERT_FILE path
ifeq ($(OS),Windows_NT)
unexport SSL_CERT_FILE
endif
endif

PYTHON ?= python
CONDA ?= conda
CONDA_ENV ?= rag-chatbot
VENV ?= .venv
APP_HOST ?= 0.0.0.0
APP_PORT ?= 18000
GRAPH_HOST ?= 0.0.0.0
GRAPH_PORT ?= 8010
UVICORN_ARGS ?=
PYTEST_ARGS ?=
TARGET_COLLECTION ?= knowledge_units_v2
REINDEX_BATCH_SIZE ?= 50
REINDEX_ARGS ?=
EVALUATION_DATASET ?= tests/data/retrieval_vi_v1.json
EVALUATION_RESULTS ?= artifacts/full-pipeline.json
EVALUATION_LABEL ?= full-pipeline
PHASE5_HARNESS_ARGS ?=
CACHE_DISABLED_RESULTS ?= artifacts/retrieval-cache-disabled.json
CACHE_ENABLED_RESULTS ?= artifacts/retrieval-cache-enabled.json
DOCKER_COMPOSE ?= docker compose
DEV_COMPOSE_FILE ?= ../docker-compose.yml
DEV_INFRA_SERVICES ?= postgres redis rabbitmq qdrant seaweedfs-master \
	seaweedfs-volume seaweedfs-filer seaweedfs-s3 graph-service
DEV_GRAPH_URL ?= http://localhost:8010
DEV_LLM_BASE_URL ?= http://localhost:11434/v1
DEV_TEXT_EMBEDDING_BASE_URL ?= http://localhost:11434
DEV_LLM_MODEL_ID ?= vylinh
DEV_TEXT_EMBEDDING_MODEL_ID ?= bge-m3
DEV_TEXT_EMBEDDING_DIMENSION ?= 1024
DEV_QDRANT_DENSE_VECTOR_NAME ?= text_bge_m3_v1
DEV_GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS ?= 4096
DEV_QDRANT_COLLECTION ?= knowledge_units_v2
DEV_RERANKER_ENABLED ?= false
DEV_RERANKER_URL ?= http://localhost:8082
DEV_RERANKER_MODEL_ID ?= Alibaba-NLP/gte-multilingual-reranker-base
DEV_RERANKER_IMAGE ?= ghcr.io/huggingface/text-embeddings-inference:cpu-1.9
DEV_RERANKER_PORT ?= 8082
DEV_RERANKER_HOST ?= 127.0.0.1
RERANKER_BIN ?= text-embeddings-router

ifeq ($(OS),Windows_NT)
VENV_PYTHON := $(VENV)/Scripts/python.exe
REMOVE := powershell -NoProfile -Command Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
else
VENV_PYTHON := $(VENV)/bin/python
REMOVE := rm -rf
endif

ifeq ($(PYTHON_MODE),conda)
PYTHON_RUN := $(CONDA) run --no-capture-output -n $(CONDA_ENV) python
else ifeq ($(PYTHON_MODE),system)
PYTHON_RUN := $(PYTHON)
else
PYTHON_RUN := $(VENV_PYTHON)
endif

.PHONY: help env install install-dev setup run run-ai dev dev-ai run-graph dev-graph \
	dev-infra dev-down dev-reranker dev-reranker-native dev-reranker-down \
	evaluate-retrieval compare-retrieval-cache baseline-cache baseline-cache-enabled \
	cleanup-semantic-answer-cache \
	recover-ingestion-checkpoints backfill-spreadsheet-artifacts generate-proto check-proto \
	cleanup-semantic-answer-cache-dry-run test check lint typecheck clean

help:
	$(info Available targets:)
	$(info   make env         Create/verify the configured Python environment)
	$(info   make setup       Install all dependencies into the Python environment)
	$(info   make install     Install runtime dependencies into the Python environment)
	$(info   make install-dev Install runtime dependencies and pytest)
	$(info   make run                  Run the AI/chat API; alias for run-ai)
	$(info   make run-ai               Run app.main:app on APP_HOST:APP_PORT)
	$(info   make dev                  Run the local AI/chat API with reload; containers are untouched)
	$(info   make dev-ai               Run only the AI/chat API with reload)
	$(info   make run-graph            Run app.graph_main:app on GRAPH_HOST:GRAPH_PORT)
	$(info   make dev-graph            Run the graph API with reload)
	$(info   make dev-infra            Start local storage and the graph container)
	$(info   make dev-down             Stop the local infrastructure started for development)
	$(info   make dev-reranker         Start the optional TEI CPU container)
	$(info   make dev-reranker-native  Run native TEI on loopback, recommended on Apple Silicon)
	$(info   make dev-reranker-down    Stop the optional TEI container)
	$(info   make evaluate-retrieval   Score recorded retrieval rankings)
	$(info   make baseline-cache       Capture the cache-disabled local latency baseline as JSON)
	$(info   make baseline-cache-enabled Label a cache-enabled comparison run as JSON)
	$(info   make cleanup-semantic-answer-cache Delete expired semantic answer-cache points)
	$(info   make cleanup-semantic-answer-cache-dry-run Preview the bounded cleanup)
	$(info   make recover-ingestion-checkpoints Republish bounded incomplete ingestion jobs)
	$(info   make backfill-spreadsheet-artifacts Backfill derived Parquet table artifacts)
	$(info   make generate-proto       Regenerate deterministic Python protobuf outputs)
	$(info   make check-proto          Verify generated protobuf outputs are current)
	$(info   make test                 Run pytest)
	$(info   make lint                 Run Ruff checks)
	$(info   make typecheck            Run mypy)
	$(info   make check                Run lint, type checks, and tests)
	$(info   make clean                Remove caches and test artifacts)
	$(info Common variables: APP_PORT, DEV_GRAPH_URL, DEV_QDRANT_COLLECTION, DEV_RERANKER_URL, DEV_RERANKER_MODEL_ID, TARGET_COLLECTION)

env:
ifeq ($(PYTHON_MODE),conda)
	$(PYTHON_RUN) --version
else ifeq ($(PYTHON_MODE),system)
	$(PYTHON_RUN) --version
else
	@$(PYTHON) -m venv $(VENV)
	@$(PYTHON_RUN) --version 1>&2
endif

install: env
	$(PYTHON_RUN) -m pip install -e .

install-dev: install
	$(PYTHON_RUN) -m pip install -e .[dev]

setup: install-dev

run: run-ai

run-ai: env
	$(PYTHON_RUN) -m uvicorn app.main:app --host $(APP_HOST) --port $(APP_PORT) $(UVICORN_ARGS)

dev: env
	GRAPH_SERVICE_URL=$(DEV_GRAPH_URL) \
	LLM_BASE_URL=$(DEV_LLM_BASE_URL) \
	TEXT_EMBEDDING_BASE_URL=$(DEV_TEXT_EMBEDDING_BASE_URL) \
	LLM_MODEL_ID=$(DEV_LLM_MODEL_ID) \
	TEXT_EMBEDDING_MODEL_ID=$(DEV_TEXT_EMBEDDING_MODEL_ID) \
	TEXT_EMBEDDING_DIMENSION=$(DEV_TEXT_EMBEDDING_DIMENSION) \
	QDRANT_DENSE_VECTOR_NAME=$(DEV_QDRANT_DENSE_VECTOR_NAME) \
	GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS=$(DEV_GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS) \
	QDRANT_COLLECTION=$(DEV_QDRANT_COLLECTION) \
	RERANKER_ENABLED=$(DEV_RERANKER_ENABLED) \
	RERANKER_URL=$(DEV_RERANKER_URL) \
	RERANKER_MODEL_ID=$(DEV_RERANKER_MODEL_ID) \
	$(PYTHON_RUN) -m uvicorn app.main:app \
		--host $(APP_HOST) --port $(APP_PORT) --reload $(UVICORN_ARGS)

dev-ai: env
	$(PYTHON_RUN) -m uvicorn app.main:app --host $(APP_HOST) --port $(APP_PORT) --reload $(UVICORN_ARGS)

run-graph: env
	$(PYTHON_RUN) -m uvicorn app.graph_main:app --host $(GRAPH_HOST) --port $(GRAPH_PORT) $(UVICORN_ARGS)

dev-graph: env
	$(PYTHON_RUN) -m uvicorn app.graph_main:app --host $(GRAPH_HOST) --port $(GRAPH_PORT) --reload $(UVICORN_ARGS)

dev-infra:
	$(DOCKER_COMPOSE) -f $(DEV_COMPOSE_FILE) up -d --wait --build $(DEV_INFRA_SERVICES)

dev-down:
	$(DOCKER_COMPOSE) -f $(DEV_COMPOSE_FILE) stop $(DEV_INFRA_SERVICES)

dev-reranker:
	RERANKER_IMAGE=$(DEV_RERANKER_IMAGE) \
	RERANKER_MODEL_ID=$(DEV_RERANKER_MODEL_ID) \
	RERANKER_PORT=$(DEV_RERANKER_PORT) \
	$(DOCKER_COMPOSE) -f $(DEV_COMPOSE_FILE) --profile reranker up -d reranker-service

dev-reranker-native:
	$(RERANKER_BIN) --model-id $(DEV_RERANKER_MODEL_ID) \
		--hostname $(DEV_RERANKER_HOST) --port $(DEV_RERANKER_PORT)

dev-reranker-down:
	$(DOCKER_COMPOSE) -f $(DEV_COMPOSE_FILE) stop reranker-service

evaluate-retrieval: env
	$(PYTHON_RUN) -m app.maintenance.evaluate_retrieval \
		--dataset $(EVALUATION_DATASET) \
		--results $(EVALUATION_RESULTS) \
		--label $(EVALUATION_LABEL)

compare-retrieval-cache: env
	$(PYTHON_RUN) -m app.maintenance.compare_retrieval_results \
		--dataset $(EVALUATION_DATASET) \
		--cache-disabled-results $(CACHE_DISABLED_RESULTS) \
		--cache-enabled-results $(CACHE_ENABLED_RESULTS) \
		--tolerance 0.001

baseline-cache: env
	@BASELINE_CACHE_ENABLED=false $(PYTHON_RUN) -m app.maintenance.cache_baseline

baseline-cache-enabled: env
	@BASELINE_CACHE_ENABLED=true $(PYTHON_RUN) -m app.maintenance.cache_baseline

cleanup-semantic-answer-cache: env
	$(PYTHON_RUN) -m app.bootstrap.semantic_cache_cleanup --apply

cleanup-semantic-answer-cache-dry-run: env
	$(PYTHON_RUN) -m app.bootstrap.semantic_cache_cleanup

recover-ingestion-checkpoints: env
	$(PYTHON_RUN) -m app.bootstrap.recover_ingestion --limit 100

backfill-spreadsheet-artifacts: env
	$(PYTHON_RUN) -m app.bootstrap.backfill_spreadsheet_artifacts $(STORAGE_KEYS)

generate-proto: env
	$(PYTHON_RUN) -m grpc_tools.protoc -I../proto \
		--python_out=app/generated --pyi_out=app/generated --grpc_python_out=app/generated \
		../proto/cacanode_ai_v1.proto
	@sed -i.bak 's/^import cacanode_ai_v1_pb2 as/from app.generated import cacanode_ai_v1_pb2 as/' app/generated/cacanode_ai_v1_pb2_grpc.py
	@$(REMOVE) app/generated/cacanode_ai_v1_pb2_grpc.py.bak

check-proto: env
	@tmp=$$(mktemp -d); \
	$(PYTHON_RUN) -m grpc_tools.protoc -I../proto \
		--python_out=$$tmp --pyi_out=$$tmp --grpc_python_out=$$tmp \
		../proto/cacanode_ai_v1.proto; \
	sed -i.bak 's/^import cacanode_ai_v1_pb2 as/from app.generated import cacanode_ai_v1_pb2 as/' $$tmp/cacanode_ai_v1_pb2_grpc.py; \
	rm -f $$tmp/cacanode_ai_v1_pb2_grpc.py.bak; \
	diff -u app/generated/cacanode_ai_v1_pb2.py $$tmp/cacanode_ai_v1_pb2.py; \
	diff -u app/generated/cacanode_ai_v1_pb2.pyi $$tmp/cacanode_ai_v1_pb2.pyi; \
	diff -u app/generated/cacanode_ai_v1_pb2_grpc.py $$tmp/cacanode_ai_v1_pb2_grpc.py; \
	rm -rf $$tmp

test: env
	$(PYTHON_RUN) -m pytest $(PYTEST_ARGS)

check: check-proto lint typecheck test

lint: env
	$(PYTHON_RUN) -m ruff check app tests

typecheck: env
	$(PYTHON_RUN) -m mypy app

clean:
	$(REMOVE) .pytest_cache .mypy_cache htmlcov .coverage $(VENV)
