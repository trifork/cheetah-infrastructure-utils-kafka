#!/bin/bash
set -euo pipefail

#echo "Waiting for Kafka to be ready..."
#cub kafka-ready -b "$KAFKA__URL" 1 20 #expected_brokers timeout_seconds

echo "Creating topics with retention set to 1 day"
bin/kafka-topics.sh --create --if-not-exists --bootstrap-server "kafka:19093" --partitions 1 --replication-factor 1 --topic "$TOPIC_NAME" --config retention.ms=86400000
bin/kafka-configs.sh --bootstrap-server "kafka:19093" --entity-type topics --entity-name "$TOPIC_NAME" --alter --add-config retention.ms=86400000
echo "Creating topics done"

echo "Publishing $COUNTER_STOP message(s)"
template=device%s:'{"deviceId":"%s","timestamp":"%s","value":%d}\n'
while [ "$COUNTER" -lt "$COUNTER_STOP" ]; do
   echo "Inserting message #$COUNTER into $TOPIC_NAME"
   json_string=$(printf "$template" "$COUNTER" "$COUNTER_STOP" "$(date -Ins)" "$RANDOM")
   echo "Inserting message $json_string into $TOPIC_NAME"
   echo "$json_string" | bin/kafka-console-producer.sh \
      --producer-property sasl.login.callback.handler.class=io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler \
      --producer-property sasl.mechanism="OAUTHBEARER" \
      --producer-property security.protocol=SASL_PLAINTEXT \
      --producer-property sasl.jaas.config="org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;" \
      --topic "$TOPIC_NAME" \
      --bootstrap-server "$KAFKA__URL" \
      --property value.serializer=custom.class.serialization.JsonSerializer \
      --property parse.key=true \
      --property key.separator=: \
      --max-block-ms 10000
      #--sync \
      #--request-required-acks 1 \
      #--request-timeout-ms 120 \
      #--message-send-max-retries 0
   let ++COUNTER # ++ must come first, otherwise let exits with code 1
done

# Idempotent producers (the default) send InitProducerId; without IDEMPOTENT_WRITE Kafka falls back to
# authorizeByResourceType(WRITE, TOPIC). The console producer exits 0 even on failure, so check its output.
produce_as() {
   echo "$1" | OAUTH_CLIENT_ID="$1" OAUTH_CLIENT_SECRET="$1-secret" bin/kafka-console-producer.sh \
      --command-property sasl.login.callback.handler.class=io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler \
      --command-property sasl.mechanism="OAUTHBEARER" \
      --command-property security.protocol=SASL_PLAINTEXT \
      --command-property sasl.jaas.config="org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;" \
      --topic "$TOPIC_NAME" \
      --bootstrap-server "$KAFKA__URL" \
      --max-block-ms 10000 2>&1 || true
}

echo "Producing as writer (Kafka_*_write)"
if produce_as default-write | grep -q "Exception"; then
   echo "Writer was not allowed to produce"
   exit 1
fi

echo "Producing as reader (Kafka_*_read), expecting a clean authorization error"
if ! produce_as default-read | grep -q "ClusterAuthorizationException"; then
   echo "Reader did not get ClusterAuthorizationException"
   exit 1
fi

echo "Consuming some messages"
output=$(timeout "$TIMEOUT" bin/kafka-console-consumer.sh \
   --consumer-property sasl.login.callback.handler.class=io.strimzi.kafka.oauth.client.JaasClientOauthLoginCallbackHandler \
   --consumer-property sasl.mechanism="OAUTHBEARER" \
   --consumer-property security.protocol=SASL_PLAINTEXT \
   --consumer-property sasl.jaas.config="org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;" \
   --bootstrap-server "$KAFKA__URL" \
   --topic "$TOPIC_NAME" \
   --max-messages "$COUNTER_STOP" \
   --from-beginning)

if [[ -n $output ]]; then
   echo "Messages found"
   exit 0
else
   echo "Messages not found after $TIMEOUT"
   exit 1
fi
