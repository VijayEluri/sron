#!/usr/bin/env bash
# vim:et:sw=2:ts=2

if [ -d ext ]
then base=ext
else base=.
fi

if [[ "$1" == -p ]]
then pidfile="$2"; shift; shift
else pidfile=/tmp/scaleron-pid
fi

export CLASSPATH=$base/mina-core-1.1.2.jar:$base/slf4j-api-1.4.3.jar:$base/slf4j-simple-1.4.3.jar:classes:scaleron.jar

# it's important that we use exec, so that the java process actually takes the
# current pid
echo $$ > "$pidfile"
exec java -Xms500M -Xmx500M -ea "$@" edu.cmu.neuron2.RonTest
