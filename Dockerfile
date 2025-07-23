# Multi-Modal Recording System - PC Controller
# Production Docker container for the PC controller application

FROM python:3.11-slim

# Set working directory
WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    pkg-config \
    libhdf5-dev \
    libssl-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user for security
RUN useradd --create-home --shell /bin/bash appuser

# Copy requirements first for better Docker layer caching
COPY pc_controller/requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY pc_controller/ ./pc_controller/
COPY INTEGRATION_GUIDE.md API_DOCUMENTATION.md ./

# Create necessary directories
RUN mkdir -p /app/data /app/logs /app/config /app/exports && \
    chown -R appuser:appuser /app

# Copy configuration files
COPY docker/config/ ./config/

# Switch to non-root user
USER appuser

# Expose ports
EXPOSE 5000 8888 8889

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:5000/api/status || exit 1

# Environment variables
ENV PYTHONPATH=/app \
    PYTHONUNBUFFERED=1 \
    LOG_LEVEL=INFO \
    DATA_DIR=/app/data \
    CONFIG_DIR=/app/config \
    EXPORT_DIR=/app/exports

# Default command
CMD ["python", "-m", "pc_controller.main"]