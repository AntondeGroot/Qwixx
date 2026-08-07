#!/bin/bash
set -e
# Resolve the repo root from this script's location (it lives in scripts/) so the relative paths
# below work no matter where the script is invoked from.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

if [ -n "$1" ]; then
  TARGET="$1"
elif ssh -o ConnectTimeout=3 -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectionAttempts=1 my-pi true 2>/dev/null; then
  TARGET=my-pi
else
  echo "⚠️  my-pi unreachable, falling back to my-pi-ext (Cloudflare Tunnel)..."
  TARGET=my-pi-ext
fi
SSH="ssh -i ~/.ssh/pi_deploy_key $TARGET"
SCP="scp -i ~/.ssh/pi_deploy_key"

# Kill leftover test processes from a previous (possibly crashed) run: Selenium's
# chromedriver + its "Chrome for Testing" browser, and any stale ng serve on the
# Playwright port. Scoped to test-only names so a normal Chrome is never touched.
echo "🧹 Killing leftover test drivers/servers..."
pkill -f chromedriver 2>/dev/null || true
pkill -f "Chrome for Testing" 2>/dev/null || true
port_pids=$(lsof -ti:5300 2>/dev/null || true); [ -n "$port_pids" ] && kill -9 $port_pids 2>/dev/null || true
# Reap the unique per-instance profile dirs these tests create (chrome-user-data-<nanoTime>).
rm -rf /tmp/chrome-user-data-* 2>/dev/null || true

echo "🔨 Linting client..."
(cd client && npm run lint)

echo "🔨 Running client unit tests..."
(cd client && npx ng test --watch=false)

echo "🔨 Running client e2e (Playwright)..."
(cd client && npx playwright install chromium >/dev/null && npm run e2e)

echo "🔨 Building frontend and running all tests..."
mvn clean verify -P frontend --file server/pom.xml

echo "📦 Uploading..."
$SCP server/target/server-1.0-SNAPSHOT.jar $TARGET:/home/ubuntu/qwixx.jar

echo "📁 Installing..."
$SSH "sudo mkdir -p /opt/qwixx && sudo mv /home/ubuntu/qwixx.jar /opt/qwixx/qwixx.jar"

echo "⚙️  Ensuring systemd service exists..."
$SSH "
if [ ! -f /etc/systemd/system/qwixx.service ]; then
  sudo tee /etc/systemd/system/qwixx.service > /dev/null << 'EOF'
[Unit]
Description=Qwixx game server
After=network.target

[Service]
User=ubuntu
ExecStart=/usr/bin/java -jar /opt/qwixx/qwixx.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
  sudo systemctl daemon-reload
  sudo systemctl enable qwixx
fi"

echo "⚙️  Ensuring application override config exists..."
$SSH "
if [ ! -f /opt/qwixx/application-override.yaml ]; then
  sudo tee /opt/qwixx/application-override.yaml > /dev/null << 'EOF'
# no context-path: Qwixx serves at root, nginx strips /qwixx/ prefix before forwarding
EOF
fi"

echo "🔄 Restarting..."
$SSH "sudo systemctl restart qwixx"

echo "✅ Done."