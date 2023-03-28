# Cheetah Kafka Authorizer

`CheetahKafkaAuthorizer` is used for claim based access to Kafka.
Access is expressed though claims in a JWT with the following patter:

```json
{
  "iat": 1679325503,
  "sub": "a252a70e-363e-42aa-a62b-6f7f54d4c8bd",
  ...
  "topics": [
    {
      "pattern": "MyKafkaTopic",
      "operation": "all"
    },
    {
      "pattern": "ReadAccessTopic",
      "operation": "read"
    }
  ]
}
```

To use the Authorizer package the project using maven:

`mvn package`

Then

`docker build . -t my-kafka`
