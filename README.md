# C8_waitforresult_loadtest

# Create the JAR file
```
mvn clean install
```

# Create the docker image

````
docker rmi pierre-yves-monnet/waitforresult:1.0.0
docker rmi ghcr.io/pierre-yves-monnet/waitforresult:1.0.0

docker build -t pierre-yves-monnet/waitforresult:1.1.0 .
````

The docker image is built using the Dockerfile present on the root level.



Push the image to my personal ghcr

````
docker tag pierre-yves-monnet/waitforresult:1.1.0 ghcr.io/pierre-yves-monnet/waitforresult:latest
docker push ghcr.io/pierre-yves-monnet/waitforresult:latest
````

Check on
https://github.com/pierre-yves-monnet/waitforresult/pkgs/container

# Start the test

```shell
cd kubernetes
kubectl create -f waitforresult.yaml
```


