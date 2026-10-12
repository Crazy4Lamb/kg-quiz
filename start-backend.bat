@echo off
chcp 65001 >nul
setlocal

rem 默认连本机 MySQL（127.0.0.1:3306, root/root）
rem 传参 dev 则切换内网 dev 库（192.168.0.69:23306）
if "%MYSQL_HOST%"=="" set MYSQL_HOST=127.0.0.1
if "%MYSQL_PORT%"=="" set MYSQL_PORT=3306
if "%MYSQL_USER%"=="" set MYSQL_USER=root
if "%MYSQL_PASSWORD%"=="" set MYSQL_PASSWORD=root

rem embedding 模型用绝对路径，避免受工作目录影响
if "%KG_EMBEDDING_MODEL%"=="" set KG_EMBEDDING_MODEL=%~dp0models\bge-small-zh-v1.5.onnx
if "%KG_EMBEDDING_TOKENIZER%"=="" set KG_EMBEDDING_TOKENIZER=%~dp0models\tokenizer.json

set PROFILE=
if /I "%~1"=="dev" set PROFILE=-Dspring-boot.run.profiles=dev

set JAVA_HOME=C:\Users\Mayn\.jdks\ms-17.0.17
set MVN=C:\Users\Mayn\.m2\wrapper\dists\apache-maven-3.9.10\a38810a491b03367137adfdfbe7d14c4\bin\mvn.cmd

cd /d "%~dp0backend"
echo Starting backend on port 8090 ^(MySQL %MYSQL_HOST%:%MYSQL_PORT%, user=%MYSQL_USER%^) ...
call "%MVN%" -B spring-boot:run %PROFILE%
