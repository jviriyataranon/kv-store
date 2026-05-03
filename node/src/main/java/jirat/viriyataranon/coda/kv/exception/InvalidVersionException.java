package jirat.viriyataranon.coda.kv.exception;

public class InvalidVersionException extends RuntimeException {

    public InvalidVersionException(Long oldVersion, Long ifVersion) {
        super(String.format("Old version %d does not match ifVersion %d.", oldVersion, ifVersion));
    }

    public InvalidVersionException(Long ifVersion) {
        super(String.format("Old version missing ifVersion %d.", ifVersion));
    }
}
