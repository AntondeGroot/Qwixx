#!/bin/bash
# Run Qwixx Test Application with a persistent test game session

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "════════════════════════════════════════════════════════════════"
echo "  Building Qwixx Test Application"
echo "════════════════════════════════════════════════════════════════"

# Build the project
mvn clean package -q -DskipTests

echo ""
echo "════════════════════════════════════════════════════════════════"
echo "  Starting Qwixx Test Application"
echo "════════════════════════════════════════════════════════════════"
echo ""

# Run the test application
java -cp "target/classes:target/dependency/*" \
     org.springframework.boot.loader.JarLauncher \
     nl.adg.qwixx.QwixxTestApplication

# Alternative if using Maven:
# mvn spring-boot:run -Dspring-boot.run.main-class=nl.adg.qwixx.QwixxTestApplication
