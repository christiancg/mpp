package io.github.christiancg.mpp.client.error;

public class FileChangedException extends MppException {
    private final String path;

    public FileChangedException(String path, int attempts) {
        super(path + " changed while it was being read, " + attempts + " time(s) in a row", null, null);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
