@echo off
powershell -NoProfile -Command "try { $t = New-Object System.Net.Sockets.TcpClient 'localhost', 10096; $t.Close(); echo SUCCESS: Port 10096 is OPEN } catch { echo FAILED: Port 10096 is CLOSED or unreachable }"
