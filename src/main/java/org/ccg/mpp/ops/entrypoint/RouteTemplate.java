package org.ccg.mpp.ops.entrypoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.ccg.mpp.config.Globals;

public record RouteTemplate(String normalized, List<Segment> segments) {
    private static final String ROOT = "/";
    private static final String EMPTY_SEGMENT = "//";
    private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]{0,63})}");
    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9._~-]+");

    public sealed interface Segment permits Literal, Param {
    }

    public record Literal(String text) implements Segment {
    }

    public record Param(String name) implements Segment {
    }

    public RouteTemplate {
        segments = List.copyOf(segments);
    }

    public static RouteTemplate parse(String template) throws InvalidRouteException {
        if (template == null || !template.startsWith(ROOT)) {
            throw new InvalidRouteException("the path must start with '/'");
        }
        if (template.contains(EMPTY_SEGMENT)) {
            throw new InvalidRouteException("the path has an empty segment");
        }
        if (template.length() > Globals.ENTRYPOINT_MAX_PATH_CHARS) {
            throw new InvalidRouteException(
                    "the path is longer than " + Globals.ENTRYPOINT_MAX_PATH_CHARS + " characters");
        }
        final var trimmed = template.length() > 1 && template.endsWith(ROOT)
                ? template.substring(0, template.length() - 1)
                : template;
        if (ROOT.equals(trimmed)) {
            return new RouteTemplate(ROOT, List.of());
        }
        final var parts = trimmed.substring(1).split(ROOT, -1);
        if (parts.length > Globals.ENTRYPOINT_MAX_SEGMENTS) {
            throw new InvalidRouteException("the path has more than " + Globals.ENTRYPOINT_MAX_SEGMENTS + " segments");
        }
        final var segments = new ArrayList<Segment>(parts.length);
        final var paramNames = new HashSet<String>();
        for (final var part : parts) {
            segments.add(segmentOf(part, paramNames));
        }
        return new RouteTemplate(ROOT + String.join(ROOT, parts), segments);
    }

    private static Segment segmentOf(String part, HashSet<String> paramNames) throws InvalidRouteException {
        if (part.isEmpty()) {
            throw new InvalidRouteException("the path has an empty segment");
        }
        final var param = PARAM.matcher(part);
        if (param.matches()) {
            if (!paramNames.add(param.group(1))) {
                throw new InvalidRouteException("the path names the parameter '" + param.group(1) + "' twice");
            }
            return new Param(param.group(1));
        }
        if (!LITERAL.matcher(part).matches()) {
            throw new InvalidRouteException("the segment '" + part
                    + "' is neither a {parameter} nor made only of letters, digits, '-', '.', '_' and '~'");
        }
        return new Literal(part);
    }

    public Map<String, String> match(List<String> decodedSegments) {
        if (decodedSegments.size() != segments.size()) {
            return null;
        }
        final var params = new LinkedHashMap<String, String>();
        for (var i = 0; i < segments.size(); i++) {
            final var value = decodedSegments.get(i);
            switch (segments.get(i)) {
                case Literal literal -> {
                    if (!literal.text().equals(value)) {
                        return null;
                    }
                }
                case Param param -> params.put(param.name(), value);
            }
        }
        return params;
    }

    public boolean sameShape(RouteTemplate other) {
        if (segments.size() != other.segments.size()) {
            return false;
        }
        for (var i = 0; i < segments.size(); i++) {
            final var mine = segments.get(i);
            final var theirs = other.segments.get(i);
            final var bothParams = mine instanceof Param && theirs instanceof Param;
            if (!bothParams && !mine.equals(theirs)) {
                return false;
            }
        }
        return true;
    }

    public int compareSpecificity(RouteTemplate other) {
        final var shared = Math.min(segments.size(), other.segments.size());
        for (var i = 0; i < shared; i++) {
            final var mine = segments.get(i);
            final var theirs = other.segments.get(i);
            if (mine instanceof Literal && theirs instanceof Param) {
                return -1;
            }
            if (mine instanceof Param && theirs instanceof Literal) {
                return 1;
            }
            if (mine instanceof Literal(var myText) && theirs instanceof Literal(var theirText)
                    && !myText.equals(theirText)) {
                return myText.compareTo(theirText);
            }
        }
        return Integer.compare(segments.size(), other.segments.size());
    }
}
