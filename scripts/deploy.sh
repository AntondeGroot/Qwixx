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

# The client's lint/test/e2e steps below read src/generated, which is gitignored and therefore only
# as fresh as the last local generate. The Maven build regenerates it too, but not until after those
# steps have already run — so a spec change would fail them against a stale tree. Lint the spec
# first: a structurally broken spec makes codegen emit wrong types rather than fail outright.
echo "🔨 Linting OpenAPI spec and regenerating the client API..."
(cd client && npm run lint:openapi && npm run generate)

echo "🔨 Linting client..."
(cd client && npm run lint)

echo "🔨 Running client unit tests..."
(cd client && npx ng test --watch=false)

echo "🔨 Running client e2e (Playwright)..."
(cd client && npx playwright install chromium >/dev/null && npm run e2e)

echo "🔨 Building frontend and running all tests..."
mvn clean verify -P frontend --file server/pom.xml

# Tomcat itself is provisioned by the GameRoom project (setup-tomcat.sh); this script only drops
# the WAR in. qwixx.war is served at /qwixx, the prefix the client is built for.
WEBAPPS=/var/lib/tomcat10/webapps
TOMCAT_URL=http://127.0.0.1:8090
LOG_FILE=/var/log/tomcat10/qwixx.log

if ! $SSH "test -d $WEBAPPS"; then
  echo "❌ No Tomcat on $TARGET. Run ./setup-tomcat.sh in the GameRoom project first."
  exit 1
fi

echo "⚙️  Ensuring application override config exists..."
# Tomcat's unit only lets the JVM write under /var/log/tomcat10, so the log lives there.
$SSH "
sudo mkdir -p /opt/qwixx
if [ ! -f /opt/qwixx/application-override.yaml ]; then
  sudo tee /opt/qwixx/application-override.yaml > /dev/null << 'EOF'
# Activates the rotation caps packaged in application.properties (10MB/file, 100MB total).
logging:
  file:
    name: $LOG_FILE
EOF
fi
# Migrate an override written for the old standalone service: its log dir isn't writable from Tomcat.
sudo sed -i -e 's|/opt/qwixx/logs/qwixx.log|$LOG_FILE|' -e '/nginx strips \/qwixx\/ prefix/d' \
  /opt/qwixx/application-override.yaml"

echo "📦 Uploading..."
$SCP server/target/qwixx.war $TARGET:/home/ubuntu/qwixx.war

echo "🚀 Deploying to Tomcat..."
# Copy under a name Tomcat ignores, then rename: a same-filesystem mv is atomic, so Tomcat never
# picks up a half-written WAR. Its autodeployer notices the new file and redeploys the app.
$SSH "sudo install -o tomcat -g tomcat -m 644 /home/ubuntu/qwixx.war $WEBAPPS/.qwixx.war.tmp \
  && sudo mv $WEBAPPS/.qwixx.war.tmp $WEBAPPS/qwixx.war && rm /home/ubuntu/qwixx.war"

# The exploded dir is recreated on redeploy, so 'newer than the WAR' rules out the old version
# still answering; then wait until Spring has started and serves the app.
$SSH "for i in \$(seq 1 90); do
  if [ $WEBAPPS/qwixx -nt $WEBAPPS/qwixx.war ] && curl -sf -o /dev/null $TOMCAT_URL/qwixx/; then exit 0; fi
  sleep 2
done
echo '❌ Qwixx did not come up in Tomcat. See $LOG_FILE and journalctl -u tomcat10.'
exit 1"

echo "🧹 Retiring the standalone service..."
# One-time migration. Only once nginx forwards /qwixx/ to Tomcat (GameRoom's nginx.conf); stopping
# it earlier would take the game offline.
$SSH "
if [ -f /etc/systemd/system/qwixx.service ]; then
  if grep -q '127.0.0.1:8090/qwixx/' /etc/nginx/sites-enabled/gameroom; then
    sudo systemctl disable --now qwixx
    sudo rm /etc/systemd/system/qwixx.service /opt/qwixx/qwixx.jar
    sudo rm -rf /opt/qwixx/logs
    sudo systemctl daemon-reload
  else
    echo '⚠️  nginx still forwards /qwixx/ to the standalone service; leaving it running.'
    echo '   Deploy the GameRoom nginx.conf, then run this script again to retire it.'
  fi
fi"

echo "✅ Done."
