package com.coder.toolbox.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test-only command that forks a child and can exit before that child.
 */
public final class ProcessRunnerTestCommand {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) {
            Thread.sleep(30_000);
            return;
        }
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(List.of(
                java, "-cp", System.getProperty("java.class.path"), ProcessRunnerTestCommand.class.getName(), "child"
        )).inheritIO();
        if (args[0].equals("detached-output")) {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        Process child = builder.start();
        Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + " " + child.pid());
        System.out.println("ready");
        if (args[0].equals("keep-parent")) {
            Thread.sleep(30_000);
        } else {
            // The test releases the parent only after the output reader has seen its readiness line.
            while (!Files.exists(Path.of(args[2]))) Thread.sleep(10);
        }
    }
}
