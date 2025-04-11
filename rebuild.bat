@echo on

echo "mvn install"
call mvn clean install
echo Maven exited with code %ERRORLEVEL%

echo "docker build"
docker build -t pierre-yves-monnet/waitforresult:1.1.0 .

echo "docker tag"
docker tag pierre-yves-monnet/waitforresult:1.1.0 ghcr.io/pierre-yves-monnet/waitforresult:latest

echo "docker push"
docker push ghcr.io/pierre-yves-monnet/waitforresult:latest
