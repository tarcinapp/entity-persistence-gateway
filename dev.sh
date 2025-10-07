#!/bin/bash

# Simple script to manage development dependencies

# PID files for background processes
OPA_PID_FILE="/tmp/opa-server.pid"
BACKEND_PID_FILE="/tmp/entity-persistence-service.pid"

# Log files
LOG_DIR="/tmp/entity-persistence-gateway-logs"
mkdir -p "$LOG_DIR"
OPA_LOG_FILE="$LOG_DIR/opa.log"
BACKEND_LOG_FILE="$LOG_DIR/backend.log"
REDIS_LOG_FILE="$LOG_DIR/redis.log"

# Redis configuration
REDIS_CONF_FILE="/tmp/redis-dev.conf"
REDIS_PASSWORD="devpassword123"

# Service commands
OPA_CMD="opa run --ignore=*_test.rego --server --log-level=debug ~/git/github/entity-persistence-gateway-policies/policies"
BACKEND_DIR="/home/kdrkrst/git/github/entity-persistence-service"
BACKEND_CMD="./start-with-env-file.sh dev.env"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_redis() {
    redis-cli -a "$REDIS_PASSWORD" ping >/dev/null 2>&1
}

create_redis_config() {
    cat > "$REDIS_CONF_FILE" << EOF
# Redis development configuration
port 6379
bind 127.0.0.1
daemonize yes
requirepass $REDIS_PASSWORD
logfile $REDIS_LOG_FILE
databases 16
save 900 1
save 300 10
save 60 10000
dir /tmp
EOF
}

start_redis() {
    if check_redis; then
        print_success "Redis is already running"
        return 0
    fi
    
    print_status "Creating Redis configuration..."
    create_redis_config
    
    print_status "Starting Redis with authentication..."
    redis-server "$REDIS_CONF_FILE" >/dev/null 2>&1
    sleep 2
    
    if check_redis; then
        print_success "Redis started successfully with password authentication"
    else
        print_error "Failed to start Redis"
        return 1
    fi
}

stop_redis() {
    if check_redis; then
        print_status "Stopping Redis..."
        redis-cli -a "$REDIS_PASSWORD" shutdown nosave >/dev/null 2>&1
        print_success "Redis stopped"
        
        # Clean up config file
        rm -f "$REDIS_CONF_FILE"
    else
        print_warning "Redis is not running"
    fi
}

check_opa() {
    if [ -f "$OPA_PID_FILE" ] && kill -0 "$(cat "$OPA_PID_FILE")" 2>/dev/null; then
        return 0
    else
        return 1
    fi
}

start_opa() {
    if check_opa; then
        print_success "OPA is already running (PID: $(cat "$OPA_PID_FILE"))"
        return 0
    fi
    
    if ! command -v opa >/dev/null 2>&1; then
        print_error "OPA command not found. Please install OPA first."
        return 1
    fi
    
    if [ ! -d "$HOME/git/github/entity-persistence-gateway-policies/policies" ]; then
        print_error "Policies directory not found: ~/git/github/entity-persistence-gateway-policies/policies"
        return 1
    fi
    
    print_status "Starting OPA server..."
    nohup bash -c "$OPA_CMD" > "$OPA_LOG_FILE" 2>&1 &
    echo $! > "$OPA_PID_FILE"
    
    sleep 2
    if check_opa; then
        print_success "OPA started successfully (PID: $(cat "$OPA_PID_FILE"))"
    else
        print_error "Failed to start OPA"
        rm -f "$OPA_PID_FILE"
        return 1
    fi
}

stop_opa() {
    if [ -f "$OPA_PID_FILE" ]; then
        local pid=$(cat "$OPA_PID_FILE")
        print_status "Stopping OPA (PID: $pid)..."
        if kill "$pid" 2>/dev/null; then
            rm -f "$OPA_PID_FILE"
            print_success "OPA stopped"
        else
            print_warning "OPA process not found or already stopped"
            rm -f "$OPA_PID_FILE"
        fi
    else
        print_warning "OPA not running"
    fi
}

check_backend() {
    if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
        return 0
    else
        return 1
    fi
}

