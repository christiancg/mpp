package org.ccg.mpp.ops.entrypoint;

import org.ccg.mpp.ejson.elements.JsonBaseElement;
import org.ccg.mpp.log.Logger;
import org.ccg.mpp.simplejs.host.ScriptResult;

public final class EntrypointReply {
    private static final Logger logger = Logger.logFor(EntrypointReply.class);
    private static final int OK = 200;
    private static final int LOWEST_STATUS = 100;
    private static final int HIGHEST_STATUS = 599;

    private EntrypointReply() {
    }

    public static ReplyEnvelope envelopeOf(ScriptResult result, String runId) {
        if (result.isError()) {
            return ReplyEnvelope.failure(runId);
        }
        final var value = result.getValue();
        final var envelopeStatus = envelopeStatusOf(value);
        try {
            if (envelopeStatus < 0) {
                return ReplyEnvelope.of(OK, null, value);
            }
            final var envelope = value.asJsonObject();
            final var declared = envelope.get(ReplyEnvelope.HEADERS);
            if (declared != null && !declared.isJsonNull() && !declared.isJsonObject()) {
                throw new InvalidEnvelopeException("'headers' must be an object");
            }
            return ReplyEnvelope.of(envelopeStatus,
                    declared == null || declared.isJsonNull() ? null : declared.asJsonObject(),
                    envelope.get(ReplyEnvelope.BODY));
        } catch (InvalidEnvelopeException e) {
            logger.warning(
                    "Entrypoint run " + runId + " returned an envelope the server cannot send: " + e.getMessage());
            return ReplyEnvelope.failure(runId);
        }
    }

    static int envelopeStatusOf(JsonBaseElement value) {
        if (value == null || !value.isJsonObject()) {
            return -1;
        }
        final var status = value.asJsonObject().get(ReplyEnvelope.STATUS);
        if (status == null || !status.isJsonNumber()) {
            return -1;
        }
        final var number = status.asJsonNumber().doubleValue();
        if (number != Math.rint(number) || number < LOWEST_STATUS || number > HIGHEST_STATUS) {
            return -1;
        }
        return (int) number;
    }
}
