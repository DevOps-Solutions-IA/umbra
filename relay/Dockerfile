FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 UMBRA_DB=/data/umbra.sqlite3
WORKDIR /app
COPY requirements.lock ./
RUN pip install --no-cache-dir -r requirements.lock && \
    groupadd -g 10001 umbra && useradd --uid 10001 --gid 10001 --no-create-home umbra && \
    mkdir /data && chown 10001:10001 /data
COPY umbra_relay ./umbra_relay
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz',timeout=3).read()"
CMD ["uvicorn", "umbra_relay.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--no-access-log", "--no-proxy-headers", "--workers", "1", "--limit-concurrency", "50", "--timeout-keep-alive", "5"]
