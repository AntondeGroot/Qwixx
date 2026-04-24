#!/bin/bash
set -e

echo "🔨 Building frontend and running all tests..."
mvn clean verify -P frontend --file server/pom.xml

echo "📦 Uploading..."
scp -i ~/.ssh/pi_deploy_key server/target/server-1.0-SNAPSHOT.jar my-pi:/home/ubuntu/qwixx.jar

echo "📁 Installing..."
ssh -i ~/.ssh/pi_deploy_key my-pi "sudo mkdir -p /opt/qwixx && sudo mv /home/ubuntu/qwixx.jar /opt/qwixx/qwixx.jar"

echo "⚙️  Ensuring systemd service exists..."
ssh -i ~/.ssh/pi_deploy_key my-pi "
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
ssh -i ~/.ssh/pi_deploy_key my-pi "
if [ ! -f /opt/qwixx/application-override.yaml ]; then
  sudo tee /opt/qwixx/application-override.yaml > /dev/null << 'EOF'
# no context-path: Qwixx serves at root, nginx strips /qwixx/ prefix before forwarding
EOF
fi"

echo "🔄 Restarting..."
ssh -i ~/.ssh/pi_deploy_key my-pi "sudo systemctl restart qwixx"

echo "✅ Done."