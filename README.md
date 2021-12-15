# akka-kafka-mock-bug

This repository contains code to reproduce a weird bug/behavior in Akka Stream Kafka (Alpakka Kafka).

The code in `Test.scala`:

1. Creates a mock consumer and producer (Kafka's own `MockConsumer` & `MockProducer`)
2. Creates a simple multi-partition flow that consumes data using the `MockConsumer`, does some simple processing and writes the data back to the `MockProducer`
3. Runs the flow then terminates it gracefully after the message has been successfully polled by Alpakka Kafka
4. Asserts that the producer has received a processed message

The mock producer setup involves:
1. Rebalancing on the first poll
2. Emitting a message on the second poll
3. Draining & shutting down the stream on the third poll

This flow works, most of the time, but will likely fail when the machine is low on available resources (specifically CPU).
On my laptop it consistently fails (= no message received by the producer) when I set the CPU frequency to the lowest possible.

Setting the CPU frequency involves running

```shell
sudo cpupower frequency-info
# Note the possible frequencies under "current policy: frequency should be within ... and ..."
sudo cpupower frequency-set -u 400 # 400Mhz is the lowest I can go
```

Run with `./gradlew run`.