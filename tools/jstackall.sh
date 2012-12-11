#!/bin/bash

# kill org.voltdb processes that aren't BenchmarkController

# list all process pids and the name of the associated executable
# look for java executables
# use ps to see the command arguments for those java pids
# find args that look like an org.voltdb class main entry point
# that aren't benchmark controller
# and kill them.

# as one line:
# ps cx -o pid -o command  | grep java | awk '{print $1}' | xargs ps -w -w -o pid -o args -p | \
#  grep " org.voltdb" | grep -v BenchmarkController | awk '{print $1}'  | xargs kill -9

# a little more error friendly when no pids are found
voltdbpid=$(jps -l | grep -v "sun.tools.jps.Jps" | grep -v "org.apache.tools.ant.launch.Launcher" | grep -v "eclipse" | grep -v "slave.jar" | awk '{print $1}')
for victim in ${voltdbpid}
do
    jstack -F -l -m ${victim} > `hostname`-${victim}.jstack
    jstack -F -l ${victim} > `hostname`-${victim}.humanjstack
done
