#!/bin/bash

# Simple script to manage development dependencies

# PID files for background processes
OPA_PID_FILE="/tmp/opa-server.pid"
BACKEND_PID_FILE="/tmp/entity-persistence-service.pid"
EPC_PID_FILE="/tmp/entity-persistence-cache.pid"
ORCHESTRATOR_PID_FILE="/tmp/entity-persistence-universal-orchestrator.pid"
GUI_PID_FILE="/tmp/tarcinapp-base-gui.pid"
GATEWAY_PID_FILE="/tmp/entity-persistence-gateway.pid"

# Log files — honour DEV_LOG_DIR if set by the caller (e.g. VSCode tasks)
LOG_DIR="${DEV_LOG_DIR:-/tmp/entity-persistence-gateway-logs}"
mkdir -p "$LOG_DIR"
OPA_LOG_FILE="$LOG_DIR/opa.log"
BACKEND_LOG_FILE="$LOG_DIR/backend.log"
REDIS_LOG_FILE="$LOG_DIR/redis.log"
EPC_LOG_FILE="$LOG_DIR/epc.log"
ORCHESTRATOR_LOG_FILE="$LOG_DIR/orchestrator.log"
GUI_LOG_FILE="$LOG_DIR/gui.log"
GATEWAY_LOG_FILE="$LOG_DIR/gateway.log"

# Redis configuration — EPG instance (port 6379, noeviction)
REDIS_CONF_FILE="/tmp/redis-dev.conf"
REDIS_PASSWORD="devpassword123"

# Redis configuration — EPC instance (port 6380, allkeys-lfu)
REDIS_EPC_CONF_FILE="/tmp/redis-epc-dev.conf"
REDIS_EPC_PORT=6380
REDIS_EPC_PASSWORD="epcpassword123"
REDIS_EPC_LOG_FILE="$LOG_DIR/redis-epc.log"

# Service commands
OPA_CMD="opa run --ignore=*_test.rego --server --log-level=debug ~/git/github/entity-persistence-gateway-policies/policies"
BACKEND_DIR="$HOME/git/github/entity-persistence-service"
BACKEND_CMD="./start-with-env-file.sh dev.env"
EPC_DIR="$HOME/git/github/entity-persistence-cache"
EPC_CMD="mvn quarkus:dev -Dquarkus.test.continuous-testing=disabled"
ORCHESTRATOR_DIR="$HOME/git/github/entity-persistence-universal-orchestrator"
ORCHESTRATOR_CMD="mvn quarkus:dev -Dquarkus.test.continuous-testing=disabled"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

rotate_logs() {
    local ts old_dir
    ts=$(date +%Y%m%d_%H%M%S)
    old_dir="$LOG_DIR/old/$ts"
    local rotated=false
    for log_file in "$OPA_LOG_FILE" "$BACKEND_LOG_FILE" "$REDIS_LOG_FILE" "$REDIS_EPC_LOG_FILE" "$EPC_LOG_FILE" "$ORCHESTRATOR_LOG_FILE" "$GUI_LOG_FILE" "$GATEWAY_LOG_FILE"; do
        if [ -s "$log_file" ]; then
            mkdir -p "$old_dir"
            mv "$log_file" "$old_dir/$(basename "$log_file")"
            rotated=true
        fi
    done
    if $rotated; then
        print_status "Old logs moved to $old_dir"
    fi
}

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
    redis-cli -p 6379 -a "$REDIS_PASSWORD" ping >/dev/null 2>&1
}

check_redis_epc() {
    redis-cli -p "$REDIS_EPC_PORT" -a "$REDIS_EPC_PASSWORD" ping >/dev/null 2>&1
}

create_redis_config() {
    cat > "$REDIS_CONF_FILE" << EOF
# Redis development configuration — EPG (port 6379, noeviction)
port 6379
bind 127.0.0.1
daemonize yes
requirepass $REDIS_PASSWORD
logfile $REDIS_LOG_FILE
databases 16
maxmemory-policy noeviction
save ""
dir /tmp
EOF
}

create_redis_epc_config() {
    cat > "$REDIS_EPC_CONF_FILE" << EOF
# Redis development configuration — EPC (port 6380, allkeys-lfu)
port $REDIS_EPC_PORT
bind 127.0.0.1
daemonize yes
requirepass $REDIS_EPC_PASSWORD
logfile $REDIS_EPC_LOG_FILE
databases 16
maxmemory-policy allkeys-lfu
save ""
dir /tmp
EOF
}

