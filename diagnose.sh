#!/bin/bash
SSH="ssh -i ~/.ssh/pi_deploy_key my-pi"

echo "=== systemd service status ==="
$SSH "systemctl status qwixx --no-pager" 2>&1 || true

echo ""
echo "=== last 50 log lines ==="
$SSH "journalctl -u qwixx -n 50 --no-pager" 2>&1 || true

echo ""
echo "=== jar file ==="
$SSH "ls -lh /opt/qwixx/qwixx.jar" 2>&1 || true

echo ""
echo "=== override config ==="
$SSH "cat /opt/qwixx/application-override.yaml" 2>&1 || true

echo ""
echo "=== java version ==="
$SSH "java -version" 2>&1 || true

echo ""
echo "=== all listening ports and programs ==="
$SSH "sudo ss -tlnp" 2>&1 || true

echo ""
echo "=== disk space ==="
$SSH "df -h /opt" 2>&1 || true

echo ""
echo "=== memory ==="
$SSH "free -h" 2>&1 || true