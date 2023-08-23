#!/bin/bash

TOPIC_NAME="test-topic"
KAFKA__URL=kafka:19092
TIMEOUT="60s"

echo "Waiting for Kafka to be ready..."
cub kafka-ready -b $KAFKA__URL 1 20 #expected_brokers timeout_seconds


echo "Creating topics with retention set to 1 day"
kafka-topics --create --if-not-exists --bootstrap-server $KAFKA__URL --partitions 1 --replication-factor 1 --topic $topic --config retention.ms=86400000
kafka-configs --bootstrap-server $KAFKA__URL --entity-type topics --entity-name $topic --alter --add-config retention.ms=86400000
echo "Creating topics done"

echo "Publishing some messages"
template=device%s:'{"deviceId":"%s","timestamp":"%s","value":%d}\n'
while [ $COUNTER -lt $COUNTER_STOP ]; do
   echo "Inserting message #$COUNTER into $TOPIC_NAME"
   json_string=$(printf "$template" "$COUNTER" "$COUNTER_STOP" "$(date -Ins)" "$RANDOM")
   echo $json_string | bin/kafka-console-producer.sh \
      --producer-property sasl.login.callback.handler.class=io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler \
      --producer-property sasl.mechanism="OAUTHBEARER" \
      --producer-property security.protocol=SASL_PLAINTEXT \
      --producer-property sasl.jaas.config="org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;" \
      --topic $TOPIC_NAME \
      --bootstrap-server $KAFKA__URL \
      --property value.serializer=custom.class.serialization.JsonSerializer \
      --property parse.key=true \
      --property key.separator=:
   ((COUNTER++))
done


output=$(timeout $TIMEOUT bin/kafka-console-consumer.sh \
   --consumer-property sasl.login.callback.handler.class=io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler \
   --consumer-property sasl.mechanism="OAUTHBEARER" \
   --consumer-property security.protocol=SASL_PLAINTEXT \
   --consumer-property sasl.jaas.config="org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;" \
   --bootstrap-server kafka:19092 \
   --topic $TOPIC_NAME\
   --from-beginning)

if [[ -n $output ]]; then
   echo "Message found"
   exit 0
else
   echo "Message not found after $TIMEOUT"
   exit 1
fi
