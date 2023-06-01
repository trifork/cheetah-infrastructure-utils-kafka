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

Configure the Claim in JWT to look for topic access by setting:
`cheetah.authorization.claim.name=<claimName>`

To use the Authorizer package the project using maven:

`mvn package`

Then

`docker build . -t my-kafka`

