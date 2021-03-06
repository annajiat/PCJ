/*
 * Copyright (c) 2011-2019, PCJ Library, Marek Nowicki
 * All rights reserved.
 *
 * Licensed under New BSD License (3-clause license).
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package org.pcj.internal;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.pcj.StartPoint;

/**
 * Class used for deploying PCJ when using one of deploy methods.
 *
 * @author Marek Nowicki (faramir@mat.umk.pl)
 */
public final class DeployPCJ {

    private static final Logger LOGGER = Logger.getLogger(DeployPCJ.class.getName());

    public static void deploy(Class<? extends StartPoint> startPoint,
                              NodeInfo node0,
                              NodeInfo currentJvm,
                              Collection<NodeInfo> allNodes,
                              Properties props) {

        List<String> jvmParams = getJvmParams();
        String properties = getPropertiesAsString(props);
        int allNodesThreadCount = allNodes.stream()
                                           .map(NodeInfo::getThreadIds)
                                           .mapToInt(Set::size)
                                           .sum();

        List<Process> processes = new ArrayList<>();
        try {
            for (NodeInfo node : allNodes) {
                if (Objects.equals(currentJvm, node)) {
                    continue;
                }

                List<String> jvmCommand = localJvmCommand(node, jvmParams, startPoint, node0, allNodesThreadCount, properties);

                List<String> command;
                if (node.isLocalAddress()) {
                    command = jvmCommand;
                } else {
                    command = remoteSshCommand(node, jvmCommand);
                }

                processes.add(exec(command));
            }

            if (currentJvm != null) {
                InternalPCJ.start(startPoint, node0, currentJvm, allNodesThreadCount);
            }

            waitForFinish(processes);
        } catch (InterruptedException | IOException ex) {
            LOGGER.log(Level.SEVERE, null, ex);
        }
    }


    private static List<String> getJvmParams() {
        String separator = System.getProperty("file.separator");
        String path = System.getProperty("java.home") + separator + "bin" + separator + "java";

        String classpath = System.getProperty("java.class.path");

        List<String> params = new ArrayList<>(Arrays.asList(path, "-cp", classpath));

        String[] skippedJvmArgs = {
                "-Xdebug",
                "-Xrunjdwp:transport=",
                "-agentpath:",
                "-agentlib:",
                "-javaagent:"
        };

        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        for (String jvmArgument : runtimeMXBean.getInputArguments()) {
            if (Arrays.stream(skippedJvmArgs).anyMatch(jvmArgument::startsWith)) {
                continue;
            }

            params.add(jvmArgument);
        }

        return Collections.unmodifiableList(params);
    }

    private static String getPropertiesAsString(Properties properties) {
        String propertiesString = "";
        try (StringWriter sw = new StringWriter()) {
            properties.store(sw, null);
            propertiesString = sw.toString();

            // remove comments from properties, eg. with timestamp
            propertiesString = propertiesString.replaceAll("^#.*\\R", "");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to write properties", e);
        }
        return propertiesString;
    }

    private static Process exec(List<String> exec) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(exec);

        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);

        LOGGER.log(Level.FINE, "Starting new process {0}", exec);

        return processBuilder.start();
    }

    private static void waitForFinish(List<Process> processes) throws InterruptedException {
        for (Process process : processes) {
            process.waitFor();
        }
    }

    private static List<String> localJvmCommand(NodeInfo node, List<String> jvmParams,
                                                Class<? extends StartPoint> startPoint,
                                                NodeInfo node0, long allNodesThreadCount,
                                                String propertiesString) {
        List<String> jvmExec = new ArrayList<>(jvmParams);

        jvmExec.addAll(Arrays.asList(
//                "-Dloader.main="+DeployPCJ.class.getName(),
//                "org.springframework.boot.loader.PropertiesLauncher",
                DeployPCJ.class.getName(),
                startPoint.getName(), // args[0]
                Integer.toString(node.getPort()), // args[1]
                node0.getHostname(), // args[2]
                Integer.toString(node0.getPort()), // args[3]
                Long.toString(allNodesThreadCount), // args[4]
                node.getThreadIds().toString(), // args[5]
                propertiesString // args[6]
        ));

        return Collections.unmodifiableList(jvmExec);
    }

    private static List<String> remoteSshCommand(NodeInfo node, List<String> jvmCommand) {
        StringBuilder sb = new StringBuilder();
        for (String arg : jvmCommand) {
            sb.append("'");
            sb.append(arg);
            sb.append("' ");
        }

        return new ArrayList<>(Arrays.asList(
                "ssh",
                "-o", "BatchMode=yes",
                node.getHostname(),
                "cd", System.getProperty("user.dir"), ";",
                sb.toString().trim()
        ));
    }

    public static void main(String[] args) throws ClassNotFoundException {
        String startPointStr = args[0];
        int localPort = Integer.parseInt(args[1]);
        String node0Str = args[2];
        int node0Port = Integer.parseInt(args[3]);
        int allNodesThreadCount = Integer.parseInt(args[4]);
        String threadIdsStr = args[5];
        Properties props = new Properties();
        try {
            if (args.length >= 7) {
                props.load(new StringReader(args[6]));
            } else {
                LOGGER.log(Level.FINE, "Properties not set");
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to parse properties", e);
        }

        InternalPCJ.setConfiguration(new Configuration(props));

        @SuppressWarnings("unchecked")
        Class<? extends StartPoint> startPoint = (Class<? extends StartPoint>) Class.forName(startPointStr);

        NodeInfo node0 = new NodeInfo(node0Str, node0Port);
        NodeInfo currentJvm = new NodeInfo("", localPort);

        String[] threadIds = threadIdsStr.substring(1, threadIdsStr.length() - 1).split(", ");
        Stream.of(threadIds).mapToInt(Integer::parseInt).forEach(currentJvm::addThreadId);

        LOGGER.log(Level.FINE, "Invoking InternalPCJ.start({0}, {1}, {2}, {3})",
                new Object[]{startPoint, node0, currentJvm, allNodesThreadCount});

        InternalPCJ.start(startPoint, node0, currentJvm, allNodesThreadCount);
    }
}
