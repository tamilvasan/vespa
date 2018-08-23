package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.log.LogLevel;
import com.yahoo.vespa.hosted.controller.deployment.LogEntry;
import com.yahoo.vespa.hosted.controller.deployment.Step;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.yahoo.vespa.hosted.controller.deployment.Step.deployReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deployTester;
import static org.junit.Assert.assertEquals;

/**
 * @author jonmv
 */
public class LogSerializerTest {

    private static final LogSerializer serializer = new LogSerializer();
    private static final Path logsFile = Paths.get("src/test/java/com/yahoo/vespa/hosted/controller/persistence/testdata/logs.json");

    @Test
    public void testSerialization() throws IOException {
        byte[] logJson = Files.readAllBytes(logsFile);

        LogEntry  first = new LogEntry(0, 0, LogLevel.INFO,     "First");
        LogEntry second = new LogEntry(1, 0, LogLevel.INFO,    "Second");
        LogEntry  third = new LogEntry(2, 1000, LogLevel.DEBUG,    "Third");
        LogEntry fourth = new LogEntry(3, 2000, LogLevel.WARNING, "Fourth");

        Map<Step, List<LogEntry>> expected = new HashMap<>();
        expected.put(deployReal, new ArrayList<>());
        expected.get(deployReal).add(third);
        expected.put(deployTester, new ArrayList<>());
        expected.get(deployTester).add(fourth);

        assertEquals(expected, serializer.fromJson(logJson, 1));

        expected.get(deployReal).add(0, first);
        expected.get(deployTester).add(0, second);
        assertEquals(expected, serializer.fromJson(logJson, -1));

        assertEquals(expected, serializer.fromJson(serializer.toJson(expected), -1));

        expected.get(deployReal).add(first);
        expected.get(deployReal).add(third);
        expected.get(deployTester).add(second);
        expected.get(deployTester).add(fourth);

        assertEquals(expected, serializer.fromJson(Arrays.asList(logJson, logJson), -1));
    }

}
