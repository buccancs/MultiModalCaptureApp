#!/bin/bash
# Multi-Modal Recording System - Automated Deployment Script
# Production deployment automation for the complete system

set -euo pipefail

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="multimodal-recording"
ENVIRONMENT="${ENVIRONMENT:-production}"
LOG_FILE="${SCRIPT_DIR}/logs/deploy-$(date +%Y%m%d-%H%M%S).log"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging function
log() {
    local level=$1
    shift
    local message="$*"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    case $level in
        INFO)  echo -e "${GREEN}[INFO]${NC} $message" | tee -a "$LOG_FILE" ;;
        WARN)  echo -e "${YELLOW}[WARN]${NC} $message" | tee -a "$LOG_FILE" ;;
        ERROR) echo -e "${RED}[ERROR]${NC} $message" | tee -a "$LOG_FILE" ;;
        DEBUG) echo -e "${BLUE}[DEBUG]${NC} $message" | tee -a "$LOG_FILE" ;;
    esac
}

# Error handling
error_exit() {
    log ERROR "$1"
    exit 1
}

# Check prerequisites
check_prerequisites() {
    log INFO "Checking prerequisites..."
    
    # Check Docker
    if ! command -v docker &> /dev/null; then
        error_exit "Docker is not installed. Please install Docker first."
    fi
    
    # Check Docker Compose
    if ! command -v docker-compose &> /dev/null; then
        error_exit "Docker Compose is not installed. Please install Docker Compose first."
    fi
    
    # Check if Docker daemon is running
    if ! docker info &> /dev/null; then
        error_exit "Docker daemon is not running. Please start Docker first."
    fi
    
    log INFO "Prerequisites check passed"
}

# Create necessary directories
create_directories() {
    log INFO "Creating necessary directories..."
    
    local dirs=(
        "data"
        "logs"
        "exports"
        "config"
        "docker/nginx"
        "docker/prometheus"
        "docker/grafana/dashboards"
        "docker/grafana/datasources"
    )
    
    for dir in "${dirs[@]}"; do
        mkdir -p "$SCRIPT_DIR/$dir"
        log DEBUG "Created directory: $dir"
    done
    
    log INFO "Directories created successfully"
}

# Generate configuration files
generate_config() {
    log INFO "Generating configuration files..."
    
    # Generate .env file if it doesn't exist
    if [[ ! -f "$SCRIPT_DIR/.env" ]]; then
        log INFO "Creating .env file..."
        cat > "$SCRIPT_DIR/.env" << EOF
# Multi-Modal Recording System Configuration
ENVIRONMENT=$ENVIRONMENT
LOG_LEVEL=INFO
MAX_DEVICES=10
SYNC_INTERVAL=30
ANALYTICS_ENABLED=true

# Database Configuration
POSTGRES_DB=multimodal
POSTGRES_USER=multimodal
POSTGRES_PASSWORD=$(openssl rand -base64 32)

# Grafana Configuration
GRAFANA_PASSWORD=$(openssl rand -base64 16)

# Network Configuration
DASHBOARD_PORT=5000
DEVICE_PORT=8888
DISCOVERY_PORT=8889
EOF
        log INFO ".env file created"
    else
        log INFO ".env file already exists, skipping creation"
    fi
    
    # Generate Nginx configuration
    if [[ ! -f "$SCRIPT_DIR/docker/nginx/nginx.conf" ]]; then
        log INFO "Creating Nginx configuration..."
        cat > "$SCRIPT_DIR/docker/nginx/nginx.conf" << 'EOF'
events {
    worker_connections 1024;
}

http {
    upstream pc_controller {
        server pc-controller:5000;
    }
    
    server {
        listen 80;
        server_name _;
        
        # Health check endpoint
        location /health {
            access_log off;
            return 200 "healthy\n";
            add_header Content-Type text/plain;
        }
        
        # Proxy to PC controller
        location / {
            proxy_pass http://pc_controller;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            
            # WebSocket support
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
        }
    }
}
EOF
        log INFO "Nginx configuration created"
    fi
    
    # Generate Prometheus configuration
    if [[ ! -f "$SCRIPT_DIR/docker/prometheus/prometheus.yml" ]]; then
        log INFO "Creating Prometheus configuration..."
        cat > "$SCRIPT_DIR/docker/prometheus/prometheus.yml" << 'EOF'
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'multimodal-system'
    static_configs:
      - targets: ['pc-controller:5000']
    metrics_path: '/metrics'
    scrape_interval: 30s
EOF
        log INFO "Prometheus configuration created"
    fi
    
    log INFO "Configuration files generated successfully"
}

# Build and deploy services
deploy_services() {
    log INFO "Building and deploying services..."
    
    # Pull latest images
    log INFO "Pulling latest base images..."
    docker-compose pull --ignore-pull-failures
    
    # Build custom images
    log INFO "Building application images..."
    docker-compose build --no-cache
    
    # Start services
    log INFO "Starting services..."
    docker-compose up -d
    
    # Wait for services to be healthy
    log INFO "Waiting for services to be healthy..."
    local max_attempts=30
    local attempt=0
    
    while [[ $attempt -lt $max_attempts ]]; do
        if docker-compose ps | grep -q "Up (healthy)"; then
            log INFO "Services are healthy"
            break
        fi
        
        attempt=$((attempt + 1))
        log DEBUG "Health check attempt $attempt/$max_attempts"
        sleep 10
    done
    
    if [[ $attempt -eq $max_attempts ]]; then
        log WARN "Some services may not be fully healthy yet"
    fi
    
    log INFO "Services deployed successfully"
}

