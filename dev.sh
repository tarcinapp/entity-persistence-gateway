#!/bin/bash

# Simple script to manage development dependencies

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICES_ROOT="$(dirname "$SCRIPT_DIR")"

# PID files for background processes
OPA_PID_FILE="/tmp/opa-server.pid"
BACKEND_PID_FILE="/tmp/entity-persistence-service.pid"
ORCHESTRATOR_PID_FILE="/tmp/entity-persistence-universal-orchestrator.pid"

# Log files
# Allow callers (e.g. VSCode tasks) to override the log directory via DEV_LOG_DIR.
# Default: <workspace-root>/logs  (one level above the gateway repo).
LOG_DIR="${DEV_LOG_DIR:-$SERVICES_ROOT/../logs}"
mkdir -p "$LOG_DIR"
OPA_LOG_FILE="$LOG_DIR/opa.log"
BACKEND_LOG_FILE="$LOG_DIR/backend.log"
REDIS_LOG_FILE="$LOG_DIR/redis.log"
ORCHESTRATOR_LOG_FILE="$LOG_DIR/orchestrator.log"
GATEWAY_LOG_FILE="$LOG_DIR/gateway.log"

# Redis configuration
REDIS_PASSWORD="devpassword123"

# Service commands
OPA_CMD="opa run --ignore=*_test.rego --server --log-level=debug $SERVICES_ROOT/entity-persistence-gateway-policies/policies"
BACKEND_DIR="$SERVICES_ROOT/entity-persistence-service"
BACKEND_CMD="./start-with-env-file.sh dev.env"
ORCHESTRATOR_DIR="$SERVICES_ROOT/entity-persistence-universal-orchestrator"
ORCHESTRATOR_CMD="mvn quarkus:dev -Dquarkus.test.continuous-testing=disabled"

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

rotate_logs() {
    local ts
    ts=$(date +%Y%m%d_%H%M%S)
    local old_dir="$LOG_DIR/old/$ts"
    local rotated=false

    for f in "$OPA_LOG_FILE" "$BACKEND_LOG_FILE" "$REDIS_LOG_FILE" "$ORCHESTRATOR_LOG_FILE" "$GATEWAY_LOG_FILE"; do
        if [ -s "$f" ]; then
            mkdir -p "$old_dir"
            mv "$f" "$old_dir/$(basename "$f")"
            rotated=true
        fi
    done

    if $rotated; then
        print_status "Previous logs moved to $old_dir"
    fi
}

check_redis() {
    # Redis must be reachable with our password AND must reject unauthenticated access.
    # (If requirepass is empty, Redis accepts any client including ones that send a wrong password.)
    redis-cli -a "$REDIS_PASSWORD" ping 2>/dev/null | grep -q "^PONG$" && \
        ! redis-cli ping 2>/dev/null | grep -q "^PONG$"
}

start_redis() {
    if check_redis; then
        print_success "Redis is already running (authenticated)"
        return 0
    fi

    # Redis is up (system service or other) but has no password — set ours at runtime.
    if redis-cli ping 2>/dev/null | grep -q "^PONG$"; then
        print_status "Configuring Redis password for dev environment..."
        redis-cli config set requirepass "$REDIS_PASSWORD" >/dev/null 2>&1
        redis-cli config set logfile "$REDIS_LOG_FILE" >/dev/null 2>&1
        sleep 1
        if check_redis; then
            print_success "Redis password configured (using existing instance)"
            return 0
        else
            print_error "Failed to set Redis password"
            return 1
        fi
    fi

    # No Redis running at all — start one ourselves.
    print_status "Redis is not running. Starting Redis..."
    redis-server --daemonize yes --logfile "$REDIS_LOG_FILE" >/dev/null 2>&1
    sleep 2

    if redis-cli ping 2>/dev/null | grep -q "^PONG$"; then
        redis-cli config set requirepass "$REDIS_PASSWORD" >/dev/null 2>&1
        sleep 1
        if check_redis; then
            print_success "Redis started and password configured"
            return 0
        else
            print_error "Redis started but failed to set password"
            return 1
        fi
    else
        print_error "Failed to start Redis"
        return 1
    fi
}

