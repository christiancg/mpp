package io.github.christiancg.mpp.spring.data.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.christiancg.mpp.client.error.AuthenticationException;
import io.github.christiancg.mpp.client.error.ConflictException;
import io.github.christiancg.mpp.client.error.ConnectionLostException;
import io.github.christiancg.mpp.client.error.ForbiddenException;
import io.github.christiancg.mpp.client.error.MappingException;
import io.github.christiancg.mpp.client.error.MppException;
import io.github.christiancg.mpp.client.error.NotFoundException;
import io.github.christiancg.mpp.client.error.PoolExhaustedException;
import io.github.christiancg.mpp.client.error.RequestTimeoutException;
import io.github.christiancg.mpp.client.error.ServerClosedConnectionException;
import io.github.christiancg.mpp.client.error.UnavailableException;
import io.github.christiancg.mpp.client.error.UnknownOutcomeException;
import io.github.christiancg.mpp.client.protocol.Status;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;

public class MppExceptionTranslatorTest {
    private final MppExceptionTranslator translator = new MppExceptionTranslator();

    private static MppException coded(String code) {
        return new MppException("refused", code, Status.ERROR);
    }

    @Test
    public void test_every_client_failure_has_a_spring_counterpart() {
        assertInstanceOf(DuplicateKeyException.class, translator.translateExceptionIfPossible(coded("400-3")));
        assertInstanceOf(DataIntegrityViolationException.class,
                translator.translateExceptionIfPossible(coded("400-7")));
        assertInstanceOf(DataIntegrityViolationException.class,
                translator.translateExceptionIfPossible(coded("400-21")));
        assertInstanceOf(InvalidDataAccessApiUsageException.class,
                translator.translateExceptionIfPossible(coded("400-1")));
        assertInstanceOf(UncategorizedMppException.class, translator.translateExceptionIfPossible(coded("500-35")));
        assertInstanceOf(UncategorizedMppException.class,
                translator.translateExceptionIfPossible(new MppException("no code", null)));
        assertInstanceOf(InvalidDataAccessApiUsageException.class,
                translator.translateExceptionIfPossible(new IllegalArgumentException("bad")));
        assertInstanceOf(QueryTimeoutException.class,
                translator.translateExceptionIfPossible(new RequestTimeoutException("slow")));
        assertInstanceOf(DataAccessResourceFailureException.class,
                translator.translateExceptionIfPossible(new ConnectionLostException("lost", null)));
        assertInstanceOf(DataAccessResourceFailureException.class, translator
                .translateExceptionIfPossible(new ServerClosedConnectionException("closed", "413-1", Status.ERROR)));
        assertInstanceOf(DataAccessResourceFailureException.class,
                translator.translateExceptionIfPossible(new PoolExhaustedException("busy")));
        assertInstanceOf(DataAccessResourceFailureException.class,
                translator.translateExceptionIfPossible(new AuthenticationException("no", "401-3", Status.ERROR)));
        assertInstanceOf(PermissionDeniedDataAccessException.class,
                translator.translateExceptionIfPossible(new ForbiddenException("no", "403-1", Status.FORBIDDEN)));
        assertInstanceOf(DataRetrievalFailureException.class,
                translator.translateExceptionIfPossible(new NotFoundException("no", "404-11", Status.NOT_FOUND)));
        assertInstanceOf(DataRetrievalFailureException.class,
                translator.translateExceptionIfPossible(new MappingException("shape", null)));
        assertInstanceOf(PessimisticLockingFailureException.class,
                translator.translateExceptionIfPossible(new ConflictException("lock", "409-5", Status.ERROR)));
        assertInstanceOf(TransientDataAccessResourceException.class,
                translator.translateExceptionIfPossible(new ConflictException("idle", "409-16", Status.ERROR)));
        assertInstanceOf(ConcurrencyFailureException.class,
                translator.translateExceptionIfPossible(new ConflictException("open", "409-3", Status.ERROR)));
        assertInstanceOf(DuplicateKeyException.class,
                translator.translateExceptionIfPossible(new ConflictException("taken", "409-18", Status.ERROR)));
        assertInstanceOf(TransientDataAccessResourceException.class,
                translator.translateExceptionIfPossible(new UnavailableException("busy", "503-2", Status.ERROR)));
        assertInstanceOf(UncategorizedMppException.class,
                translator.translateExceptionIfPossible(new UnknownOutcomeException("maybe", "500-34", Status.ERROR)));
    }

    @Test
    public void test_foreign_and_translated_exceptions() {
        assertNull(translator.translateExceptionIfPossible(new IllegalStateException("not ours")));
        final var translated = new DuplicateKeyException("dup");
        assertSame(translated, translator.translateExceptionIfPossible(translated));
    }
}
