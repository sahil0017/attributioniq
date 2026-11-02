FROM python:3.12-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
COPY requirements.txt ./
RUN pip install -r requirements.txt
COPY . .
RUN useradd --system --uid 10001 appuser && chown -R appuser /app
USER appuser

# ATTRIBUTION_API_URL must point at the backend (see README); PORT is set by Railway.
EXPOSE 8501
CMD ["sh", "-c", "exec streamlit run streamlit_app.py --server.address=0.0.0.0 --server.port=${PORT:-8501} --server.headless=true --browser.gatherUsageStats=false"]