start_redis() {
    if check_redis; then
        print_success "Redis (EPG, :6379) is already running"
        return 0
    fi

    # Detect a passwordless Redis on 6379 (e.g. system service started by systemd)
    if redis-cli -p 6379 ping >/dev/null 2>&1; then
        print_error "A passwordless Redis is already running on port 6379."
        print_error "This is likely the system Redis service. Disable it once with:"
        print_error "  sudo systemctl stop redis-server && sudo systemctl disable redis-server"
        return 1
    fi

    print_status "Creating Redis (EPG) configuration..."
    create_redis_config
    
    print_status "Starting Redis (EPG, :6379) with authentication..."
    redis-server "$REDIS_CONF_FILE" >/dev/null 2>&1
    sleep 2
    
    if check_redis; then
        print_success "Redis (EPG, :6379) started successfully"
    else
        print_error "Failed to start Redis (EPG, :6379)"
        return 1
    fi
}

start_redis_epc() {
    if check_redis_epc; then
        print_success "Redis (EPC, :${REDIS_EPC_PORT}) is already running"
        return 0
    fi

    # Detect a passwordless Redis on 6380
    if redis-cli -p "$REDIS_EPC_PORT" ping >/dev/null 2>&1; then
        print_error "A passwordless Redis is already running on port ${REDIS_EPC_PORT}."
        return 1
    fi

    print_status "Creating Redis (EPC) configuration..."
    create_redis_epc_config

    print_status "Starting Redis (EPC, :${REDIS_EPC_PORT}) with authentication..."
    redis-server "$REDIS_EPC_CONF_FILE" >/dev/null 2>&1
    sleep 2

    if check_redis_epc; then
        print_success "Redis (EPC, :${REDIS_EPC_PORT}) started successfully"
    else
        print_error "Failed to start Redis (EPC, :${REDIS_EPC_PORT})"
        return 1
    fi
}

stop_redis() {
    if check_redis; then
        print_status "Stopping Redis (EPG, :6379)..."
        redis-cli -p 6379 -a "$REDIS_PASSWORD" shutdown nosave >/dev/null 2>&1
        print_success "Redis (EPG) stopped"
        rm -f "$REDIS_CONF_FILE"
    else
        print_warning "Redis (EPG) is not running"
    fi
}

