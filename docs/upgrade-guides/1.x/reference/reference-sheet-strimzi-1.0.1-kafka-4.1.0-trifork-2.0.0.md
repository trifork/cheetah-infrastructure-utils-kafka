# Reference Sheet

Supporting material for the [strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0](../strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0.md) upgrade guide.

## Reference for crd-upgrade-job for step 8

```yaml
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: strimzi-v1-api-conversion
  namespace: kafka
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: strimzi-v1-api-conversion
rules:
  - apiGroups: ["kafka.strimzi.io"]
    resources: [kafkas, kafkanodepools, kafkaconnects, kafkaconnectors,
                kafkabridges, kafkamirrormaker2s, kafkarebalances, kafkatopics, kafkausers]
    verbs: ["get", "list", "patch", "update"]
  - apiGroups: ["core.strimzi.io"]
    resources: [strimzipodsets]
    verbs: ["get", "list", "patch", "update"]
  - apiGroups: ["apiextensions.k8s.io"]
    resources: [customresourcedefinitions, customresourcedefinitions/status]
    resourceNames:
      - kafkabridges.kafka.strimzi.io
      - kafkaconnectors.kafka.strimzi.io
      - kafkaconnects.kafka.strimzi.io
      - kafkamirrormaker2s.kafka.strimzi.io
      - kafkanodepools.kafka.strimzi.io
      - kafkarebalances.kafka.strimzi.io
      - kafkas.kafka.strimzi.io
      - kafkatopics.kafka.strimzi.io
      - kafkausers.kafka.strimzi.io
      - strimzipodsets.core.strimzi.io
    verbs: ["get", "list", "patch", "update"]
  - apiGroups: [""]
    resources: [configmaps]
    verbs: ["get", "list", "create", "patch", "update"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: strimzi-v1-api-conversion
subjects:
  - kind: ServiceAccount
    name: strimzi-v1-api-conversion
    namespace: kafka
roleRef:
  kind: ClusterRole
  name: strimzi-v1-api-conversion
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: batch/v1
kind: Job
metadata:
  name: strimzi-v1-api-conversion
  namespace: kafka
spec:
  backoffLimit: 0
  template:
    metadata:
      labels:
        app: strimzi-v1-api-conversion
    spec:
      serviceAccountName: strimzi-v1-api-conversion
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
        runAsUser: 1001
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: strimzi-v1-api-conversion
          image: quay.io/strimzi/operator:0.51.0
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
          command:
            - /opt/v1-api-conversion/bin/v1-api-conversion.sh
            - crd-upgrade
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: strimzi-v1-api-conversion-egress
  namespace: kafka
spec:
  podSelector:
    matchLabels:
      app: strimzi-v1-api-conversion
  policyTypes: [Egress]
  egress:
    - to:
        - ipBlock: { cidr: <API_CLUSTER_IP>/32 }
        - ipBlock: { cidr: <API_ENDPOINT_IP>/32 }
      ports:
        - protocol: TCP
          port: 443
```

## Reference for verification check for step 11

PowerShell:

```powershell
# No CRD still lists v1beta2 as stored or served
foreach ($c in @("kafkas","kafkanodepools","kafkatopics","kafkausers","strimzipodsets.core")) {
  $full = if ($c -like "*.core") { "$($c.Split('.')[0]).core.strimzi.io" } else { "$c.kafka.strimzi.io" }
  kubectl get crd $full -o "jsonpath={.metadata.name}{' served=['}{.spec.versions[*].name}{'] storage='}{.spec.versions[?(@.storage==true)].name}{' stored='}{.status.storedVersions}{'\n'}"
}

# Kafka CR is Ready on the new operator version
kubectl -n <ns> get kafka <name> -o "jsonpath=Ready={.status.conditions[?(@.type=='Ready')].status} op={.status.operatorLastSuccessfulVersion} gen={.metadata.generation}/{.status.observedGeneration}{'\n'}"

# No managedFields still reference v1beta2 (should print nothing)
foreach ($kind in @("kafka","kafkanodepool","kafkatopic","kafkauser","strimzipodset")) {
  kubectl get $kind -A -o "jsonpath={range .items[?(@.metadata.managedFields[*].apiVersion=='kafka.strimzi.io/v1beta2')]}{.metadata.namespace}/{.metadata.name}{'\n'}{end}"
}
```
