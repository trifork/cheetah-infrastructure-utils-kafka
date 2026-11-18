# Migration from Zookeeper to KRaft

## Overview

The goal of this guide is to migrate your Kafka cluster from Zookeeper to KRaft.

## Guide References

- https://strimzi.io/docs/operators/0.45.0/deploying#proc-migrating-clusters-node-pools-str
- https://strimzi.io/blog/2024/03/22/strimzi-kraft-migration/

## Prerequisites

- Kafka version **3.9.0**
- Strimzi-Kafka-Operator version **0.45.0**
- cheetah-infrastructure-utils-kafka image version/tag **strimzi-0.45.0-kafka-3.9.0-trifork-1.8.0**

## Notes for migration

- This migration may take a long time (like 20 minutes) to be completed.

## Steps

1. Migrate existing Kafka brokers to use KafkaNodePool by creating a new KafkaNodePool resource with broker role. Set the replica count and storage configuration to match your current Kafka cluster.

   ```yaml
   apiVersion: kafka.strimzi.io/v1beta2
   kind: KafkaNodePool
   metadata:
     name: kafka
     namespace: <name-of-kafka-namespace>
     labels:
       strimzi.io/cluster: <name-of-kafka-resource>
   spec:
     replicas: 3
     roles:
       - broker
     storage:
       # Value from the current Kafka.spec.kafka.storage
       #
       # For instance:
       #
       # type: jbod
       # volumes:
       #    - id: 0
       #      type: persistent-claim
       #      size: 10Gi
       #      deleteClaim: false
   ```

   Note that the namespace, label, and storage depends on the current kafka setup.

2. Update annotations for the Kafka resource to enable use of the newly created KafkaNodePool

   ```yaml
   metadata:
     name: <name-of-kafka-resource>
     namespace: <name-of-kafka-namespace>
     annotations:
       strimzi.io/node-pools: enabled # Add this line
   ```

   When the KafkaNodePool resource is in use, you can remove the properties `.spec.kafka.replicas` and `.spec.kafka.storage` from the Kafka resource.
   To check if the node pool is in use, run the following command

   ```bash
   kubectl get knp -n [namespace]
   ```

   Check if ROLES and NODEIDS have been assigned.

   ```text
   NAME              DESIRED REPLICAS          ROLES                  NODEIDS
   kafka             3                         ["broker"]             [0,1,2]
   ```

3. Prepare the cluster for migration by updating annotations for the Kafka resource.

   ```yaml
   metadata:
     name: <name-of-kafka-resource>
     namespace: <name-of-kafka-namespace>
     annotations:
       strimzi.io/kraft: disabled # Add this line
   ```

   Create a KafkaNodePool with controller role. Set the replica count and storage configuration to match your current Kafka cluster.

   ```yaml
   apiVersion: kafka.strimzi.io/v1beta2
   kind: KafkaNodePool
   metadata:
     name: controller
     namespace: <name-of-kafka-namespace>
     labels:
       strimzi.io/cluster: <name-of-kafka-resource>
   spec:
     replicas: 3
     roles:
       - controller
     storage:
       type: jbod
       volumes:
         - id: 0
           type: persistent-claim
           size: 10Gi
           deleteClaim: false
   ```

4. Update strimzi.io/kraft annotation to enable migration.

   ```yaml
   metadata:
     name: <name-of-kafka-resource>
     namespace: <name-of-kafka-namespace>
     annotations:
       strimzi.io/node-pools: enabled
       strimzi.io/kraft: migration # Changed from disabled to migration
   ```

   Thit will start the migration process. To follow along with the process you can run the following command

   ```bash
   kubectl get kafka <name-of-kafka-resource> -n <name-of-kafka-namespace> -w
   ```

5. When the Metadata State on the Kafka resource changes to KRaftDualWriting, update the authorizer class reference to CheetahKRaftAuthorizer.

   Note: At this point, one of the Kafka brokers will be in a state of Error/CrashLoopBackOff. This will be fixed by updating the AuthorizerClass, though it might take some time.

   ```yaml
   spec:
     kafka:
       authorization:
         authorizerClass: com.trifork.cheetah.CheetahKRaftAuthorizer # Changed from com.trifork.cheetah.CheetahKafkaAuthorizer
       config:
         authorizer.class.name: com.trifork.cheetah.CheetahKRaftAuthorizer # Changed from com.trifork.cheetah.CheetahKafkaAuthorizer
   ```

6. Remove the inter.broker.protocol.version and log.message.format.version parameters from the .spec.kafka.config section if present.

   ```yaml
   spec:
     kafka:
       config:
         inter.broker.protocol.version: "3.9" # Remove this line if it´s present
         log.message.format.version: "3.9"    # Remove this line if it´s present
   ```

7. When the Metadata State changes to KRaftPostMigration, update the strimzi.io/kraft annotation to finalize the migration.

   ```yaml
   metadata:
     name: <name-of-kafka-resource>
     namespace: <name-of-kafka-namespace>
     annotations:
       strimzi.io/node-pools: enabled
       strimzi.io/kraft: enabled # Changed from migration to enabled
   ```

   When the migration is finished, the Metadata State will change to KRaft. You can now delete the .spec.zookeeper section from the Kafka custom resource.

## Testing done (by Trifork)

Cluster testing

Ensure that existing data persist through migration. Ensure that the job is running without any issues. Run a job using a continuous input source.
