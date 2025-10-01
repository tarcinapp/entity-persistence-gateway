# Development Environment Setup

This project requires several dependencies to run in debug mode. Here are the automated solutions:

## Quick Start

### Option 1: Simple Script (Recommended)
```bash
# Start everything
./dev.sh start

# Check status
./dev.sh status

# Stop everything
./dev.sh stop

# View logs
./dev.sh logs
```

### Option 2: VS Code Tasks
1. Open Command Palette (`Ctrl+Shift+P`)
2. Type "Tasks: Run Task"
3. Select "Start All Dependencies"

### Option 3: Debug with Dependencies
Use the "Debug with Dependencies" launch configuration in VS Code - it will automatically start dependencies before launching your application.

## Manual Commands

If you prefer to start services manually:

```bash
# Redis
redis-server --daemonize yes

# OPA
opa run --ignore=*_test.rego --server --log-level=debug ~/git/github/entity-persistence-gateway-policies/policies

# Backend
cd /home/kdrkrst/git/github/entity-persistence-service
./start-with-env-file.sh dev.env
```

## Service URLs

- **Redis**: localhost:6379
- **OPA**: http://localhost:8181
- **Backend**: Check logs for port information

## Troubleshooting

### Redis Issues
If Redis doesn't start automatically:
```bash
redis-server --daemonize yes
```

### OPA Issues
Make sure the policies directory exists:
```bash
ls ~/git/github/entity-persistence-gateway-policies/policies
```

### Backend Issues
Check the backend logs:
```bash
tail -f /tmp/entity-persistence-gateway-logs/backend.log
```

## Log Files

All service logs are stored in `/tmp/entity-persistence-gateway-logs/`:
- OPA: `opa.log`
- Backend: `backend.log`

View all logs: `./dev.sh logs`