package org.ccg.mpp.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.ccg.mpp.config.Globals;
import org.ccg.mpp.ex.InvalidFilePathException;

public final class ProjectFilePath implements Comparable<ProjectFilePath> {
    private static final String SEPARATOR = "/";
    private static final String CURRENT = ".";
    private static final String PARENT = "..";
    private static final String FORBIDDEN_CHARACTERS = "/\\:*?\"<>|%#";
    private static final char FIRST_PRINTABLE = ' ';
    private static final char LAST_PRINTABLE = '~';
    private static final int RAW_LENGTH_FACTOR = 4;
    private static final Set<String> DEVICE_NAMES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
            "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8",
            "lpt9");
    private static final ProjectFilePath ROOT = new ProjectFilePath(List.of());

    private final List<String> segments;
    private final String text;

    private ProjectFilePath(List<String> segments) {
        this.segments = List.copyOf(segments);
        this.text = SEPARATOR + String.join(SEPARATOR, segments);
    }

    public static ProjectFilePath root() {
        return ROOT;
    }

    public static ProjectFilePath parse(String raw) throws InvalidFilePathException {
        if (raw == null) {
            throw new InvalidFilePathException("A file path is required");
        }
        if (raw.length() > Globals.FILE_PATH_MAX_CHARS * RAW_LENGTH_FACTOR) {
            throw new InvalidFilePathException(
                    "The path is longer than " + Globals.FILE_PATH_MAX_CHARS + " characters");
        }
        final var segments = new ArrayList<String>();
        for (final var segment : raw.split(SEPARATOR, -1)) {
            if (segment.isEmpty() || CURRENT.equals(segment)) {
                continue;
            }
            if (PARENT.equals(segment)) {
                if (segments.isEmpty()) {
                    throw new InvalidFilePathException("The path '" + raw + "' climbs above the project's root");
                }
                segments.removeLast();
                continue;
            }
            requireValidSegment(segment);
            segments.add(segment);
        }
        final var parsed = new ProjectFilePath(segments);
        if (segments.size() > Globals.FILE_PATH_MAX_SEGMENTS) {
            throw new InvalidFilePathException(
                    "The path has more than " + Globals.FILE_PATH_MAX_SEGMENTS + " segments");
        }
        if (parsed.text.length() > Globals.FILE_PATH_MAX_CHARS) {
            throw new InvalidFilePathException(
                    "The path is longer than " + Globals.FILE_PATH_MAX_CHARS + " characters");
        }
        return parsed;
    }

    private static void requireValidSegment(String segment) throws InvalidFilePathException {
        if (segment.length() > Globals.FILE_SEGMENT_MAX_CHARS) {
            throw new InvalidFilePathException(
                    "The name '" + segment + "' is longer than " + Globals.FILE_SEGMENT_MAX_CHARS + " characters");
        }
        for (var i = 0; i < segment.length(); i++) {
            final var c = segment.charAt(i);
            if (c < FIRST_PRINTABLE || c > LAST_PRINTABLE || FORBIDDEN_CHARACTERS.indexOf(c) >= 0) {
                throw new InvalidFilePathException("The name '" + segment
                        + "' holds a character a file name cannot have; names take printable ASCII except "
                        + FORBIDDEN_CHARACTERS);
            }
        }
        if (segment.startsWith(" ") || segment.endsWith(" ")) {
            throw new InvalidFilePathException("The name '" + segment + "' starts or ends with a space");
        }
        if (segment.endsWith(CURRENT)) {
            throw new InvalidFilePathException("The name '" + segment + "' ends with a dot");
        }
        final var dot = segment.indexOf('.');
        final var stem = (dot < 0 ? segment : segment.substring(0, dot)).toLowerCase(Locale.ROOT);
        if (DEVICE_NAMES.contains(stem)) {
            throw new InvalidFilePathException("The name '" + segment + "' is reserved by Windows");
        }
    }

    public static ProjectFilePath ofStored(String normalized) {
        if (normalized.equals(SEPARATOR)) {
            return ROOT;
        }
        return new ProjectFilePath(List.of(normalized.substring(1).split(SEPARATOR)));
    }

    public List<String> segments() {
        return segments;
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public ProjectFilePath parent() {
        return isRoot() ? this : new ProjectFilePath(segments.subList(0, segments.size() - 1));
    }

    public String name() {
        return isRoot() ? "" : segments.getLast();
    }

    public String foldedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ProjectFilePath child(String name) {
        final var childSegments = new ArrayList<>(segments);
        childSegments.add(name);
        return new ProjectFilePath(childSegments);
    }

    public boolean isWithin(ProjectFilePath ancestor) {
        return segments.size() >= ancestor.segments.size()
                && segments.subList(0, ancestor.segments.size()).equals(ancestor.segments);
    }

    public ProjectFilePath rebased(ProjectFilePath from, ProjectFilePath to) {
        final var rebasedSegments = new ArrayList<>(to.segments);
        rebasedSegments.addAll(segments.subList(from.segments.size(), segments.size()));
        return new ProjectFilePath(rebasedSegments);
    }

    public List<ProjectFilePath> ancestors() {
        final var ancestors = new ArrayList<ProjectFilePath>(segments.size());
        for (var depth = 1; depth < segments.size(); depth++) {
            ancestors.add(new ProjectFilePath(segments.subList(0, depth)));
        }
        return ancestors;
    }

    public String subtreeLowerBound() {
        return isRoot() ? SEPARATOR : text + SEPARATOR;
    }

    @Override
    public int compareTo(ProjectFilePath other) {
        return text.compareTo(other.text);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProjectFilePath path && text.equals(path.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
