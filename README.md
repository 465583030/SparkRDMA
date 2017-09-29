# SparkRDMA ShuffleManager Plugin
SparkRDMA is a high performance ShuffleManager plugin for Apache Spark that uses RDMA (instead of TCP) when
performing Shuffle data transfers in Spark jobs.

This open-source project is developed, maintained and supported by [Mellanox Technologies](http://www.mellanox.com).

## Performance results
Example performance speedup for HiBench TeraSort:
![Alt text](https://user-images.githubusercontent.com/20062725/28947340-30d45c6a-7864-11e7-96ea-ca3cf505ce7a.png)

Running TeraSort with SparkRDMA is x1.41 faster than standard Spark (runtime in seconds)

Testbed:

175GB Workload

15 Workers, 2x Intel Xeon E5-2697 v3 @ 2.60GHz, 28 cores per Worker, 256GB RAM, non-flash storage (HDD)

Mellanox ConnectX-4 network adapter with 100GbE RoCE fabric, connected with a Mellanox Spectrum switch

## Wiki pages
For more information on configuration, performance tuning and troubleshooting, please visit the [SparkRDMA GitHub Wiki](https://github.com/Mellanox/SparkRDMA/wiki)

## Runtime requirements
* Apache Spark 2.0.0/2.1.0/2.2.0
* Java 8
* libdisni 1.3
* An RDMA-supported network, e.g. RoCE or Infiniband

## Build

Building the SparkRDMA plugin requires [Apache Maven](http://maven.apache.org/) and Java 8

1. Obtain a clone of [SparkRDMA](https://github.com/Mellanox/SparkRDMA)

2. Build the plugin for your Spark version (either 2.0.0, 2.1.0 or 2.2.0), e.g. for Spark 2.0.0:
```
mvn -DskipTests clean package -Pspark-2.0.0
```

3. Obtain a clone of [DiSNI](https://github.com/zrlio/disni) for building libdisni:

```
git clone https://github.com/zrlio/disni.git
cd disni
git checkout tags/v1.3 -b v1.3
```

4. Compile and install only libdisni (the jars are already included in the SparkRDMA plugin):

```
cd libdisni
autoprepare.sh
./configure --with-jdk=/path/to/java8/jdk
make
make install
```
5. libdisni.so **must** be installed on every Spark Master and Worker (usually in /usr/lib)

## Configuration

Provide Spark the location of the SparkRDMA plugin jars by using the extraClassPath option.  For standalone mode this can
be added to either spark-defaults.conf or any runtime configuration file.  For client mode this **must** be added to spark-defaults.conf. For Spark 2.0.0 (Replace with 2.1.0 or 2.2.0 according to your Spark version):
```
spark.driver.extraClassPath   /path/to/SparkRDMA/target/spark-rdma-1.0-for-spark-2.0.0-jar-with-dependencies.jar
spark.executor.extraClassPath /path/to/SparkRDMA/target/spark-rdma-1.0-for-spark-2.0.0-jar-with-dependencies.jar
```

## Running

To enable the SparkRDMA Shuffle Manager plugin, add the following line to either spark-defaults.conf or any runtime configuration file:

```
spark.shuffle.manager   org.apache.spark.shuffle.rdma.RdmaShuffleManager
```

## Community discussions and support

For any questions, issues or suggestions, please use our Google group:
https://groups.google.com/forum/#!forum/sparkrdma

## Contributions

Any PR submissions are welcome
