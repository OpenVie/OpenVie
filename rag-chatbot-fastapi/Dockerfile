FROM python:3.11-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
# Tesseract carries the OCR for scanned PDFs and images (Apache-2.0); the vie
# and eng language packs cover the ingestion defaults (INGESTION_OCR_LANGUAGES).
# ffmpeg carries the optional ASR stage: it decodes media containers for
# transcription (video demux, openai-compat payloads) and extracts keyframes.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-eng tesseract-ocr-vie ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY pyproject.toml ./
COPY app app
RUN python -m pip install --no-cache-dir --upgrade pip && python -m pip install --no-cache-dir .[asr]
EXPOSE 8000 8010
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
