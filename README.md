# README

## Logstsh

### Version

6.3.1

### cmd

    ./bin/logstash -f first-pipleline.conf

## Waterdrop

### Version

1.0.3

### cmd

    ./bin/start-waterdrop.sh --config application.conf --deploy-mode client --master 'local[12]'

## Spark

### Version

2.2.0

### cmd

    ../bin/spark-submit --class io.github.interestinglab.waterdrop.benchmark.SparkMain --master 'local[12]' benchmark-1.0-SNAPSHOT-jar-with-dependencies.jar --app-name benchmark --topic waterdrop_benchmark_input --target-topic waterdrop_benchmark_output --group-id waterdrop_benchmark --window-step 5000


