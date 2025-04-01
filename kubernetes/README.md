# Introduction
This directory contains a help deployment to deploy Blueberry application


# Deployment
Check the deployment file, and adapt it.


## Connection to Zeebe
The deployment must be run in the same cluster as Zeebe. If it is a different cluster, then the zeebe connection must be adapted

# Operation

```shell
kubectl create -f blueberry.yaml
```

Forward the Blueberry access 

```shell
kubectl port-forward svc/blueberry-service 9082:9082 -n camunda
```

# Remove Blueberry

```shell
kubectl delete -f blueberry.yaml
```