# Verify deployment
verify_deployment() {
    log INFO "Verifying deployment..."
    
    # Check if containers are running
    local containers=(
        "multimodal-pc-controller"
        "multimodal-nginx"
    )
    
    for container in "${containers[@]}"; do
        if docker ps --format "table {{.Names}}" | grep -q "$container"; then
            log INFO "Container $container is running"
        else
            log ERROR "Container $container is not running"
            return 1
        fi
    done
    
    # Test API endpoint
    log INFO "Testing API endpoint..."
    local max_attempts=10
    local attempt=0
    
    while [[ $attempt -lt $max_attempts ]]; do
        if curl -f -s http://localhost/api/status > /dev/null; then
            log INFO "API endpoint is responding"
            break
        fi
        
        attempt=$((attempt + 1))
        log DEBUG "API test attempt $attempt/$max_attempts"
        sleep 5
    done
    
    if [[ $attempt -eq $max_attempts ]]; then
        log ERROR "API endpoint is not responding"
        return 1
    fi
    
    log INFO "Deployment verification completed successfully"
}

# Show deployment status
show_status() {
    log INFO "Deployment Status:"
    echo
    docker-compose ps
    echo
    
    log INFO "Service URLs:"
    echo "  Dashboard:    http://localhost (or http://localhost:5000)"
    echo "  Prometheus:   http://localhost:9090"
    echo "  Grafana:      http://localhost:3000"
    echo "  API Status:   http://localhost/api/status"
    echo
    
    log INFO "Logs can be viewed with: docker-compose logs -f [service_name]"
    log INFO "To stop services: docker-compose down"
    log INFO "To update services: ./deploy.sh"
}

# Cleanup function
cleanup() {
    log INFO "Cleaning up old containers and images..."
    
    # Remove stopped containers
    docker container prune -f
    
    # Remove unused images
    docker image prune -f
    
    # Remove unused volumes (be careful with this)
    if [[ "${CLEANUP_VOLUMES:-false}" == "true" ]]; then
        log WARN "Removing unused volumes..."
        docker volume prune -f
    fi
    
    log INFO "Cleanup completed"
}

# Backup function
backup_data() {
    log INFO "Creating data backup..."
    
    local backup_dir="$SCRIPT_DIR/backups/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$backup_dir"
    
    # Backup data directory
    if [[ -d "$SCRIPT_DIR/data" ]]; then
        cp -r "$SCRIPT_DIR/data" "$backup_dir/"
        log INFO "Data directory backed up"
    fi
    
    # Backup configuration
    if [[ -f "$SCRIPT_DIR/.env" ]]; then
        cp "$SCRIPT_DIR/.env" "$backup_dir/"
        log INFO "Configuration backed up"
    fi
    
    # Backup database if running
    if docker ps --format "table {{.Names}}" | grep -q "multimodal-postgres"; then
        log INFO "Backing up database..."
        docker-compose exec -T postgres pg_dump -U multimodal multimodal > "$backup_dir/database.sql"
        log INFO "Database backed up"
    fi
    
    log INFO "Backup created at: $backup_dir"
}

# Main deployment function
main() {
    log INFO "Starting Multi-Modal Recording System deployment..."
    log INFO "Environment: $ENVIRONMENT"
    log INFO "Log file: $LOG_FILE"
    
    # Create log directory
    mkdir -p "$(dirname "$LOG_FILE")"
    
    # Parse command line arguments
    case "${1:-deploy}" in
        "deploy")
            check_prerequisites
            create_directories
            generate_config
            deploy_services
            verify_deployment
            show_status
            ;;
        "status")
            show_status
            ;;
        "stop")
            log INFO "Stopping services..."
            docker-compose down
            log INFO "Services stopped"
            ;;
        "restart")
            log INFO "Restarting services..."
            docker-compose restart
            log INFO "Services restarted"
            ;;
        "logs")
            docker-compose logs -f "${2:-}"
            ;;
        "cleanup")
            cleanup
            ;;
        "backup")
            backup_data
            ;;
        "update")
            log INFO "Updating system..."
            backup_data
            docker-compose down
            docker-compose pull
            docker-compose build --no-cache
            docker-compose up -d
            verify_deployment
            show_status
            ;;
        *)
            echo "Usage: $0 {deploy|status|stop|restart|logs|cleanup|backup|update}"
            echo
            echo "Commands:"
            echo "  deploy   - Deploy the complete system (default)"
            echo "  status   - Show deployment status"
            echo "  stop     - Stop all services"
            echo "  restart  - Restart all services"
            echo "  logs     - Show service logs (optionally specify service name)"
            echo "  cleanup  - Clean up old containers and images"
            echo "  backup   - Create backup of data and configuration"
            echo "  update   - Update and redeploy the system"
            exit 1
            ;;
    esac
    
    log INFO "Operation completed successfully"
}

# Run main function with all arguments
main "$@"