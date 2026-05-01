package jirat.viriyataranon.coda.kv.exception;

public class MissingKeyException extends RuntimeException {

    public MissingKeyException(String key) {
        super(String.format("Missing key %s.", key));
    }
}
