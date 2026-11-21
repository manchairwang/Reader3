@echo off
cd /d "%~dp0"
echo 启动本地 CORS 代理 (端口 8727)...
python cors-proxy.py
pause