stop_redis() {
    if check_redis; then
        # Check if this Redis is managed by systemd (keep it running, just remove our password).
        if systemctl is-active redis redis-server 2>/dev/null | grep -q "^active$"; then
            print_status "Removing Redis dev password..."
            redis-cli -a "$REDIS_PASSWORD" config set requirepass "" >/dev/null 2>&1
            print_success "Redis dev password removed (system service kept running)"
        else
            print_status "Stopping Redis..."
            redis-cli -a "$REDIS_PASSWORD" shutdown nosave >/dev/null 2>&1
            print_success "Redis stopped"
        fi
    elif redis-cli ping 2>/dev/null | grep -q "^PONG$"; then
        print_warning "Redis is running but dev password was not set (already cleaned up?)"
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
    
    if [ ! -d "$SERVICES_ROOT/entity-persistence-gateway-policies/policies" ]; then
        print_error "Policies directory not found: $SERVICES_ROOT/entity-persistence-gateway-policies/policies"
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
    setsid nohup bash -c "$BACKEND_CMD" > "$BACKEND_LOG_FILE" 2>&1 &
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

check_orchestrator() {
    if [ -f "$ORCHESTRATOR_PID_FILE" ] && kill -0 "$(cat "$ORCHESTRATOR_PID_FILE")" 2>/dev/null; then
        return 0
    else
        return 1
    fi
}

start_orchestrator() {
    if check_orchestrator; then
        print_success "Orchestrator is already running (PID: $(cat "$ORCHESTRATOR_PID_FILE"))"
        return 0
    fi

    if [ ! -d "$ORCHESTRATOR_DIR" ]; then
        print_error "Orchestrator directory not found: $ORCHESTRATOR_DIR"
        return 1
    fi

    print_status "Starting universal orchestrator..."
    cd "$ORCHESTRATOR_DIR"
    nohup bash -c "$ORCHESTRATOR_CMD" > "$ORCHESTRATOR_LOG_FILE" 2>&1 &
    echo $! > "$ORCHESTRATOR_PID_FILE"

    local timeout=180
    local elapsed=0
    local started=false
    print_status "Waiting for orchestrator to become ready (timeout: ${timeout}s)..."
    while [ "$elapsed" -lt "$timeout" ]; do
        if ! check_orchestrator; then
            print_error "Orchestrator process died during startup"
            rm -f "$ORCHESTRATOR_PID_FILE"
            return 1
        fi
        if grep -qE "INFO[[:space:]]+\[io\.quarkus\].*\(Quarkus Main Thread\).*started in.*Listening on:" "$ORCHESTRATOR_LOG_FILE" 2>/dev/null; then
            started=true
            break
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    if $started; then
        print_success "Orchestrator started successfully (PID: $(cat "$ORCHESTRATOR_PID_FILE"))"
    else
        print_error "Orchestrator did not become ready within ${timeout}s"
        rm -f "$ORCHESTRATOR_PID_FILE"
        return 1
    fi
}

stop_orchestrator() {
    if [ -f "$ORCHESTRATOR_PID_FILE" ]; then
        local pid=$(cat "$ORCHESTRATOR_PID_FILE")
        print_status "Stopping orchestrator (PID: $pid)..."
        if kill "$pid" 2>/dev/null; then
            pkill -P "$pid" 2>/dev/null
            rm -f "$ORCHESTRATOR_PID_FILE"
            print_success "Orchestrator stopped"
        else
            print_warning "Orchestrator process not found or already stopped"
            rm -f "$ORCHESTRATOR_PID_FILE"
        fi
    else
        print_warning "Orchestrator not running"
    fi
}

check_gateway() {
    lsof -ti :8081 2>/dev/null | grep -q .
}

stop_gateway() {
    local pids
    pids=$(lsof -ti :8081 2>/dev/null)
    if [ -n "$pids" ]; then
        print_status "Stopping gateway (port 8081, PID(s): $pids)..."
        echo "$pids" | xargs kill 2>/dev/null
        sleep 2
        # Force-kill if still running
        pids=$(lsof -ti :8081 2>/dev/null)
        if [ -n "$pids" ]; then
            echo "$pids" | xargs kill -9 2>/dev/null
        fi
        print_success "Gateway stopped"
    else
        print_warning "Gateway is not running"
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

    if check_orchestrator; then
        echo "  Orchestrator: Running (PID: $(cat "$ORCHESTRATOR_PID_FILE"))"
    else
        echo "  Orchestrator: Stopped"
    fi

    if check_gateway; then
        echo "  Gateway: Running (port 8081, PID: $(lsof -ti :8081 2>/dev/null | tr '\n' ' '))"
    else
        echo "  Gateway: Stopped"
    fi
    
    echo
    echo "Service URLs:"
    echo "  Redis:   localhost:6379"
    echo "  OPA:     http://localhost:8181"
    echo "  Backend: Check logs for port information"
    echo "  Orchestrator: Check logs for port information"
    echo
    echo "Log Files:"
    echo "  Redis:        $REDIS_LOG_FILE"
    echo "  OPA:          $OPA_LOG_FILE"
    echo "  Backend:      $BACKEND_LOG_FILE"
    echo "  Orchestrator: $ORCHESTRATOR_LOG_FILE"
}

case "${1:-help}" in
    start)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        rotate_logs
        start_redis
        start_opa
        start_backend
        start_orchestrator
        echo
        print_success "All services started!"
        echo
        echo "Log Files:"
        echo "  Redis:        $REDIS_LOG_FILE"
        echo "  OPA:          $OPA_LOG_FILE"
        echo "  Backend:      $BACKEND_LOG_FILE"
        echo "  Orchestrator: $ORCHESTRATOR_LOG_FILE"
        ;;
    
    stop)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        stop_gateway
        stop_orchestrator
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
        tail -f "$REDIS_LOG_FILE" "$OPA_LOG_FILE" "$BACKEND_LOG_FILE" "$ORCHESTRATOR_LOG_FILE" 2>/dev/null || echo "No log files found yet"
        ;;
    
    debug)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        print_status "Starting dependencies for debugging..."
        rotate_logs
        start_redis
        start_opa
        start_backend
        start_orchestrator
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
        echo "  OPA:     opa run --ignore=*_test.rego --server --log-level=debug $SERVICES_ROOT/entity-persistence-gateway-policies/policies"
        echo "  Backend: cd $SERVICES_ROOT/entity-persistence-service && ./start-with-env-file.sh dev.env"
        ;;
esac