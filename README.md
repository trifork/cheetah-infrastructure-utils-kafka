# Cheetah Kafka Authorizer

`CheetahKafkaAuthorizer` is used for claim based access to Kafka.
Access is expressed through claims in a JWT with the following pattern:

```json
{
  "iat": 1679325503,
  "sub": "a252a70e-363e-42aa-a62b-6f7f54d4c8bd",
  ...
  "topics": "*_all, MyTopic_all, YourTopic_read"
}
```

# Deployment

Configurations:

* `cheetah.authorization.claim.name=<claimName>` (optional - default: `topics`) - Name of claim in JWT to look for topic access
* `cheetah.authorization.prefix=<prefix>` (optional - default: `""`) - Prefix before the topic name in the claims (i.e. `Kafka_MyTopicName_Read` )
* `cheetah.authorization.claim.is-list=true`(optional - default: `false`) - Set to `true` if the structure of the claim is a list, instead of a comma seperated string
* `cheetah.authorization.readonly.superusers` (optional - default: `""`) - List of Principals to bypass the authorizer and gain read and describe access to everything.

# Development

To use the Authorizer, package the project using maven:

`mvn package`

Then

`docker build . -t my-kafka`
