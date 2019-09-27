// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.maintenance.disk;

import com.yahoo.vespa.hosted.node.admin.task.util.file.FileFinder;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Logs are cleaned up by normalizing their age (last modified timestamp) against their expected max age.
 * The final priorities are calculated by evenly splitting the normalized score between the given lowest and
 * the given highest priority.
 *
 * @author freva
 */
public class LogCleanupRule implements DiskCleanupRule {
    private final Supplier<List<FileFinder.FileAttributes>> lister;
    private final Function<FileFinder.FileAttributes, Priority> prioritizer;

    public LogCleanupRule(Supplier<List<FileFinder.FileAttributes>> lister,
                          Function<FileFinder.FileAttributes, Double> scorer, Priority lowest, Priority highest) {
        if (lowest.ordinal() > highest.ordinal())
            throw new IllegalArgumentException("Lowest priority: " + lowest + " is higher than highest priority: " + highest);

        this.lister = lister;
        int range = highest.ordinal() - lowest.ordinal();
        this.prioritizer = fa -> {
            int ordinal = (int) (lowest.ordinal() + scorer.apply(fa) * range);
            return Priority.values()[Math.max(lowest.ordinal(), Math.min(highest.ordinal(), ordinal))];
        };
    }

    @Override
    public Collection<PrioritizedFileAttributes> prioritize() {
        return lister.get().stream()
                .map(fa -> new PrioritizedFileAttributes(fa, prioritizer.apply(fa)))
                .collect(Collectors.toList());
    }
}