start_backend() {
    if check_backend; then
        print_success "Backend is already running (PID: $(cat "$BACKEND_PID_FILE"))"
        return 0
    fi
    
    if [ ! -d "$BACKEND_DIR" ]; then
        print_error "Backend directory not found: $BACKEND_DIR"
        return 1
    fi
    
    if [ ! -f "$BACKEND_DIR/start-with-env-file.sh" ]; then
        print_error "Backend startup script not found: $BACKEND_DIR/start-with-env-file.sh"
        return 1
    fi
    
    print_status "Starting backend service..."
    cd "$BACKEND_DIR"
    nohup bash -c "$BACKEND_CMD" > "$BACKEND_LOG_FILE" 2>&1 &
    echo $! > "$BACKEND_PID_FILE"
    
    sleep 3
    if check_backend; then
        print_success "Backend started successfully (PID: $(cat "$BACKEND_PID_FILE"))"
    else
        print_error "Failed to start backend"
        rm -f "$BACKEND_PID_FILE"
        return 1
    fi
}

stop_backend() {
    if [ -f "$BACKEND_PID_FILE" ]; then
        local pid=$(cat "$BACKEND_PID_FILE")
        print_status "Stopping backend (PID: $pid)..."
        # Kill the process and all its children
        if kill "$pid" 2>/dev/null; then
            # Also kill any child processes
            pkill -P "$pid" 2>/dev/null
            rm -f "$BACKEND_PID_FILE"
            print_success "Backend stopped"
        else
            print_warning "Backend process not found or already stopped"
            rm -f "$BACKEND_PID_FILE"
        fi
    else
        print_warning "Backend not running (checking for orphaned processes)"
    fi
    
    # Additional cleanup: kill any node processes running from the backend directory
    local backend_pids=$(lsof -ti :3000 2>/dev/null)
    if [ -n "$backend_pids" ]; then
        print_status "Found backend process(es) on port 3000: $backend_pids"
        for pid in $backend_pids; do
            print_status "Killing backend process $pid..."
            kill "$pid" 2>/dev/null && print_success "Killed process $pid" || print_warning "Could not kill process $pid"
        done
    fi
}

show_status() {
    echo "Entity Persistence Gateway - Development Environment"
    echo "=================================================="
    echo
    echo "Service Status:"
    
    if check_redis; then
        echo "  Redis:   Running"
    else
        echo "  Redis:   Stopped"
    fi
    
    if check_opa; then
        echo "  OPA:     Running (PID: $(cat "$OPA_PID_FILE"))"
    else
        echo "  OPA:     Stopped"
    fi
    
    if check_backend; then
        echo "  Backend: Running (PID: $(cat "$BACKEND_PID_FILE"))"
    else
        echo "  Backend: Stopped"
    fi
    
    echo
    echo "Service URLs:"
    echo "  Redis:   localhost:6379"
    echo "  OPA:     http://localhost:8181"
    echo "  Backend: Check logs for port information"
    echo
    echo "Log Files:"
    echo "  Redis:   $REDIS_LOG_FILE"
    echo "  OPA:     $OPA_LOG_FILE"
    echo "  Backend: $BACKEND_LOG_FILE"
}

case "${1:-help}" in
    start)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        start_redis
        start_opa
        start_backend
        echo
        print_success "All services started!"
        ;;
    
    stop)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        stop_backend
        stop_opa
        stop_redis
        echo
        print_success "All services stopped!"
        ;;
    
    status)
        show_status
        ;;
    
    logs)
        print_status "Showing recent logs (Ctrl+C to exit)..."
        tail -f "$REDIS_LOG_FILE" "$OPA_LOG_FILE" "$BACKEND_LOG_FILE" 2>/dev/null || echo "No log files found yet"
        ;;
    
    debug)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        print_status "Starting dependencies for debugging..."
        start_redis
        start_opa
        start_backend
        echo
        print_success "Dependencies started. You can now run your application in debug mode."
        ;;
    
    *)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        echo "Usage: $0 {start|stop|status|logs|debug}"
        echo
        echo "Commands:"
        echo "  start  - Start Redis and all dependencies"
        echo "  stop   - Stop all dependencies and Redis"
        echo "  status - Show status of all services"
        echo "  logs   - Show service logs"
        echo "  debug  - Start everything for debugging"
        echo
        echo "Manual commands:"
        echo "  Redis:   redis-server --daemonize yes"
        echo "  OPA:     opa run --ignore=*_test.rego --server --log-level=debug ~/git/github/entity-persistence-gateway-policies/policies"
        echo "  Backend: cd /home/kdrkrst/git/github/entity-persistence-service && ./start-with-env-file.sh dev.env"
        ;;
esac