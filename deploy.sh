#!/bin/bash
set -e

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

echo "🔨 Linting client..."
(cd client && npm run lint)

echo "🔨 Running client unit tests..."
(cd client && npx ng test --watch=false)

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