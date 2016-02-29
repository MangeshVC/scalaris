---
title: Benchmark Scalaris using YCSB
layout: default
root: ../..
excerpt: This blog post shows you how to benchmark Scalaris using the YCSB benchmark.
---

# {{ page.title }}

This blog post shows you how to benchmark Scalaris using the YCSB benchmark.  A
client has been created for Scalaris. The Scalaris client is not yet part of the
official YCSB bundle but a fork of YCSB with the necessary implementation is
available on GitHub.

## Compile

Check out the scalaris repository:

    git clone https://github.com/scalaris-team/scalaris.git

Build with Maven:

    cd scalaris/contrib/ycsb
    mvn package

## Maven

To use the YCSB scalaris-binding as dependency of an existing maven project,
add the following to your pom.xml:

{% highlight xml %}
<dependencies>
  <dependency>
    <groupId>com.yahoo.ycsb</groupId>
    <artifactId>scalaris-binding</artifactId>
    <version>[0.1.4,)</version>
  </dependency>
</dependencies>

<repositories>
  <repository>
    <id>scalaris</id>
    <url>https://scalaris-team.github.io/scalaris/maven</url>
  </repository>
</repositories>
{% endhighlight %}

## Run

    ./bin/ycsb load scalaris -P workloads/workloada
    ./bin/ycsb run scalaris -P workloads/workloada

## Troubleshooting

If you're experiencing problems, first make sure Scalaris is running. You can
pass configuration parameters to YCBS. For instance, if your first Scalaris node
is node1@localhost, the configuration looks as follows:

    ./bin/ycsb load scalaris -P workloads/workloada -p scalaris.node="node1@localhost"

The following parameters with their default values are available:

    scalaris.node = "node1@localhost"
    scalaris.name = "YCSB"
    scalaris.cookie = "chocolate chip cookie"
