#!/bin/bash
# Run Qwixx Test Application with a persistent test game session

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT/server"

echo "════════════════════════════════════════════════════════════════"
echo "  Qwixx Test Application - Persistent Test Game"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Build and run
mvn spring-boot:run -Dspring-boot.run.main-class=nl.adg.qwixx.testapp.QwixxTestApplication
