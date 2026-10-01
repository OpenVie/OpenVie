.DEFAULT_GOAL := help

PYTHON ?= python
VENV ?= .venv
APP_HOST ?= 0.0.0.0
APP_PORT ?= 8000
UVICORN_ARGS ?=
PYTEST_ARGS ?=
PROFILE ?= dev
ENV_FILE ?= .env
TEST_ENV_FILE ?=
ROOT_COMPOSE_FILE ?= ../docker-compose.yml

ifneq (,$(wildcard $(ENV_FILE)))
include $(ENV_FILE)
export
endif

ifeq ($(OS),Windows_NT)
SHELL := cmd.exe
MVNW ?= mvnw.cmd
ENV_RUN ?=
VENV_PYTHON := $(VENV)/Scripts/python.exe
REMOVE := powershell -NoProfile -Command Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
else
MVNW ?= sh ./mvnw
ENV_RUN ?= set -a; [ ! -f $(ENV_FILE) ] || . ./$(ENV_FILE); set +a;
VENV_PYTHON := $(VENV)/bin/python
REMOVE := rm -rf
endif

ifneq ($(strip $(TEST_ENV_FILE)),)
TEST_ENV_RUN := set -a; [ ! -f $(TEST_ENV_FILE) ] || . ./$(TEST_ENV_FILE); set +a;
else
TEST_ENV_RUN :=
endif

FLYWAY_PROPS = -Dflyway.url="$(POSTGRES_URL)" -Dflyway.user="$(POSTGRES_USER)" -Dflyway.password="$(POSTGRES_PASSWORD)" -Dflyway.locations=filesystem:src/main/resources/db/migration

.PHONY: help deps run dev run-dev run-prod test verify package clean migrate migrate-info migrate-validate migrate-repair db-reset recover-owner

help:
	$(info Available targets:)
	$(info   make deps       Download Maven dependencies for offline use)
	$(info   make run        Run Spring Boot with PROFILE, default: dev)
	$(info   make dev        Run Spring Boot with the dev profile)
	$(info   make run-dev    Run Spring Boot with the dev profile)
	$(info   make run-prod   Run Spring Boot with the prod profile)
	$(info   make test       Run tests with the test profile)
	$(info   make verify     Run the full Maven verification lifecycle)
	$(info   make package    Build the production JAR)
	$(info   make clean      Remove Maven build output)
	$(info   make migrate          Run Flyway migrations against POSTGRES_URL)
	$(info   make migrate-info     Show Flyway migration status)
	$(info   make migrate-validate Validate applied Flyway migrations)
	$(info   make migrate-repair   Repair Flyway metadata)
	$(info   make db-reset         Reset local Postgres, Qdrant, Kuzu, and SeaweedFS data, restart them, then migrate)
	$(info   make recover-owner    Offline reset of a lost organization-owner password)
	$(info Optional variables: PROFILE=dev ENV_FILE=.env MAVEN_ARGS=...)
	$(info)
	$(info The first account is created in the browser at /setup once the stack is)
	$(info running. There is no seed file and no CLI provisioning step; a fresh)
	$(info database has no users, and setup closes permanently after it is claimed.)

deps:
	$(ENV_RUN) $(MVNW) dependency:go-offline $(MAVEN_ARGS)

run:
	$(ENV_RUN) $(MVNW) -P$(PROFILE) spring-boot:run $(MAVEN_ARGS)

dev: run-dev

run-dev:
	$(ENV_RUN) $(MVNW) -Pdev spring-boot:run $(MAVEN_ARGS)

run-prod:
	$(ENV_RUN) $(MVNW) -Pprod spring-boot:run $(MAVEN_ARGS)

test:
	$(TEST_ENV_RUN) $(MVNW) -Ptest -Dspring.profiles.active=test test $(MAVEN_ARGS)

verify:
	$(TEST_ENV_RUN) $(MVNW) -Ptest -Dspring.profiles.active=test verify $(MAVEN_ARGS)

package:
	$(ENV_RUN) $(MVNW) -Pprod clean package $(MAVEN_ARGS)

clean:
	$(ENV_RUN) $(MVNW) clean $(MAVEN_ARGS)

migrate:
	$(ENV_RUN) $(MVNW) flyway:migrate $(FLYWAY_PROPS) $(MAVEN_ARGS)

migrate-info:
	$(ENV_RUN) $(MVNW) flyway:info $(FLYWAY_PROPS) $(MAVEN_ARGS)

migrate-validate:
	$(ENV_RUN) $(MVNW) flyway:validate $(FLYWAY_PROPS) $(MAVEN_ARGS)

migrate-repair:
	$(ENV_RUN) $(MVNW) flyway:repair $(FLYWAY_PROPS) $(MAVEN_ARGS)

db-reset:
	$(ENV_RUN) docker compose --env-file $(ENV_FILE) -f $(ROOT_COMPOSE_FILE) rm -s -f \
		postgres qdrant graph-service seaweedfs-s3 seaweedfs-filer seaweedfs-volume seaweedfs-master
	$(ENV_RUN) $(PYTHON) scripts/clean_docker_volumes.py $(ENV_FILE) $(ROOT_COMPOSE_FILE)
	$(ENV_RUN) docker compose --env-file $(ENV_FILE) -f $(ROOT_COMPOSE_FILE) up -d --wait --wait-timeout 120 \
		postgres qdrant graph-service seaweedfs-master seaweedfs-volume seaweedfs-filer seaweedfs-s3
	$(MAKE) migrate ENV_FILE=$(ENV_FILE)

# Offline owner recovery. Requires local database access; pass the target and
# new credential explicitly:
#   make recover-owner RECOVER_ARGS="--recover-owner-email=ada@acme.test \
#     --recover-owner-password="$(openssl rand -base64 24)""
recover-owner:
	$(ENV_RUN) $(MVNW) -P$(PROFILE) package -DskipTests $(MAVEN_ARGS)
	$(ENV_RUN) java -jar target/cacanode-api.jar --app.command.mode=recover-owner --server.port=0 --spring.task.scheduling.enabled=false $(RECOVER_ARGS)