stop_redis_epc() {
    if check_redis_epc; then
        print_status "Stopping Redis (EPC, :${REDIS_EPC_PORT})..."
        redis-cli -p "$REDIS_EPC_PORT" -a "$REDIS_EPC_PASSWORD" shutdown nosave >/dev/null 2>&1
        print_success "Redis (EPC) stopped"
        rm -f "$REDIS_EPC_CONF_FILE"
    else
        print_warning "Redis (EPC) is not running"
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

check_epc() {
    if [ -f "$EPC_PID_FILE" ] && kill -0 "$(cat "$EPC_PID_FILE")" 2>/dev/null; then
        return 0
    else
        return 1
    fi
}

start_epc() {
    if check_epc; then
        print_success "EPC is already running (PID: $(cat "$EPC_PID_FILE"))"
        return 0
    fi

    if [ ! -d "$EPC_DIR" ]; then
        print_error "EPC directory not found: $EPC_DIR"
        return 1
    fi

    print_status "Starting entity-persistence-cache..."
    cd "$EPC_DIR"
    # Truncate the log so we only scan lines from this startup
    > "$EPC_LOG_FILE"
    nohup bash -c "$EPC_CMD" > "$EPC_LOG_FILE" 2>&1 &
    echo $! > "$EPC_PID_FILE"

    local timeout=180
    local elapsed=0
    local started=false
    print_status "Waiting for EPC to become ready (timeout: ${timeout}s)..."
    while [ "$elapsed" -lt "$timeout" ]; do
        if ! check_epc; then
            print_error "EPC process died during startup"
            rm -f "$EPC_PID_FILE"
            return 1
        fi
        if grep -qE "INFO[[:space:]]+\[io\.quarkus\].*\(Quarkus Main Thread\).*started in.*Listening on:" "$EPC_LOG_FILE" 2>/dev/null; then
            started=true
            break
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    if $started; then
        print_success "EPC started successfully (PID: $(cat "$EPC_PID_FILE"))"
    else
        print_error "EPC did not become ready within ${timeout}s"
        rm -f "$EPC_PID_FILE"
        return 1
    fi
}

stop_epc() {
    if [ -f "$EPC_PID_FILE" ]; then
        local pid=$(cat "$EPC_PID_FILE")
        print_status "Stopping EPC (PID: $pid)..."
        if kill "$pid" 2>/dev/null; then
            pkill -P "$pid" 2>/dev/null
            rm -f "$EPC_PID_FILE"
            print_success "EPC stopped"
        else
            print_warning "EPC process not found or already stopped"
            rm -f "$EPC_PID_FILE"
        fi
    else
        print_warning "EPC not running"
    fi

    # Additional cleanup: kill any process still holding port 3001
    local epc_pids
    epc_pids=$(lsof -ti :3001 2>/dev/null)
    if [ -n "$epc_pids" ]; then
        print_status "Found EPC process(es) on port 3001: $epc_pids"
        for pid in $epc_pids; do
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
    # Truncate the log so we only scan lines from this startup
    > "$ORCHESTRATOR_LOG_FILE"
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

GATEWAY_DIR="$(cd "$(dirname "$0")" && pwd)"

check_gateway() {
    if [ -f "$GATEWAY_PID_FILE" ] && kill -0 "$(cat "$GATEWAY_PID_FILE")" 2>/dev/null; then
        return 0
    else
        return 1
    fi
}

start_gateway() {
    if check_gateway; then
        print_success "Gateway is already running (PID: $(cat "$GATEWAY_PID_FILE"))"
        return 0
    fi

    print_status "Starting entity-persistence-gateway (Spring Boot)..."
    cd "$GATEWAY_DIR"
    nohup ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,booksapp > "$GATEWAY_LOG_FILE" 2>&1 &
    echo $! > "$GATEWAY_PID_FILE"

    local timeout=120
    local elapsed=0
    print_status "Waiting for gateway to become ready (timeout: ${timeout}s)..."
    while [ "$elapsed" -lt "$timeout" ]; do
        if ! check_gateway; then
            print_error "Gateway process died during startup"
            rm -f "$GATEWAY_PID_FILE"
            return 1
        fi
        if grep -q "Started.*Application\|Netty started on port\|Tomcat started on port" "$GATEWAY_LOG_FILE" 2>/dev/null; then
            break
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    if check_gateway; then
        print_success "Gateway started successfully (PID: $(cat "$GATEWAY_PID_FILE"))"
    else
        print_error "Gateway did not become ready within ${timeout}s"
        rm -f "$GATEWAY_PID_FILE"
        return 1
    fi
}

stop_gateway() {
    if [ -f "$GATEWAY_PID_FILE" ]; then
        local pid=$(cat "$GATEWAY_PID_FILE")
        print_status "Stopping gateway (PID: $pid)..."
        if kill "$pid" 2>/dev/null; then
            pkill -P "$pid" 2>/dev/null
            rm -f "$GATEWAY_PID_FILE"
            print_success "Gateway stopped"
        else
            print_warning "Gateway process not found or already stopped"
            rm -f "$GATEWAY_PID_FILE"
        fi
    else
        print_warning "Gateway not running"
    fi
}

GUI_DIR="$HOME/git/github/tarcinapp-base-gui"

check_gui() {
    if [ -f "$GUI_PID_FILE" ] && kill -0 "$(cat "$GUI_PID_FILE")" 2>/dev/null; then
        return 0
    else
        return 1
    fi
}

start_gui() {
    if check_gui; then
        print_success "GUI is already running (PID: $(cat "$GUI_PID_FILE"))"
        return 0
    fi

    if [ ! -d "$GUI_DIR" ]; then
        print_error "GUI directory not found: $GUI_DIR"
        return 1
    fi

    print_status "Starting GUI (vite dev)..."
    cd "$GUI_DIR"
    nohup npm run dev > "$GUI_LOG_FILE" 2>&1 &
    echo $! > "$GUI_PID_FILE"

    sleep 3
    if check_gui; then
        print_success "GUI started successfully (PID: $(cat "$GUI_PID_FILE"))"
    else
        print_error "Failed to start GUI"
        rm -f "$GUI_PID_FILE"
        return 1
    fi
}

stop_gui() {
    if [ -f "$GUI_PID_FILE" ]; then
        local pid=$(cat "$GUI_PID_FILE")
        print_status "Stopping GUI (PID: $pid)..."
        if kill "$pid" 2>/dev/null; then
            pkill -P "$pid" 2>/dev/null
            rm -f "$GUI_PID_FILE"
            print_success "GUI stopped"
        else
            print_warning "GUI process not found or already stopped"
            rm -f "$GUI_PID_FILE"
        fi
    else
        print_warning "GUI not running"
    fi
}

show_status() {
    echo "Entity Persistence Gateway - Development Environment"
    echo "=================================================="
    echo
    echo "Service Status:"
    
    if check_redis; then
        echo "  Redis (EPG, :6379):  Running"
    else
        echo "  Redis (EPG, :6379):  Stopped"
    fi

    if check_redis_epc; then
        echo "  Redis (EPC, :${REDIS_EPC_PORT}): Running"
    else
        echo "  Redis (EPC, :${REDIS_EPC_PORT}): Stopped"
    fi
    
    if check_opa; then
        echo "  OPA:          Running (PID: $(cat "$OPA_PID_FILE"))"
    else
        echo "  OPA:          Stopped"
    fi
    
    if check_backend; then
        echo "  Backend (EPS): Running (PID: $(cat "$BACKEND_PID_FILE"))"
    else
        echo "  Backend (EPS): Stopped"
    fi

    if check_epc; then
        echo "  EPC:           Running (PID: $(cat "$EPC_PID_FILE"))"
    else
        echo "  EPC:           Stopped"
    fi

    if check_orchestrator; then
        echo "  Orchestrator:  Running (PID: $(cat "$ORCHESTRATOR_PID_FILE"))"
    else
        echo "  Orchestrator:  Stopped"
    fi
    
    echo
    echo "Service URLs:"
    echo "  Redis (EPG): localhost:6379"
    echo "  Redis (EPC): localhost:${REDIS_EPC_PORT}"
    echo "  OPA:         http://localhost:8181"
    echo "  EPS:         http://localhost:3000"
    echo "  EPC:         http://localhost:3001"
    echo "  Orchestrator:http://localhost:8079"
    echo
    echo "Log Files:"
    echo "  Redis (EPG):  $REDIS_LOG_FILE"
    echo "  Redis (EPC):  $REDIS_EPC_LOG_FILE"
    echo "  OPA:          $OPA_LOG_FILE"
    echo "  Backend:      $BACKEND_LOG_FILE"
    echo "  EPC:          $EPC_LOG_FILE"
    echo "  Orchestrator: $ORCHESTRATOR_LOG_FILE"
}

case "${1:-help}" in
    start)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        rotate_logs
        start_redis
        start_redis_epc
        start_opa
        start_backend
        start_epc
        start_orchestrator
        start_gateway
        echo
        print_success "All services started!"
        echo
        echo "Log Files:"
        echo "  Redis (EPG):  $REDIS_LOG_FILE"
        echo "  Redis (EPC):  $REDIS_EPC_LOG_FILE"
        echo "  OPA:          $OPA_LOG_FILE"
        echo "  Backend:      $BACKEND_LOG_FILE"
        echo "  EPC:          $EPC_LOG_FILE"
        echo "  Orchestrator: $ORCHESTRATOR_LOG_FILE"
        echo "  Gateway:      $GATEWAY_LOG_FILE"
        ;;
    
    stop)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        stop_gateway
        stop_orchestrator
        stop_epc
        stop_backend
        stop_opa
        stop_redis_epc
        stop_redis
        echo
        print_success "All services stopped!"
        ;;
    
    status)
        show_status
        ;;
    
    logs)
        print_status "Showing recent logs (Ctrl+C to exit)..."
        tail -f "$REDIS_LOG_FILE" "$REDIS_EPC_LOG_FILE" "$OPA_LOG_FILE" "$BACKEND_LOG_FILE" "$EPC_LOG_FILE" "$ORCHESTRATOR_LOG_FILE" 2>/dev/null || echo "No log files found yet"
        ;;
    
    start-gui)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        start_gui
        echo
        print_success "GUI started!"
        echo "  GUI log: $GUI_LOG_FILE"
        ;;

    start-epc)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        start_redis_epc
        start_epc
        echo
        echo "  Redis (EPC) log: $REDIS_EPC_LOG_FILE"
        echo "  EPC log:         $EPC_LOG_FILE"
        ;;

    stop-epc)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        stop_epc
        stop_redis_epc
        echo
        print_success "EPC and Redis (EPC) stopped!"
        ;;

    start-gateway)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        start_gateway
        echo
        echo "  Gateway log: $GATEWAY_LOG_FILE"
        ;;

    stop-gateway)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        stop_gateway
        echo
        print_success "Gateway stopped!"
        ;;

    stop-gui)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        stop_gui
        echo
        print_success "GUI stopped!"
        ;;

    debug)
        echo "Entity Persistence Gateway - Development Environment"
        echo "=================================================="
        echo
        print_status "Starting dependencies for debugging..."
        start_redis
        start_redis_epc
        start_opa
        start_backend
        start_epc
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
        echo "  start     - Start both Redis instances and all dependencies"
        echo "  stop      - Stop all dependencies and both Redis instances"
        echo "  status    - Show status of all services"
        echo "  logs      - Show service logs"
        echo "  debug     - Start everything for debugging"
        echo "  start-epc - Start Redis (EPC) and entity-persistence-cache"
        echo "  stop-epc  - Stop entity-persistence-cache and Redis (EPC)"
        echo
        echo "Manual commands:"
        echo "  Redis:   redis-server --daemonize yes"
        echo "  OPA:     opa run --ignore=*_test.rego --server --log-level=debug ~/git/github/entity-persistence-gateway-policies/policies"
        echo "  Backend: cd /home/kdrkrst/git/github/entity-persistence-service && ./start-with-env-file.sh dev.env"
        ;;
esac